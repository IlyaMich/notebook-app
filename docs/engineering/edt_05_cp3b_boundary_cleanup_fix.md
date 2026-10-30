# EDT-05 CP3B — Gesture demo boundary and reset repair

Status: debug-only verification checkpoint; **do not merge or commit until Android emulator acceptance passes**.

## Why the existing CP3B demonstration failed

The original debug demo drew unbounded page rectangles in a Compose `Canvas` without clipping its parent layout. Showing the rejection error inserted a new header line, resizing the viewport and triggering the `LaunchedEffect(size)` fit-to-width reset. The native Jetpack Ink wet layer could show strokes in screen pixels outside the page and retained transient rendering when rejected or cleared, even when the canonical in-memory stroke list was empty. No production editor or Room code is involved.

## Changes

- The viewport `Box` and dry-ink `Canvas` are clipped to their allocated bounds, below the header.
- A permanent, single-line ink status occupies stable header space whether or not there is an error. It cannot resize the canvas simply by reporting a rejected stroke.
- `InProgressStrokes(maskPath=...)` masks **the region where ink must NOT be visible**: viewport margins and gaps. It uses an even-odd path containing the full viewport with holes for visible page rectangles. **A page-only path would hide the pages, which is the opposite of the correct Ink mask semantics.** Native wet ink inside the pages remains visible. Canonical strokes still use page-local DU and dry ink is clipped to its owning page.
- An Initial-pass pointer-start gate consumes writing down-events starting outside visible pages and continues blocking that pointer until release, even if it moves into a page. The existing navigation ownership rule remains separate.
- The session records an `inkLayerGeneration` key. Clear, rejected conversion, and switching finger mode dispose/recreate the `InProgressStrokes` native layer to clear any stale wet rendering state. Old-generation callbacks are ignored. `Clear` also removes canonical stored ink and resets rejection/error state; it does not modify the camera.
- Added five JVM tests for the pointer gate and three UI/state regressions for stable viewport bounds, native-layer invalidation and repeated rejection/Clear cycles. These tests **cannot** inspect all low-level native wet graphics, so the manual checks below are mandatory.

## Apply from repository root

Unzip `EDT-05_CP3B_Boundary_Cleanup_Fix.zip` into the repository root with `Expand-Archive -Force`. This replaces only `InkGestureArbitrationActivity.kt`. It adds `InkPointerStartGate.kt`, two new test files, and this document. No manifest/Gradle/repository/schema change.

## Automated verification (PowerShell, from `android/`)

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.ink.InkPointerStartGateTest"
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkGestureCleanupRegressionTest
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkGestureArbitrationActivityTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.viewport.*"
```

Expected: all five commands report `BUILD SUCCESSFUL`; confirm **five** pointer-start gate and **three** UI cleanup methods appear in XML test reports. Original CP3B routing tests are included in the full `:app:testDebugUnitTest` task.

## Mandatory manual emulator acceptance

1. Install debug: `.\gradlew.bat :app:installDebug`. Launch `com.notebook.app/com.notebook.app.ui.editor.ink.InkGestureArbitrationActivity` with adb or from the app drawer.
2. In Finger navigate mode, pan/zoom both pages repeatedly. Neither page nor ink may cover the title/status/buttons; the header must remain clickable. Test zoom above and below fit-to-width.
3. In Finger draw mode, draw a short stroke fully **inside** the first page. The live blue ink must be visible and the stored count must increase to 1. In Navigate mode, moving the page must move this dry stroke with the page, not leave it fixed on the screen.
4. Start a stroke on page one and end in the gap or page two. It must be rejected; the header shows a rejection; the previous stored stroke remains; **the camera must NOT reset** merely because the rejection line appears. No blue ghost is allowed in the page gap after the gesture is finished.
5. Without restarting the app, draw another valid stroke on page one, then page two. Both must be accepted and remain attached to their respective pages through zoom/pan.
6. Try a writing gesture **starting in the gap** and entering a page. Nothing should appear or be stored. (This is distinct from a gesture starting on the page and crossing the gap.)
7. Press Clear. Previously accepted strokes and any transient ghost lines must disappear; stored/rejected counts return to 0; camera must remain where the user left it. Draw again, pan/zoom, and verify none of the older marks reappear. Repeat Clear and drawing at least twice.
8. Rotate and repeat drawing/clearing. Run the normal Notebook editor and previous debug Ink Probe/Room Ink Demo to ensure they are unaffected.
9. A physical stylus is still needed to validate real stylus/finger coexistence and palm rejection. The emulator cannot certify those behaviors.

## Scope and remaining risks

The temporary CP3A policy still rejects complete gestures crossing page bounds rather than partially clipping/storing them. `maskPath` affects wet **rendering**, not the raw recorded samples. Some Ink 1.0.0 devices have documented mask-path flicker caveats; record any such behavior. This checkpoint does not guarantee production-grade partial-stroke clipping, dry/wet latency, cancellation, physical-stylus behavior or durability of this particular debug demo. Do not change the portable schema or select a final handwriting engine from this checkpoint alone.

If the native wet layer continues to outlive the recreated Compose key or leak outside the clipped viewport, stop here and report the device/API version and precise reproduction sequence; we will isolate the Ink view lifecycle before changing the production editor.
