# STO-08 — Asset Reference Lifecycle and Safe Cleanup Contract V0.1

**Date:** 2026-11-05
**Status:** Proposed — C0 architecture review; **not approved for implementation**
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31)
**Baseline inspected:** Android commit `7ab050c` (Room v3 / STO-04), feature branch `feature/sto-08-reference-contract`
**Dependencies:** STO-03, STO-04, LIB-07; downstream QAT-04
**Scope:** Cross-platform reference semantics; Android integration boundaries; cleanup eligibility and verification gates. **No schema migration or destructive behavior is authorized by this document.**

## 1. Authority and acceptance boundary

The issue's acceptance criterion is: **Assets are not deleted while referenced by active notes, Trash, or an in-progress export.** This document refines, but does not replace:

- `docs/architecture/notebook_document_model_spec_v0_2.md` (page-local elements, image/PDF `assetId`, content-addressed archive assets).
- `docs/architecture/notebook_library_storage_plan_v0_1.md` (SQLite/private originals, Trash, staged publication, export snapshot, GC ordering).
- `docs/architecture/lib_07_android_permanent_purge_safety_contract_v0_1.md` (explicit confirmed, transaction-scoped, asset-free purge).
- `docs/architecture/storage_migration_recovery_contract_v0_1_reviewed.md` (non-destructive migrations, acknowledged commits, crash recovery).
- `docs/decisions/0001-library-hierarchy-and-trash-semantics.md` (proposed library ownership/Trash semantics).
- `docs/product/notebook_v1_prd_and_delivery_plan.md` (R-DAT-02, R-DAT-03, R-DAT-04, R-DAT-05).

**Acceptance is a safety property.** Zero tracked references is not, by itself, proof that an original can be deleted. No cleaning of committed originals or asset metadata is authorized before owner completeness and concurrent import/export protection are demonstrated.

## 2. Verified Android baseline (not proposed future behavior)

At `7ab050c`:

1. `AppDatabase` is Room **v3** with `AssetEntity` metadata, but has no `asset_references` entity or reference-counting DAO. `AssetDao` has no deletion method.
2. `ManagedAssetFileStore` stages, hashes, signature-checks, synchronizes and publishes immutable content-addressed originals. It deletes **its own incomplete newly created final file** on failed publication and deletes **its own staging temporary file** in `finally`. These narrowly bounded cleanups are **not reference-aware GC**.
3. `ManagedAssetRegistrar` registers verified bytes before metadata. It explicitly does **not** attach a page/element owner or export pin and is not wired to UI/import entry points.
4. `ManagedAssetRecoveryInventory` and `ManagedAssetReconciler` are bounded/read-only; their findings are diagnostic, not deletion permissions.
5. `CanonicalContentValidator` rejects `pdfPage` backgrounds and `image` elements. `RoomDocumentRepository`, `RoomNoteContentTransactionAdapter`, and `RoomPermanentPurge` retain fail-closed guards on unsupported media.
6. `RoomLibraryTrashRepository` performs soft-delete/restore and confirmed batch purge through `AppDatabase.withTransaction`. Current purge supports validated **asset-free** content only and does not remove asset files or rows.
7. `AppDatabaseProvider` registers explicit migrations through v3, with **no** destructive fallback.

These observations do not establish that legacy/corrupt asset references cannot exist, or that an asset row without a tracked owner is safe to reclaim.

## 3. Ownership definitions and invariants

### 3.1 Durable document owners

- A `pdfPage` background's `assetId` is a **direct durable reference from its page** to an immutable PDF original.
- An `image` element payload's `assetId` is a **direct durable reference from its element** to an immutable image original.
- A note owns pages and pages own elements through existing hierarchical relationships. A note's active/Trash state affects access and recoverability, **not the existence of those direct asset references**.
- An asset may be used by many pages/elements or notes. Identical original bytes may be deduplicated by SHA-256; the stored asset ID resolves to the verified local asset metadata, not a user-controlled filesystem path.
- Library folders/notebooks and virtual views (All Notes, Recent, Search, Trash) do **not** add another copy of a document or a second asset owner.
- A proposed physical `asset_references` table, if selected, is a **projection of authoritative committed page/element content**; it must remain transactionally consistent with that content. A raw integer `ref_count` is not an independent source of truth.

### 3.2 Required invariants

