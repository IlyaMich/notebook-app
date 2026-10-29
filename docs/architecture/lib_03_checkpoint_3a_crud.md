# LIB-03 — Checkpoint 3A: Room library CRUD repositories

**Scope:** GitHub issue #18; builds on the reviewed 2A repository interfaces and committed 2B Room V1 foundation.
**Status:** implementation candidate; local Android compilation, instrumentation and peer review required.

## Files and entrypoint

- `data/library/impl/RoomFolderRepository.kt`: folder create/rename/move/find/list.
- `data/library/impl/RoomNotebookRepository.kt`: idempotent Quick Notes provisioning and notebook create/rename/move/find/list.
- `data/library/impl/RoomNoteRepository.kt`: note **metadata-only** find/list/recent/rename/move/opened; it does not insert notes.
- `data/library/impl/LibraryRepositorySupport.kt`: domain conversion and common transactional owner/cycle/error guards.
- `data/library/impl/LibraryRepositoryFactory.kt`: optional construction entrypoint backed by the **same** `AppDatabaseProvider.get(context)` singleton. No Compose wiring yet.
- Existing `FolderDao` and `NotebookDao`: add nullable maximum active sibling sort-order query. No table/column/index/schema changes.

## Invariants

1. `FolderRepository` and `NotebookRepository` mutation methods use `AppDatabase.withTransaction`. Parent ownership is checked within the transaction. Folder moves traverse destination ancestors inside that transaction, rejecting cycles (including corrupt preexisting ancestor loops).
2. Generated IDs are canonical lowercase UUID strings. Folder and notebook names are trimmed and nonblank; equal sibling names are permitted. Root placement uses `null` parent ID.
3. Library `sort_order` is scoped by type and parent. Create/move assigns `(MAX(active sibling sort_order) + 1)`, default 0; ties always sort by ID. Same-parent move is idempotent; explicit manual reorder awaits a later UI contract.
4. `ensureQuickNotes()` is idempotent, creates a single active root notebook with structural `system_role='quick_notes'` if missing. Root-only protection and uniqueness are also enforced by the DB schema guards from 2B. Renaming its **display name** is permitted; the system role is preserved. It cannot be moved into a folder.
5. Note metadata mutations do not change `revision`, page IDs or element IDs. `recordOpened()` only changes `last_opened_at` (not content revision nor last edited time). An empty note title is allowed in local storage.
6. Invalid writes throw `LibraryOperationException` with relevant `LibraryErrorCode` and preserve existing state. Lower-level SQLite exceptions still propagate and abort the current transaction; they must not be displayed as successful saves.
7. `findById()` returns a record (including Trash metadata if present); list queries return only records whose own `deleted_at` is null. Full deletion/restore, ancestor effective-deletion filtering, manual reordering, and asset cleanup remain other checkpoints.

## Explicitly not part of 3A

- `NoteLifecycleRepository.createNote()` and `.duplicateNote()` are **not implemented** here. The merged contract mandates that the note row and its first page/clone are written via STO-02's `NoteContentTransactionPort` in **one** Room transaction. No metadata-only fallback or fake first-page row is allowed. Checkpoint 3B will address the coordinator boundary without creating a separate Room database.
- `LibraryTrashRepository` remains a proposed interface for LIB-07; no purge or unsafe cascade deletion is introduced.
- Room physical schema remains **V1**. Do not regenerate a different 1.json or edit a deployed V1 physical schema; future content persistence requires an explicit V1->V2 migration.
- UI still uses synthetic library data. These repositories have no UI binding yet.

## Android verification

1. `:app:assembleDebug` and `:app:testDebugUnitTest` succeed.
2. On the running emulator, `:app:connectedDebugAndroidTest` executes five `LibraryRepositoriesIntegrationTest` tests **in addition to** existing database foundation tests.
3. Verify real SQLite close/reopen persistence, idempotent Quick Notes, cycle rejection, inactive/missing parent rejection, order and metadata revision invariance.
4. Existing `android/app/schemas/com.notebook.app.data.local.AppDatabase/1.json` remains unchanged (`git diff -- .../1.json` empty); no schema migration is required by DAO query-only additions.
5. Review and commit normal source files only after all checks pass; peer review before merge.

**Known limitation of 3A test setup:** The note metadata test inserts a synthetic note using the internal DAO **only as a fixture**, because the app's atomic note+first-page API is not yet available. That fixture is never a production creation path; it is cleaned up with its isolated test database. Real note creation tests belong to 3B/STO-02 integration.
