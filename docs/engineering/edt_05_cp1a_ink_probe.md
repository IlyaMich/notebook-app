# EDT-05 — CP1A: Debug-only Jetpack Ink capture probe

**Scope:** Verify stable Jetpack Ink 1.0.0 wet/dry rendering on Android, isolated from the read-only EDT-03 viewport, Room, autosave, and canonical document state.

This package is deliberately **not** a completed EDT-05 checkpoint 1. It validates the native authoring stack before CP1B adds page-local DU conversion, canonical `sampledPointsV1` rebuild and rigorous tests.

## Gradle dependency setup (one manual insertion)

The existing `android/app/build.gradle.kts` on `main` has `dependencies { ... }` and uses Google Maven. **Do not replace the build file.** Insert the following lines inside its existing `dependencies { }` block, directly after the other `debugImplementation(...)` lines:

```kotlin
    // EDT-05 CP1A — debug-only evaluation. Pin all Ink modules to the same stable version.
    val inkProbeVersion = "1.0.0"
    debugImplementation("androidx.ink:ink-authoring:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-authoring-compose:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-brush:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-brush-compose:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-geometry:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-nativeloader:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-rendering:$inkProbeVersion")
    debugImplementation("androidx.ink:ink-strokes:$inkProbeVersion")
```

**Why debugImplementation?** The entire activity is under `src/debug`, so a production release APK does not acquire these Ink dependencies yet. The eventual engine adapter may use `implementation` after evaluation.

## Installation

Extract ZIP into the repository root. These are new files only; `src/debug/AndroidManifest.xml` must not already exist with unrelated contents. Inspect changes before building.

## Verification

From PowerShell (repo root):

```powershell
cd C:\Users\ilmih\Desktop\develop\notebook-app\android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeActivityTest
```

Run the debug app on your Pixel Tablet or phone. It creates **two launcher entries**: the standard `Notebook` and `Notebook Ink Probe`. Open the Ink Probe entry; draw using touch or emulator mouse. After lifting the pointer, the blue stroke should remain drawn and the `finished strokes` count should rise. `Clear` empties the strokes. Returning to the normal Notebook app should not create/modify pages or revisions.

**If only one launcher icon is visible:** Inspect `AndroidManifest.xml` under `src/debug`, perform `:app:installDebug`, then inspect the emulator's app drawer; some launchers group the two entries. Or use the following ADB start command (with one connected emulator):

```powershell
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.notebook.app/com.notebook.app.ui.editor.ink.InkProbeActivity
```

## Passing evidence

- Build compiles against pinned Ink 1.0.0.
- Instrumentation probe opens and displays `0 finished strokes`.
- Native wet drawing becomes finished dry drawing without a visible disappearance after release.
- `Clear` removes dry strokes; restarting probe resets them (in-memory only).
- Production notebook editor and note revisions remain unchanged.
- Compare functional behavior on tablet and phone emulator; **do not** assert true stylus latency/palm behavior from emulators.

## Known limitations / next steps

- This probe uses *local pixel coordinates* in a standalone view. It does not implement `screen PX -> page DU`, hit-testing, clipping, zoom/pan, canonical JSON, pressure normalization, save/reopen, or ownership arbitration. Those start in CP1B/CP2.
- The instrumentation test validates startup only. Manually draw, or later add precise pointer-event tests.
- Native Ink code is **not compiled or executed in this artifact environment**; your Android Studio/Gradle build is the acceptance gate. If an Ink API mismatch appears, share the first compiler error and we will correct it before committing.
- To keep normal editor behavior unchanged, all probe Kotlin/UI code is debug-source-set only.

References: https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-draw-stroke ; https://developer.android.com/jetpack/androidx/releases/ink
