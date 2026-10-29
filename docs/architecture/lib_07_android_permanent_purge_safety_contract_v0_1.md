# LIB-07 — Android Permanent-Purge Safety Contract V0.1

**Date:** 2026-10-29  
**Checkpoint:** 3A — review and implementation specification  
**Status:** Asset-free database-only purge approved by both developers (2026-10-29); Checkpoint 3B implements the bounded subset. Asset-reference cleanup remains deferred.  
**Depends on:** ADR-0001, LIB-01 schema contract, LIB-03 repository contract, STO-02 Room V2, LIB-07 checkpoints 1–2.

## 1. Scope and source inspection

The Android production implementation currently uses a single `AppDatabase` at Room version 2, with five tables: `folders`, `notebooks`, `notes`, `pages`, and `elements`. The hierarchy uses `RESTRICT` foreign keys, not cascaded deletion. `RoomLibraryTrashRepository` already implements transactional soft-delete, listing of deletion batches, and batch restore; its public `LibraryTrashRepository` deliberately has no purge method. `PageDao` and `ElementDao` offer per-row deletes, but the folder/notebook/note DAOs do not expose hard-delete operations yet.

Room V2 does **not** provide production `assets` or `asset_references` tables, nor content-addressed file garbage collection. Shared SQLite/Python tests model these future tables as a **behavioral oracle**, not as implemented Android storage. `CanonicalContentValidator` accepts blank/ruled/grid/dotted pages and stroke/shape/text elements and rejects PDF-backed pages/image elements. Any real hard-delete must therefore be limited to a proven asset-free subset or deferred; neither the existence of the validator nor passing soft-delete tests proves general asset safety.

### Invariants to preserve

1. Permanent purge is an explicitly confirmed user action; V1 has no automatic or age-based purge. Quick Notes is never eligible.
2. A purge targets **only members of one explicitly selected deletion batch**. Active rows and members of other batches must never be deleted as a side effect.
3. A separately trashed descendant survives, retaining its own deletion batch, `deleted_at`, historical original-owner snapshot and complete pages/elements.
4. Because all FKs are `RESTRICT`, survivors must be detached/reowned **before** deletion. If safe rehoming is impossible, the entire transaction aborts.
5. The exact page/element rows of each purged note are removed child-first in the same transaction; surviving notes keep their IDs, revision, pages and elements unchanged.
6. All preflight checks, ownership changes and hard deletes occur inside **one** `AppDatabase.withTransaction`; failure rolls back every change. No direct DAO access from UI.
7. No asset file, registry entry or reference may be deleted by this checkpoint. Unknown, malformed or asset-backed content must fail closed until asset-reference ownership is implemented.
8. A successful purge cannot be undone via Trash; the UI must distinguish this from non-destructive Move to Trash.

## 2. Proposed API and confirmation boundary (review before coding)

Extend `LibraryTrashRepository` with a **read-only preview** of one selected batch and an **explicit purge** of that batch. The preview reports root title/kind, batch ID, counts of folders/notebooks/notes/pages/elements to delete, and counts of independently trashed items to preserve/rehome. It must clearly say **permanent** and **cannot be restored**. Do not expose DAOs or present a generic 'empty trash' operation at this stage.

The purge call should receive the selected `deletionBatchId` and a representation of the **confirmed preview membership** (for example, an ordered list of the member UUIDs or a deterministic fingerprint of the sorted kind/UUID tuples). Recompute the membership within the write transaction; reject a changed/stale preview rather than deleting content the user did not confirm. Counts alone are insufficient to detect swapped members. No need to add a database table for confirmation.

If the entire batch cannot safely be purged, the operation should return a distinct unsupported-content/invalid-state failure with no mutation. Do **not** silently delete the subset of entries it could handle. Use existing `LibraryOperationException` and `LibraryErrorCode` where appropriate; avoid reporting a failed write as success.

### Preflight, inside the same transaction

