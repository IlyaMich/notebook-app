"""Synthetic, in-memory structural tests; not the FMT-03 golden fixtures."""

import json
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from shared.validators.validate_notebook import NotebookValidationError, preflight_notebook

PAGE_ID = "7d6acdd3-504f-4bb3-9c70-3ba7af56b4ba"
PAGE_PATH = f"pages/{PAGE_ID}.json"


class NotebookPreflightTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.archive = Path(self.tempdir.name) / "test.notebook"
        self.manifest = {"schemaVersion": 1, "pages": [{"id": PAGE_ID, "path": PAGE_PATH}]}

    def write_archive(self, entries=None):
        if entries is None:
            entries = [
                ("manifest.json", json.dumps(self.manifest)),
                (PAGE_PATH, '{}'),
            ]
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)  # Intentional duplicate entry test.
                for name, contents in entries:
                    package.writestr(name, contents)

    def test_safe_package_passes_preflight(self):
        self.write_archive()
        self.assertEqual(preflight_notebook(self.archive)["schemaVersion"], 1)

    def test_traversal_rejected(self):
        self.write_archive([("manifest.json", json.dumps(self.manifest)), ("../evil.txt", "x")])
        with self.assertRaisesRegex(NotebookValidationError, "Unsafe ZIP member"):
            preflight_notebook(self.archive)

    def test_duplicate_zip_entry_rejected(self):
        self.write_archive([
            ("manifest.json", json.dumps(self.manifest)),
            ("manifest.json", json.dumps(self.manifest)),
            (PAGE_PATH, '{}'),
        ])
        with self.assertRaisesRegex(NotebookValidationError, "Duplicate ZIP member"):
            preflight_notebook(self.archive)

    def test_unsupported_version_rejected(self):
        self.manifest["schemaVersion"] = 2
        self.write_archive()
        with self.assertRaisesRegex(NotebookValidationError, "Unsupported schemaVersion"):
            preflight_notebook(self.archive)

    def test_missing_referenced_page_rejected(self):
        self.write_archive([("manifest.json", json.dumps(self.manifest))])
        with self.assertRaisesRegex(NotebookValidationError, "Missing referenced page"):
            preflight_notebook(self.archive)

    def test_duplicate_manifest_page_id_rejected(self):
        self.manifest["pages"].append({"id": PAGE_ID, "path": PAGE_PATH})
        self.write_archive()
        with self.assertRaisesRegex(NotebookValidationError, "Duplicate manifest page"):
            preflight_notebook(self.archive)


if __name__ == "__main__":
    unittest.main()
