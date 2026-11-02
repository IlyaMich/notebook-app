# EDT-05 / CP4B1 — Controlled Jetpack Ink preview compatibility spike

**Status:** Awaiting Android Gradle, emulator, and manual verification.  
**Stable baseline:** `androidx.ink:ink-*:1.0.0` (CP4A, three short runs on Android 17 x86_64 emulator).  
**Comparison candidate:** `androidx.ink:ink-*:1.1.0-alpha10` (Google's latest preview listed on 2026-11-02).  
**Issue:** EDT-05 (#36).  
**Scope:** Debug-only dependency version switch plus existing tests; no canonical schema, production viewport, Room, or autosave changes.

## Why this checkpoint is intentionally limited

The CP4A stable baseline has repeated UI-frame p95 of approximately 51–52 ms while drawing at zoom, and an earlier CP3C debug session had an intermittent native `libhwui.so` RenderThread `SIGSEGV`. Neither is a measurement of physical stylus latency, nor proof that Ink caused the crash. Google release notes for the 1.1 preview mention fixes for native numerical precision, rendering and `InProgressStrokesView` lifecycle/memory, but they **do not establish** that this app's crash has been fixed.

Separate *compatibility and native-behavior smoke testing* from *performance comparison*. Do not collect comparative CP4B measurements until the preview builds and the gesture tests pass. Do not adopt alpha into production on this evidence alone.

## Files added

- `tools/ink_evaluation/switch_ink_cp4b.py` — guarded, reversible version switch. This is the **only** command in this checkpoint that edits a Gradle file; it changes only the `inkProbeVersion` literal and refuses unexpected dependency wiring.
- `tools/ink_evaluation/tests/test_switch_ink_cp4b.py` — offline tests for the switch and rollback behavior.
- `docs/engineering/edt_05_cp4b1_ink_preview_compatibility.md` — this verification protocol.

No `build.gradle.kts` replacement file is included. Do **not** overwrite your feature branch's Gradle file with an older copy.

## Procedure (Windows PowerShell)

From the repository root `C:\Users\ilmih\Desktop\develop\notebook-app`:

```powershell
# Verify current branch / local changes; don't switch branches mid-checkpoint.
git status --short --branch

# Verify the offline version-switch tests.
py -3 -m unittest discover -s tools/ink_evaluation/tests -v

# Check the current version before changing anything.
py -3 tools/ink_evaluation/switch_ink_cp4b.py status

# Upgrade only the debug Ink dependency family.
py -3 tools/ink_evaluation/switch_ink_cp4b.py preview

# Inspect exactly what changed; only inkProbeVersion should differ.
git diff -- android/app/build.gradle.kts
```

Expected status before switching: `1.0.0`; expected preview: `1.1.0-alpha10`. The script validates the eight debug-only artifacts used since CP1A. If it rejects your local setup, **stop and share its error**. Do not force a text replacement.

From `android/`:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

# Verify the runtime dependency graph is using the new version.
.\gradlew.bat :app:dependencyInsight `
  --configuration debugRuntimeClasspath `
  --dependency androidx.ink:ink-authoring
```

Check that dependency resolution succeeds and that `ink-authoring` resolves to `1.1.0-alpha10`. If the preview introduces a Kotlin/API or transitive dependency error, preserve the build output and do not change canonical models to work around it.

### Focused instrumentation regressions (Pixel Tablet emulator)

```powershell
$classes = @(
  "InkCanonicalRebuildTest",
  "InkGestureRejectedInputSequenceTest",
  "InkGestureClearZoomRegressionTest",
  "InkGestureHandoffRegressionTest"
)
foreach ($class in $classes) {
  .\gradlew.bat :app:connectedDebugAndroidTest `
    "-Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.$class"
  if ($LASTEXITCODE -ne 0) { throw "Failed: $class" }
}
```

### Manual smoke (same emulator and debug activity as CP4A)

```powershell
.\gradlew.bat :app:installDebug
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am force-stop com.notebook.app
& $adb shell am start -n com.notebook.app/com.notebook.app.ui.editor.ink.InkGestureArbitrationActivity
```

1. Enable finger drawing, draw several accepted strokes at normal zoom, confirm counter and wet/dry handoff.
2. Reject a page-crossing stroke, then immediately draw another valid stroke without changing modes. Repeat several times.
3. Navigate and pinch to approximately 2x, draw several new accepted strokes, then Clear; verify no ghosts, freeze, or crash. Repeat Clear/redraw cycles.
4. Rotate and repeat a small drawing/navigation/clear sequence.
5. Confirm the original Ink Probe and Room Ink Demo still open, and the normal Notebook library/editor remain unaffected.
6. Record any failed scenarios, observed crashes, the Android build fingerprint, and whether host memory pressure was atypical.

This is an emulator smoke test. Neither successful completion nor `gfxinfo` alone proves stylus latency, palm rejection, native crash freedom, or cross-platform brush fidelity.

## Rollback (required before committing the overall CP4B evaluation)

From the repository root:

```powershell
py -3 tools/ink_evaluation/switch_ink_cp4b.py stable
py -3 tools/ink_evaluation/switch_ink_cp4b.py status
git diff -- android/app/build.gradle.kts
```

Expected status: `1.0.0`; Gradle diff should be empty if CP4A was committed with stable 1.0.0 and no other Gradle edits were made. Do not use `git restore` or `git reset --hard` to revert an unknown user change.

If the preview passes, we will design CP4B2 comparison measurements next. **The existing CP4A capture script hardcodes an Ink 1.0.0 metadata label; it must be updated or explicitly version-tagged before collecting preview metrics.** Do not label preview data as stable or compare mismatched runs. Do not commit the alpha dependency merely because the smoke test succeeds.

## Acceptance criteria for CP4B1

- Version switch and rollback tests pass; no unrelated file edits.
- All eight debug Ink dependencies remain on one selected version; release dependencies untouched.
- Preview debug assembly and JVM tests pass, or all incompatibilities are clearly recorded.
- Focused instrumentation and manual rejection/zoomed Clear/recreation checks pass, or failures are captured as evidence.
- No confirmed data damage; no change to canonical format or Room database.
- Previous native crash remains an open risk until further measurements and physical-device testing.

## References

- Google Jetpack Ink release notes: https://developer.android.com/jetpack/androidx/releases/ink
- EDT-05 CP4A three-run report: `docs/engineering/edt_05_cp4a_three_run_results.md`
