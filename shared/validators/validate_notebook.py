"""FMT-02 checkpoint 1: safe ZIP/package preflight (not full schema validation)."""

from __future__ import annotations

import json
import re
import stat
import zipfile
from pathlib import Path
from typing import Any

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