- Validate the canonical batch UUID and retrieve rows with matching `deletion_batch_id` and non-null `deleted_at` from all three library tables. Reject empty batch, unexpected active batch member, malformed membership or Quick Notes.
- Build exact target ID sets `P_folders`, `P_notebooks`, `P_notes`, and a **separate** set of foreign-key dependents outside those sets. Do not infer membership from current ancestry alone.
- Ensure every affected note remains either a purge target or a separately trashed survivor. Reject any active non-target child currently owned by a targeted parent (inconsistent library state).
- Confirm the distinguished Quick Notes notebook exists and is active at root before choosing it for a surviving note. It must not be a target.
- For **every note in the purge set**, validate all stored page backgrounds and element envelopes, including that indexed element ID/kind match canonical JSON, and reject unsupported or unrecognized media/reference content. Never assume that a row is safe solely because the current UI cannot create images/PDFs. Consider the note unpurgeable if the available storage cannot prove it has no asset references.
- Confirm the preview identity still matches the selected batch. Apply a bounded, well-defined traversal of folder ancestry, rejecting cycles/corrupt ownership.

**Approved:** the team agreed to a fail-closed, no-filesystem, asset-free **database-only** purge as an interim feature. Full asset-aware purge remains deferred. This is narrower than the fully asset-aware LIB-01 oracle.

## 3. Transaction algorithm

1. Begin `AppDatabase.withTransaction` and complete the preflight above, including validated preview membership.
2. **Rehome surviving independently trashed folder rows** whose current `parent_folder_id` points to a folder in `P_folders`: set `parent_folder_id = NULL`. Preserve deletion metadata, `original_parent_folder_id`, `original_sort_order`, IDs and timestamps. A surviving subtree is preserved; do not traverse it as a deletion target.
3. **Rehome surviving independently trashed notebook rows** whose current `parent_folder_id` points to a folder in `P_folders`: set `parent_folder_id = NULL`; preserve all deletion metadata and content ownership.
4. **Rehome surviving independently trashed note rows** whose current `notebook_id` points to a notebook in `P_notebooks`: set `notebook_id` to the active protected Quick Notes ID while keeping `deleted_at`, `deletion_batch_id`, `original_notebook_id`, note revision and its page/element records unchanged. Reject an active survivor instead of moving it.
5. Recheck that no FK-dependent surviving records still point into the purge sets. If they do, abort.
6. For each note ID in `P_notes`: explicitly delete all its **elements**, then **pages**, then its **note** row. Use constrained DAO operations and assert the intended number of affected rows. This is a permanent note-removal operation, not STO-02's editor `RemovePage` command, so the last-page prohibition does not apply. Never increment surviving note revisions.
7. Delete target **notebooks** only after all target notes are gone and non-target notes have been rehomed.
8. Delete target **folders in leaf-to-root order**, only after any externally owned children have been rehomed. A failure or unexpected nonzero dependent count aborts the full transaction. SQLite `RESTRICT` constraints are the final guardrail, not a substitute for explicit validation.
9. Complete the transaction; the returned result reports exactly the selected deleted members and retained/reowned descendants. Invalidate Trash/library UI listings only after success. If any step throws, all preflight-dependent updates and deletions roll back.

Historical snapshot IDs (`original_parent_folder_id` / `original_notebook_id`) are intentionally **not FKs** and must **not** be rewritten when their historical parent disappears. Subsequent `restoreBatch` resolves them against active owners and falls back to root or Quick Notes, as already implemented.

## 4. Asset boundary and future integration

There are **no production asset-reference records** in Room V2; do not claim to have implemented reference-aware file cleanup. The shared library test oracle includes hypothetical `assets` / `asset_references` tables and tests shared assets surviving one note purge. That is a future storage contract and is not directly executable against the current Android V2 schema.

