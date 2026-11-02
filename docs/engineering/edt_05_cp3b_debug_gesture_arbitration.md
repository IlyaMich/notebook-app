# EDT-05 CP3B — Debug gesture arbitration

**Scope:** Debug-only evaluation. Adds a third launcher activity, a pure pointer-ownership
policy, ten JVM tests, and two Compose activity smoke tests. Reuses CP3A's canonical
page mapping, EDT-03's viewport geometry/navigation, and CP1B's native renderer.
No production editor, persistence, schema, autosave, or migration changes.

## Input contract being evaluated

- **Tablet default:** Touch is routed to navigation; parent Compose pointer listener
  consumes touch on the Initial pass before the child Jetpack Ink layer receives it.
- **Stylus:** Unconsumed stylus input reaches Jetpack Ink for live handwriting.
  A concurrent finger is consumed but cannot change camera until stylus is up.
- **Mouse:** Unconsumed, permitting debug mouse-stroke experiments.
- **Finger draw toggle:** Allows touch input to reach Ink, explicitly disabling
  finger navigation. This mode is for phone/emulator experimentation, not a final UX.
- **Navigation:** One-finger pan / two-finger pan+pinch use existing
  `ViewportNavigation.updateOrNull`, including scale bounds and edge clamping.
- **Stroke ownership:** Native completed samples are passed to CP3A's
  `InkViewportPageAdapter.captureOrNull` with the current camera. Only a stroke
  wholly within one page is accepted. Saved strokes here are *in memory only*.
- **Dry rendering:** Project canonical samples using CP3A on each camera update,
  and clip each resulting native stroke to its owning page.

**Deliberately unresolved:** Jetpack Ink wet-stroke clipping/masking; strokes crossing
page edges; cancellation with no completion callback and a stale pointer-down camera snapshot; palm rejection on real hardware; stylus pressure/latency; real simultaneous
stylus and multi-finger behavior; wet-to-dry pixel-level fidelity. Rejecting a
boundary-crossing stroke is a temporary evaluation policy, not a final V1 editor rule.

### Files

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkPointerOwnership.kt`
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkGestureArbitrationActivity.kt`
- `android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/InkPointerOwnershipTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureArbitrationActivityTest.kt`
- `android/app/src/debug/AndroidManifest.xml` — replaces CP2C manifest; retains both existing debug launchers

## Verification (PowerShell from `android/`)

```
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.ink.InkPointerOwnershipTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkGestureArbitrationActivityTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.viewport.*"
```

Start debug app from launcher "Notebook Ink Gestures" or run:

```
.\gradlew.bat :app:installDebug
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.notebook.app/com.notebook.app.ui.editor.ink.InkGestureArbitrationActivity
```

### Manual acceptance (tablet and phone emulators)

1. In default **Finger navigate** mode, swipe on the page. The page pans when
   not at an edge and the navigation counter advances. No ink is added.
2. Pinch with two touch pointers; camera zoom/clamping work and stored stroke count
   remains unchanged. The Android emulator may need multi-touch emulation.
3. Toggle **Enable finger drawing**, draw wholly inside the first page, and check
   one finished ink stroke remains visible and `Stored strokes` increments.
4. Return to **Finger navigate** mode; pan/zoom again and confirm the stroke
   remains on the correct page and scales with it.
5. Draw inside the second mixed-size page after navigating to it. A completed
   stroke belongs to that page; a stroke crossing a gap is explicitly rejected.
6. Rotate; completed strokes remain, although the demo refits the camera.
7. Clear; stored stroke count resets. Verify the old Ink Probe, Room Ink Demo,
   and production Notebook editor are unchanged.
8. **Physical stylus testing only when hardware is available:** stylus writes,
   finger moves navigate, and a finger touching while stylus is down does not
   move the camera or generate stray ink. Emulator simulation is not proof of
   palm rejection or simultaneous stylus/finger correctness.

Inspect XML test reports to confirm methods actually ran. Do not commit if any
build, regression, or manual acceptance check fails.

## Design caveats

`InProgressStrokes` does not have a `modifier` input parameter in Jetpack Ink
1.0.0. It consumes unconsumed pointer events; parent Initial-pass touch
consumption is a *hypothesis that needs real instrumentation/manual verification*.
We deliberately do not claim it works until tested on the target runtime.
