"""FMT-03 permanent fixture acceptance/rejection and reproducibility tests.

The expected-results catalog is deliberately data-driven for future Kotlin/Swift
conformance checks. Only the *Python* error message fragments are local to this
validator: a different language must reject the bad files, not copy wording.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import unittest
import zipfile

from shared.fixtures.build_edge_cases import build_all, expected_results
from shared.validators.validate_notebook import NotebookValidationError, validate_notebook

ROOT = Path(__file__).resolve().parents[2] / "fixtures"
CATALOG = ROOT / "expected_results.json"


class EdgeFixtureTests(unittest.TestCase):
    def test_catalog_matches_builder(self):
        self.assertEqual(json.loads(CATALOG.read_text(encoding="utf-8")), expected_results())

    def test_all_committed_fixture_bytes_reproducible(self):
        for relative, expected in build_all().items():
            with self.subTest(fixture=relative):
                self.assertEqual((ROOT / relative).read_bytes(), expected)

    def test_catalog_covers_each_new_fixture_once(self):
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        listed = [entry["path"] for category in ("valid", "invalid")
                  for entry in catalog[category]]
        self.assertEqual(len(listed), len(set(listed)))
        actual = {str(p.relative_to(ROOT)).replace("\\", "/")
                  for category in ("valid", "invalid")
                  for p in (ROOT / category).glob("*.notebook")
                  if p.name != "mixed_pages_v1.notebook"}
        self.assertEqual(set(listed), actual)

    def test_valid_edge_payloads_have_expected_geometry(self):
        empty = ROOT / "valid" / "empty_page_v1.notebook"
        with zipfile.ZipFile(empty) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            page = json.loads(archive.read(manifest["pages"][0]["path"]))
        self.assertEqual(manifest["assets"], [])
        self.assertEqual(manifest["createdAt"], manifest["updatedAt"])
        self.assertEqual(page["elements"], [])
        empty_note_id = manifest["id"]

        single = ROOT / "valid" / "single_point_stroke_v1.notebook"
        with zipfile.ZipFile(single) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            page = json.loads(archive.read(manifest["pages"][0]["path"]))
        self.assertNotEqual(manifest["id"], empty_note_id)
        self.assertEqual(page["background"]["type"], "dotted")
        element = page["elements"][0]
        self.assertEqual(element["transform"], [-1, 0, 0, 1, 120, 0])
        self.assertEqual(len(element["payload"]["points"]), 1)
        self.assertEqual(element["payload"]["brush"]["opacity"], 0)


def _create_valid_test(relative: str):
    def check(self):
        manifest = validate_notebook(ROOT / relative)
        self.assertEqual(manifest["schemaVersion"], 1)
    return check


def _create_invalid_test(relative: str, message_fragment: str):
    def check(self):
        with self.assertRaisesRegex(NotebookValidationError, re.escape(message_fragment)):
            validate_notebook(ROOT / relative)
    return check


# Each fixture gets a separate unittest result (not just subTest lines), so CI
# reports exactly which permanent case has changed behavior.
for entry in expected_results()["valid"]:
    name = Path(entry["path"]).stem
    setattr(EdgeFixtureTests, f"test_accept_{name}", _create_valid_test(entry["path"]))
for entry in expected_results()["invalid"]:
    name = Path(entry["path"]).stem
    setattr(EdgeFixtureTests, f"test_reject_{name}",
            _create_invalid_test(entry["path"], entry["error_contains"]))


if __name__ == "__main__":
    unittest.main()
