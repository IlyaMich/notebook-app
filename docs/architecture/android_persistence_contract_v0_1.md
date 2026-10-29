# STO-02 — Android Note/Page/Element Persistence Contract V0.1

**Date:** 2026-10-28  
**Status:** Proposed implementation mapping for joint review; **not** an approved Room database schema or implemented migration.  
**Issue:** [STO-02 #25](https://github.com/IlyaMich/notebook-app/issues/25)  
**Dependencies:** FMT-01 (reviewed); LIB-03 #18 (awaiting LIB-01 #16 and repository-contract agreement).  
**Primary owner:** storage / document persistence developer.  
**Related ownership:** library domain and repository interfaces developer.

## 1. Basis, goal and non-goals

Based on the committed project references:

- `docs/architecture/notebook_document_model_spec_v0_2.md` — proposed portable `schemaVersion: 1`, editable objects, page-local ownership, ordered arrays, UTC, document units and asset rules.
- `docs/architecture/notebook_library_storage_plan_v0_1.md` — SQLite + private files, proposed logical tables, revisions, transactions, save-state rules, crash consistency and migrations.
- `docs/product/notebook_v1_prd_and_delivery_plan.md` — offline-first V1 and M1 outcomes.
- `shared/fixtures/README.md` — reference package fixtures, not a substitute for Room instrumentation tests.

STO-02's recorded acceptance criterion is **element insert/update/delete and ordered pages persist without a whole-page rewrite per stroke**. The broader M1 integration path is `create notebook → create note → restart → reopen the same saved note`.

This checkpoint produces a **mapping and verification contract only**. It must not:

- Create another note domain model or a second database independent of LIB-03.
- Modify your collaborator's LIB-01/LIB-03 Kotlin packages.
- Commit Room/KSP dependencies, Room entities, DAOs or migrations until the shared ownership decisions below are accepted.
- Start a handwriting engine, PDF rendering or full-library backup implementation.
- Assume the provisional portable wire format is already frozen.

## 2. Model boundary and responsibilities

```text
Folder / Notebook  — LIB-01 / LIB-03 library domain and repositories
         |
         v
Note(id, notebookId, title, revision, metadata) — LIB-03 ownership
         |
         v
Page(id, noteId, orderIndex, size, background, revision) — STO-02
         |
         v
Element(id, pageId, orderIndex, kind, canonical JSON, revision) — STO-02
```

**Library hierarchy metadata is not serialized into the portable note page geometry.** Moving a note between notebooks changes `notes.notebook_id`; pages, elements, transforms and content remain unchanged.

The `notes` table and note lifecycle interface belong to LIB-03, unless the two developers explicitly agree otherwise. STO-02 should *reference* its note ID and participate in the **same SQLite/Room database instance and transaction boundary**, not invent a second `notes` entity.

## 3. Candidate persistence mapping (pending joint review)

| Table | Proposed columns | Storage rules |
|---|---|---|
| `notes` (owned by LIB-03) | `id`, `notebook_id`, `title`, `created_at`, `updated_at`, `revision`, `deleted_at?` | Non-null notebook owner; note revision is incremented on successful content commit, not on raw input sampling. Exact LIB-03 contract takes precedence. |
| `pages` | `id`, `note_id`, `order_index`, `width`, `height`, `background_json`, `revision` | `note_id` FK to `notes`; ordered per note; double-precision size in document units; background remains tagged, editable metadata. |
| `elements` | `id`, `page_id`, `order_index`, `kind`, `element_json`, `revision` | `page_id` FK to `pages`; one row per editable element, not a whole-page JSON document; `element_json` preserves the full canonical V0.2 envelope. |
| `assets` / `asset_references` (later coordinated scope) | `id`, `sha256`, `mime_type`, `byte_size`, `relative_path`, `state` / owner references | Original bytes live in app-private immutable files; references and lifetime are coordinated transactionally. **Do not** eagerly implement in this checkpoint. |

**Suggested physical types:** UUIDs as `TEXT` using canonical textual UUID representation; page dimensions as SQLite `REAL`/Kotlin `Double`; ordered indexes and revisions as `INTEGER`/Kotlin `Long` or `Int` according to actual bounds; canonical JSON as UTF-8 `TEXT` with explicit serializers. The choice of `TEXT` timestamps (ISO/RFC 3339 UTC) versus `INTEGER` UTC epoch milliseconds is **open** until agreement. If using epoch milliseconds, serialization to the portable archive must produce UTC RFC 3339 strings without precision loss at the agreed granularity.

**Identity scope needs explicit agreement:** the document spec requires element IDs unique *within a note*; the storage plan proposes globally unique UUID identifiers. A table-level global primary key is a stronger rule. If adopted, importing a second valid note with colliding element/page IDs requires an explicit deterministic ID remap with preserved references; alternatively use composite keys. Do not silently reject valid portable documents only because of a storage-key shortcut.

**Canonical JSON:** store stroke points, transform, `locked`, and payload as individual element JSON. `kind` may be an indexed projection for queries but is never an independent incompatible source of truth: validate that it matches the JSON envelope on writes. No authoritative persisted `bounds`, viewport zoom or rasterized element replacement. Pixel caches and native ink data are disposable.

## 4. Constraints and indexes to implement or enforce

- `pages.note_id → notes.id` and `elements.page_id → pages.id` are enforced FKs. The proposed `notes` deletion model uses soft delete; any hard-delete cascades must be deliberate and tested, not accidental loss of trashed content.
- Pages are unique and ordered by `(note_id, order_index)`; elements by `(page_id, order_index)`. Reading must always use `ORDER BY order_index` plus a stable ID tie-breaker for legacy/corrupt states.
- Recommended indexes: `pages(note_id, order_index)` and `elements(page_id, order_index)`; choose uniqueness and enforcement in Room after verifying reorder behavior.
- Reordering inside a single transaction must not transiently violate immediate SQLite unique constraints. Use a reviewed two-phase temporary-index algorithm (or a safe equivalent), not a naïve swap of two unique positions.
- Positive finite page sizes, recognized page backgrounds, finite/non-singular element transforms and typed payload validity must be checked before persisted content is acknowledged as saved. Do not rely on SQL constraints alone for the complete portable semantic contract.
- A page belongs to exactly one note and an element belongs to exactly one page. Moving a note in the library must not rewrite its page/element rows.
- An empty **note** is not part of the portable V0.2 contract: a newly created note should atomically receive at least one default page, following the agreed note-creation API.

## 5. Repository operations and atomicity

The names below describe behaviors, **not finalized Kotlin signatures**:

| Behavior | Transactional expectation | SQL row impact |
|---|---|---|
| Create note with first page | Insert note + initial page atomically in one Room database | New note and one page |
| Add page | Append one page; update note revision | One page insert + note metadata |
| Move/reorder page | Reassign page order within transaction | Affected page order rows only |
| Add element | Insert one element and advance note revision after durable commit | One element insert + note metadata |
| Replace element | Update one element JSON/kind/revision and advance note revision | One element row + note metadata |
| Delete element | Remove one element and advance note revision | One element delete + note metadata |
| Reorder elements | Transactional index changes and revision | Ordering fields of affected elements + note metadata |
| Move element between pages | Atomic ownership (`page_id`) and order changes; preserve ID for a move | Affected element and order rows; note revision(s) according to ownership |
| Move note to another notebook | Library operation, outside STO-02 element mutation path | Update note notebook link only |

**Save-state invariant:** `Saving` means a command is pending, `Saved(revision)` means the Room transaction for that revision has actually committed, and `Save failed` means a commit failed. Never advance the reported saved revision when an insert/update throws or a transaction is rolled back. Editing gestures are batched into completed commands; no per-sample `@Insert` in a stylus loop.

**Concurrent edits:** serialize writes for one note (e.g. a per-note queue) or use an equivalent concurrency-safe revision scheme. One logical editor command increments the note revision once after a successful database transaction, even if it changes multiple elements/pages. In-memory undo is not a persistence requirement; an undo action is another durable transaction.

**Asset boundary:** local SQLite commits are not atomic with arbitrary file moves. When asset support is implemented, stage and verify immutable original bytes **before** committing DB references. Delete DB references **before** asynchronously deleting unreferenced originals; do not put irreplaceable assets in cache storage.

## 6. Proposed initial Room design, not yet code

When LIB-03 is ready:

1. Integrate `PageEntity`/`ElementEntity` with the **existing or agreed** shared Room `AppDatabase` and LIB-03 `NoteEntity`; don't duplicate database ownership.
2. Add `PageDao` and `ElementDao`, plus mapping helpers between canonical document models and persisted rows. Surface behavior through the agreed repository interfaces, not by letting Compose call DAOs directly.
3. Implement transaction-coordinated note/page creation and command commits using `RoomDatabase.withTransaction` or equivalent. Room/KSP versions and Gradle integration are reviewed against the **current** Android build, not chosen by this document.
4. Add real database tests (in-memory Room for DAO semantics and disk-backed/reopen tests for persistence); keep the existing Python shared-format tests unaffected.
5. Record initial Room schema export; test all later migrations explicitly. Never enable destructive migration fallback for real user notes.

## 7. Test matrix for STO-02 implementation

| ID | Test | Pass condition |
|---|---|---|
| S01 | Create a notebook and note | New note has the agreed notebook ID and a first page in one committed transaction. |
| S02 | Add three pages of different sizes | Pages reload in exactly the persisted order and sizes. |
| S03 | Add stroke-like canonical element JSON | Reopen database; element ID, transform, points and order are unchanged. No ink engine required. |
| S04 | Update only one element out of many | Changed element survives reload; unrelated element rows/revisions are unchanged; no whole-page JSON replacement. |
| S05 | Delete an element | Exactly that element is absent after reopen; other element rows survive. |
| S06 | Reorder two pages and two elements | Valid ordering persists after process/database reopen, including unique-index handling. |
| S07 | Transaction intentionally fails halfway | No partial page/element changes and no falsely advanced durable note revision. |
| S08 | Move a note between notebooks | Its note ID, page IDs and element payloads are unchanged. |
| S09 | Soft-delete/restore note | No underlying document content is lost; Trash rules remain LIB-03 owned. |
| S10 | Force-close and reopen app | Last acknowledged committed revision and same note/page/element identities are recovered. |
| S11 | Attempt bad owner IDs/invalid input | Foreign-key constraints and semantic validators reject writes; existing data remains intact. |
| S12 | Export database-backed note later | Ordered data can map to `manifest.pages[]` and `page.elements[]` without changes to canonical geometry; native cross-platform conformance stays a later milestone. |

For issue #25, **S02–S07 and S10** directly demonstrate the stated acceptance criterion; S01, S08–S09, S11–S12 protect M1 integration and the eventual portability boundary.

## 8. Required handshake with LIB-01/LIB-03 developer

Before Room code begins, record explicit agreement to each item:

- [ ] Which Kotlin type and package represent `NoteId`, `PageId`, and `ElementId`? Are the IDs stored as UUID strings or inline/value classes?
- [ ] Who owns the single `AppDatabase` / `NoteEntity` and database version; which code owns the atomic **create note + initial page** operation?
- [ ] What Kotlin repository methods and result/error types will STO-02 implement or extend? Does the friend plan to expose note mutation transactions?
- [ ] How are timestamps and revisions represented, and which layer reports `Saving/Saved/Failure`?
- [ ] How are soft deletion, note duplication and ID remapping coordinated with the document-content tables?
- [ ] How will ordered page/element indices be renumbered safely under SQLite uniqueness constraints?

Status of all items: **Pending joint review.** No decisions are implied by this document's candidate names/types.

## 9. Checkpoint-1 exit criteria

- [ ] Both developers reviewed this mapping and edited any incompatible assumptions.
- [ ] The `notes` owner, shared DB instance, initial-page transaction and typed ID/timestamp conventions are recorded.
- [ ] No duplicate `NoteEntity`, Room database or repository interface was introduced.
- [ ] A follow-up implementation branch is started only after LIB-03 supplies or agrees to stable contracts.

**Issue status:** STO-02 remains open/in preparation until real persistence tests pass and a reviewed implementation PR is merged.
