# EDT-05 CP3A — Page-local ink coordinates and ownership (debug only)

**Status:** Candidate for local verification; not production editor integration.  
**Issue:** EDT-05 (#36); prerequisites EDT-03 and FMT-04.  
**Baseline:** CP2C debug capture/save/reopen passed on emulator.

## Scope

This checkpoint is a narrow pure-Kotlin bridge connecting the EDT-03 `ViewportGeometry` and `ViewportInteraction` APIs to the already tested CP1B `CanonicalInkConverter`.

- Hit-test the **first** confirmed input sample in the visible viewport.
- Lock stroke ownership to that stable page ID.
- Convert all samples into **page-local document units** using the existing viewport transform.
- Preserve timestamps and optional pressure/tilt/orientation through the existing canonical converter.
- Convert brush width from physical pixels to document units at capture time.
- Reproject canonical samples and brush width for a changed viewport camera without mutating the stroke.
- Reject off-page/other-page samples **as a temporary strict evaluation policy**, rather than accidentally assigning them to another page.

`InkViewportPageAdapter` is debug-only and has no Jetpack Ink, Compose, Android SDK, Room, or autosave dependencies. These tests do not change production viewport input handlers.

## Important limits and decisions postponed

- **Strict rejection is only a temporary CP3A guard.** A production handwriting stroke that leaves the page must eventually be handled using a documented clipping/cancellation/segment policy. Do not silently truncate multi-page gestures or adopt rejection as the final UX.
- **No wet-ink page mask yet.** `projectOrNull` returns viewport coordinates, but the future dry renderer must clip to the page bounds. This checkpoint does not establish visual clipping.
- **No pointer arbitration yet.** We have not shown Jetpack Ink and EDT-03 pinch/pan working together; this is the focus of CP3B.
- **Camera snapshot is supplied by caller.** A future live editor must freeze the stroke's camera at pointer-down or coordinate input and camera updates explicitly; CP3A assumes a consistent transform for the samples of each stroke.
- **No production note writing.** The debug CP2C persistence activity remains separate; note revisions must not change during geometry-only tests.

## Repository-relative files

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkViewportPageAdapter.kt`
- `android/app/src/testDebug/java/com/notebook/app/ui/editor/ink/InkViewportPageAdapterTest.kt`
- `docs/engineering/edt_05_cp3a_viewport_page_mapping.md`

## Verification on Windows

First commit the already verified CP2C files. Next extract the CP3A ZIP at the repository root.

```powershell
cd C:\Users\ilmih\Desktop\develop\notebook-app\android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.ink.InkViewportPageAdapterTest"
```

Expected: `BUILD SUCCESSFUL`. Check the XML report at `android/app/build/test-results/testDebugUnitTest/TEST-com.notebook.app.ui.editor.ink.InkViewportPageAdapterTest.xml` and verify **9 test methods**, failures `0`, errors `0`.

Run existing EDT-03 viewport tests without changing their source:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.notebook.app.ui.editor.viewport.*"
```

Expected: `BUILD SUCCESSFUL`; existing viewport tests remain green.

Finally, inspect `git status --short` and `git diff --check`. Only the three new CP3A files should be uncommitted. No emulator is required because CP3A is pure Kotlin.

## CP3A acceptance

1. Mixed-size page ID and page-local coordinate tests pass.
2. Outside/gutter/gap and other-page samples are not misassigned.
3. Different pan/zoom cameras do not mutate canonical geometry.
4. Brush width remains document-unit based.
5. Optional input fields are preserved and no pressure is invented.
6. Existing viewport and ink JVM tests pass.

Do **not** commit CP3A until local results have been reviewed. CP3B will perform the first debug UI gesture arbitration experiment.