**AR-01 — No dangling committed owners.** Before acknowledging an asset-backed document commit, every referenced asset must resolve to the expected immutable metadata and verified finalized original bytes. A metadata row or staging path alone is insufficient.

**AR-02 — Transactional ownership.** A logical insert/replace/delete/transfer/duplicate/purge commits document changes and the resulting durable ownership changes in **one SQLite transaction**. A failure rolls back all database mutations; no early `Saved` acknowledgement.

**AR-03 — Trash retains ownership.** Soft-deletion, Trash listing, restoration and rehoming of independently trashed descendants preserve their page/element references. Only an explicitly confirmed permanent purge or a committed document edit can release durable ownership.

**AR-04 — Shared originals survive.** Removing one owner does not reclaim bytes while any other active or trashed owner exists, including a separately trashed descendant outside a purge batch.

**AR-05 — Export safety.** An export cannot lose source originals between its consistent document snapshot and completion of a verified independent asset snapshot. Protection must coordinate with cleanup across all writers that can touch the app-private files, not merely a UI flag.

**AR-06 — Conservative uncertainty.** Malformed/unknown canonical JSON, unresolved asset IDs, incomplete scans, migration/backfill discrepancies, corrupt metadata, unavailable DB, and uncertain concurrent operations block cleanup rather than being interpreted as absence of references. Surface diagnostics; never erase a note to repair a missing asset.

**AR-07 — File/DB ordering.** Publish and verify immutable bytes **before** the transaction attaching an owner. Commit removal of the last owner **before** considering filesystem reclamation. A crash may leave an orphan; a committed owner must never become dangling due to cleanup.

**AR-08 — Bounded private paths.** Only validated app-private, regular, non-symlink files at expected content-addressed paths are candidates for future reclamation. Unknown, unreadable, oversized, mismatched or unscanned entries are retained and reported.

**AR-09 — No implicit GC.** Neither a document edit, Trash restore, metadata registration nor a read-only reconciliation result directly deletes a committed original. Cleanup remains a separately guarded workflow, disabled until the gates in §8 pass.

**AR-10 — Existing safeguards stay active.** Image/PDF mutation and asset-aware purge remain unsupported until each new path has its own transaction, migration and failure evidence. Existing STO-04 temporary/failed-publication cleanup is unchanged by this proposal.

## 4. Operation lifecycle matrix

| Operation | Durable reference behavior | Transaction / safety boundary |
|---|---|---|
| Import/stage image or PDF | No owner during staging or file-only registration; attach only when document content commits | Verified final bytes first; DB metadata + reference + document edit atomically as applicable |
| Insert image element | Add one element-owner edge to existing verified asset | Same transaction as element insertion and note revision |
| Change image element asset | Replace owner edge, preserving all other edges | Same transaction as element JSON update |
| Set/change `pdfPage` background | Add/exchange page-owner edge | Same transaction as background change |
| Delete element or page | Remove its direct edges, without immediate file deletion | Same transaction as document removal |
| Move element between pages | Reassociate ownership to destination element/page identity without a liveness gap | One document command transaction; no file copy |
| Move note between notebooks | No asset-reference change | Existing library metadata transaction |
| Soft-delete note, notebook or folder | Preserve all descendant page/element edges | Existing Trash transaction; no asset deletion |
| Restore batch | Preserve edges; only library state changes | Existing restore transaction |
| Duplicate note/page/element | New element/page owner IDs create additional edges; reuse verified immutable bytes where appropriate | All copied content and its edges commit together; rollback on failure |
| Purge a confirmed Trash batch | Remove only edges of actually purged content; preserve separately trashed/reowned survivors | Same DB transaction as confirmed batch purge and child-first row deletion; file cleanup deferred |
| Begin/export note or backup | Protect originals used by a consistent document snapshot until independent copies are verified | Export pin/lease or protected-copy protocol required before any GC |
| Cancel/crash during import/export | Retain committed originals; abandoned staging/export temps handled only by separately proved recovery rules | Never infer owner absence from operation timeout alone |

Undo/redo of a committed edit is another ordinary durable mutation and must update ownership and note revision on commit; the session-only undo stack is not a persistent asset owner.

## 5. Export protection: decision required before cleanup

**Required outcome:** A garbage collector must be unable to delete an original between an export's DB snapshot and the point where the export has independently verified its private copy of that original.

