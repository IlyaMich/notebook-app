#!/usr/bin/env python3
"""EDT-05 CP4B: guarded, reversible switch of the debug-only Jetpack Ink version.

Changes exactly the `inkProbeVersion` literal in android/app/build.gradle.kts.
Refuses to run if the expected debug-only dependency wiring is missing or altered.
Does not modify the release dependencies or any other source code.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import sys
import tempfile

STABLE = "1.0.0"
PREVIEW = "1.1.0-alpha10"
EXPECTED_ARTIFACTS = frozenset({
    "ink-authoring",
    "ink-authoring-compose",
    "ink-brush",
    "ink-brush-compose",
    "ink-geometry",
    "ink-nativeloader",
    "ink-rendering",
    "ink-strokes",
})

VERSION_RE = re.compile(
    r'(?m)^(?P<before>[ \t]*val[ \t]+inkProbeVersion[ \t]*=[ \t]*")'
    r'(?P<version>[^"\r\n]+)(?P<after>"[^\r\n]*)\r?$'
)
INK_DEPENDENCY_RE = re.compile(
    r'(?m)^[ \t]*(?P<configuration>debugImplementation|releaseImplementation|implementation|debugApi|releaseApi|api)'
    r'[ \t]*\([ \t]*"androidx\.ink:(?P<artifact>ink-[a-z0-9-]+):(?P<version>[^"\r\n]+)"[ \t]*\)'
)


class InkConfigurationError(ValueError):
    """The local project is not the known-safe CP1A/CP3C debug wiring."""


def inspect_gradle(source: str) -> tuple[str, re.Match[str]]:
    versions = list(VERSION_RE.finditer(source))
    if len(versions) != 1:
        raise InkConfigurationError(
            "Expected exactly one `val inkProbeVersion = \"...\"` declaration in android/app/build.gradle.kts. "
            "No changes made."
        )
    match = versions[0]
    current = match.group("version")
    if current not in (STABLE, PREVIEW):
        raise InkConfigurationError(f"Unexpected Ink version {current!r}. No changes made.")

    matches = list(INK_DEPENDENCY_RE.finditer(source))
    if not matches:
        raise InkConfigurationError("No Jetpack Ink dependencies were found. No changes made.")

    found = []
    for dep in matches:
        if dep.group("configuration") != "debugImplementation":
            raise InkConfigurationError(
                f"Ink dependency {dep.group('artifact')} uses {dep.group('configuration')}; "
                "CP4B must remain debug-only. No changes made."
            )
        if dep.group("version") != "$inkProbeVersion":
            raise InkConfigurationError(
                f"Ink dependency {dep.group('artifact')} does not reference $inkProbeVersion. "
                "No changes made."
            )
        found.append(dep.group("artifact"))

    if len(found) != len(set(found)):
        raise InkConfigurationError("Duplicate Ink dependency declarations. No changes made.")
    missing = sorted(EXPECTED_ARTIFACTS - set(found))
    if missing:
        raise InkConfigurationError(f"Missing expected Ink artifacts: {', '.join(missing)}. No changes made.")

    # Refuse any references that our strict dependency matcher didn't recognize.
    # The lone version declaration and verified dependency declarations are allowed.
    other_refs = source.count("androidx.ink:") - len(matches)
    if other_refs:
        raise InkConfigurationError(
            "Found unrecognized androidx.ink dependency declarations. No changes made."
        )
    return current, match


def read_current(path: Path) -> str:
    content = path.read_bytes().decode("utf-8-sig")
    return inspect_gradle(content)[0]


def switch_version(path: Path, target: str) -> tuple[str, str, bool]:
    if target not in (STABLE, PREVIEW):
        raise ValueError(f"Unsupported target version: {target}")
    original = path.read_bytes()
    bom = original.startswith(b"\xef\xbb\xbf")
    source = original.decode("utf-8-sig")
    current, match = inspect_gradle(source)
    if current == target:
        return current, target, False
    updated = source[:match.start("version")] + target + source[match.end("version"):]
    inspect_gradle(updated)
    encoded = (b"\xef\xbb\xbf" if bom else b"") + updated.encode("utf-8")
    fd, staging = tempfile.mkstemp(prefix=".edt05-ink-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(encoded)
            out.flush()
            os.fsync(out.fileno())
        os.replace(staging, path)
    finally:
        if os.path.exists(staging):
            os.unlink(staging)
    return current, target, True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("status", "preview", "stable"))
    parser.add_argument(
        "--repo", type=Path, default=Path(__file__).resolve().parents[2],
        help="Notebook repository root (defaults to script's own repository)",
    )
    args = parser.parse_args(argv)
    target = args.repo / "android" / "app" / "build.gradle.kts"
    if not target.is_file():
        raise InkConfigurationError(f"Missing Gradle file: {target}")
    if args.action == "status":
        print(f"Current debug-only Jetpack Ink version: {read_current(target)}")
        return 0
    previous, current, changed = switch_version(
        target, PREVIEW if args.action == "preview" else STABLE
    )
    if changed:
        print(f"Updated debug-only Jetpack Ink: {previous} -> {current}")
        print("Changed file: android/app/build.gradle.kts")
    else:
        print(f"Debug-only Jetpack Ink already set to {current}; no changes made")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (InkConfigurationError, OSError, UnicodeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
