# STO-02 — Android Note/Page/Element Persistence Contract V0.1 (Joint Review Update)

**Date:** 2026-10-28  
**Status:** Jointly reviewed architectural direction (reported agreement on 2026-10-28); **specific LIB-03 API, Kotlin type, Room schema, and transaction-owner decisions remain pending**. No Room code or migration is implemented by this document.  
**Issue:** [STO-02 #25](https://github.com/IlyaMich/notebook-app/issues/25)  
**Dependencies:** FMT-01 (reviewed); LIB-03 #18 (awaiting LIB-01 #16 and repository-contract agreement).  
**Primary owner:** storage / document persistence developer.  
**Related ownership:** library domain and repository interfaces developer.

**Review record (2026-10-28):** Both developers have reviewed the V0.1 proposal and agreed to incorporate the recommendations from the Checkpoint-1 review: explicit shared-database ownership, note lifecycle and first/last page rules, duplication/identity handling, revision and ordering semantics, Android-side validation, and focused persistence tests. This records agreement on **what must be specified**, not decisions about as-yet-unseen LIB-01/LIB-03 Kotlin classes. The detailed decisions in Section 8 remain deliberately open until those interfaces are available.

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

The `notes` table, `NoteEntity` and library-facing note lifecycle interface belong to LIB-03, unless the two developers explicitly agree otherwise. STO-02 should *reference* its note ID and participate in the **same SQLite/Room database instance and transaction boundary**, not invent a second `notes` entity.

| Concern | Agreed direction | Still to assign or finalize |
|---|---|---|
| Folder/notebook domain and repositories | LIB-01/LIB-03 developer | Concrete Kotlin contracts and packages |
| `notes` metadata, notebook relationship, and `NoteEntity` | LIB-03 developer | Table layout, revision and lifecycle integration |
| `pages`, `elements`, and their DAOs | STO-02 developer | Entity/DAO signatures, serializers and adapters |
| One `AppDatabase`, schema export and migrations | Shared database, **one owner** | Named maintainer for database class/version/migrations |
| Create note + initial page | **One transaction** in shared database | Which public service orchestrates both inserts |
| Element-command durability and save status | Commit before acknowledging saved revision | Final interface, caller and error/result contracts |
| Assets and asset references | Separate coordinated work, later | Asset owner and lifecycle implementation |

**Integration rule:** neither developer introduces duplicate note entities or independent databases. Compose screens depend on agreed repository/use-case APIs, not Room DAOs. An orchestration service may be jointly designed, but should have a single code owner.

## 3. Candidate persistence mapping (reviewed direction; physical schema pending)

| Table | Proposed columns | Storage rules |
|---|---|---|
| `notes` (owned by LIB-03) | `id`, `notebook_id`, `title`, `created_at`, `updated_at`, `revision`, `deleted_at?` | Non-null notebook owner; note revision is incremented on successful content commit, not on raw input sampling. Exact LIB-03 contract takes precedence. |
| `pages` | `id`, `note_id`, `order_index`, `width`, `height`, `background_json`, `revision` | `note_id` FK to `notes`; ordered per note; double-precision size in document units; background remains tagged, editable metadata. |
| `elements` | `id`, `page_id`, `order_index`, `kind`, `element_json`, `revision` | `page_id` FK to `pages`; one row per editable element, not a whole-page JSON document; `element_json` preserves the full canonical V0.2 envelope. |
| `assets` / `asset_references` (later coordinated scope) | `id`, `sha256`, `mime_type`, `byte_size`, `relative_path`, `state` / owner references | Original bytes live in app-private immutable files; references and lifetime are coordinated transactionally. **Do not** eagerly implement in this checkpoint. |

**Unfinalized suggested physical types:** UUIDs as `TEXT` using canonical textual UUID representation; page dimensions as SQLite `REAL`/Kotlin `Double`; ordered indexes and revisions as `INTEGER`/Kotlin `Long` or `Int` according to actual bounds; canonical JSON as UTF-8 `TEXT` with explicit serializers. The choice of `TEXT` timestamps (ISO/RFC 3339 UTC) versus `INTEGER` UTC epoch milliseconds is **open** until agreement. If using epoch milliseconds, serialization to the portable archive must produce UTC RFC 3339 strings without precision loss at the agreed granularity.

**Identity scope needs explicit agreement:** the document spec requires element IDs unique *within a note*; the storage plan proposes globally unique UUID identifiers. A table-level global primary key is a stronger rule. If adopted, importing a second valid note with colliding element/page IDs requires an explicit deterministic ID remap with preserved references; alternatively use composite keys. Do not silently reject valid portable documents only because of a storage-key shortcut.

**Canonical JSON:** store stroke points, transform, `locked`, and payload as individual element JSON. `kind` may be an indexed projection for queries but is never an independent incompatible source of truth: validate that it matches the JSON envelope on writes. No authoritative persisted `bounds`, viewport zoom or rasterized element replacement. Pixel caches and native ink data are disposable.

**Duplicating a note:** LIB-03's acceptance criteria include duplication. A duplicate must have a **new note ID**, new page IDs and new element IDs; preserve the source note's ordered content and canonical geometry. Perform the snapshot-and-clone operation atomically with the agreed library orchestration. Remap all internal references consistently. Image/PDF asset reference handling must wait for the later asset-lifecycle work; no partial asset-backed duplication should be falsely reported complete.

**Local revision fields:** The note revision is the authoritative monotonic acknowledgment token for document writes. Page/element `revision` columns are optional optimization candidates until the collaborators choose a clear meaning (e.g. their last-modified note revision). They must not create competing save-status counters. `updated_at` is modified in the same transaction as a committed document command, using the agreed UTC convention.

## 4. Constraints and indexes to implement or enforce

- `pages.note_id → notes.id` and `elements.page_id → pages.id` are enforced FKs. The proposed `notes` deletion model uses soft delete; any hard-delete cascades must be deliberate and tested, not accidental loss of trashed content.
- Pages are unique and ordered by `(note_id, order_index)`; elements by `(page_id, order_index)`. Reading must always use `ORDER BY order_index` plus a stable ID tie-breaker for legacy/corrupt states.
- Recommended indexes: `pages(note_id, order_index)` and `elements(page_id, order_index)`; choose uniqueness and enforcement in Room after verifying reorder behavior.
- Reordering inside a single transaction must not transiently violate immediate SQLite unique constraints. Use a reviewed two-phase temporary-index algorithm (or a safe equivalent), not a naïve swap of two unique positions. A candidate is temporarily allocating non-colliding indexes above the current maximum, then writing final indexes; validate integer bounds, cross-parent moves, and failure rollback with SQLite tests.
- **Recommended committed-state invariant (pending exact API decision):** per-note page indexes and per-page element indexes are contiguous, zero-based integers `0..N-1` with no duplicates. Append, insert, delete, reorder and cross-page transfer must leave a normalized order at transaction commit; only affected ordering rows should change.
- Positive finite page sizes, recognized page backgrounds, finite/non-singular element transforms and typed payload validity must be checked before persisted content is acknowledged as saved. Do not rely on SQL constraints alone for the complete portable semantic contract.
- A page belongs to exactly one note and an element belongs to exactly one page. Moving a note in the library must not rewrite its page/element rows.
- An empty **note** is not part of the portable V0.2 contract: a newly created note should atomically receive at least one default page, following the agreed note-creation API. Deleting the **last remaining page** of an active note must either be rejected or atomically replaced by a default page according to the finalized UI/repository policy; do not commit an active zero-page note. Soft-deleted notes retain pages/elements for restore. Permanent deletion and hard-delete cascades remain jointly owned and need explicit tests.

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
| Delete last remaining page | Reject or replace atomically (policy pending); never persist an active zero-page note | No change, or page insert/delete plus note metadata |
| Duplicate note | Atomic clone under LIB-03 orchestration, with new note/page/element IDs | New note, pages, elements, and later coordinated asset refs |

**Save-state invariant:** `Saving` means a command is pending, `Saved(revision)` means the Room transaction for that revision has actually committed, and `Save failed` means a commit failed. Never advance the reported saved revision when an insert/update throws or a transaction is rolled back. Editing gestures are batched into completed commands; no per-sample `@Insert` in a stylus loop.

**Concurrent edits:** serialize writes for one note (e.g. a per-note queue) or use an equivalent concurrency-safe revision scheme. One logical editor command increments the note revision **exactly once inside its successful database transaction**, even if it changes multiple elements/pages. The caller receives an acknowledgment for that revision **only after** the transaction completes successfully. In-memory undo is not a persistence requirement; an undo action is another durable transaction.

**Asset boundary:** local SQLite commits are not atomic with arbitrary file moves. When asset support is implemented, stage and verify immutable original bytes **before** committing DB references. Delete DB references **before** asynchronously deleting unreferenced originals; do not put irreplaceable assets in cache storage.

## 6. Android validation boundary and proposed Room design (not yet code)

**Validation before commit:** the Android persistence adapter must reject invalid canonical element envelopes, even when written directly by an editor command rather than imported through the Python portable-package validator. At minimum: supported element `type`, envelope `id` matching row ID, `type` matching indexed `kind`, finite invertible transforms, typed payload requirements, valid owner references, page dimensions/background, and applicable element-ID uniqueness within the note. If an element needs an image/PDF asset, do not claim a fully validated committed document before asset references are verifiable. Reuse the published shared-format rules; write Kotlin validation/tests rather than invoking Python from the app.

**Failure rule:** validation failure, foreign-key failure, or injected transaction exception leaves persisted content and the acknowledged note revision unchanged. Database validation is independent of future ink rendering and PDF decoding; do not claim to validate PDF page-count bounds or fully decode media at this milestone.

**Room design after LIB-03 contracts stabilize:**

When LIB-03 is ready:

1. Integrate `PageEntity`/`ElementEntity` with the **existing or agreed** shared Room `AppDatabase` and LIB-03 `NoteEntity`; don't duplicate database ownership.
2. Add `PageDao` and `ElementDao`, plus mapping helpers between canonical document models and persisted rows. Surface behavior through the agreed repository interfaces, not by letting Compose call DAOs directly.
3. Implement transaction-coordinated note/page creation and command commits using `RoomDatabase.withTransaction` or equivalent. Room/KSP versions and Gradle integration are reviewed against the **current** Android build, not chosen by this document.
4. Add real database tests (in-memory Room for DAO semantics and disk-backed/reopen tests for persistence); keep the existing Python shared-format tests unaffected.
5. Record initial Room schema export; test all later migrations explicitly. Never enable destructive migration fallback for real user notes.
6. Keep page/element data operations and mapper tests owned by STO-02; use shared-domain types and note repositories when available rather than adding parallel models. Decide the single owner for `AppDatabase` and its version **before** editing shared Gradle/database code.

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
| S13 | Duplicate a multi-page note containing mixed elements | Duplicated note/page/element IDs differ; hierarchy, order, transform and payload semantics match; failed clone rolls back everything. Asset-backed duplication remains separately gated. |
| S14 | Attempt to delete the last page | Operation follows the agreed reject-or-replace policy; an active note never commits zero pages. |
| S15 | Reject structurally invalid canonical element content | Wrong kind/envelope ID, invalid transforms or payloads cause no element write and no note revision increment. |

For issue #25, **S02–S07 and S10** directly demonstrate the stated acceptance criterion; S01, S08–S09, and S11–S15 protect M1 integration, the library boundary, and the eventual portability boundary.

## 8. LIB-01/LIB-03 handshake and decision register

**Joint review acknowledged:** both developers reviewed the proposed approach and agreed to incorporate the recommendations in this document. **This is not yet a finalized Kotlin contract**. Record the decisions below against actual LIB-01/LIB-03 types before implementing Room. Prefer adding precise package/type/method names after the collaborator's work is available rather than guessing here.

| ID | Agreement / item | Current status | Decision required before Room integration |
|---|---|---|---|
| D01 | LIB-03 owns note metadata and `NoteEntity`; STO-02 owns page/element persistence | Agreed direction | Confirm exact package/entity ownership on the active implementation branch |
| D02 | A **single** shared `AppDatabase`, schema export and migration strategy | Agreed direction | Name the sole owner of `AppDatabase`, DB version and migration files |
| D03 | Stable UUID identities and explicit collision remapping for clones/imports | Agreed direction | Choose Kotlin `String` / `UUID` / value-class ID types, converters and packages |
| D04 | UTC timestamps and durable monotonic note revisions | Agreed direction | Choose epoch-milliseconds `Long` vs UTC text, exact revision type, and page/element revision meaning |
| D05 | Note creation and initial-page insertion are atomic | Agreed direction | Choose orchestration owner, transaction entrypoint and default-page parameter contract |
| D06 | STO-02 must implement/integrate with LIB-03 repository contracts | Agreed direction | Confirm interfaces, method signatures, results/errors and coroutine concurrency ownership |
| D07 | `Saved(revision)` only after commit; failures never advance saved state | Agreed direction | Name layer responsible for save status and exact acknowledgment API |
| D08 | Soft delete preserves pages/elements; duplication remaps note/page/element IDs | Agreed direction | Specify clone snapshot, last-page policy, hard delete and future asset-reference integration |
| D09 | Ordered pages/elements with collision-safe transactional renumbering | Agreed direction | Approve zero-based contiguous indexes and tested reorder/transfer algorithm |
| D10 | Android-side canonical validation before commit | Agreed direction | Identify serializer/validator owner and API; exclude media decode/PDF page-count claims |

For each outstanding decision, record **selected option**, **Kotlin type or file path**, **owner**, and **reviewer approval** in the LIB-03 integration PR or this document. Agreement on a goal is not a substitute for picking a concrete code contract.

**Dependency gate:** do not edit shared `AppDatabase`/`NoteEntity`, add Room dependencies, or implement adapter code against provisional duplicate interfaces until D02–D07 and D09 have concrete agreed answers. The remaining cases must have an integration plan and tests before merging STO-02.

## 9. Checkpoint-1 exit criteria

- [x] Both developers reviewed the mapping and agreed on the recommended architectural refinements (reported 2026-10-28).
- [x] The proposed scope has not introduced another `NoteEntity`, Room database or library repository interface; this artifact is documentation only.
- [ ] Actual LIB-01/LIB-03 Kotlin contracts and ownership decisions D02–D07/D09 are written down, including initial-page transaction and ID/timestamp conventions.
- [ ] Reviewed documentation is committed and merged separately through the normal PR workflow.
- [ ] Any Room implementation branch starts only after LIB-03 supplies or agrees to stable integration contracts.

**Issue status:** STO-02 remains open/in preparation until real persistence tests pass and a reviewed implementation PR is merged. Documentation review/merge alone does not complete issue #25.
