# EDT-05 CP3C — RenderThread crash investigation and Clear isolation

**Status:** Open technical risk, debug-only. A successful manual run after a restart does **not** prove the native crash is resolved.

## Observed evidence (2026-11-02)

- On Android 17 x86_64 Pixel Tablet emulator, clearing multiple strokes after zoom sometimes terminated `com.notebook.app`.
- Captured native tombstone/log: `SIGSEGV`, `SEGV_MAPERR`, fault address `0x67d0`, crashing thread `RenderThread`, with `libhwui.so` `DrawFrameTask::postAndWait()` at the top of the native stack.
- This evidence identifies an Android HWUI RenderThread memory fault; **it does not identify Jetpack Ink itself as the crashing function nor establish application-level causation**.
- The failure was not consistently reproducible. Reducing host memory usage and restarting the laptop preceded a complete successful manual acceptance sequence. Correlation alone does not identify memory pressure as the cause.

## Effective repair being evaluated

The debug UI no longer destroys/rekeys `InProgressStrokes` for a finished rejected stroke **or** ordinary Clear. It removes only canonical dry strokes and updates indicators. This avoids native view teardown during the ordinary Clear path. `Cancel wet ink` and drawing-mode switches still explicitly remount the native view and retain their existing generation guard. Canonical geometry, viewport navigation and persistence architecture are unchanged.

The earlier `rejection_lifecycle_repair` (deferred remount) and `rejection_no_remount` intermediate documents were superseded; do not treat their old reset steps as implementation instructions.

## Tests and interpretation

- Debug build, JVM tests and targeted regression classes reported passing following the final changes, including `InkGestureClearZoomRegressionTest`, `InkGestureCleanupRegressionTest`, `InkGestureRejectionLifecycleTest`, `InkGestureRejectedInputSequenceTest` and `InkGestureHandoffRegressionTest`.
- Manual scenarios: multiple valid strokes, zoom, multi-stroke Clear, re-draw, boundary rejection, repeated Clear, cancellation and rotation. A complete pass was reported after the host restart, with prior intermittent native crashes.
- These results support retaining the present debug-only implementation for continued evaluation. They **do not clear the crash as a product acceptance risk**.

## Required follow-up in CP4

Record emulator API/build, device/host memory conditions, zoom factor, stroke count, repetition count and crash/no-crash results. Repeat the same stress scenario on a second Android API emulator and ideally real stylus hardware. If reproducible, isolate dry native renderer, native wet view lifecycle, and mask/clip paths independently before further architectural changes. Preserve the original crash log outside the repository unless an anonymized sanitized excerpt is deliberately approved for sharing.

Do not enable this debug-only path in the production editor on the basis of these tests alone.
