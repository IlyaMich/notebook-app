"""Build deterministic FMT-03 valid-edge and invalid .notebook fixtures.

Run from the repository root:
    py -3 shared/fixtures/build_edge_cases.py

Uses only the Python standard library and the existing FMT-03 golden builder.
The expected-results catalog is a test contract, not the wire-format specification.
"""
from __future__ import annotations

import copy
from io import BytesIO
from pathlib import Path
import warnings
import zipfile

if __package__:
    from .build_golden import identifier, json_bytes, make_documents
else:
    from build_golden import identifier, json_bytes, make_documents

ROOT = Path(__file__).resolve().parent

# (filename stem, expected Python validator message fragment, concise rationale)
# Reasons express the currently implemented FMT-02 behavior, not a required
# message format for future Swift or Kotlin validators.
INVALID_CASES = (
    ("traversal_member", "Unsafe ZIP member", "ZIP entry escapes the package root"),
    ("absolute_path_member", "Unsafe ZIP member", "ZIP entry uses an absolute path"),
    ("duplicate_zip_member", "Duplicate ZIP member", "Two ZIP entries have the same name"),
    ("symlink_member", "ZIP symlink is forbidden", "ZIP member is a symbolic link"),
    ("missing_manifest", "Missing manifest.json", "Required manifest is absent"),
    ("unsupported_schema_version", "Unsupported schemaVersion", "Unknown wire version must not be accepted"),
    ("missing_page", "Missing referenced page", "Manifest references an absent page JSON file"),
    ("duplicate_manifest_json_key", "Duplicate JSON key", "Manifest contains ambiguous duplicate object key"),
    ("negative_page_width", "Schema violation", "Page width must be positive"),
    ("unknown_element_type", "Schema violation", "Unknown required element type must not be dropped"),
    ("duplicate_element_id", "Duplicate element ID", "Element identity must be unique throughout a note"),
    ("singular_transform", "Singular or unstable transform", "Element transformation is noninvertible"),
    ("nonmonotonic_stroke_time", "Non-monotonic stroke tMs", "Stroke sample timestamps must not go backwards"),
    ("out_of_bounds_image_crop", "Image crop exceeds normalized bounds", "Normalized crop spills outside the source image"),
    ("missing_image_asset_reference", "Image references missing/non-image asset", "Image element references an unknown asset ID"),
    ("wrong_pdf_background_asset", "PDF background references missing/non-PDF asset", "PDF page uses a PNG asset ID"),
    ("altered_png_bytes", "Asset SHA-256 mismatch", "Asset bytes must match the manifest hash"),
    ("incorrect_asset_length", "Asset byteLength mismatch", "Manifest byte count must match asset content"),
    ("missing_asset_member", "Missing asset file", "Manifest declares an asset not in the ZIP"),
    ("duplicate_asset_id", "Duplicate asset ID or path", "Assets must have unique IDs"),
    ("creation_after_modification", "createdAt must not be later", "Creation timestamp cannot follow modification timestamp"),
    ("non_utc_timestamp", "createdAt must be UTC", "Portable timestamps must have UTC offsets"),
    ("unlisted_archive_member", "Unlisted archive member", "Package contains a file not listed in its manifest"),
    ("page_id_mismatch", "Page ID mismatch", "Page JSON ID must agree with manifest reference"),
)

VALID_CASES = (
    ("empty_page_v1", "One blank page, no elements or assets, equal UTC creation/update times"),
    ("single_point_stroke_v1", "One-point stroke, zero opacity, reflection transform and dotted paper"),
)


