"""Checkpoint 2 synthetic schema tests, not FMT-03 golden fixtures."""

import json
import hashlib
import struct
import zlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from shared.validators.validate_notebook import NotebookValidationError, validate_notebook

NOTE_ID = "986c1ddb-3c5b-4e54-9171-d36bb3132f3a"
PAGE_ID = "7d6acdd3-504f-4bb3-9c70-3ba7af56b4ba"
PAGE_PATH = f"pages/{PAGE_ID}.json"
ASSET_ID = "d31c4420-2ec6-4dc7-8508-a235f79161f3"


def _png_chunk(chunk_type, content):
    body = chunk_type + content
    return struct.pack(">I", len(content)) + body + struct.pack(">I", zlib.crc32(body))


# A tiny synthetic 1x1 RGBA image. Never user content.
PNG_DATA = (
    b"\x89PNG\r\n\x1a\n"
    + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0))
    + _png_chunk(b"IDAT", zlib.compress(b"\x00\xff\xff\xff\xff"))
    + _png_chunk(b"IEND", b"")
)
SHA256 = hashlib.sha256(PNG_DATA).hexdigest()
ASSET_PATH = f"assets/{SHA256}.png"


def make_element(kind, payload, suffix):
    return {
        "id": f"10000000-0000-4000-8000-{suffix:012d}",
        "type": kind,
        "transform": [1, 0, 0, 1, 0, 0],
        "locked": False,
        "payload": payload,
    }


def valid_documents():
    manifest = {
        "schemaVersion": 1,
        "id": NOTE_ID,
        "title": "Schema example",
        "createdAt": "2026-10-28T10:00:00Z",
        "updatedAt": "2026-10-28T10:01:00Z",
        "pages": [{"id": PAGE_ID, "path": PAGE_PATH}],
        "assets": [{"id": ASSET_ID, "path": ASSET_PATH, "mimeType": "image/png",
                    "sha256": SHA256, "byteLength": len(PNG_DATA)}],
    }
    brush = {"kind": "pen", "width": 2.5, "color": "#2255AA", "opacity": 1}
    stroke = make_element("stroke", {"encoding": "sampledPointsV1", "brush": brush,
        "points": [{"x": 80, "y": 110, "tMs": 0, "pressure": 0.45},
                   {"x": 99, "y": 100, "tMs": 18, "pressure": 0.65}]}, 1)
    arrow = make_element("shape", {
        "kind": "arrow", "geometry": {"x1": 170, "y1": 160, "x2": 250, "y2": 110},
        "stroke": {"color": "#008F70", "width": 3, "opacity": 1},
        "fill": None, "arrowHead": {"length": 12, "angleDeg": 30},
    }, 2)
    text = make_element("text", {
        "text": "F = ma", "frame": {"x": 80, "y": 190, "width": 165, "height": 42},
        "style": {"fontFamily": "sans", "fontSize": 20, "color": "#202020",
                  "bold": True, "italic": False, "underline": False, "alignment": "left"},
    }, 3)
    # The image asset exists in this synthetic archive. Integrity will be checked in CP3.
    image = make_element("image", {
        "assetId": ASSET_ID,
        "frame": {"x": 280, "y": 160, "width": 80, "height": 53.333},
        "opacity": 1, "crop": {"x": 0.1, "y": 0.1, "width": 0.8, "height": 0.8},
    }, 4)
    page = {"id": PAGE_ID, "size": {"width": 595.276, "height": 841.890},
            "background": {"type": "ruled", "color": "#FFFFFF",
                           "patternColor": "#D8E7F7", "spacing": 24},
            "elements": [stroke, arrow, text, image]}
    return manifest, page


class NotebookSchemaTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.addCleanup(self.tempdir.cleanup)
        self.archive = Path(self.tempdir.name) / "test.notebook"
        self.manifest, self.page = valid_documents()

    def write_archive(self, page_content=None):
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as package:
            package.writestr("manifest.json", json.dumps(self.manifest))
            package.writestr(PAGE_PATH, json.dumps(self.page) if page_content is None else page_content)
            package.writestr(ASSET_PATH, PNG_DATA)

    def rejects(self):
        self.write_archive()
        with self.assertRaises(NotebookValidationError):
            validate_notebook(self.archive)

    def test_four_element_types_accepted(self):
        self.write_archive()
        self.assertEqual(validate_notebook(self.archive)["id"], NOTE_ID)

    def test_manifest_missing_title_rejected(self):
        del self.manifest["title"]
        self.rejects()

    def test_invalid_timestamp_rejected(self):
        self.manifest["updatedAt"] = "not-a-timestamp"
        self.rejects()

    def test_negative_page_width_rejected(self):
        self.page["size"]["width"] = -1
        self.rejects()

    def test_unknown_element_type_rejected(self):
        self.page["elements"][0]["type"] = "audio"
        self.rejects()

    def test_invalid_transform_length_rejected(self):
        self.page["elements"][0]["transform"] = [1, 0, 0, 1]
        self.rejects()

    def test_invalid_shape_geometry_rejected(self):
        self.page["elements"][1]["payload"]["geometry"]["width"] = 50
        self.rejects()

    def test_invalid_brush_opacity_rejected(self):
        self.page["elements"][0]["payload"]["brush"]["opacity"] = 1.5
        self.rejects()

    def test_page_id_mismatch_rejected(self):
        self.page["id"] = "ec0c9493-22de-4a05-ae77-e69a74538642"
        self.rejects()

    def test_duplicate_json_key_rejected(self):
        page = json.dumps(self.page)
        self.write_archive(page_content=page.replace('"id":', '"id": "duplicate", "id":', 1))
        with self.assertRaisesRegex(NotebookValidationError, "Duplicate JSON key"):
            validate_notebook(self.archive)

    def test_exponent_overflow_rejected(self):
        page = json.dumps(self.page).replace('"width": 595.276', '"width": 1e309', 1)
        self.write_archive(page_content=page)
        with self.assertRaisesRegex(NotebookValidationError, "Non-finite JSON number"):
            validate_notebook(self.archive)


if __name__ == "__main__":
    unittest.main()