Two candidate protocols need a concurrency/failure design review:

- **Transactional export pins/leases:** persist a set of protected asset IDs before releasing the snapshot; release only after verified copy or safe cancellation. Define process-death recovery, stale lease rules and behavior with concurrent GC.
- **Coordinated copy-to-private-snapshot:** hold an explicit importer/exporter/GC exclusion boundary during snapshot acquisition and copying, then package only the independent copies. Define lock scope across processes, disk-space failure, rollback and crash handling.

**Do not choose a mechanism based on this document alone.** A normal open file descriptor, an in-memory boolean, or a long-running Room read snapshot is not automatically sufficient proof of cleanup safety. No export-aware cleanup can be enabled without a deterministic race test.

## 6. Room schema and legacy transition (not approved)

**Current:** Room v3 stores asset metadata, not ownership. **Decision open:** authoritative JSON traversal versus an explicit ownership table. The latter is the recommended direction for efficient transactionally maintained edges, but no physical columns, FK actions, indices or v4 migration are approved at C0.

If explicit durable ownership is selected, expect an additive Room **v3→v4** migration; the production database provider, exported schema, historical migration tests and isolated-room tests must be updated in that later checkpoint. **Do not** bump the Room version just for this contract.

A proposed migration must:

1. Preserve existing library metadata, all note/page/element IDs, order, revisions, timestamps, deletion batches, Trash snapshots and existing asset metadata.
2. Inspect or safely classify **all** existing active and trashed page backgrounds and element envelopes, not only currently exposed UI objects. A corrupt/unrecognized payload is not evidence of no owner.
3. Define a lossless backfill (or durable unresolved-reference quarantine/gate) before treating the new ownership inventory as complete. If the data cannot be classified, fail opening the new mutable implementation safely or keep all original-file cleanup disabled; never overwrite the existing data.
4. Maintain explicit historical upgrade paths and verify Room schema exports and database foreign keys. Never use `fallbackToDestructiveMigration` or recreate the user's library on failure.
5. Keep local DB schema version separate from the `.notebook` portable `manifest.schemaVersion` and Apple GRDB versioning.

Metadata rows without owner edges after migration may be genuine unreferenced registrations, staging remnants, or evidence of incomplete backfill. They are **not** automatic deletion candidates.

## 7. Reconciliation and future GC eligibility

STO-04 reconciliation is **diagnostic only**. A future reclaim decision would require, **at minimum**, all of the following at the actual removal boundary:

1. Verified complete, internally consistent authoritative ownership inventory, covering active and Trash content plus applicable legacy data.
2. Zero durable page/element owners **and** zero effective export/import protection for the exact asset/version; no unresolved owner or unclassified scan findings relevant to that asset.
3. Verified file hash, byte count, MIME signature, metadata identity and canonical private path; no symlink or unexpected file type.
4. An exclusion/serialization protocol that prevents a concurrent writer, importer or exporter from attaching or reading the candidate between eligibility check and unlink, including multi-process behavior.
5. Confirmed DB/FS operation ordering and crash recovery that cannot leave a surviving committed owner pointing at a deleted file.
6. Conservative failures: interrupted scan, I/O exception, uncertain transaction or partial export => no deletion. Record diagnostic outcome without leaking note content.

These are **necessary, not a complete GC algorithm**. A future implementation must handle the check-to-delete race and database metadata retirement explicitly. Do not rely on a stale `COUNT(*) == 0` query or a standalone reconciliation report.

## 8. Implementation stages and review gates

| Checkpoint | Scope | Exit evidence | No-go boundary |
|---|---|---|---|
| **C0 — contract and read-only audit** | Approve invariants, trace mutation paths, decide unresolved questions | Joint review of this document and test matrix; no production code changes | No new deletion, media writes or Room migration |
| **C1 — durable reference inventory** | Select representation; implement explicit migration/backfill **if approved**, diagnostic read model | Historical v1/v2/v3 upgrade tests, legacy/ambiguity cases, read-only consistency tests | No file cleanup |
| **C2 — atomic document writers** | Integrate page/element edits and duplication/transfer with references; retain unsupported-path gates until each is tested | Success, rollback, shared-asset, revision and idempotency tests | No GC from document commits |
| **C3 — Trash + export protection** | Integrate exact-batch purge edge updates; implement reviewed export protection | Independently trashed survivor tests and forced export/purge races | No cleanup until concurrency proof |
| **C4 — opt-in controlled reclamation** | Dry-run candidate audit first; only then guarded reclaimer in isolated environments | Crash/fault/race tests, no-surviving-owner deletion, no false-positive reclamation | Never touch primary emulator for destructive/fault tests |