def _archive(members: list[tuple[str, bytes]], symlink: str | None = None) -> bytes:
    output = BytesIO()
    with zipfile.ZipFile(output, mode="w") as package:
        for filename, content in members:
            info = zipfile.ZipInfo(filename, date_time=(2026, 10, 21, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED  # Cross-platform deterministic bytes.
            info.external_attr = (0o120777 if filename == symlink else 0o100644) << 16
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)  # Intentional duplicate ZIP entry.
                package.writestr(info, content)
    return output.getvalue()


def _source():
    manifest, pages, assets = make_documents()
    return copy.deepcopy(manifest), copy.deepcopy(pages), dict(assets)


def _pack(manifest: dict, pages: dict[str, dict], assets: dict[str, bytes], *,
          missing_manifest=False, extra: list[tuple[str, bytes]] | None = None,
          symlink: str | None = None, duplicate_manifest=False,
          duplicate_json_key=False) -> bytes:
    mbytes = json_bytes(manifest)
    if duplicate_json_key:
        mbytes = mbytes.replace(b'  "title":', b'  "title": "duplicate key",\n  "title":', 1)
    members = [] if missing_manifest else [("manifest.json", mbytes)]
    members.extend((p, json_bytes(doc)) for p, doc in pages.items())
    members.extend(assets.items())
    members.extend(extra or [])
    if duplicate_manifest:
        members.append(("manifest.json", mbytes))
    return _archive(members, symlink=symlink)


def build_valid(name: str) -> bytes:
    manifest, pages, assets = _source()
    first = manifest["pages"][0]
    page = pages[first["path"]]
    manifest["id"] = identifier(f"note:edge:{name}")
    fresh_page_id = identifier(f"page:edge:{name}")
    first["id"] = fresh_page_id
    first["path"] = f"pages/{fresh_page_id}.json"
    page["id"] = fresh_page_id
    manifest["pages"] = [first]
    manifest["assets"] = []
    manifest["title"] = f"FMT-03 synthetic edge: {name}"
    manifest["updatedAt"] = manifest["createdAt"]
    assets = {}
    page["background"] = {"type": "blank", "color": "#FFFFFF"}
    page["elements"] = []
    if name == "single_point_stroke_v1":
        page["background"] = {"type": "dotted", "color": "#FFFFFF",
                              "patternColor": "#CED8F1", "spacing": 12}
        page["elements"] = [{
            "id": identifier("element:edge-single-sample"), "type": "stroke",
            "transform": [-1, 0, 0, 1, 120, 0], "locked": False,
            "payload": {"encoding": "sampledPointsV1",
                        "brush": {"kind": "pencil", "width": 1.5,
                                  "color": "#123456", "opacity": 0},
                        "points": [{"x": 50, "y": 60, "tMs": 0}]},
        }]
    return _pack(manifest, {first["path"]: page}, assets)


def build_invalid(name: str) -> bytes:
    manifest, pages, assets = _source()
    first_path = manifest["pages"][0]["path"]
    second_path = manifest["pages"][1]["path"]
    fourth_path = manifest["pages"][3]["path"]
    first = pages[first_path]
    second = pages[second_path]
    fourth = pages[fourth_path]
    extras = []
    options = {}

    if name == "traversal_member":
        extras.append(("../outside.txt", b"synthetic"))
    elif name == "absolute_path_member":
        extras.append(("/etc/unsafe.txt", b"synthetic"))
    elif name == "duplicate_zip_member":
        options["duplicate_manifest"] = True
    elif name == "symlink_member":
        extras.append(("shortcut", b"manifest.json"))
        options["symlink"] = "shortcut"
    elif name == "missing_manifest":
        options["missing_manifest"] = True
    elif name == "unsupported_schema_version":
        manifest["schemaVersion"] = 2
    elif name == "missing_page":
        del pages[first_path]
    elif name == "duplicate_manifest_json_key":
        options["duplicate_json_key"] = True
    elif name == "negative_page_width":
        first["size"]["width"] = -595.276
    elif name == "unknown_element_type":
        first["elements"][0]["type"] = "audio"
    elif name == "duplicate_element_id":
        second["elements"][0]["id"] = first["elements"][0]["id"]
    elif name == "singular_transform":
        first["elements"][0]["transform"] = [1, 0, 0, 0, 0, 0]
    elif name == "nonmonotonic_stroke_time":
        first["elements"][0]["payload"]["points"][2]["tMs"] = 3
    elif name == "out_of_bounds_image_crop":
        second["elements"][0]["payload"]["crop"]["x"] = 0.4
    elif name == "missing_image_asset_reference":
        second["elements"][0]["payload"]["assetId"] = identifier("absent:image")
    elif name == "wrong_pdf_background_asset":
        fourth["background"]["assetId"] = manifest["assets"][0]["id"]
    elif name == "altered_png_bytes":
        png_path = manifest["assets"][0]["path"]
        raw = bytearray(assets[png_path])
        raw[20] ^= 0x1  # Same length and PNG magic; now hash mismatch.
        assets[png_path] = bytes(raw)
    elif name == "incorrect_asset_length":
        manifest["assets"][0]["byteLength"] += 1
    elif name == "missing_asset_member":
        del assets[manifest["assets"][0]["path"]]
    elif name == "duplicate_asset_id":
        manifest["assets"][1]["id"] = manifest["assets"][0]["id"]
    elif name == "creation_after_modification":
        manifest["createdAt"] = "2026-10-22T00:00:00Z"
    elif name == "non_utc_timestamp":
        manifest["createdAt"] = "2026-10-21T14:00:00+02:00"
    elif name == "unlisted_archive_member":
        extras.append(("notes.txt", b"not declared"))
    elif name == "page_id_mismatch":
        first["id"] = identifier("different:page-id")
    else:
        raise ValueError(f"Unknown invalid fixture: {name}")

    return _pack(manifest, pages, assets, extra=extras, **options)


def expected_results() -> dict:
    return {
        "description": "FMT-03 synthetic fixture contract; messages are Python-validator-specific",
        "valid": [{"path": f"valid/{name}.notebook", "reason": reason}
                  for name, reason in VALID_CASES],
        "invalid": [{"path": f"invalid/{name}.notebook", "error_contains": fragment,
                     "reason": reason}
                    for name, fragment, reason in INVALID_CASES],
    }


def build_all() -> dict[str, bytes]:
    files = {f"valid/{name}.notebook": build_valid(name) for name, _ in VALID_CASES}
    files.update({f"invalid/{name}.notebook": build_invalid(name)
                  for name, _, _ in INVALID_CASES})
    files["expected_results.json"] = json_bytes(expected_results())
    return files


def main():
    for relative, payload in build_all().items():
        output = ROOT / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
        print(f"Wrote {output.relative_to(ROOT)} ({len(payload)} bytes)")


if __name__ == "__main__":
    main()
