"""FMT-02: safe .notebook preflight, draft schema, and semantic checks."""

from __future__ import annotations

import argparse
import hashlib
from datetime import datetime, timedelta
import json
import math
import re
import stat
import zipfile
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker

MAX_ARCHIVE_BYTES = 100 * 1024 * 1024  # Provisional safety limits; review in FMT-01/02.
MAX_UNCOMPRESSED_BYTES = 256 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_ENTRIES = 10_000


class NotebookValidationError(ValueError):
    """A .notebook archive is unsafe or structurally inconsistent."""


def _safe_member_name(name: str) -> bool:
    if not name or name.startswith("/") or "\\" in name or "\x00" in name:
        return False
    parts = name.split("/")
    # Reject dot segments, empty segments, and Windows drive/ADS syntax.
    return all(part not in ("", ".", "..") and ":" not in part for part in parts)


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise NotebookValidationError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise NotebookValidationError(f"Non-finite JSON number: {value}")


def preflight_notebook(archive: Path) -> dict[str, Any]:
    """Check archive safety and manifest/page references; NOT full V1 conformance."""
    try:
        if archive.stat().st_size > MAX_ARCHIVE_BYTES:
            raise NotebookValidationError("Compressed archive exceeds size limit")
        with zipfile.ZipFile(archive) as package:
            infos = package.infolist()
            if len(infos) > MAX_ENTRIES:
                raise NotebookValidationError("Too many ZIP entries")

            names: set[str] = set()
            folded_names: set[str] = set()
            total_size = 0
            for info in infos:
                name = info.filename
                if not _safe_member_name(name) or info.is_dir():
                    raise NotebookValidationError(f"Unsafe ZIP member: {name!r}")
                if name in names or name.casefold() in folded_names:
                    raise NotebookValidationError(f"Duplicate ZIP member: {name!r}")
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise NotebookValidationError(f"ZIP symlink is forbidden: {name!r}")
                if info.flag_bits & 0x1:
                    raise NotebookValidationError(f"Encrypted ZIP member is unsupported: {name!r}")
                names.add(name)
                folded_names.add(name.casefold())
                total_size += info.file_size
                if total_size > MAX_UNCOMPRESSED_BYTES:
                    raise NotebookValidationError("Uncompressed ZIP exceeds size limit")

            if "manifest.json" not in names:
                raise NotebookValidationError("Missing manifest.json")
            if package.getinfo("manifest.json").file_size > MAX_MANIFEST_BYTES:
                raise NotebookValidationError("Manifest exceeds size limit")
            manifest = json.loads(
                package.read("manifest.json").decode("utf-8"),
                object_pairs_hook=_no_duplicate_keys,
                parse_constant=_reject_nonfinite,
            )
            if not isinstance(manifest, dict):
                raise NotebookValidationError("Manifest must be a JSON object")
            version = manifest.get("schemaVersion")
            if type(version) is not int or version != 1:
                raise NotebookValidationError(f"Unsupported schemaVersion: {version!r}")
            pages = manifest.get("pages")
            if not isinstance(pages, list) or not pages:
                raise NotebookValidationError("Manifest must reference at least one page")
            ids: set[str] = set()
            paths: set[str] = set()
            for reference in pages:
                if not isinstance(reference, dict):
                    raise NotebookValidationError("Page reference must be an object")
                page_id, path = reference.get("id"), reference.get("path")
                if not isinstance(page_id, str) or not re.fullmatch(
                    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                    page_id,
                ):
                    raise NotebookValidationError("Invalid manifest page ID")
                if not isinstance(path, str) or not _safe_member_name(path):
                    raise NotebookValidationError("Unsafe manifest page path")
                if page_id in ids or path in paths:
                    raise NotebookValidationError("Duplicate manifest page ID or path")
                if path not in names:
                    raise NotebookValidationError(f"Missing referenced page: {path}")
                ids.add(page_id)
                paths.add(path)
            return manifest
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise NotebookValidationError(f"Unreadable .notebook archive: {exc}") from exc

# Checkpoint 2: draft JSON Schema verification. Semantic checks are implemented
# incrementally in subsequent FMT-02 checkpoints.
SCHEMA_PATH = Path(__file__).resolve().parents[1] / "schemas" / "notebook_schema_v1_draft.json"
MAX_PAGE_JSON_BYTES = 16 * 1024 * 1024  # Provisional; benchmark before release.
MAX_ELEMENTS_PER_PAGE = 20_000  # Provisional, pending benchmark.
MAX_STROKE_POINTS = 100_000  # Provisional, pending benchmark.


