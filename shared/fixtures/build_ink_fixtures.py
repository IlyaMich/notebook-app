"""FMT-04 CP2: build deterministic, synthetic canonical ink .notebook fixtures.

From the repository root:
    py -3 shared/fixtures/build_ink_fixtures.py --check
    py -3 shared/fixtures/build_ink_fixtures.py --write

Uses the Python standard library only. The FMT-03 fixture catalog remains untouched.
"""
from __future__ import annotations

import argparse
from io import BytesIO
import json
import math
from pathlib import Path
import uuid
import zipfile

OUTPUT_ROOT = Path(__file__).resolve().parent / "ink"
NAMESPACE = uuid.UUID("1a5e8af9-5935-47dc-a1db-236f6a335990")
STAMP = "2026-10-30T00:00:00Z"

# Names, rationale and Python validator error fragments are an evaluation catalog.
# Error wording is NOT part of the cross-platform document contract.
VALID_CASES = (
    ("ink_pen_straight_v1", "Straight pen with constant pressure and exact integer timestamps"),
    ("ink_pen_curve_v1", "Curved pen with varying pressure and valid repeated timestamps"),
    ("ink_pencil_tilt_v1", "Pencil with optional tilt and signed page-space orientation"),
    ("ink_highlighter_v1", "Translucent highlighter preserving semantic brush values"),
    ("ink_single_sample_v1", "Single-point stroke with all optional characteristics absent"),
    ("ink_page_local_v1", "Two mixed-size pages and independent page-local strokes"),
)
INVALID_CASES = (
    ("ink_decreasing_time", "Non-monotonic stroke tMs", "Decreasing timestamps are not permitted"),
    ("ink_negative_time", "Schema violation", "Negative elapsed time is not permitted"),
    ("ink_unknown_encoding", "Schema violation", "Unknown required stroke encoding cannot be silently accepted"),
    ("ink_unknown_brush", "Schema violation", "Unsupported canonical brush is not part of the agreed subset"),
    ("ink_negative_pressure", "Schema violation", "Pressure below zero is invalid"),
    ("ink_excess_pressure", "Schema violation", "Pressure above one is invalid"),
    ("ink_excess_tilt", "Schema violation", "Tilt exceeds the canonical upper bound of pi/2"),
    ("ink_zero_width", "Schema violation", "Brush width must be positive"),
    ("ink_empty_points", "Schema violation", "A stroke requires at least one point"),
    ("ink_nan_coordinate", "Non-finite JSON number", "NaN is not a canonical coordinate"),
    ("ink_infinite_coordinate", "Non-finite JSON number", "Infinity is not a canonical coordinate"),
)


def identifier(label: str) -> str:
    return str(uuid.uuid5(NAMESPACE, "FMT-04:" + label))


def _brush(kind="pen", width=2.5, color="#2255AA", opacity=1.0) -> dict:
    return {"kind": kind, "width": width, "color": color, "opacity": opacity}


def _stroke(case: str, suffix: str, brush: dict, points: list[dict], *, transform=None) -> dict:
    return {
        "id": identifier(f"{case}:element:{suffix}"),
        "type": "stroke",
        "transform": [1, 0, 0, 1, 0, 0] if transform is None else transform,
        "locked": False,
        "payload": {"encoding": "sampledPointsV1", "brush": brush, "points": points},
    }


def _make_document(case: str) -> tuple[dict, list[dict]]:
    if case == "ink_pen_straight_v1":
        elements = [_stroke(case, "main", _brush(), [
            {"x": 60.25, "y": 120.0, "tMs": 0, "pressure": 0.5},
            {"x": 110.5, "y": 120.0, "tMs": 17, "pressure": 0.5},
            {"x": 180.75, "y": 120.0, "tMs": 34, "pressure": 0.5},
        ])]
    elif case == "ink_pen_curve_v1":
        elements = [_stroke(case, "main", _brush(width=3.25, color="#0A54C0"), [
            {"x": 44.125, "y": 95.75, "tMs": 0, "pressure": 0.2},
            {"x": 58.5, "y": 86.25, "tMs": 11, "pressure": 0.45},
            {"x": 78.875, "y": 103.5, "tMs": 11, "pressure": 0.88},
            {"x": 94.25, "y": 142.125, "tMs": 27, "pressure": 0.51},
            {"x": 130.0, "y": 150.75, "tMs": 42, "pressure": 0.1},
        ])]
    elif case == "ink_pencil_tilt_v1":
        elements = [_stroke(case, "main", _brush(kind="pencil", width=1.8,
                                                   color="#4A4A4A", opacity=0.9), [
            {"x": 37.5, "y": 55.5, "tMs": 0, "pressure": 0.38,
             "tiltRad": 0.0, "orientationRad": -1.5707963267948966},
            {"x": 68.25, "y": 79.25, "tMs": 14, "pressure": 0.59,
             "tiltRad": 0.48, "orientationRad": 0.25},
            {"x": 101.125, "y": 62.875, "tMs": 33, "pressure": 0.77,
             "tiltRad": 1.5707963267948966, "orientationRad": 3.0},
        ])]
    elif case == "ink_highlighter_v1":
        elements = [_stroke(case, "main", _brush(kind="highlighter", width=14.0,
                                                   color="#FFC633", opacity=0.35), [
            {"x": 35.0, "y": 180.5, "tMs": 0},
            {"x": 210.0, "y": 182.0, "tMs": 28},
        ])]
    elif case == "ink_single_sample_v1":
        elements = [_stroke(case, "main", _brush(kind="pen", width=2.0,
                                                   color="#AA4400", opacity=1.0), [
            {"x": 300.0, "y": 400.0, "tMs": 0},
        ])]
    elif case == "ink_page_local_v1":
        elements = [_stroke(case, "portrait", _brush(), [
            {"x": 12.0, "y": 16.0, "tMs": 0},
            {"x": 595.276, "y": 841.89, "tMs": 30},
        ])]
    else:
        # Invalid fixtures start from the same valid minimal reference case.
        elements = [_stroke(case, "main", _brush(), [
            {"x": 45.0, "y": 52.0, "tMs": 0, "pressure": 0.35},
            {"x": 75.0, "y": 82.0, "tMs": 20, "pressure": 0.72},
        ])]

    page_id = identifier(f"{case}:page:0")
    page = {
        "id": page_id,
        "size": {"width": 595.276, "height": 841.89},
        "background": {"type": "blank", "color": "#FFFFFF"},
        "elements": elements,
    }
    pages = [page]
    if case == "ink_page_local_v1":
        other_id = identifier(f"{case}:page:1")
        pages.append({
            "id": other_id,
            "size": {"width": 792, "height": 612},
            "background": {"type": "blank", "color": "#FFFFFF"},
            "elements": [_stroke(case, "landscape", _brush(kind="pencil", width=1.25), [
                {"x": 5.0, "y": 6.0, "tMs": 0},
                {"x": 390.0, "y": 120.0, "tMs": 50},
            ], transform=[1, 0, 0, 1, 4, -2])],
        })
    manifest = {
        "schemaVersion": 1,
        "id": identifier(f"{case}:note"),
        "title": f"FMT-04 synthetic ink fixture: {case}",
        "createdAt": STAMP,
        "updatedAt": STAMP,
        "pages": [{"id": p["id"], "path": f"pages/{p['id']}.json"} for p in pages],
        "assets": [],
    }
    return manifest, pages


