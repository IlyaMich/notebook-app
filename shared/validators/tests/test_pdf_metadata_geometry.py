"""PDF-01 C3: inherited PDF page metadata and annotation-rectangle golden vectors.

Synthetic root-to-leaf metadata snapshots only. Not a PDF parser, native adapter,
PDF import, rendering test, or proof of Apple/Android runtime compatibility.
"""

import json
import math
from pathlib import Path
import unittest

from shared.validators.tests.test_pdf_geometry_contract import _forward, _geometry, _inverse

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "fixtures" / "pdf" / "metadata_cases_v1.json"
)


def _resolve_nodes(case):
    nodes = case["nodes"]
    if not isinstance(nodes, list) or len(nodes) < 2 or any(
        not isinstance(node, dict) for node in nodes
    ):
        raise ValueError("Expected root-to-leaf metadata dictionaries")

    def inherited(name):
        for node in reversed(nodes):
            if name in node:
                return node[name]
        return None

    media = inherited("mediaBox")
    if media is None:
        raise ValueError("Missing inherited MediaBox")

    flat = {"mediaBox": media}
    crop = inherited("cropBox")
    if crop is not None:
        flat["cropBox"] = crop
    elif any("cropBox" in node for node in nodes):
        raise ValueError("Present but null CropBox")

    rotation = inherited("rotate")
    flat["rotate"] = 0 if rotation is None and all(
        "rotate" not in node for node in nodes
    ) else rotation

    # /UserUnit is page-local: ancestor values must never be inherited.
    flat["userUnit"] = nodes[-1].get("userUnit", 1)
    return flat


def _close(actual, expected):
    if len(actual) != len(expected):
        return False
    return all(
        math.isfinite(value) and math.isclose(value, target, rel_tol=1e-12, abs_tol=1e-9)
        for value, target in zip(actual, expected)
    )


def _project_rectangle(affine, rectangle):
    x0, y0, x1, y1 = rectangle
    if not (x1 > x0 and y1 > y0):
        raise ValueError("Annotation rectangle is not positive")
    corners = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    mapped = [_forward(affine, point) for point in corners]
    if any(not all(math.isfinite(v) for v in point) for point in mapped):
        raise ValueError("Nonfinite projected annotation rectangle")
    xs, ys = [point[0] for point in mapped], [point[1] for point in mapped]
    return mapped, (min(xs), min(ys), max(xs), max(ys))


class PdfMetadataGeometryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_catalog_covers_tree_rotation_unit_and_rectangles(self):
        self.assertEqual("pdf-page-metadata-v1", self.catalog["contract"])
        valid, invalid = self.catalog["validCases"], self.catalog["invalidCases"]
        self.assertGreaterEqual(len(valid), 7)
        self.assertGreaterEqual(len(invalid), 12)
        ids = [case["id"] for case in valid + invalid]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual({0, 90, 180, 270}, {
            case["expected"]["rotation"] for case in valid
        })
        self.assertTrue(any(
            any("userUnit" in node for node in case["nodes"][:-1])
            and "userUnit" not in case["nodes"][-1]
            for case in valid
        ))
        self.assertTrue(all("rectangle" in case["expected"] for case in valid))
        self.assertTrue(any(
            any(any(n < 0 for n in point["canonical"])
                for point in case["expected"]["points"])
            for case in valid
        ))

    def test_inherited_metadata_and_normalized_geometry_match_goldens(self):
        for case in self.catalog["validCases"]:
            with self.subTest(case=case["id"]):
                resolved = _resolve_nodes(case)
                effective_crop, size, _ = _geometry(resolved)
                expected = case["expected"]
                self.assertTrue(_close(resolved["mediaBox"], expected["mediaBox"]))
                self.assertTrue(_close(effective_crop, expected["cropBox"]))
                self.assertTrue(_close(size, expected["pageSize"]))
                self.assertEqual(resolved["rotate"] % 360, expected["rotation"])
                self.assertEqual(resolved["userUnit"], expected["userUnit"])

    def test_annotation_points_rectangles_and_inverse_match_goldens(self):
        for case in self.catalog["validCases"]:
            with self.subTest(case=case["id"]):
                _, _, affine = _geometry(_resolve_nodes(case))
                expected = case["expected"]
                for point in expected["points"]:
                    self.assertTrue(_close(_forward(affine, point["source"]), point["canonical"]))
                    self.assertTrue(_close(_inverse(affine, point["canonical"]), point["source"]))
                rectangle = expected["rectangle"]
                corners, bounds = _project_rectangle(affine, rectangle["source"])
                x0, y0, x1, y1 = rectangle["source"]
                original_corners = ((x0, y0), (x1, y0), (x1, y1), (x0, y1))
                self.assertEqual(4, len(rectangle["canonicalCorners"]))
                for source, actual, golden in zip(original_corners, corners, rectangle["canonicalCorners"]):
                    self.assertTrue(_close(actual, golden), (case["id"], actual, golden))
                    self.assertTrue(_close(_inverse(affine, golden), source))
                self.assertTrue(_close(bounds, rectangle["canonicalBounds"]))

    def test_invalid_inherited_and_numeric_metadata_fails_closed(self):
        for case in self.catalog["invalidCases"]:
            with self.subTest(case=case["id"]):
                with self.assertRaises(ValueError):
                    _geometry(_resolve_nodes(case))


if __name__ == "__main__":
    unittest.main()
