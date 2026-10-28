# LIB-03 Android Repository & Room Handshake — Checkpoint 2A

**Date:** 2026-10-28  
**Status:** Proposed integration contract for peer review; not a Room migration or completed CRUD implementation.  
**Scope:** LIB-03 #18 / STO-02 #25 handshake (D01–D10 from `sto_02_android_persistence_contract_v0_1.md`).

## Sources / verified state

- `docs/decisions/0001-library-hierarchy-and-trash-semantics.md` (ADR-0001, proposed)
- `docs/architecture/notebook_library_schema_contract_v1.md` (LIB-01 logical schema, proposed)
- `docs/architecture/notebook_library_sqlite_schema_proposal_v1.sql` (proposed SQL, not a migration)
- `docs/architecture/sto_02_android_persistence_contract_v0_1.md` (jointly reviewed direction, Kotlin contract pending)
- Existing Android `com.notebook.app` Compose/navigation shell on main. No Room code is currently implemented.

## Decisions for reviewer approval (proposed)

| STO ID | Concrete decision / contract | Owner |
|---|---|---|
| D01 | `com.notebook.app.data.library.model.Note` is the domain model; later `com.notebook.app.data.local.entity.NoteEntity` is the sole `notes` Room row and belongs to LIB-03. `PageEntity`/`ElementEntity` belong to STO-02. | LIB-03 note; STO-02 content |
| D02 | One `com.notebook.app.data.local.AppDatabase`, version/migrations and exported `schemas/`; LIB-03 maintains database class, schema version and migration PRs, with STO-02 reviewing relevant changes. Never build a second Room DB for note content. | LIB-03 |
| D03 | Kotlin `String` canonical lowercase UUIDs in public models, entities and SQLite `TEXT`. `UUID.randomUUID().toString()` at creation. Move/rename preserve IDs; duplication creates a new note UUID and delegates page/element ID remapping to STO-02. | LIB-03 / STO-02 |
| D04 | Local time fields `Long` UTC epoch milliseconds (`INTEGER`), `System.currentTimeMillis()` at the repository boundary; `Note.revision: Long` starts at 0. It advances once per successful **content command** commit, not rename, move, opened time or per-input-sample. Portable JSON dates become UTC RFC3339 when exporting. `updated_at` is updated for meaningful metadata/content writes. | LIB-03 note metadata; STO-02 content |
| D05 | `NoteLifecycleRepository.createNote()` inserts note metadata, then invokes `NoteContentTransactionPort.insertInitialPage()` within **one** `AppDatabase.withTransaction`. First page order 0; no zero-page active notes; missing content adapter is a hard error/rollback, never a metadata-only fallback. `duplicateNote()` likewise clones all content in the same transaction. | LIB-03 orchestrator; STO-02 port adapter |
| D06 | Public suspending repository interfaces in `com.notebook.app.data.library.repository`. Room DAOs stay internal to data layer; Compose must not call them directly. `NoteRepository` exposes metadata mutations only; `NoteLifecycleRepository` is the only normal note create/clone entrypoint. Invalid operations fail by exception and roll back; a later UI adapter can map exceptions to result states. | LIB-03 |
| D07 | A returned successful suspend mutation signifies database commit, not just an in-memory update. Do not report `Saved(revision)` before the outer transaction commits. STO-02 owns editor save-state mapping and retries; library operations do not synthesize a saved content revision. | LIB-03 transaction, STO-02 save status |
| D08 | Soft delete preserves note pages/elements/asset references. `LibraryTrashRepository` contracts are preliminary; permanent purge is gated on reference-aware asset deletion. `cloneContent()` must fail atomically for unsupported asset-backed notes, not silently drop images/PDFs. | LIB-03 Trash; STO-02 content |
| D09 | Page indexes are dense `0..N-1` per note; element indexes dense `0..N-1` per page; maintain uniqueness and stable ID tie-breaks for reads. When reordering under SQLite immediate `UNIQUE` constraints, use a two-phase temporary-index transaction and test rollback. Library `sort_order` permits ties, sorted by ID as tie-breaker. | STO-02 content; LIB-03 hierarchy sort |
| D10 | Validate canonical page/element JSON in Kotlin on the Android side *before* a successful commit; Python validators remain build-time fixtures only. This first ZIP validates only primitive library inputs and finite positive initial page size; full background/element validation belongs to STO-02. | STO-02 full content |

## Ownership and source paths

- This checkpoint adds pure Kotlin **models and interfaces**, not database entities or DAOs.
- LIB-03 future Room code: `android/app/src/main/java/com/notebook/app/data/local/AppDatabase.kt`, `.../entity/FolderEntity.kt`, `.../entity/NotebookEntity.kt`, `.../entity/NoteEntity.kt`; Room DAOs under `.../dao/`; repository implementations under `.../library/impl/`.
- STO-02 future code: `.../data/local/entity/PageEntity.kt`, `.../data/local/entity/ElementEntity.kt`, respective DAOs and `NoteContentTransactionPort` adapter.
- The database maintainer updates the **same** `AppDatabase` on integration. Changes to version/migrations and schema exports require both developers' review.

## Required transaction behavior

**New note**: transaction starts → validate active notebook/Quick Notes → insert `NoteEntity` (revision 0) → invoke content adapter to insert validated initial page at index 0 → transaction commits → return `Note`. Any failure rolls back note and page; a database without content adapter must reject note creation.

**Duplicate**: transaction starts → read active source and validate target → insert a new note row (new UUID) → content adapter clones ordered pages/elements with new IDs and validated references → transaction commits → return new note. Missing/unsupported assets, bad source content, duplicate IDs and injected errors roll back all changes.

**Move**: transaction checks active ownership, valid destination and folder ancestry inside the transaction; changes only hierarchy metadata and never page/element IDs. Invalid operations roll back.

**Quick Notes**: persisted notebook role `system_role = 'quick_notes'`, not name/id. Provision at bootstrap transaction; at most one enforced by partial unique index and protection trigger; bootstrap ensures exactly one. Never move/delete it.

**Trash**: delete batches and historic parent snapshots from ADR-0001 are required for the eventual implementation; this checkpoint only reserves a surface API and deliberately does not provide purge.

## Room migration plan, later checkpoints

1. Decide/review complete Room version-1 physical entity set and how the earliest STO-02 initial-page adapter is delivered; **do not** ship a production DB version containing creatable zero-page notes.
2. Configure Room/KSP only after D02–D07 and D09 are reviewed. Room entities must be generated from the approved logical contract; keep schema export enabled and commit the generated JSON.
3. Use explicit migrations for every later physical schema change; never use `fallbackToDestructiveMigration` for user data.
4. Verify real on-disk database reopen, foreign-key constraints, Quick Notes uniqueness, rejected folder cycles, rollback of creation/duplication, and note-move content invariance before calling LIB-03 complete.

## Checkpoint 2A local acceptance

- Pure Kotlin contracts compile in `:app:assembleDebug` and `:app:testDebugUnitTest` with no new Gradle dependencies.
- `LibraryValidationTest` passes four tests (UUID, names, page dimensions, Quick Notes role).
- No second Room database, no `NoteEntity`/`PageEntity` yet, no Android UI behavior changes.
- Peer reviewer confirms/adjusts D01–D10 and the initial-page integration strategy before Room code is added.
