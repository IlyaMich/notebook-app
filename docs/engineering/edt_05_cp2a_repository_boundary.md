# EDT-05 CP2A — Canonical ink persistence boundary

**Issue:** [EDT-05 #36](https://github.com/IlyaMich/notebook-app/issues/36)
**Scope:** Debug-only serialization and repository contract probe. **Not** a completed save/relaunch demonstration.

## Why this checkpoint exists

CP1B established canonical `sampledPointsV1` samples and native reconstruction. Before touching the existing read-only editor or the collaborator's autosave/save-state code, CP2A proves that one completed canonical stroke becomes one valid element envelope, is submitted as one `DocumentEdit.InsertElements`, and can be decoded from a `DocumentRepository.load` result. The repository is still the sole authority for transactions and revisions.

## Files

- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/CanonicalInkDocumentCodec.kt`
  - Encodes `CanonicalInkStroke` to a canonical element envelope: UUID ID, `type=stroke`, identity transform, `locked=false`, payload `sampledPointsV1`.
  - Preserves `Double` document coordinates, signed orientation, monotonic `tMs`, optional absent pressure/tilt/orientation and semantic brush fields.
  - Decodes only supported identity-transform strokes as a **read-only projection**. Unsupported encoding or nonidentity transforms fail explicitly rather than being silently flattened or changed.
- `android/app/src/debug/java/com/notebook/app/ui/editor/ink/InkDocumentRepositoryBridge.kt`
  - Uses the existing `DocumentRepository.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(envelope)))` contract; does not instantiate Room or manage revisions independently.
  - Loads only stroke elements of a specified page from `DocumentRepository.load` and checks ID correspondence.
- `android/app/src/androidTest/java/com/notebook/app/ui/editor/ink/InkDocumentBridgeTest.kt`
  - Six instrumentation tests for schema shape, optional fields, round-trip, unsupported cases, repository calls, page isolation and failure propagation.
  - Uses a **fake DocumentRepository**; therefore it **does not** prove Room durability or application relaunch recovery.

## Install

Extract ZIP at repository root. All paths in the ZIP are repository-relative, and no existing source file is overwritten. The test runs in the Android emulator because the codec uses Android's `org.json` implementation.

## Run verification (from `android/`)

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkDocumentBridgeTest

.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkCanonicalRebuildTest

.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkProbeRecreationTest
```

Expected: successful build and individual test classes visible as passed in instrumentation XML reports. Manually verify the debug probe still draws, clears and retains completed strokes across rotation. The production note editor must still be read-only, and no note revision should change due to merely using the probe.

## Limits and next checkpoint

CP2B adds the **real Room-backed** commit/reopen test and then a controlled entry point for an actual note, subject to reviewing the latest collaborator changes. CP2A deliberately does not alter `NoteEditorScreen`, `ContinuousPageViewport`, Room schema, migration, save coordinator or feature dependencies.

The codec does **not** pretend to round-trip unknown envelope fields or transformed elements through its DTO. A loaded element remains canonical JSON in the repository. Do not write a projected object back without retaining its full envelope. Stored elements are not native Ink caches.

## Review gates

- Encoder output accepted by existing shared wire/schema rules.
- One stroke == one insert command, and a durable revision is reported only by the repository.
- Failure propagates; no optimistic `Saved` message.
- No additional production dependency or persistence API introduced.
- Test results and debug probe confirmed on emulator before commit.
