# EDT-05 — CP1B: In-memory canonical ink capture and reconstruction

**Scope:** Extend the successful CP1A debug-only native Ink probe. Persisted data, Room, autosave, note revisions, app navigation and production viewport files remain untouched.

## What changed

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/CanonicalInkStroke.kt` — pure Kotlin `sampledPointsV1`-shaped brush/point DTO, native-pixel input DTO, scale and coordinate conversion.
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/JetpackInkProbeAdapter.kt` — Jetpack Ink 1.0.0 bridge: completed `Stroke.inputs` -> canonical DU samples and canonical samples -> new native `Stroke`. The bridge is disposable and can be replaced.
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkProbeActivity.kt` — **replaces** CP1A version, stores only canonical strokes in memory, reconstructs dry strokes from canonical. Native draw remains in-progress only. Page-width proof uses an illustrative **612-DU** wide canvas.
- `android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/CanonicalInkConverterTest.kt` — pure JVM tests (no Ink native library runtime dependency).
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkCanonicalRebuildTest.kt` — native Ink mesh/batch rebuild tests on emulator or device.

The original `InkProbeActivityTest.kt`, debug manifest and CP1A notes remain unchanged. Keep the Jetpack Ink 1.0.0 `debugImplementation` dependencies already added during CP1A.

## Important scope notes

- **Canonical, not native:** The in-memory source of truth is a canonical object with `Double` page-local DU positions, relative millisecond timestamps, and optional pressure/tilt/orientation. The native renderer is rebuilt from canonical when strokes are finished or the canvas width changes.
- **Debug-only page example:** This is one standalone drawing surface with a conceptual width of 612 DU, derived via `pxPerDu = measuredCanvasWidthPx / 612.0`. It does *not* yet use `ViewportGeometry` or an actual Room page. Integration with existing mixed-size page targeting and gestures is a later checkpoint.
- **Brush:** The only *native brush mapping tested in CP1B* is `pen` -> `StockBrushes.pressurePen()`. `pencil` and `highlighter` remain valid canonical semantic kinds but native reconstruction explicitly refuses them until evaluated. Color, width and opacity are copied from canonical into the transient native brush.
- **Optional values:** Missing pressure, tilt and orientation remain absent in canonical form. Because Ink requires uniform optional-field presence within a native batch, an entire *rendering-only* field is omitted when even one point lacks it. Canonical samples remain unchanged. Negative / large finite canonical orientation radians are normalized to the native `[0,2π)` range for rendering only.
- **Native batch limitation:** A canonical stroke with exact repeated `(x,y,t)` tuples is valid under FMT-04 but may be rejected by Ink's batch. CP1B reports errors rather than silently dropping samples. A future adapter must provide an explicitly documented fallback renderer if needed.
- **Precision:** Native coordinates are Float; canonical points remain Double. Coordinate tolerance 0.01 DU is provisionally tested, not proven for every zoom/page size.
- **No JSON or database write:** CP1B models payload *values* but does not serialize JSON, assign element IDs, insert into Room or increment revisions. These are later checkpoints.
- **No hardware fidelity claim:** Emulator drawing proves basic behavior, not pressure/palm rejection/stylus latency. Observe any visible wet-to-dry discontinuity but do not claim numerical latency gates yet.

## Installation (Windows PowerShell)

In your existing `feature/edt-05-android-ink-evaluation` branch, **commit successful CP1A first** after verifying the staged file list. The CP1A dependency changes to `android/app/build.gradle.kts` were added manually and must be part of that commit.

Download the CP1B ZIP to Downloads and extract at the *repository root*:

```powershell
cd C:\Users\ilmih\Desktop\develop\notebook-app
Expand-Archive -Path "$env:USERPROFILE\Downloads\EDT-05_CP1B_Canonical_Ink_Adapter.zip" -DestinationPath "." -Force
```

`-Force` is necessary because CP1B intentionally updates `InkProbeActivity.kt`. Only that existing source is replaced; every other path is new.

## Verification A: debug assembly and JVM tests

```powershell
cd .\android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. `CanonicalInkConverterTest` must be included in the `testDebugUnitTest` report. If Gradle fails, send the **first** Kotlin compiler error rather than trying to guess a library API correction.

## Verification B: instrumented Ink rebuild and existing probe smoke

Start one emulator (Pixel Tablet or phone). Then:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkCanonicalRebuildTest

.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeActivityTest
```

Expected: both commands report `BUILD SUCCESSFUL`. The native rebuild test uses real Ink input-batch and stroke creation. It checks geometry, timestamp, optional pressure and brush-width behavior.

## Verification C: manual drawing and regression

```powershell
.\gradlew.bat :app:installDebug
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.notebook.app/com.notebook.app.ui.editor.ink.InkProbeActivity
```

- Draw 3 strokes. The counter must show 3 and each finished stroke must stay visible when wet rendering ends.
- Check `Canonical DU -> rebuilt Jetpack Ink` is shown and no `Ink conversion error` appears.
- Clear and draw again: the counter resets and marks disappear.
- Rotate the emulator and observe that the strokes remain anchored proportionally to the drawing surface. This is only a basic resize check, not EDT-03 viewport state restoration.
- Exit/reopen the Ink Probe: counter returns to 0; nothing is stored.
- Open the normal Notebook app and confirm its library/editor still work.

## Review and commit gate

From the repository root:

```powershell
git status --short
git diff --check
git add android/app/src/debug/java/com/notebook/app/ui/editor/ink/CanonicalInkStroke.kt `
        android/app/src/debug/java/com/notebook/app/ui/editor/ink/JetpackInkProbeAdapter.kt `
        android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkProbeActivity.kt `
        android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/CanonicalInkConverterTest.kt `
        android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkCanonicalRebuildTest.kt `
        docs/engineering/edt_05_cp1b_canonical_ink_probe.md
git diff --cached --check
git diff --cached --stat
```

Only commit CP1B after A, B and C pass, and after checking the staged diff. Keep the PR open until all EDT-05 checkpoints are verified.