def _json_bytes(obj: dict, *, allow_nonfinite: bool = False) -> bytes:
    return (json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False,
                       allow_nan=allow_nonfinite) + "\n").encode("utf-8")


def _archive(manifest: dict, pages: list[dict], *, allow_nonfinite=False) -> bytes:
    members = [("manifest.json", _json_bytes(manifest))]
    members.extend((f"pages/{page['id']}.json", _json_bytes(page, allow_nonfinite=allow_nonfinite))
                   for page in pages)
    result = BytesIO()
    with zipfile.ZipFile(result, mode="w") as zf:
        for name, content in members:
            info = zipfile.ZipInfo(name, date_time=(2026, 10, 30, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            zf.writestr(info, content)
    return result.getvalue()


def build_valid(name: str) -> bytes:
    if name not in {case for case, _ in VALID_CASES}:
        raise ValueError(f"Unknown valid case: {name}")
    manifest, pages = _make_document(name)
    return _archive(manifest, pages)


def build_invalid(name: str) -> bytes:
    if name not in {case for case, _, _ in INVALID_CASES}:
        raise ValueError(f"Unknown invalid case: {name}")
    manifest, pages = _make_document(name)
    payload = pages[0]["elements"][0]["payload"]
    points, brush = payload["points"], payload["brush"]
    if name == "ink_decreasing_time":
        points[0]["tMs"], points[1]["tMs"] = 20, 10
    elif name == "ink_negative_time":
        points[0]["tMs"] = -1
    elif name == "ink_unknown_encoding":
        payload["encoding"] = "nativeInkBlobV1"
    elif name == "ink_unknown_brush":
        brush["kind"] = "airbrush"
    elif name == "ink_negative_pressure":
        points[0]["pressure"] = -0.01
    elif name == "ink_excess_pressure":
        points[1]["pressure"] = 1.01
    elif name == "ink_excess_tilt":
        points[0]["tiltRad"] = math.pi / 2 + 0.1
    elif name == "ink_zero_width":
        brush["width"] = 0
    elif name == "ink_empty_points":
        payload["points"] = []
    elif name == "ink_nan_coordinate":
        points[0]["x"] = math.nan
    elif name == "ink_infinite_coordinate":
        points[1]["y"] = math.inf
    else:
        raise ValueError(f"Unknown invalid case: {name}")
    return _archive(manifest, pages, allow_nonfinite=name in {
        "ink_nan_coordinate", "ink_infinite_coordinate"})


def expected_results() -> dict:
    return {
        "contract": "FMT-04 sampledPointsV1 synthetic canonical ink fixtures",
        "valid": [{"path": f"valid/{name}.notebook", "reason": reason}
                  for name, reason in VALID_CASES],
        "invalid": [{"path": f"invalid/{name}.notebook", "error_contains": fragment,
                     "reason": reason}
                    for name, fragment, reason in INVALID_CASES],
    }


def build_all() -> dict[str, bytes]:
    result = {f"valid/{name}.notebook": build_valid(name) for name, _ in VALID_CASES}
    result.update({f"invalid/{name}.notebook": build_invalid(name)
                   for name, _, _ in INVALID_CASES})
    result["expected_results.json"] = _json_bytes(expected_results())
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="Regenerate committed ink fixtures")
    mode.add_argument("--check", action="store_true", help="Compare fixtures byte for byte without writing")
    options = parser.parse_args()
    fixture_bytes = build_all()
    if options.write:
        for relative, data in fixture_bytes.items():
            target = OUTPUT_ROOT / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            print(f"WROTE {target.relative_to(OUTPUT_ROOT)} ({len(data)} bytes)")
    else:
        errors = []
        for relative, data in fixture_bytes.items():
            target = OUTPUT_ROOT / relative
            if not target.exists() or target.read_bytes() != data:
                errors.append(relative)
        if errors:
            for item in errors:
                print(f"MISMATCH OR MISSING: {item}")
            return 1
        print(f"PASS: {len(fixture_bytes) - 1} deterministic FMT-04 ink packages and catalog")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