Full STO-08 acceptance remains dependent on Apple-side STO-03 integration and joint semantic verification. Android code passing alone is not the joint issue's definition of done.

## 9. Minimum regression and failure matrix

| ID | Scenario | Expected result |
|---|---|---|
| S08-01 | Single asset owned by two separate active notes; one owner removed | Bytes and metadata required by surviving note remain intact |
| S08-02 | Shared asset with one active and one trashed note | Original retained; Trash restore unchanged |
| S08-03 | Duplicate note with image and PDF references | New owners; same verified immutable original; independent subsequent deletions safe |
| S08-04 | Page/element move or replacement | Exactly the intended edges after one committed command; no transient GC eligibility |
| S08-05 | Trash a folder then restore one batch | No asset owner lost; no duplicate document/reference rows |
| S08-06 | Purge one batch containing an ancestor of an independently trashed descendant | Descendant and its original survive; original deletion metadata retained |
| S08-07 | Stale confirmation preview or failed purge transaction | No content, ownership or asset state changed |
| S08-08 | Import fails before DB commit, after final bytes published | No dangling committed owner; orphan retained for later guarded classification |
| S08-09 | Export starts; last document owner purged while copy is in progress | Export has verified independent source or fails safely; protected original is not deleted prematurely |
| S08-10 | Crash during owner removal, GC eligibility, unlink and export protection release | Surviving committed owners never point to deleted originals |
| S08-11 | Invalid JSON, unknown asset, missing file, bad hash, symlink or incomplete scan | Fail closed; no automatic file deletion |
| S08-12 | Room v1/v2/v3 historical upgrades, including Trash | All existing data retained; incomplete/ambiguous backfill blocks cleanup |
| S08-13 | Concurrent registration and possible last-reference removal | No TOCTOU loss, duplicate authority, or acceptance of partially published bytes |
| S08-14 | Retry after uncertain DB outcome | No duplicate edges, false revision increment or false `Saved` status |
| S08-15 | Filesystem permission/storage-full failures | Atomic DB behavior and actionable failure; no data loss or cleanup side effects |

Use isolated databases/private temporary directories for normal failure tests. For any destructive Android device/fault-injection test, first verify emulator serial `emulator-5556` is `STO07_S03B_Disposable`; never wipe or apply storage pressure to the primary emulator.

## 10. Decisions for joint review (must be resolved before C1/C3)

- **D1 — Ownership representation:** explicit page/element-reference table versus authoritative JSON scan; how to enforce JSON↔reference consistency across all mutation APIs.
- **D2 — Backfill/legacy gate:** how to detect, classify and retain unsupported/corrupt v3 content; whether a migration must abort or can persist an unresolved status while keeping GC disabled.
- **D3 — Export protection:** durable pins/leases versus coordinated private-copy snapshot; multi-process and process-death semantics.
- **D4 — Cleanup policy:** whether any automatic GC is allowed in V1 at all; explicit purge of DB content must not imply immediate file deletion.
- **D5 — Verified bytes:** when and by whom hash/signature/path verification is repeated before owners attach and when exports consume originals.
- **D6 — Apple parity:** Apple storage semantics, shared failure fixtures and closure criteria once STO-03 is available.

**Recommendation for review:** explicit durable page/element reference edges, an additive migration only after audit design approval, no age-based asset reclamation, and disabled GC until export/rollback/recovery races pass. Record long-term cross-platform or storage decisions in an ADR under `docs/decisions/` after review; this contract is not an acceptance or migration authorization.

## 11. C0 acceptance checklist

- [ ] Both developers review direct owners, Trash semantics and unsupported-content safeguards.
- [ ] Mutation, duplication, purge and export boundaries match actual production APIs.
- [ ] Open decisions D1–D6 are assigned/resolved or expressly deferred to the correct checkpoint.
- [ ] Required tests S08-01 through S08-15 have owners and planned fixtures.
- [ ] C0 document reviewed in PR. No production GC, Room migration or image/PDF enablement was introduced.
