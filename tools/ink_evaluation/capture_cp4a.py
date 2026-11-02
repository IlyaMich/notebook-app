#!/usr/bin/env python3
"""EDT-05 CP4A: non-invasive Android Ink baseline capture using adb.

Only reads app/device diagnostics and resets app-specific gfxinfo at the start.
Does not send input, alter the database, or claim stylus input-to-display latency.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

PACKAGE = "com.notebook.app"
FRAME_MARKER = "---PROFILEDATA---"
LABEL_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,40}$")


def find_adb(explicit: str | None = None) -> str:
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            raise RuntimeError(f"adb path does not exist: {p}")
        return str(p)
    found = shutil.which("adb")
    if found:
        return found
    for home in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")):
        if home:
            p = Path(home) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if p.is_file():
                return str(p)
    if os.name == "nt":
        local = os.environ.get("LOCALAPPDATA")
        if local:
            p = Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
            if p.is_file():
                return str(p)
    raise RuntimeError("adb not found. Pass --adb C:\\...\\adb.exe")


def run_adb(adb: str, serial: str | None, *args: str, allow_error: bool = False) -> str:
    cmd = [adb] + (["-s", serial] if serial else []) + list(args)
    completed = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=45)
    output = (completed.stdout or "").strip()
    if completed.returncode != 0 and not allow_error:
        raise RuntimeError(f"adb returned {completed.returncode}: {' '.join(args)}\n{completed.stderr.strip()}")
    if completed.returncode != 0:
        output = output + "\n" + (completed.stderr or "")
    return output.strip()


def select_device(adb: str, serial: str | None) -> str:
    output = run_adb(adb, None, "devices")
    devices = [line.split("\t", 1)[0] for line in output.splitlines()
               if "\tdevice" in line]
    if serial:
        if serial not in devices:
            raise RuntimeError(f"Requested device {serial!r} is not available: {devices}")
        return serial
    if len(devices) != 1:
        raise RuntimeError(f"Expected one connected Android device; found {devices}. Use --serial.")
    return devices[0]


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def pid_for(adb: str, serial: str) -> str | None:
    pid = run_adb(adb, serial, "shell", "pidof", "-s", PACKAGE, allow_error=True)
    value = pid.splitlines()[0].strip() if pid else ""
    return value if value.isdigit() else None


def snapshot(adb: str, serial: str, root: Path, label: str) -> dict:
    if not LABEL_RE.fullmatch(label):
        raise ValueError("Snapshot label must consist of letters, digits, underscores, hyphens")
    target = root / "snapshots" / label
    target.mkdir(parents=True, exist_ok=False)
    pid = pid_for(adb, serial)
    for stem, cmd in (
        ("meminfo", ("shell", "dumpsys", "meminfo", PACKAGE)),
        ("gfxinfo", ("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")),
    ):
        result = run_adb(adb, serial, *cmd, allow_error=True)
        (target / f"{stem}.txt").write_text(result + "\n", encoding="utf-8")
    info = {"label": label, "utc": utc_now(), "pid": pid}
    write_json(target / "snapshot.json", info)
    return info


def parse_total_pss_kb(text: str) -> int | None:
    # Android versions differ: accept the named summary or the TOTAL table row.
    m = re.search(r"(?im)^\s*TOTAL PSS:\s*([\d,]+)", text)
    if not m:
        m = re.search(r"(?im)^\s*TOTAL\s+([\d,]+)\s+\d+", text)
    return int(m.group(1).replace(",", "")) if m else None


def parse_framestats_ms(text: str) -> list[float]:
    """Read only valid FLAGS=0 rows in gfxinfo PROFILEDATA blocks.

    FRAME_COMPLETED minus INTENDED_VSYNC is a proxy frame duration,
    not input latency nor FrameTimingMetric.frameOverrunMs.
    """
    frames: list[float] = []
    parts = text.split(FRAME_MARKER)
    for block in parts[1::2]:
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        if not lines:
            continue
        header = [re.sub(r"[^A-Z0-9]", "", v.upper()) for v in lines[0].split(",")]
        try:
            flags_index = header.index("FLAGS")
            intended_index = header.index("INTENDEDVSYNC")
            complete_index = header.index("FRAMECOMPLETED")
        except ValueError:
            continue
        for line in lines[1:]:
            values = line.split(",")
            if len(values) != len(header):
                continue
            try:
                flags = int(values[flags_index])
                intended = int(values[intended_index])
                completed = int(values[complete_index])
            except ValueError:
                continue
            if flags != 0 or intended <= 0 or completed <= intended:
                continue
            duration_ms = (completed - intended) / 1_000_000.0
            if 0 < duration_ms < 10_000:  # drop nonsensical device records
                frames.append(duration_ms)
    return frames


def percentile(values: list[float], p: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (len(ordered) - 1) * p / 100
    left = int(rank)
    right = min(left + 1, len(ordered) - 1)
    return ordered[left] + (ordered[right] - ordered[left]) * (rank - left)


def collect_report(root: Path) -> dict:
    metadata = read_json(root / "session.json")
    items = []
    for path in sorted((root / "snapshots").glob("*/snapshot.json")):
        parent = path.parent
        snapshot_data = read_json(path)
        mem = (parent / "meminfo.txt").read_text(encoding="utf-8-sig")
        gfx = (parent / "gfxinfo.txt").read_text(encoding="utf-8-sig")
        frames = parse_framestats_ms(gfx)
        pss = parse_total_pss_kb(mem)
        items.append({
            **snapshot_data,
            "total_pss_kb": pss,
            "frame_count": len(frames),
            "frame_p50_ms": percentile(frames, 50),
            "frame_p95_ms": percentile(frames, 95),
            "frame_p99_ms": percentile(frames, 99),
            "frames_over_16_67_ms": sum(v > 16.67 for v in frames),
            "frames_over_33_33_ms": sum(v > 33.33 for v in frames),
        })
    items.sort(key=lambda item: (0 if item["label"] == "baseline" else 2 if item["label"] == "final" else 1, item["utc"], item["label"]))
    pids = {item["pid"] for item in items if item["pid"]}
    missing = any(item["pid"] is None for item in items)
    return {
        "session": metadata,
        "snapshots": items,
        "process_identity_changed": len(pids) > 1,
        "process_missing_at_snapshot": missing,
        "interpretation": (
            "gfxinfo durations cover only recent app UI frames; they are not stylus "
            "input-to-display latency or full native Ink frame timing. Memory is "
            "device app PSS, not host RAM. Empty frame sets are unavailable data."
        ),
    }


def save_report(root: Path) -> None:
    report = collect_report(root)
    write_json(root / "summary.json", report)
    with (root / "summary.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "label", "utc", "pid", "total_pss_kb", "frame_count", "frame_p50_ms",
            "frame_p95_ms", "frame_p99_ms", "frames_over_16_67_ms", "frames_over_33_33_ms",
        ])
        writer.writeheader()
        writer.writerows(report["snapshots"])
    print(f"Summary: {root / 'summary.csv'}")
    print(f"Full report: {root / 'summary.json'}")
    if report["process_identity_changed"] or report["process_missing_at_snapshot"]:
        print("WARNING: app process changed or was missing; investigate for crash/restart.")
    for entry in report["snapshots"]:
        print(f"  {entry['label']}: PSS={entry['total_pss_kb']} KB; "
              f"recent frames={entry['frame_count']}; p95={entry['frame_p95_ms']} ms")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("start", "sample", "finish", "analyze"):
        command = sub.add_parser(name)
        command.add_argument("--output", type=Path, required=True, help="Folder outside repository")
        if name != "analyze":
            command.add_argument("--adb", default=None, help="Path to adb.exe, optional")
            command.add_argument("--serial", default=None, help="ADB device serial, required if multiple")
        if name == "sample":
            command.add_argument("--label", required=True)
    args = parser.parse_args(argv)
    root = args.output.resolve()
    if args.command == "analyze":
        save_report(root)
        return 0
    adb = find_adb(args.adb)
    # For follow-up commands, keep device selection consistent with session metadata.
    existing = read_json(root / "session.json") if args.command != "start" else None
    serial = select_device(adb, args.serial or (existing["serial"] if existing else None))
    if existing and serial != existing["serial"]:
        raise RuntimeError("Captured device does not match initial session")
    if args.command == "start":
        if root.exists():
            raise RuntimeError(f"Session directory already exists: {root}")
        if pid_for(adb, serial) is None:
            raise RuntimeError(f"{PACKAGE} is not running. Open debug gesture demo first.")
        root.mkdir(parents=True)
        details = {"started_utc": utc_now(), "package": PACKAGE, "serial": serial,
                   "android_sdk": run_adb(adb, serial, "shell", "getprop", "ro.build.version.sdk"),
                   "model": run_adb(adb, serial, "shell", "getprop", "ro.product.model"),
                   "build_fingerprint": run_adb(adb, serial, "shell", "getprop", "ro.build.fingerprint"),
                   "ink_dependency": "1.0.0 (verify against local Gradle file)",
                   "capture_tool": "EDT-05 CP4A v1"}
        write_json(root / "session.json", details)
        snapshot(adb, serial, root, "baseline")
        reset = run_adb(adb, serial, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset", allow_error=True)
        (root / "gfxinfo_reset.txt").write_text(reset + "\n", encoding="utf-8")
        print(f"Started {root}; use 'sample' for checkpoints, 'finish' when done.")
    elif args.command == "sample":
        record = snapshot(adb, serial, root, args.label)
        print(f"Captured {record['label']} (pid {record['pid'] or 'NOT RUNNING'})")
    else:
        record = snapshot(adb, serial, root, "final")
        print(f"Captured {record['label']} (pid {record['pid'] or 'NOT RUNNING'})")
        save_report(root)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired, FileNotFoundError) as ex:
        print(f"ERROR: {ex}", file=sys.stderr)
        raise SystemExit(1)