def _finite_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise NotebookValidationError(f"Non-finite JSON number: {value}")
    return parsed


def _read_json_member(package: zipfile.ZipFile, name: str, limit: int) -> dict[str, Any]:
    info = package.getinfo(name)
    if info.file_size > limit:
        raise NotebookValidationError(f"JSON member exceeds size limit: {name}")
    data = json.loads(
        package.read(name).decode("utf-8"),
        object_pairs_hook=_no_duplicate_keys,
        parse_constant=_reject_nonfinite,
        parse_float=_finite_float,
    )
    if not isinstance(data, dict):
        raise NotebookValidationError(f"JSON member must be an object: {name}")
    return data


def _schema_check(validator: Draft202012Validator, document: dict[str, Any], label: str) -> None:
    error = next(validator.iter_errors(document), None)
    if error is not None:
        location = "/".join(map(str, error.absolute_path)) or "<root>"
        raise NotebookValidationError(f"Schema violation in {label} at {location}: {error.message}")


def _validate_timestamps(manifest: dict[str, Any]) -> None:
    parsed = []
    for field in ("createdAt", "updatedAt"):
        try:
            stamp = datetime.fromisoformat(manifest[field].replace("Z", "+00:00"))
        except ValueError as exc:
            raise NotebookValidationError(f"Invalid {field} timestamp") from exc
        # JSON Schema permits nonzero offsets; portable metadata requires UTC.
        if stamp.tzinfo is None or stamp.utcoffset() != timedelta(0):
            raise NotebookValidationError(f"{field} must be UTC")
        parsed.append(stamp)
    if parsed[0] > parsed[1]:
        raise NotebookValidationError("createdAt must not be later than updatedAt")


def _validate_transform(transform: list[float], element_id: str) -> None:
    a, b, c, d, _, _ = transform
    determinant = a * d - b * c
    if determinant == 0 or not math.isfinite(determinant):
        raise NotebookValidationError(f"Singular or unstable transform: {element_id}")


def _verify_asset_bytes(package: zipfile.ZipFile, asset: dict[str, Any]) -> None:
    expected_extension = {"image/png": ".png", "image/jpeg": ".jpg", "application/pdf": ".pdf"}
    extension = expected_extension[asset["mimeType"]]
    path = asset["path"]
    if path.casefold() != f"assets/{asset['sha256'].lower()}{extension}":
        raise NotebookValidationError(f"Asset path does not match hash and MIME: {path}")
    try:
        info = package.getinfo(path)
    except KeyError as exc:
        raise NotebookValidationError(f"Missing asset file: {path}") from exc
    if info.file_size != asset["byteLength"]:
        raise NotebookValidationError(f"Asset byteLength mismatch: {path}")

    digest = hashlib.sha256()
    signature = b""
    # Stream assets to avoid loading images/PDFs into memory.
    with package.open(info) as source:
        while chunk := source.read(64 * 1024):
            if len(signature) < 8:
                signature = (signature + chunk)[:8]
            digest.update(chunk)
    if digest.hexdigest() != asset["sha256"].lower():
        raise NotebookValidationError(f"Asset SHA-256 mismatch: {path}")
    prefixes = {"image/png": b"\x89PNG\r\n\x1a\n", "image/jpeg": b"\xff\xd8\xff",
                "application/pdf": b"%PDF-"}
    if not signature.startswith(prefixes[asset["mimeType"]]):
        raise NotebookValidationError(f"Asset MIME/signature mismatch: {path}")


