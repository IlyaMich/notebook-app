"""EDT-12 C1: one shared page fixture, existing schema/version, no decoder semantics."""

import copy
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from shared.validators.validate_notebook import NotebookValidationError, validate_notebook

FIXTURE = Path(__file__).resolve().parents[2] / "fixtures" / "shapes" / "semantic_shapes_v1.json"


class ShapeContractTests(unittest.TestCase):
    def setUp(self):
        self.page = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def validate(self, page):
        page_path = f"pages/{page['id']}.json"
        manifest = {
            "schemaVersion": 1,
            "id": "986c1ddb-3c5b-4e54-9171-d36bb3132f3a",
            "title": "Synthetic semantic shape contract",
            "createdAt": "2026-11-10T10:00:00Z",
            "updatedAt": "2026-11-10T10:00:00Z",
            "pages": [{"id": page["id"], "path": page_path}],
            "assets": [],
        }
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "shapes.notebook"
            with zipfile.ZipFile(archive, "w") as package:
                package.writestr("manifest.json", json.dumps(manifest))
                package.writestr(page_path, json.dumps(page))
            return validate_notebook(archive)

    def test_shared_fixture_accepts_all_four_kinds_and_retains_order(self):
        self.assertEqual(
            ["line", "rectangle", "ellipse", "arrow"],
            [element["payload"]["kind"] for element in self.page["elements"]],
        )
        before = copy.deepcopy(self.page)
        self.assertEqual(1, self.validate(self.page)["schemaVersion"])
        self.assertEqual(before, self.page)

    def test_zero_length_endpoints_nonnull_line_fill_and_unrestricted_angles_are_valid(self):
        line, _, _, arrow = self.page["elements"]
        line["payload"]["geometry"] = {"x1": -1.25, "y1": 2.5, "x2": -1.25, "y2": 2.5}
        arrow["payload"]["geometry"] = copy.deepcopy(line["payload"]["geometry"])
        arrow["payload"]["arrowHead"] = {"length": 1e-300, "angleDeg": 1e300}
        self.assertIsNotNone(line["payload"]["fill"])
        self.validate(self.page)

    def test_missing_unknown_mismatched_and_invalid_fields_fail_closed(self):
        def change(index, field, value):
            page = copy.deepcopy(self.page)
            page["elements"][index]["payload"][field] = value
            return page

        invalid = [
            change(0, "kind", "triangle"),
            change(0, "geometry", {"x": 0, "y": 0, "width": 1, "height": 1}),
            change(0, "geometry", {"x1": "10", "y1": 0, "x2": 1, "y2": 1}),
            change(1, "geometry", {"x": 0, "y": 0, "width": 0, "height": 1}),
            change(2, "geometry", {"x": 0, "y": 0, "width": 1, "height": -1}),
            change(3, "arrowHead", {"length": 0, "angleDeg": 30}),
            change(3, "arrowHead", {"length": 12, "angleDeg": "30"}),
            change(0, "stroke", {"color": "#123456", "width": 1, "opacity": 1.01}),
            change(0, "fill", {"color": "#123", "opacity": 0.5}),
            change(0, "arrowHead", {"length": 12, "angleDeg": 30}),
        ]
        for field in ("fill", "arrowHead"):
            page = copy.deepcopy(self.page)
            del page["elements"][3]["payload"][field]
            invalid.append(page)
        page = copy.deepcopy(self.page)
        page["elements"][0]["transform"] = [1, 0, 0, 0, 0, 0]
        invalid.append(page)
        for index, page in enumerate(invalid):
            with self.subTest(index=index):
                with self.assertRaises(NotebookValidationError):
                    self.validate(page)

    def test_nonfinite_geometry_is_rejected(self):
        for number in (float("nan"), float("inf"), -float("inf")):
            with self.subTest(number=number):
                page = copy.deepcopy(self.page)
                page["elements"][0]["payload"]["geometry"]["x1"] = number
                with self.assertRaises(NotebookValidationError):
                    self.validate(page)
