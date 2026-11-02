# EDT-05 CP1B — Rotation retention correction

**Reason:** CP1B passed Gradle and Android Ink reconstruction tests, but a manual rotation reset the finished-stroke counter to zero. `remember { mutableStateListOf<CanonicalInkStroke>() }` was scoped to the previous Activity's Compose composition. Configuration recreation disposed that composition.

## Scope

- Add `InkProbeSessionViewModel.kt` in `src/debug`. It holds only canonical `sampledPointsV1`-shaped strokes and the temporary capture error.
- Update the debug-only `InkProbeActivity.kt` to get its Activity-scoped session via `ViewModelProvider`, and render the new Activity composition from the retained canonical points. The native Ink stroke cache, canvas width and renderer are still recreated normally.
- Add `InkProbeRecreationTest.kt` under `src/androidTest` to assert the canonical stroke survives `ActivityScenario.recreate()`, stays exactly equal, and is removed on Clear.

**No changes:** Gradle, Room, production editor/viewport, note revisions, schema, canonical converter or native adapter.

**Important limitation:** ViewModel retention is *not durable saving*. Completing/closing the Activity intentionally destroys the session; process death can also lose all probe strokes. Future EDT-05 checkpoints will use `DocumentRepository.commit` for durability.

## Verify on Windows

From the repository root, extract the ZIP using `Expand-Archive -Force` so the debug Activity is replaced. Then:

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeRecreationTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkCanonicalRebuildTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeActivityTest
```

All must report `BUILD SUCCESSFUL`; inspect the XML report to ensure `completedCanonicalStrokeSurvivesRecreationAndCanBeCleared` executed. This is particularly important because a Gradle success alone does not guarantee a test filter matched.

## Manual verification

1. Install the debug application, open **Notebook Ink Probe**, draw a short stroke near the **upper third** of the drawing surface and verify the count becomes `1`.
2. Rotate portrait → landscape → portrait (and the reverse). The counter must remain `1` throughout, and the visible stroke must be reconstructed after rotation. Its position is anchored by page-width-based DU mapping, not by screen-pixel location.
3. Repeat with three strokes. No `Ink conversion error` should appear.
4. Press **Clear**, rotate again, and verify the counter is still `0` and no marks remain.
5. Fully exit the probe and relaunch; it should start at `0`. This confirms the probe is *not* durable storage.
6. Verify the normal Notebook library/editor still operates.

Because CP1B is a standalone *width-scaled probe*, strokes near the bottom may be clipped when the new orientation offers less height; a count that remains nonzero means the canonical strokes were retained, even if temporarily outside the visible probe area. Restoring the original orientation should restore their visibility. Full document viewport navigation and positioning remain separate EDT-05 work.

## Commit gate

Do not commit this correction until all commands and manual checks pass. Then stage only these files (plus your previously uncommitted CP1B files) and review `git diff --cached --check` before committing the verified CP1B checkpoint.
