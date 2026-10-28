"""Synthetic semantic validation tests. Golden documents belong to FMT-03."""

import copy
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from shared.validators.tests.test_schema_validation import (
    ASSET_ID,
    ASSET_PATH,
    PAGE_ID,
    PAGE_PATH,
    PNG_DATA,
    valid_documents,
)
from shared.validators.validate_notebook import NotebookValidationError, validate_notebook


class NotebookSemanticTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.archive = Path(temp.name) / "semantic.notebook"
        self.manifest, self.page = valid_documents()
        self.pages = {PAGE_PATH: self.page}
        self.assets = {ASSET_PATH: PNG_DATA}

    def write_archive(self):
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr("manifest.json", json.dumps(self.manifest))
            for path, page in self.pages.items():
                package.writestr(path, json.dumps(page))
            for path, content in self.assets.items():
                package.writestr(path, content)

    def rejects(self, error_fragment):
        self.write_archive()
        with self.assertRaisesRegex(NotebookValidationError, error_fragment):
            validate_notebook(self.archive)

    def add_second_page(self, retain_element_ids=True):
        new_id = "aae09138-94eb-4cc2-aaf2-ec858e683ddc"
        new_path = f"pages/{new_id}.json"
        second_page = copy.deepcopy(self.page)
        second_page["id"] = new_id
        if not retain_element_ids:
            second_page["elements"] = []
        self.pages[new_path] = second_page
        self.manifest["pages"].append({"id": new_id, "path": new_path})
        return second_page

    def test_original_document_accepted(self):
        self.write_archive()
        self.assertEqual(validate_notebook(self.archive)["id"], self.manifest["id"])

    def test_utc_zero_offset_accepted(self):
        self.manifest["updatedAt"] = "2026-10-28T10:01:00+00:00"
        self.write_archive()
        self.assertEqual(validate_notebook(self.archive)["schemaVersion"], 1)

    def test_equal_stroke_times_accepted(self):
        self.page["elements"][0]["payload"]["points"][1]["tMs"] = 0
        self.write_archive()
        validate_notebook(self.archive)

    def test_second_page_with_distinct_elements_accepted(self):
        self.add_second_page(retain_element_ids=False)
        self.write_archive()
        validate_notebook(self.archive)

    def test_created_after_updated_rejected(self):
        self.manifest["createdAt"] = "2026-10-28T11:00:00Z"
        self.rejects("createdAt must not")

    def test_non_utc_offset_rejected(self):
        self.manifest["updatedAt"] = "2026-10-28T10:01:00+03:00"
        self.rejects("updatedAt must be UTC")

    def test_duplicate_asset_id_rejected(self):
        other = copy.deepcopy(self.manifest["assets"][0])
        other["id"] = other["id"].upper()
        self.manifest["assets"].append(other)
        self.rejects("Duplicate asset ID")

    def test_duplicate_asset_path_rejected(self):
        other = copy.deepcopy(self.manifest["assets"][0])
        other["id"] = "fa291ca0-ff3d-4c8a-aa85-9f2847e4ceca"
        self.manifest["assets"].append(other)
        self.rejects("Duplicate asset ID or path")

    def test_missing_asset_file_rejected(self):
        self.assets = {}
        self.rejects("Missing asset file")

    def test_wrong_asset_byte_length_rejected(self):
        self.manifest["assets"][0]["byteLength"] += 1
        self.rejects("byteLength mismatch")

    def test_corrupted_asset_bytes_rejected(self):
        self.assets[ASSET_PATH] = PNG_DATA[:-1] + b"x"
        self.rejects("SHA-256 mismatch")

    def test_asset_hash_path_mismatch_rejected(self):
        self.manifest["assets"][0]["sha256"] = "0" * 64
        self.rejects("path does not match")

    def test_mime_signature_mismatch_rejected(self):
        bad = b"not really PNG data"
        digest = hashlib.sha256(bad).hexdigest()
        self.assets = {f"assets/{digest}.png": bad}
        self.manifest["assets"][0]["path"] = f"assets/{digest}.png"
        self.manifest["assets"][0]["sha256"] = digest
        self.manifest["assets"][0]["byteLength"] = len(bad)
        self.rejects("MIME/signature mismatch")

    def test_duplicate_element_id_on_same_page_rejected(self):
        self.page["elements"][1]["id"] = self.page["elements"][0]["id"]
        self.rejects("Duplicate element ID")

    def test_duplicate_element_id_across_pages_rejected(self):
        self.add_second_page(retain_element_ids=True)
        self.rejects("Duplicate element ID")

    def test_singular_transform_rejected(self):
        self.page["elements"][0]["transform"] = [1, 0, 0, 0, 0, 0]
        self.rejects("Singular or unstable transform")

    def test_dependent_transform_vectors_rejected(self):
        self.page["elements"][0]["transform"] = [2, 2, 1, 1, 0, 0]
        self.rejects("Singular or unstable transform")

    def test_backwards_stroke_time_rejected(self):
        self.page["elements"][0]["payload"]["points"][1]["tMs"] = 0
        self.page["elements"][0]["payload"]["points"][0]["tMs"] = 1
        self.rejects("Non-monotonic stroke")

    def test_crop_right_overflow_rejected(self):
        self.page["elements"][3]["payload"]["crop"]["x"] = 0.3
        self.rejects("crop exceeds")

    def test_crop_bottom_overflow_rejected(self):
        self.page["elements"][3]["payload"]["crop"]["y"] = 0.4
        self.rejects("crop exceeds")

    def test_missing_image_asset_reference_rejected(self):
        self.page["elements"][3]["payload"]["assetId"] = "d6e8dd6f-8ec7-4f3d-99b2-c3c2d72011f1"
        self.rejects("Image references missing")

    def test_wrong_pdf_background_asset_type_rejected(self):
        self.page["background"] = {"type": "pdfPage", "assetId": ASSET_ID,
                                   "pageIndex": 0, "displayBox": "crop"}
        self.rejects("PDF background references missing/non-PDF")

    def test_nonexistent_pdf_background_reference_rejected(self):
        self.page["background"] = {"type": "pdfPage", "assetId": "d6e8dd6f-8ec7-4f3d-99b2-c3c2d72011f1",
                                   "pageIndex": 0, "displayBox": "crop"}
        self.rejects("PDF background references missing/non-PDF")

    def test_unlisted_zip_entry_rejected(self):
        self.assets["extra.txt"] = b"not registered"
        self.rejects("Unlisted archive member")

    def test_page_path_id_mismatch_rejected(self):
        different = "a074a4ea-f8e4-494b-aebd-9a5130374f0f"
        new_path = f"pages/{different}.json"
        self.pages = {new_path: self.page}
        self.manifest["pages"][0]["path"] = new_path
        self.rejects("Page path does not match")

    def test_archive_without_assets_accepted(self):
        self.page["elements"].pop()  # Remove image element.
        self.manifest["assets"] = []
        self.assets = {}
        self.write_archive()
        validate_notebook(self.archive)


if __name__ == "__main__":
    unittest.main()
