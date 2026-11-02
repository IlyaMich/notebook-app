# EDT-05 CP3C — Wet/dry fidelity, clipping, and native lifecycle (consolidated)

**Status:** Debug-only evaluation; Android emulator build and regression tests passed. Manual acceptance ultimately passed after reducing host memory pressure and restarting, **but an intermittent native RenderThread SIGSEGV remains unresolved**. CP3C is not evidence of production-level renderer stability.

**Scope:** Jetpack Ink 1.0.0 experiment, preserving canonical page-local `sampledPointsV1` geometry, the existing EDT-03 viewport model, and debug-only integration. No Room, autosave, portable-schema, or production editor changes in CP3C.

## Current effective behavior

- The viewport `Box` and dry `Canvas` are clipped below the header. `InkClipGeometry.visiblePageRects()` supplies one set of page/viewport intersections for both the dry-ink clipping and the wet-ink mask. **Ink `maskPath` identifies hidden areas**: page gaps and viewport margins, represented by an even-odd path, not the visible page interiors.
- `InkHandoffDiagnostics` compares confirmed native input coordinates with canonical samples projected back using the stroke-start camera. A **provisional 0.5 px position/brush-width budget** is an instrumentation diagnostic, not proof of visual equivalence or latency. Record numerical device measurements in CP4.
- A completed stroke that crosses page boundaries is rejected as a whole; it is not rehomed to another page. After rejection, the demo increments the rejection counter and displays the message, but **does not increment `inkLayerGeneration` or remount `InProgressStrokes`**. A later valid stroke may be drawn immediately.
- **Clear** removes completed canonical strokes, resets rejection and diagnostics, and leaves the mounted native Ink layer in place. It does not alter the camera. Clearing finished dry strokes does not require remounting the wet-input view.
- **Cancel wet ink** and **switch drawing mode** still intentionally change `inkLayerGeneration` to remount native input. A generation guard drops callbacks from a replaced layer. Cancellation is a debug control; real palm/stylus cancellation remains unverified.
- Canonical strokes remain in an activity-scoped ViewModel through configuration recreation. Native dry strokes are rebuilt from page-local canonical coordinates for display; zoom and pan never update canonical geometry.

## Final CP3C components

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkGestureArbitrationActivity.kt` — debug UI, gesture ownership, input lifecycle, wet/dry rendering
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkClipGeometry.kt` — shared clipping geometry
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkHandoffDiagnostics.kt` — geometric handoff diagnostics
- `android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/InkClipGeometryTest.kt`
- `android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/InkHandoffDiagnosticsTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureHandoffRegressionTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureRejectionLifecycleTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureRejectedInputSequenceTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureClearZoomRegressionTest.kt`
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkGestureCleanupRegressionTest.kt` — originally CP3B; updated during CP3C

Keep `InkPointerOwnership`, `InkPointerStartGate`, and their tests from CP3B. The older intermediate CP3C notes titled `rejection_lifecycle_repair` and `rejection_no_remount` documented abandoned/partial fixes and are intentionally consolidated here rather than committed as current guidance.

## Verification evidence

- User reported successful Android build and focused JVM/instrumentation checks for the latest CP3C repairs.
- User reproduced: valid drawing → boundary rejection → immediate valid drawing, repeatedly, without losing input.
- After the Clear isolation change, a native crash was observed initially during multi-stroke drawing at zoom and Clear. Following reduced host memory pressure and a laptop restart, user completed all CP3C manual checks without a crash. **This does not establish that memory pressure caused the crash or that the crash is fixed.**
- Test reports and exact handoff measurement values have not been collected in this document. Preserve raw XML/Logcat evidence locally or in the issue/PR where appropriate.

## Known limitations and CP4 exit work

1. Investigate repeatable zoom/draw/Clear stress on another emulator API level and a real stylus device. Consider renderer-on/off comparison if the native crash returns.
2. Measure physical input latency, wet-to-dry visual fidelity, pressure/tilt behavior, cancellation and palm rejection. JVM geometry tests alone cannot validate these.
3. Confirm any crash-free runs with device build, runtime, host load, iteration count and relevant Logcat context; do not claim production stability from one successful run.
4. Document any accepted approximation, rejected-cross-page policy, mask-path flicker, or brush mismatch before choosing an engine.

The associated incident is tracked in `edt_05_cp3c_renderthread_clear_isolation.md`. The FMT-04 portable stroke contract remains authoritative.
