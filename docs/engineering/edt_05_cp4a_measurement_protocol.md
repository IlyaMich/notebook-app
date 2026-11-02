# EDT-05 / CP4A — Baseline stability and UI-frame evidence

**Status:** Proposed checkpoint for local validation. **No measured performance claims yet.**

## Scope and caution

This checkpoint adds no Kotlin, Compose, Room, or native Ink code. It uses `adb` to capture app PSS and recent UI frame statistics while **you** exercise the existing *Ink gesture arbitration (debug only)* demo. Its evidence is a first diagnostic baseline, not an input-to-photon latency benchmark or production release gate.

The native `SIGSEGV` on Android 17 x86_64 `RenderThread` during zoomed multi-stroke Clear is **open**. It was not proven to be caused by low host memory. Jetpack Ink stays pinned to 1.0.0. Do not interpret one successful run as proving native stability. We are also not changing CP3C's code during this measurement checkpoint.

## Requirements

- Windows PowerShell, Python 3, and `adb` installed in Android SDK platform-tools.
- Connected Pixel Tablet (optionally repeat on phone); ensure only one device is connected or use `--serial`.
- CP3C debug build installed; **manually launch** `com.notebook.app.ui.editor.ink.InkGestureArbitrationActivity` before starting capture.
- Use synthetic strokes. Do not include real notes, personal content or private logs in PRs.
- Write measurements **outside the Git repository**. Each run must have a new destination folder.

## Suggested PowerShell commands

From `C:\Users\ilmih\Desktop\develop\notebook-app`:

```powershell
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.notebook.app/com.notebook.app.ui.editor.ink.InkGestureArbitrationActivity

$run = Join-Path $env:USERPROFILE "Desktop\EDT05_CP4A_Tablet_Run01"
py -3 tools/ink_evaluation/capture_cp4a.py start --output $run --adb $adb
```

The script checks that the app process is active, records Android SDK/model/build fingerprint and baseline PSS, and resets **only that app's gfxinfo counter/history**. It never clears app storage or changes the real Notebook library.

### Run the manual scenario

Use the demo's Finger Drawing toggle for emulator touches. For repeatability, record the number of strokes and approximate scale in your notebook/results:

1. Draw **10 accepted strokes** on page 1 at default zoom (keep all within page).
2. Take a snapshot:

```powershell
py -3 tools/ink_evaluation/capture_cp4a.py sample --output $run --adb $adb --label after_default_draw
```

3. Switch to navigation, pinch to approximately 2× zoom. Switch to finger drawing and draw **10 accepted strokes**. Capture:

```powershell
py -3 tools/ink_evaluation/capture_cp4a.py sample --output $run --adb $adb --label after_zoom_draw
```

4. Press **Clear**, draw two more accepted strokes and confirm cleared strokes stay gone; then capture:

```powershell
py -3 tools/ink_evaluation/capture_cp4a.py sample --output $run --adb $adb --label after_clear
```

5. Repeat **zoomed: 5 strokes → Clear → redraw** at least five times, watching for crashes, ghosts or lost input. Record a pass/fail result manually.
6. Finish and produce `summary.csv` and `summary.json`:

```powershell
py -3 tools/ink_evaluation/capture_cp4a.py finish --output $run --adb $adb
```

If the app crashes, **do not try to complete the failing sequence repeatedly**. Run `finish` once to preserve available evidence (it records missing process). Collect the crash traceback separately and do not attach unrestricted Logcat without reviewing it. Do not commit measurement outputs or crash dumps.

Repeat at least **three runs** with new folders after a warm-up. If possible, do another three on the Android phone emulator, documenting SDK and memory conditions. These are emulator-only diagnostic observations; do not claim stylus/palm-rejection quality from them.

## Outputs and interpretation

- `session.json`: device build, model, serial, Ink version assumption (confirm pinned Gradle version), timestamp.
- `snapshots/<label>/meminfo.txt`: raw Android per-process memory dump.
- `snapshots/<label>/gfxinfo.txt`: raw app UI `framestats` dump.
- `summary.csv`: total PSS KB, observed valid frame count, recent frame-duration P50/P95/P99 and counts over 16.67 ms / 33.33 ms.
- `summary.json`: above plus missing PID or PID-change warnings.

**Limitations**: `gfxinfo` buffers only recent UI frame samples and does not measure full Ink wet-rendering latency. Frame duration is computed as `FRAME_COMPLETED - INTENDED_VSYNC` for `FLAGS=0`; the threshold counts are **not** the same as officially measured missed deadlines, nor can 16.67 ms be assumed the device's real frame deadline. PSS is *app memory on the Android device*, not host-computer memory. Neither metric establishes crash causation. An absent frame or memory measurement is reported as unavailable, not as zero.

For a publication-quality release gate, consider Android Macrobenchmark `FrameTimingMetric`, Perfetto traces, and a physical stylus test with a suitable latency measurement method (e.g. high-speed video or hardware timing). Do **not** label UI-frame P95 as stylus input-to-display latency.

## Parser tests (no Android required)

```powershell
py -3 -m unittest discover -s tools/ink_evaluation/tests -v
```

## Completion criteria

- The parser tests pass.
- Capture succeeds on the tablet emulator, with all session files and a generated CSV/JSON summary.
- At least three scenario runs have recorded manual pass/fail and any crashes.
- Provide raw metrics and a note about ambient host memory pressure, without attributing native crashes to it as fact.
- CP4A is not complete solely because the script runs: the measurements and crash notes must be reviewed.

## References

- https://developer.android.com/tools/dumpsys
- https://developer.android.com/topic/performance/memory/guide/tools-overview
- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- https://developer.android.com/jetpack/androidx/releases/ink