def _validate_page_semantics(
    page: dict[str, Any], assets_by_id: dict[str, dict[str, Any]], seen_element_ids: set[str]
) -> None:
    page_id = page["id"]
    if len(page["elements"]) > MAX_ELEMENTS_PER_PAGE:
        raise NotebookValidationError(f"Too many elements on page {page_id}")

    background = page["background"]
    if background["type"] == "pdfPage":
        asset = assets_by_id.get(background["assetId"].casefold())
        if asset is None or asset["mimeType"] != "application/pdf":
            raise NotebookValidationError(f"PDF background references missing/non-PDF asset: {page_id}")
        # Full PDF parsing and pageIndex verification remain a separate checkpoint.

    for element in page["elements"]:
        element_id = element["id"]
        key = element_id.casefold()
        if key in seen_element_ids:
            raise NotebookValidationError(f"Duplicate element ID: {element_id}")
        seen_element_ids.add(key)
        _validate_transform(element["transform"], element_id)

        payload = element["payload"]
        if element["type"] == "stroke":
            points = payload["points"]
            if len(points) > MAX_STROKE_POINTS:
                raise NotebookValidationError(f"Too many stroke points: {element_id}")
            last_time = -1
            for point in points:
                if point["tMs"] < last_time:
                    raise NotebookValidationError(f"Non-monotonic stroke tMs: {element_id}")
                last_time = point["tMs"]
        elif element["type"] == "image":
            asset = assets_by_id.get(payload["assetId"].casefold())
            if asset is None or asset["mimeType"] not in ("image/png", "image/jpeg"):
                raise NotebookValidationError(f"Image references missing/non-image asset: {element_id}")
            crop = payload.get("crop")
            if crop is not None and (crop["x"] + crop["width"] > 1 or
                                     crop["y"] + crop["height"] > 1):
                raise NotebookValidationError(f"Image crop exceeds normalized bounds: {element_id}")


def validate_notebook(archive: Path) -> dict[str, Any]:
    """Validate the draft schema and implemented semantic invariants.

    Returns manifest after validation. NOT a production-ready security boundary:
    embedded image/PDF decoding and PDF page-index checks are deferred.
    """
    preflight_notebook(archive)
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    page_schema = {"$ref": "#/$defs/page", "$defs": schema["$defs"]}
    page_validator = Draft202012Validator(page_schema, format_checker=FormatChecker())
    try:
        with zipfile.ZipFile(archive) as package:
            manifest = _read_json_member(package, "manifest.json", MAX_MANIFEST_BYTES)
            _schema_check(validator, manifest, "manifest.json")
            _validate_timestamps(manifest)

            asset_ids: set[str] = set()
            asset_paths: set[str] = set()
            assets_by_id: dict[str, dict[str, Any]] = {}
            for asset in manifest["assets"]:
                key = asset["id"].casefold()
                path = asset["path"].casefold()
                if key in asset_ids or path in asset_paths:
                    raise NotebookValidationError(f"Duplicate asset ID or path: {asset['path']}")
                asset_ids.add(key)
                asset_paths.add(path)
                assets_by_id[key] = asset
                _verify_asset_bytes(package, asset)

            # Only explicitly declared files belong in a schema v1 archive.
            declared = {"manifest.json"}
            declared.update(reference["path"] for reference in manifest["pages"])
            declared.update(asset["path"] for asset in manifest["assets"])
            extra = set(package.namelist()) - declared
            if extra:
                raise NotebookValidationError(f"Unlisted archive member: {sorted(extra)[0]}")

            seen_element_ids: set[str] = set()
            seen_page_ids: set[str] = set()
            for reference in manifest["pages"]:
                name = reference["path"]
                key = reference["id"].casefold()
                if key in seen_page_ids:
                    raise NotebookValidationError(f"Duplicate manifest page ID: {reference['id']}")
                seen_page_ids.add(key)
                if name.casefold() != f"pages/{reference['id'].lower()}.json":
                    raise NotebookValidationError(f"Page path does not match its ID: {name}")
                page = _read_json_member(package, name, MAX_PAGE_JSON_BYTES)
                _schema_check(page_validator, page, name)
                if page["id"].casefold() != key:
                    raise NotebookValidationError(f"Page ID mismatch: {name}")
                _validate_page_semantics(page, assets_by_id, seen_element_ids)
            return manifest
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError,
            UnicodeError, ValueError) as exc:
        if isinstance(exc, NotebookValidationError):
            raise
        raise NotebookValidationError(f"Unreadable .notebook archive: {exc}") from exc


def _main() -> int:
    parser = argparse.ArgumentParser(description="Validate a draft .notebook package")
    parser.add_argument("archive", type=Path, help="Path to .notebook archive")
    args = parser.parse_args()
    try:
        validate_notebook(args.archive)
    except NotebookValidationError as exc:
        print(f"INVALID: {exc}")
        return 1
    print(f"VALID (preflight + draft schema + implemented semantic checks): {args.archive}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
