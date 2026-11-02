# EDT-05 — Checkpoint 2B: On-disk Room ink persistence

## Purpose

Prove the existing production `RoomDocumentRepository` can durably store FMT-04
`sampledPointsV1` elements created by the CP2A bridge and reconstruct usable
Jetpack Ink strokes **after closing and reopening the database**.

The `InkRoomPersistenceTest` test suite creates a unique **on-disk isolated**
`AppDatabase` using `AppDatabaseProvider.openIsolated`. It never opens the user's
`library.sqlite`, never changes production editor behavior, and deletes its test
DB on teardown. Every note is created through the existing production note
lifecycle; every stroke is committed through `DocumentRepository.commit`.

## Tests

1. `canonicalStrokeSurvivesClosingAndReopeningOnDiskRoom` — revision, page
   ownership, exact canonical JSON, ordering and optional absence survive a
   DB close/reopen.
2. `reloadedCanonicalSamplesReconstructNativeJetpackInk` — rebuild native ink
   from the reloaded canonical stroke and verify coordinate/pressure tolerance.
3. `twoCompletedStrokesProduceTwoOrderedTransactions` — ordering, element
   revisions and note revisions reflect two distinct completed commands.
4. `duplicateElementIdFailsAtomicallyAndLeavesPreviousRevisionIntact` —
   uniqueness violation does not create a second element or advance the revision.
5. `wrongPageOwnershipFailsWithoutInsertingAnyElements` — an invalid page ID
   cannot receive the stroke and does not advance revision.

## Intentionally deferred

- User gestures and a saved-stroke UI in `NoteEditorScreen`.
- Reopening the production editor to display stroke geometry.
- Autosave coordinator integration, retry UI, and pending edits across process death.
- Stylus input latency, mixed brush native fidelity, and full viewport integration.
- Changing the production Room schema or portable format.

## Verification on Windows

From the `android` folder (with a running Pixel Tablet emulator):

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkRoomPersistenceTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkDocumentBridgeTest
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.notebook.app.ui.editor.ink.InkCanonicalRebuildTest
```

Expected: all commands report `BUILD SUCCESSFUL`. Inspect the instrumentation
XML report to ensure **all five CP2B test methods actually ran** (not zero tests).
The normal Notebook app and the debug Ink Probe should remain unaffected.

## Scope and limitations

This checkpoint proves database durability in an emulator instrumentation test,
not the complete handwriting UI workflow. Do not report EDT-05 complete until the
later integration and measurements have passed.
