"""FMT-04: regression tests for the sampledPointsV1 golden ink fixtures.

Run from the repository root:
    py -3 -m unittest discover -s shared/validators/tests -p test_ink_fixtures.py -v

Uses the existing FMT-02 validator and the CP2 fixture generator.  It deliberately
keeps FMT-03's independent fixture catalog and existing tests unchanged.
Native Jetpack Ink/PencilKit rendering and performance are evaluated in EDT-05/04.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
import unittest
import zipfile

from shared.fixtures.build_ink_fixtures import build_all
from shared.validators.validate_notebook import NotebookValidationError, validate_notebook


SHARED_ROOT = Path(__file__).resolve().parents[2]
INK_ROOT = SHARED_ROOT / "fixtures" / "ink"
ALLOWED_BRUSHES = {"pen", "pencil", "highlighter"}


class GoldenInkFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = json.loads((INK_ROOT / "expected_results.json").read_text(encoding="utf-8"))

    @staticmethod
    def _archive_pages(relative_path: str) -> tuple[dict, list[dict]]:
        """Read the page files referenced by the manifest, in manifest order."""
        with zipfile.ZipFile(INK_ROOT / relative_path) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            pages = [json.loads(archive.read(ref["path"])) for ref in manifest["pages"]]
        return manifest, pages

    def test_catalog_matches_committed_packages(self) -> None:
        valid = [case["path"] for case in self.catalog["valid"]]
        invalid = [case["path"] for case in self.catalog["invalid"]]
        self.assertEqual(self.catalog["contract"], "FMT-04 sampledPointsV1 synthetic canonical ink fixtures")
        self.assertEqual(len(valid), 6)
        self.assertEqual(len(invalid), 11)
        self.assertEqual(len(set(valid + invalid)), 17, "Duplicate catalog entries")

        discovered = {path.relative_to(INK_ROOT).as_posix() for path in INK_ROOT.rglob("*.notebook")}
        self.assertEqual(discovered, set(valid + invalid), "Unlisted or missing ink packages")
        self.assertTrue(all(case["reason"] for case in self.catalog["valid"] + self.catalog["invalid"]))
        self.assertTrue(all(case["error_contains"] for case in self.catalog["invalid"]))

    def test_committed_fixtures_match_deterministic_generator(self) -> None:
        expected = build_all()  # No filesystem writes.
        self.assertEqual(set(expected), {
            *[case["path"] for case in self.catalog["valid"]],
            *[case["path"] for case in self.catalog["invalid"]],
            "expected_results.json",
        })
        for relative_path, regenerated_bytes in expected.items():
            with self.subTest(path=relative_path):
                self.assertEqual(
                    (INK_ROOT / relative_path).read_bytes(), regenerated_bytes,
                    "Fixture was edited by hand or is out of sync with the generator",
                )

    def test_all_valid_ink_packages_pass_existing_validator(self) -> None:
        for case in self.catalog["valid"]:
            with self.subTest(path=case["path"]):
                manifest = validate_notebook(INK_ROOT / case["path"])
                self.assertEqual(manifest["schemaVersion"], 1)

    def test_all_invalid_ink_packages_are_rejected_for_expected_reason(self) -> None:
        for case in self.catalog["invalid"]:
            with self.subTest(path=case["path"]):
                with self.assertRaises(NotebookValidationError) as caught:
                    validate_notebook(INK_ROOT / case["path"])
                self.assertIn(case["error_contains"], str(caught.exception))

    def test_valid_fixtures_store_only_canonical_page_local_ink(self) -> None:
        """Check golden fixture semantics independently of native ink adapters."""
        for case in self.catalog["valid"]:
            with self.subTest(path=case["path"]):
                manifest, pages = self._archive_pages(case["path"])
                self.assertEqual(len(manifest["pages"]), len(pages))
                self.assertEqual([ref["id"] for ref in manifest["pages"]], [page["id"] for page in pages])
                for page in pages:
                    self.assertGreater(page["size"]["width"], 0)
                    self.assertGreater(page["size"]["height"], 0)
                    for element in page["elements"]:
                        self.assertEqual(element["type"], "stroke")
                        self.assertEqual(len(element["transform"]), 6)
                        payload = element["payload"]
                        self.assertEqual(payload["encoding"], "sampledPointsV1")
                        brush = payload["brush"]
                        self.assertIn(brush["kind"], ALLOWED_BRUSHES)
                        self.assertGreater(brush["width"], 0)
                        self.assertGreaterEqual(brush["opacity"], 0)
                        self.assertLessEqual(brush["opacity"], 1)
                        points = payload["points"]
                        self.assertGreaterEqual(len(points), 1)
                        self.assertEqual(points[0]["tMs"], 0)
                        previous_time = -1
                        for point in points:
                            for axis in ("x", "y"):
                                self.assertTrue(math.isfinite(point[axis]))
                            self.assertIsInstance(point["tMs"], int)
                            self.assertGreaterEqual(point["tMs"], previous_time)
                            previous_time = point["tMs"]
                            if "pressure" in point:
                                self.assertGreaterEqual(point["pressure"], 0)
                                self.assertLessEqual(point["pressure"], 1)
                            if "tiltRad" in point:
                                self.assertGreaterEqual(point["tiltRad"], 0)
                                self.assertLessEqual(point["tiltRad"], math.pi / 2)
                            if "orientationRad" in point:
                                self.assertTrue(math.isfinite(point["orientationRad"]))

    def test_required_edge_cases_are_represented(self) -> None:
        _, straight_pages = self._archive_pages("valid/ink_pen_straight_v1.notebook")
        straight = straight_pages[0]["elements"][0]["payload"]
        self.assertEqual(straight["brush"]["kind"], "pen")
        self.assertEqual({p["pressure"] for p in straight["points"]}, {0.5})

        _, curved_pages = self._archive_pages("valid/ink_pen_curve_v1.notebook")
        curved_points = curved_pages[0]["elements"][0]["payload"]["points"]
        self.assertLess(len({p["tMs"] for p in curved_points}), len(curved_points))
        self.assertGreater(len({p["pressure"] for p in curved_points}), 1)

        _, pencil_pages = self._archive_pages("valid/ink_pencil_tilt_v1.notebook")
        pencil = pencil_pages[0]["elements"][0]["payload"]
        self.assertEqual(pencil["brush"]["kind"], "pencil")
        self.assertTrue(all("tiltRad" in p and "orientationRad" in p for p in pencil["points"]))

        _, highlighter_pages = self._archive_pages("valid/ink_highlighter_v1.notebook")
        highlighter = highlighter_pages[0]["elements"][0]["payload"]["brush"]
        self.assertEqual(highlighter["kind"], "highlighter")
        self.assertLess(highlighter["opacity"], 1)

        _, single_pages = self._archive_pages("valid/ink_single_sample_v1.notebook")
        single = single_pages[0]["elements"][0]["payload"]["points"]
        self.assertEqual(len(single), 1)
        self.assertTrue(all(field not in single[0] for field in ("pressure", "tiltRad", "orientationRad")))

        _, mixed_pages = self._archive_pages("valid/ink_page_local_v1.notebook")
        self.assertEqual(len(mixed_pages), 2)
        self.assertNotEqual(mixed_pages[0]["size"], mixed_pages[1]["size"])
        self.assertNotEqual(mixed_pages[0]["id"], mixed_pages[1]["id"])
        self.assertNotEqual(mixed_pages[0]["elements"][0]["id"], mixed_pages[1]["elements"][0]["id"])
        self.assertEqual(mixed_pages[1]["elements"][0]["transform"], [1, 0, 0, 1, 4, -2])


if __name__ == "__main__":
    unittest.main()
