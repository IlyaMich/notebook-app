# STO-08 — C2A Transactional Asset-Ownership Writer Assessment and Contract V0.1

**Date:** 2026-11-05

**Status:** D1-D7 jointly approved for C2B planning; production media writers remain deferred

**Issue:** STO-08 #31

**Inspected baseline:** `ff0d0d1` (Room v4, PR #113), assessment branch `feature/sto-08-ownership-writer-assessment`

**Related architecture:** `docs/architecture/sto_08_asset_reference_lifecycle_contract_v0_1.md`; `docs/architecture/sto_08_room_v4_ownership_migration_design_v0_1.md`

**Scope:** Map production writer boundaries, establish atomic ownership-projection rules, identify safe C2B tests and explicit deferred work.

## 1. Baseline findings (observed, not proposed)

1. Room v4 has `page_asset_references`, `element_asset_references` and `asset_reference_index_state`. The v3→v4 migration and fresh-install initialization start with an empty ownership projection and durable `UNVERIFIED` gate. The gate blocks direct `VERIFIED` promotion. The projection is **not authoritative** over legacy content.
2. A repository-wide `git grep` of main Kotlin sources found ownership table names only in the Room entities, migration and safety-gate trigger code—not in a production read/write DAO. This search supports *no known production ownership-edge writer yet*; it is not proof that every possible raw SQL path has been exhaustively audited.
3. `RoomDocumentRepository.commit` wraps its command and revision change in `AppDatabase.withTransaction`. Supported page/element creation, ordering and non-media changes use existing page/element DAOs. PDF-background replacement/removal and image replacement/removal/transfer are explicitly blocked. `CanonicalContentValidator` rejects new PDF backgrounds and image elements.
4. `RoomNoteLifecycleRepository.createNote` and `duplicateNote` own their `withTransaction`. `RoomNoteContentTransactionAdapter` deliberately opens **no** additional transaction. It validates content and rejects unsupported PDF/image sources when creating or cloning.
5. `RoomLibraryTrashRepository` uses transactions for soft Trash, restore, preview and confirmed purge. Soft Trash and restore retain page and element rows. `RoomPermanentPurge` is invoked inside its caller's transaction, preflights canonical content and currently purges only an asset-free subset, child-first; it does not remove original files or `assets` metadata.
6. `ManagedAssetRegistrar` finalizes verified immutable file bytes before inserting asset metadata; it is not wired to the UI/import flows, does not attach owners, and is not itself a transactional document-edit coordinator. `AssetDao` exposes no asset deletion method.
7. `DocumentAssetReferenceInspector` and `RoomAssetOwnerInventory` are diagnostic only. The inventory is bounded and reports incomplete/unresolved states; its output is not a GC permit.

## 2. Source-of-truth and invariants (proposed C2 contract)

- **C2-01 Canonical authority:** `pages.background_json` (`pdfPage.assetId`) and `elements.element_json` (`image.payload.assetId`) are the authoritative direct references. The v4 tables are derived projections, never independent owner counts.
- **C2-02 One atomic database commit:** For any future asset-backed create/replace/remove/transfer/duplicate/purge, canonical content, exact ownership edges, and relevant revisions are written **inside the already owning Room transaction**, or all roll back. Never acknowledge an edit before commit. Do not create an independent transaction in `RoomNoteContentTransactionAdapter` or `RoomPermanentPurge`.
- **C2-03 Asset precondition:** Before committing a new durable reference, resolve the asset ID to **finalized** immutable metadata and validated original bytes (identity, location, digest, length/type as applicable); a mere metadata row or staged file is insufficient. Asset publication and database commit need a separately reviewed concurrency/failure protocol. Filesystem verification cannot be made atomic with SQLite merely by calling `withTransaction`.
- **C2-04 Exact projection:** A PDF-backed page has one `page_asset_references` row to its referenced asset; a non-PDF page has none. An image element has one `element_asset_references` row; a non-image element has none. Shared asset IDs across different owners are valid; duplicate/conflicting rows for one owner fail. Unknown/corrupt content does **not** imply 'no reference'.
- **C2-05 Conservative state:** All C2A and early C2B work leaves the durable gate `UNVERIFIED` (or `UNRESOLVED` if an approved diagnostic workflow records ambiguity). No automatic `VERIFIED` transition; empty ownership tables and a completed bounded inventory cannot authorize asset cleanup.
- **C2-06 No silent edge loss:** Existing non-media edits may not erase a legacy media reference or an unexpectedly present ownership row. When canonical JSON, kind and edge disagree, reject the destructive change and preserve data; defer repair to the audited backfill/reconciliation checkpoint.
- **C2-07 Trash retention:** Trash, restore, note/notebook/folder moves, rename and reordering do not remove direct owner edges. Purge may release edges only under a separately approved asset-aware protocol; existing fail-closed purge validation remains unchanged for now.
- **C2-08 Sharing and failure:** Removing one direct owner must not delete shared original bytes/metadata; failures (validation, foreign key, SQLite, cancellation) must preserve both content and edges. `RESTRICT` foreign keys are a defense-in-depth backstop, not a substitute for planned write ordering.
- **C2-09 No cleanup side effects:** C2 adds no asset GC, file deletion, metadata deletion, export pins or ability to write previously unsupported PDF/image content. C3/C4 must separately prove export synchronization and cleanup eligibility.

## 3. Operation matrix and integration boundaries

| Existing operation | Current transaction owner | Intended ownership effect | C2 disposition |
|---|---|---|---|
| Insert non-media page / element | `RoomDocumentRepository.commit` | No new asset edge | Preserve behavior; tests for unexpected indexed edges |
| Change non-PDF background / replace non-image | `RoomDocumentRepository.commit` | No new edge; never silently clear conflicting legacy edge | Preserve guard; fail closed on mismatch |
| Reorder pages/elements; transfer non-image | `RoomDocumentRepository.commit` | Edge identity unchanged (owner ID stable) | No edge rewrite normally |
| Future PDF/image insert or replace | `RoomDocumentRepository.commit` | Attach/replace exactly one direct edge atomically with canonical JSON and revision | **Deferred** until verified bytes + protocol + tests |
| Future page/element deletion with media | `RoomDocumentRepository.commit` | Remove edge before deleting restricted owner, in same transaction | **Deferred**; current guard remains |
| Create note with non-media first page | `RoomNoteLifecycleRepository` + adapter | No edge | Preserve |
| Duplicate note | `RoomNoteLifecycleRepository` + adapter | New page/element IDs; distinct edges pointing to the same verified assets | **Media cloning deferred**; current validation rejects |
| Trash / restore / note or notebook move | Library repositories | Preserve existing direct owner edges | Preserve |
| Confirmed permanent purge | `RoomLibraryTrashRepository` + `RoomPermanentPurge` | Future: delete eligible owner edges before owner rows, never original bytes here | **Asset-aware purge deferred**; present asset-free subset only |
| Managed asset register | `ManagedAssetRegistrar` | Adds finalized asset metadata, not a document owner | Keep separate; no implicit edge |
| Audit/backfill | Future reviewed transaction coordinator | Materialize projection from all canonical rows, including Trash | Deferred; no verified-state promotion in C2A |

## 4. Recommended C2B implementation sequencing

**C2B-1 (first code checkpoint):** Internal owner-edge DAO/query layer and deliberately narrow synchronization *primitive*, tested on isolated Room v4 databases. Include lookups by owner and by asset, insert/update/delete with ABORT/restrictive semantics, and an explicit fail-closed gate read. Do **not** attach this primitive to UI, canonical validators, `ManagedAssetRegistrar`, file deletion or existing media guards. Do not expose an API that returns 'safe to delete'.

**C2B-2:** Test transactional edge operations under one owner transaction, including rollback on injected error, both shared ownership types, invalid owner/asset foreign keys, duplicate owner conflicts, and gate `UNVERIFIED` during and after writes. Tests must use only isolated DBs and verified disposable emulator.

**C2C (after separate approval):** Integrate with existing document/lifecycle/purge transaction owners *only for explicitly reviewed operations*, after defining verified-byte acquisition and publication synchronization. Preserve all currently unsupported PDF/image mutation guards until full end-to-end safety tests pass.

**Audit/backfill dependency:** Choose and review a resumable full-content audit that includes active and Trash owners, bounded/ambiguous classification, crash recovery, and concurrency with writers. This is separate from making local synthetic edge writes atomic. Until then, the edge tables are not a complete ledger.

## 5. Minimum C2B/C2C test matrix

| ID | Scenario | Expected |
|---|---|---|
| C2-01 | Insert page/element edge with existing owner + finalized asset metadata in isolated test DB | Exactly one typed edge; gate remains unverified |
| C2-02 | Two pages/elements share one asset | Independent owners coexist; no reference counter |
| C2-03 | Missing asset / owner / duplicate owner | Constraint or checked operation fails; no partial commit |
| C2-04 | Synthetic JSON + edge update with injected exception inside `withTransaction` | Both content and edges roll back, revision unchanged |
| C2-05 | Move/reorder and Trash/restore with existing edges | Edges retained; owner IDs and asset IDs unchanged |
| C2-06 | Delete owner with restrictive edge still present | Database rejects; no silent cascading deletion |
| C2-07 | Corrupt/mismatched canonical JSON vs projection | Operation fails closed; no orphan reclamation inferred |
| C2-08 | Legacy upgraded v3 data with no new edges | Gate stays `UNVERIFIED`; no absence-as-ownership proof |
| C2-09 | Note clone and child-first permanent purge involving asset owners | Protected until separate media-aware implementations are reviewed |
| C2-10 | Restart/cancellation/fault injection | Durable gate and committed rows survive; failed updates leave no partial edge state |
| C2-11 | Missing or corrupt gate row | All ownership-dependent cleanup decisions fail closed |
| C2-12 | Exporting while owner is removed or duplicated | Deferred to C3: pin/snapshot protocol must prevent missing originals |

## 6. Jointly approved C2A decisions

**Joint review outcome (2026-11-05):** Both developers approved D1-D7 without amendments.

These approvals authorize planning and isolated implementation of the internal C2B ownership DAO and test primitives only. They do not authorize production PDF/image writes, legacy completeness certification, gate promotion to `VERIFIED`, or asset cleanup.

1. **D1:** Confirm `RoomDocumentRepository.commit` and existing lifecycle/purge owners remain the only transaction coordinators; no new nested independent transaction API.
2. **D2:** Confirm typed v4 edge tables remain a projection of canonical content; no reference counts or GC-authorizing queries in C2B.
3. **D3:** Confirm C2B-1 may introduce a private DAO/synthetic test primitive while keeping all PDF/image validators and UI paths unchanged.
4. **D4:** Confirm an unexpected edge on an apparently non-media owner is treated conservatively (fail closed) for content deletion/replacement and purge until reviewed reconciliation.
5. **D5:** Agree that finalized-byte verification and cross-resource acquisition synchronization are hard prerequisites before any asset-backed production writer is enabled.
6. **D6:** Confirm C2B test implementation runs only against disposable isolated Room databases and never triggers asset cleanup.
7. **D7:** Agree that full legacy audit/backfill and gate promotion are separately reviewed; C2A does not claim the index is complete.

## 7. C2A exit criteria

- [x] Merged v4 baseline and new assessment branch verified.
- [x] Repository-wide production DAO, transaction, table/gate and media-guard grep reviewed.
- [x] Primary write owners and fail-closed media restrictions identified.
- [x] Both developers reviewed and approved D1–D7 without amendments.
- [x] Review decisions recorded and document finalized for a documentation-only commit.
- [ ] Begin C2B-1 on a reviewed scope; do not relax media restrictions or enable GC.

**Safety conclusion:** Room v4 now supports durable ownership edges, but it is not a complete owner ledger. C2 must prove atomicity for any supported writer, while legacy completeness, export pins and reclamation remain separately gated.