For now, restrict any new purge implementation to a narrowly validated asset-free domain. Reject image elements, `pdfPage` backgrounds, unrecognized element kinds, invalid canonical JSON, and anything whose asset ownership is not provable. Never delete files as an implicit consequence of a DB write. When asset storage is introduced, registry/ref removal must be transactional and filesystem garbage collection must occur only **after** a committed reference removal; shared assets and assets referenced by Trash must survive. That future integration may require a new Room migration and recovery tests.

The UI must either hide/disable permanent delete outside the supported domain or present a clear error explaining why the batch cannot yet be permanently deleted. Do not expose an 'Empty Trash' action that can only partially process the library.

## 5. Test matrix required for Checkpoint 3B

| ID | Scenario | Required result |
|---|---|---|
| P01 | Purge single trashed blank-note batch, with two pages and editable elements | Note, pages and elements removed; unrelated data unchanged |
| P02 | Purge folder batch with multi-level folders, notebooks and notes | Only this batch removed, child-first, no FK violations |
| P03 | Purge parent batch while note was trashed in an earlier independent batch | Independent note moved to Quick Notes **still trashed**; original snapshot, revision and content unchanged; later restore works |
| P04 | Purge parent batch while a folder/notebook is independently trashed | Survivor becomes trashed root; all its descendants and original snapshots remain intact |
| P05 | Multiple independent batches under same ancestor | Purging one never resurrects or deletes others; Trash listings remain meaningful |
| P06 | Attempt to purge Quick Notes, an active item or non-existent batch | Rejected, no writes |
| P07 | Inject failure after rehome, after first element deletion, after note delete and during folder delete | Full state matches pre-transaction snapshot (including page/element order and revision) |
| P08 | Tamper with stale preview membership before executing purge | Rejected without any deletion |
| P09 | Asset-backed PDF/image or unknown/corrupt canonical content in any target note | Whole purge fails; no row or file affected |
| P10 | Database close/reopen after successful purge and survivor restore | Same content, owners and deletion batches persist; `PRAGMA foreign_key_check` clean |
| P11 | Verify protected Quick Notes survives and has its original role/root location | Unchanged; independently trashed notes can be restored there |
| P12 | UI cancellation and explicit irreversible confirmation | Cancellation makes no writes; successful confirmation refreshes Trash and library |

For tests with *genuine asset references*, defer the actual Room instrumentation until the asset subsystem exists; retain the shared Python oracle tests for cross-platform contract validation.

## 6. Exact implementation boundary for 3B

- Reuse `RoomLibraryTrashRepository` and `LibraryRepositoryFactory`; no second database, new note entity or migration.
- Add only the minimum DAO reads/deletes needed for an atomic, identity-bounded purge. Prefer dedicated `deleteById` methods and read queries scoped to the affected owners/batch; do not expose unconstrained delete SQL to Compose.
- Reuse `CanonicalContentValidator` where it genuinely validates stored fields; never modify STO-02's editor transaction behavior to make purge work.
- Provide isolated Room instrumentation tests for P01–P11 and Compose tests for P12; run all three standard Gradle checks plus a manual Pixel Tablet scenario before committing.
- Permanent purge is a separate action from `trash*()`/`restoreBatch()`. The existing non-destructive Trash UI must continue functioning even if purge is unavailable.

## 7. Review gate before 3B

The two developers should confirm: (a) whether asset-free database-only purge is acceptable now; (b) confirmation preview and stale-preview handling; (c) independent-batch rehome rules; (d) no-asset-files policy; and (e) test cases above. If any decision conflicts with ADR-0001 or forthcoming asset design, update this contract first. The document is **not** a claim that permanent deletion has already been implemented or verified on Android.

**Reference materials:** `docs/decisions/0001-library-hierarchy-and-trash-semantics.md`; `docs/architecture/notebook_library_schema_contract_v1.md`; `docs/architecture/android_repository_contract_v0_1.md`; `docs/architecture/sto_02_room_v2_integration_decisions.md`; `shared/validators/test_library_trash_invariants.py`; `shared/validators/test_library_content_invariants.py`.
