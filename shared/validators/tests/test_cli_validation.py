"""FMT-02: validate_notebook.py must work as a command line tool in CI.

These synthetic tests are not the permanent FMT-03 golden fixture suite.
"""

import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from shared.validators.tests.test_schema_validation import (
    ASSET_PATH,
    PAGE_PATH,
    PNG_DATA,
    valid_documents,
)

VALIDATOR_SCRIPT = Path(__file__).resolve().parents[1] / "validate_notebook.py"


class NotebookCliTests(unittest.TestCase):
    def setUp(self):
        temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(temporary_directory.cleanup)
        self.archive = Path(temporary_directory.name) / "cli.notebook"
        self.manifest, self.page = valid_documents()
        self.assets = {ASSET_PATH: PNG_DATA}
        self.pages = {PAGE_PATH: self.page}

    def write_archive(self):
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr("manifest.json", json.dumps(self.manifest))
            for path, page in self.pages.items():
                package.writestr(path, json.dumps(page))
            for path, payload in self.assets.items():
                package.writestr(path, payload)

    def run_validator(self):
        return subprocess.run(
            [sys.executable, str(VALIDATOR_SCRIPT), str(self.archive)],
            text=True,
            capture_output=True,
            timeout=15,
            check=False,
        )

    def test_valid_package_returns_zero(self):
        self.write_archive()
        result = self.run_validator()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("VALID", result.stdout)

    def test_unsupported_version_returns_nonzero(self):
        self.manifest["schemaVersion"] = 999
        self.write_archive()
        result = self.run_validator()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("INVALID", result.stdout)

    def test_corrupted_asset_returns_nonzero(self):
        self.assets[ASSET_PATH] = PNG_DATA[:-1] + b"X"
        self.write_archive()
        result = self.run_validator()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("SHA-256 mismatch", result.stdout)

    def test_traversal_member_returns_nonzero(self):
        self.assets["../outside.txt"] = b"not a safe package member"
        self.write_archive()
        result = self.run_validator()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("Unsafe ZIP member", result.stdout)


if __name__ == "__main__":
    unittest.main()
