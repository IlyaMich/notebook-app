# STO-02 — LIB-03 handshake and Room V2 foundation

**Date:** 2026-10-29  
**Checkpoint:** 2A source inspection complete; 2B Room foundation ready for Android verification and peer review  
**Status:** Implementation proposal; **not accepted until build, on-device tests, and collaborator review pass**

## Actual source inspected

The checkpoint was prepared against an archive of the `main` checkout supplied by the Android developer after merging LIB-03. The source-of-truth files are:

- `android/app/src/main/java/com/notebook/app/data/local/AppDatabase.kt` (`AppDatabase`, single Room database; V1 before this checkpoint)
- `android/app/src/main/java/com/notebook/app/data/local/AppDatabaseProvider.kt` (`library.sqlite`, schema callback, no destructive migration)
- `android/app/schemas/com.notebook.app.data.local.AppDatabase/1.json` (exported V1 schema, unchanged)
- `android/app/src/main/java/com/notebook/app/data/local/LibrarySchemaGuards.kt` (onCreate triggers)
- `android/app/src/main/java/com/notebook/app/data/local/entity/{FolderEntity,NotebookEntity,NoteEntity}.kt`
- `android/app/src/main/java/com/notebook/app/data/library/repository/NoteContentTransactionPort.kt`
- `android/app/src/main/java/com/notebook/app/data/library/impl/RoomNoteLifecycleRepository.kt`
- `android/app/src/main/java/com/notebook/app/data/library/model/InitialPageSpec.kt`
- `docs/architecture/android_persistence_contract_v0_1.md`

## Selected concrete handshake (review required)

| Decision | Selected option / exact integration point | Owner |
|---|---|---|
| D01 | LIB-03 retains `NoteEntity`, `NoteDao` and library repositories. STO-02 adds `PageEntity`, `ElementEntity`, `PageDao`, `ElementDao` under `com.notebook.app.data.local`. | LIB-03 for notes; STO-02 for content |
| D02 | Exactly one `AppDatabase` and one `AppDatabaseProvider`; STO-02 changes their declarations for V2 in this checkpoint. Historical migrations live in `data/local/migration/AppDatabaseMigrations.kt`; preserve `1.json` and generated `2.json`. Never add destructive migration fallback. | Shared database; STO-02 migration author, collaborator reviewer |
| D03 | Canonical lowercase UUIDs as Kotlin `String`, SQLite `TEXT` global primary keys. Page/element IDs in imports that collide with local database IDs must later be remapped consistently; the portable requirement of uniqueness within the note remains satisfied. | STO-02 |
| D04 | Timestamps stay `Long` UTC epoch milliseconds using the existing LIB-03 `NoteEntity`. `notes.revision: Long` is the sole durable document revision. `pages.revision`/`elements.revision: Long` mean last changed note revision, not competing counters. | Shared note field; STO-02 content mutation code |
| D05 | `RoomNoteLifecycleRepository.createNote` owns `AppDatabase.withTransaction`; STO-02's implementation of `NoteContentTransactionPort.insertInitialPage(noteId, spec: InitialPageSpec)` joins it without another transaction or database. Fresh note and page start at revision 0. | LIB-03 coordinator; STO-02 adapter (Checkpoint 3B) |
| D06 | Reuse `NoteContentTransactionPort.cloneContent(sourceNoteId, targetNoteId)` under the same LIB-03 transaction. Public page/element repository APIs will be specified in Checkpoint 3A; direct DAOs are not UI APIs. Existing `LibraryOperationException`/`LibraryErrorCode` remain the shared errors. | STO-02 |
| D07 | Future mutation repository wraps each logical edit in one `db.withTransaction`, updates `NoteEntity.updatedAtMillis` and increments `NoteEntity.revision` exactly once. Only return an acknowledged revision after successful transaction completion; UI save state remains a later integration. | STO-02 mutation layer (Checkpoint 3A) |
| D08 | New notes must have an initial page; deleting the last active page will **reject** rather than auto-create a replacement. Soft delete retains content. `RESTRICT` FKs avoid accidental hard-delete cascades. Future explicit content purge is jointly coordinated with LIB-07. Duplicating asset-dependent content fails atomically until asset lifecycle is implemented. | STO-02 with LIB-07 integration later |
| D09 | `orderIndex: Long` is 0-based and contiguous at committed repository boundaries. Unique `(note_id,order_index)` and `(page_id,order_index)` indexes are enforced by SQLite. Reordering will use a two-phase allocation of collision-free temporary positions in one transaction, checking overflow, then restore contiguous final positions; no naïve swap. | STO-02 (Checkpoint 3A) |
| D10 | Element JSON is canonical full UTF-8 V0.2 envelope, one row per element. `kind` is checked against its envelope type on writes. Android-side typed semantic validation will be built in Checkpoint 3A (likely using `kotlinx-serialization-json`), including background validation, finite/invertible transforms and payload shape. Media refs are rejected until asset ownership exists. | STO-02 (Checkpoint 3A) |

## V1→V2 migration decision: legacy note rows

V1 had folders, notebooks and notes **without pages or elements**. Though production creation lacked a real content adapter, V1 SQLite could contain existing note metadata. To preserve that data and establish the nonempty-note rule, migration gives **each preexisting note** (including trashed notes) a single default blank A4 portrait page (`595.276 × 841.890` document units), indexed at 0. It uses a new UUID for each page. The page revision is copied from the note revision. It does **not** change any `notes` column, timestamps, notebook ownership, deletion state or note revision. Existing triggers/indexes/tables are not dropped. On a brand-new V2 database there is no backfill; initial page creation stays the responsibility of the future production adapter.

Unlike editable content operations, these historical rows are metadata-only, so migration does not purport to reconstruct content that V1 never stored. If this fallback policy is not approved by the collaborator, do not merge this migration; revise it before Checkpoint 3A.

## Checkpoint 2B files and explicit exclusions

Added: `PageEntity`, `ElementEntity`, `PageDao`, `ElementDao`, `AppDatabaseMigrations`, two Android instrumentation tests and this document. Modified: `AppDatabase` and `AppDatabaseProvider` only. No modifications to LIB-03 `NoteEntity`, note coordinator or existing repository contracts. No handwriting engine, PDF decode, backup, Trash implementation or asset management.

**Known guardrail:** raw DAOs are internal persistence primitives without canonical validation or last-page protection. Only repository methods implemented in Checkpoint 3A should be used for application edits. The foundation tests directly call DAOs only to test Room/SQLite behavior. This checkpoint is **not yet full STO-02 functionality**.

## Verification and review gate

1. On Windows, merge the supplied repository-relative files on `feature/sto-02-android-persistence` and run `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest` from `android/`.
2. Confirm `android/app/schemas/com.notebook.app.data.local.AppDatabase/2.json` is generated and has exactly five entities: `folders`, `notebooks`, `notes`, `pages`, `elements`. Keep `1.json` untouched.
3. Verify migration integration test upgrades exported-V1 physical schema and retains existing library metadata, Quick Notes trigger and durable revision. Verify the foundation test checks ordering/FK/unique constraints and database reopen.
4. Review the V1→V2 backfill policy and decisions D01–D10 with the collaborating developer. No claim of peer approval until the review actually happens.
5. Confirm `git diff --check` succeeds; commit only after passing build/tests and review of the generated schema. Advance to Checkpoint 3A only after this checkpoint is verified.
