"""FMT-03: stable committed positive golden package and cross-version invariants."""

import hashlib
import json
from pathlib import Path
import unittest
import zipfile

from shared.fixtures.build_golden import build_bytes, make_pdf, make_png
from shared.validators.validate_notebook import validate_notebook

FIXTURE = Path(__file__).resolve().parents[2] / "fixtures" / "valid" / "mixed_pages_v1.notebook"


class GoldenFixtureTests(unittest.TestCase):
    def test_committed_fixture_validates(self):
        manifest = validate_notebook(FIXTURE)
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(len(manifest["pages"]), 4)
        self.assertEqual(len(manifest["assets"]), 2)

    def test_fixture_is_byte_for_byte_reproducible(self):
        self.assertEqual(FIXTURE.read_bytes(), build_bytes())

    def test_ordered_mixed_pages_and_elements(self):
        with zipfile.ZipFile(FIXTURE) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            pages = [json.loads(archive.read(ref["path"])) for ref in manifest["pages"]]
        self.assertEqual([page["background"]["type"] for page in pages],
                         ["ruled", "grid", "blank", "pdfPage"])
        self.assertEqual([(page["size"]["width"], page["size"]["height"]) for page in pages],
                         [(595.276, 841.89), (792, 612), (612, 792), (612, 792)])
        self.assertEqual([[element["type"] for element in page["elements"]] for page in pages],
                         [["stroke", "shape", "text"], ["image", "shape"], ["stroke"], ["text"]])
        self.assertEqual(pages[-1]["background"]["pageIndex"], 1)

    def test_assets_have_matching_hashes_and_bytes(self):
        with zipfile.ZipFile(FIXTURE) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            for asset in manifest["assets"]:
                raw = archive.read(asset["path"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), asset["sha256"])
                self.assertEqual(len(raw), asset["byteLength"])
        self.assertTrue(make_png().startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertTrue(make_pdf().startswith(b"%PDF-"))

    def test_pdf_background_references_correct_asset(self):
        with zipfile.ZipFile(FIXTURE) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            final_page = json.loads(archive.read(manifest["pages"][-1]["path"]))
        pdf_assets = [asset for asset in manifest["assets"] if asset["mimeType"] == "application/pdf"]
        self.assertEqual(len(pdf_assets), 1)
        self.assertEqual(final_page["background"]["assetId"], pdf_assets[0]["id"])
        self.assertEqual(final_page["background"]["displayBox"], "crop")

    def test_no_library_organizational_metadata_in_manifest(self):
        with zipfile.ZipFile(FIXTURE) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        for key in ("notebookId", "folderId", "parentFolderId", "libraryPath"):
            self.assertNotIn(key, manifest)


if __name__ == "__main__":
    unittest.main()
