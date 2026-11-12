"""PDF-01 C1: evaluate shared, hand-reviewed PDF geometry golden vectors.

This is a platform-neutral mathematical reference test, NOT a PDF parser,
Android/PDFKit adapter, or proof of Apple runtime conformance.
"""

import json
import math
from pathlib import Path
import unittest

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "fixtures" / "pdf" / "geometry_cases_v1.json"
)


def _box(value):
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError("PDF box must have four numbers")
    if any(isinstance(v, bool) or not isinstance(v, (float, int)) or
           not math.isfinite(v) for v in value):
        raise ValueError("PDF box coordinates must be finite numbers")
    x0, y0, x1, y1 = value
    if x1 <= x0 or y1 <= y0:
        raise ValueError("PDF box must have positive width and height")
    return (x0, y0, x1, y1)


def _geometry(case):
    """Returns effective crop, displayed size and source-to-page affine."""
    media = _box(case["mediaBox"])
    crop = _box(case["cropBox"]) if "cropBox" in case else media
    x0 = max(media[0], crop[0])
    y0 = max(media[1], crop[1])
    x1 = min(media[2], crop[2])
    y1 = min(media[3], crop[3])
    if x1 <= x0 or y1 <= y0:
        raise ValueError("Effective crop is empty")

    rotate = case["rotate"]
    if isinstance(rotate, bool) or not isinstance(rotate, int) or rotate % 90 != 0:
        raise ValueError("Rotate must be an integer multiple of 90")
    rotate %= 360

    scale = case["userUnit"]
    if (isinstance(scale, bool) or not isinstance(scale, (int, float)) or
            not math.isfinite(scale) or scale <= 0):
        raise ValueError("UserUnit must be finite and positive")
    width, height = x1 - x0, y1 - y0
    page_size = ((height, width) if rotate in (90, 270) else (width, height))
    page_size = tuple(v * scale for v in page_size)
    affine = {
        0: (scale, 0, 0, -scale, -scale * x0, scale * y1),
        90: (0, scale, scale, 0, -scale * y0, -scale * x0),
        180: (-scale, 0, 0, scale, scale * x1, -scale * y0),
        270: (0, -scale, -scale, 0, scale * y1, scale * x1),
    }[rotate]
    if not all(math.isfinite(v) and v > 0 for v in page_size):
        raise ValueError("Invalid displayed page dimensions")
    if not all(math.isfinite(v) for v in affine):
        raise ValueError("Nonfinite affine transform")
    a, b, c, d, _, _ = affine
    determinant = a * d - b * c
    if not math.isfinite(determinant) or determinant == 0:
        raise ValueError("Noninvertible source-to-page affine")
    return (x0, y0, x1, y1), page_size, affine


def _forward(affine, point):
    a, b, c, d, tx, ty = affine
    x, y = point
    return (a * x + c * y + tx, b * x + d * y + ty)


def _inverse(affine, point):
    a, b, c, d, tx, ty = affine
    determinant = a * d - b * c
    x, y = point[0] - tx, point[1] - ty
    return ((d * x - c * y) / determinant,
            (-b * x + a * y) / determinant)


class PdfGeometryContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def assertPointClose(self, actual, expected):
        self.assertEqual(len(actual), len(expected))
        for actual_value, expected_value in zip(actual, expected):
            self.assertTrue(
                math.isclose(actual_value, expected_value,
                             rel_tol=1e-12, abs_tol=1e-9),
                f"actual {actual} differs from expected {expected}",
            )

    def test_catalog_has_required_coverage(self):
        self.assertEqual(self.catalog["contract"], "pdf-page-geometry-v1")
        valid = self.catalog["validCases"]
        invalid = self.catalog["invalidCases"]
        ids = [item["id"] for item in valid + invalid]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue({0, 90, 180, 270}.issubset({item["rotate"] for item in valid}))
        self.assertTrue(any("cropBox" not in item for item in valid))
        self.assertTrue(any(item["userUnit"] != 1 for item in valid))
        self.assertTrue(any(any(v < 0 for v in item["mediaBox"]) for item in valid))
        self.assertTrue(all(len(item["points"]) >= 3 for item in valid))

    def test_expected_effective_crop_and_page_size(self):
        for case in self.catalog["validCases"]:
            with self.subTest(case=case["id"]):
                crop, size, _ = _geometry(case)
                self.assertPointClose(crop, case["expectedCropBox"])
                self.assertPointClose(size, case["expectedPageSize"])

    def test_forward_and_inverse_against_independent_golden_points(self):
        for case in self.catalog["validCases"]:
            _, _, affine = _geometry(case)
            for point in case["points"]:
                with self.subTest(case=case["id"], source=point["source"]):
                    self.assertPointClose(
                        _forward(affine, point["source"]), point["canonical"])
                    self.assertPointClose(
                        _inverse(affine, point["canonical"]), point["source"])
                    self.assertPointClose(
                        _inverse(affine, _forward(affine, point["source"])),
                        point["source"])

    def test_invalid_metadata_fails_explicitly(self):
        for case in self.catalog["invalidCases"]:
            with self.subTest(case=case["id"]):
                with self.assertRaises(ValueError):
                    _geometry(case)


if __name__ == "__main__":
    unittest.main()
