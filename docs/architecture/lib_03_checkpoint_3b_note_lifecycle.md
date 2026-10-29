# LIB-03 Checkpoint 3B — Atomic note lifecycle coordinator

**Issue:** LIB-03 #18; STO-02 #25 integration handshake.
**Status:** implementation candidate; requires Android build, on-device tests and peer review.
**Scope:** LIB-03 coordinator only, not page/element persistence.

## Repository integration

- Adds `data/library/impl/RoomNoteLifecycleRepository.kt` implementing the 2A `NoteLifecycleRepository` contract.
- Updates `LibraryRepositoryFactory` with `noteLifecycle(content: NoteContentTransactionPort)`. The constructor requires an explicit content adapter. **There is no default/no-op port and no production creation path before STO-02.**
- Uses the existing **same** `AppDatabase.withTransaction` for note metadata insert and content port call. Does not create new tables, DAOs, `NoteEntity` or a second database.
- No UI wiring and no Room schema change; `schemas/.../1.json` must remain identical.

## Behavior and identity

- `createNote(notebookId=null)` ensures the unique protected Quick Notes notebook **inside the outer write transaction**, then inserts note metadata and invokes `insertInitialPage(noteId, firstPage)`. If the content adapter fails, **both** the note insert and initial Quick Notes provisioning (if any) are rolled back.
- `createNote(notebookId=<id>)` validates an active notebook inside the transaction. Initial page gets validated for finite positive dimensions and nonempty JSON by existing `LibraryValidation.initialPage`; **STO-02 additionally must validate canonical background JSON** before accepting a save.
- `duplicateNote(sourceNoteId, targetNotebookId=null)` validates the source note is active and target notebook is active. When target is omitted, duplicate stays in source notebook. It creates a new UUID and delegates *all* ordered page/element cloning and ID remapping to `cloneContent`. New revision is `0`, new created/updated timestamps, title copied unchanged, source unchanged. Content failure or unsupported asset references must abort all writes.
- Both operations return only after the shared Room transaction commits. They never increase source content revision or advertise `Saved` status on a failed write.
- Direct `NoteDao.insert()` remains solely an internal storage API, not a way for production code to create page-less notes.

## Deliberate limitation and required STO-02 integration

The existing Room **v1** schema has only folder/notebook/note tables. This coordinator alone does **not** enable creating a valid user-facing active note; no production `NoteContentTransactionPort` exists yet. Supplying a fake or no-op port in application code is explicitly forbidden, as it would commit a zero-page note. Therefore **LIB-03 #18's actual note creation/duplication acceptance remains open** until STO-02 implements page/element tables, validators and the real port in the same database, with an explicit migration v1→v2.

STO-02 adapter requirements:

1. Use the exact same injected `AppDatabase` instance; never start a separate independent transaction or use a second database.
2. Insert one real page with index 0, a new canonical UUID, validated tagged background JSON, valid page dimensions, and note ID FK; no empty active note.
3. For duplication, read a consistent source snapshot, insert new page/element rows preserving semantic order, assign fresh page and element IDs and remap internal references; reject unsupported asset-backed content until asset lifetimes can be committed safely.
4. Surface any validation/foreign-key/asset error via exception so the **outer** LIB-03 transaction rolls back; never swallow exceptions and report a successful clone.
5. Integration tests must inspect actual page/element rows after database reopen and verify rollback after partial content writes, not just mock interactions. This is an explicit pre-merge gate before declaring full LIB-03 issue acceptance.
6. Add an explicit schema migration and migration test. Do not edit the committed v1 `1.json`, or enable destructive migration fallback.

## Test intent and limits

`NoteLifecycleTransactionIntegrationTest` uses a **test-only** `NoteContentTransactionPort` which inserts a `FolderEntity` marker in the same shared Room database. The marker demonstrates that the port call executes inside the same SQLite transaction and is rolled back with the new note when the fake port throws. The tests check Quick Notes rollback, source metadata preservation, missing/inactive owners, duplicate delegation, same-notebook default and disk reopen.

**A test marker is not a real page.** These tests do not prove note content was created, copied, ordered or validated. STO-02 must add real content integration tests later before LIB-03 can be called fully complete. The fixture code must never be copied into production.

## Verification

```powershell
cd android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
cd ..
git diff --check
git diff -- android/app/schemas
```

Expect all Gradle tasks to pass with the Pixel Tablet emulator running; schema diff empty. Commit only after successful testing and review.
