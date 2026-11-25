# STO-08 S08-01–S08-15 — Current Android traceability and open closure gates (D3-A)

**Date:** 2026-11-25. **Baseline:** `main` `ca9423fcd0560c27a9403f87fb103f2077f6d6aa`.  
**Method:** code/test existence inventory + named PR/device evidence. The D3-A test cases below are **new candidates**, not passed until both device runs are executed. The prior targeted D2I **68/68 per API** is **not** a comprehensive S08-01…S08-15 execution.

Status legend: **implemented/test source** = named code/tests exist, but does not assert complete current two-API execution; **candidate** = introduced by D3-A pending runtime; **open** = essential scenario is still not proven.

| ID | Original acceptance scenario | Relevant Android test or protection evidence | Current closure gap |
|---|---|---|---|
| **S08-01** | Two active notes share original; one owner removed | `AssetOwnershipTransactionBoundaryAndroidTest`, `Sto08AssetAwarePermanentPurgeAndroidTest`, D3-A owner observer (candidate) | End-to-end shared active-owner removal/absence of physical unlink needs consolidated two-device receipt |
| **S08-02** | Shared original, active+Trash owner | `RoomLibraryTrashRepositoryIntegrationTest`, `AssetProjectionDiagnosticRoomAndroidTest`; D3-A shared active/Trash test (candidate) | Test the positive restore and post-purge survival sequence against current main before final signoff |
| **S08-03** | Duplicate note with media references | `Sto08AssetBackedNoteDuplicationAndroidTest` plus D1 ownership production path | Consolidated two-device acceptance, rollback and subsequent independent ownership removal |
| **S08-04** | Page/element move, replace or remove | `AssetOwnershipTransactionBoundaryAndroidTest`, `InsertVerifiedImageWriterAndroidTest` | Full production owner+note revision race/rollback results across both APIs |
| **S08-05** | Trash folder then restore batch | `RoomLibraryTrashRepositoryIntegrationTest`, `RoomLibraryPermanentPurgeTest` | Media-backed folder restoration matrix and exact post-restore owner comparison |
| **S08-06** | Independently trashed descendant survives ancestor purge | `Sto08AssetAwarePermanentPurgeAndroidTest`, `Sto08MissingProjectionPurgeAndroidTest` | Consolidated post-purge descendant survival/restore and original-file retention evidence |
| **S08-07** | Stale purge confirmation / transaction rollback | `RoomLibraryPermanentPurgeTest`, `Sto08AssetAwarePermanentPurgeAndroidTest` | Reconcile historical B6 receipts with current main and trace all fail-closed paths |
| **S08-08** | Failure after original finalized but before owner commit | `ManagedAssetFileStoreAndroidTest`, `ManagedAssetRegistrarAndroidTest`, `ManagedAssetRecoveryInventory` | Exact interrupted attachment after byte finalization needs acceptance trace; orphan is retained, never auto-GC'd |
| **S08-09** | Export vs last owner purge / copy | D2–D2I Room-bound protected stage and immutable hold tests; D3-A simulated lease contender (candidate) | No production GC/unlink race exists; do not substitute synthetic contender for source deletion evidence |
| **S08-10** | Crash during owner removal, GC eligibility, unlink or export release | `ExportCommittedHoldProcessDeathAndroidTest`, source/hold immutability; D2I local picker process-kill evidence | GC/release crash phases cannot be claimed tested, because these protocols remain disabled |
| **S08-11** | Invalid JSON, unregistered asset, missing/unsafe file, incomplete inventory | `RoomAssetOwnerInventoryAndroidTest`, `AssetProjectionDiagnosticRoomAndroidTest`, `ReadOnlyFinalizedAssetVerifierAndroidTest` (if present); D3-A invalid/missing/budget tests (candidate) | Full corrupted metadata/link/permissions matrix and post-corruption audit still open |
| **S08-12** | Historical Room upgrade + Trash | `AppDatabaseMigrationV1ToV2Test`, `AppDatabaseMigrationV2ToV3Test`, `AppDatabaseMigrationV3ToV4Test`, STO-08 v4→v5, v5→v6 and v6→v7 migration tests | Cross-version ambiguous legacy/Apple evidence and consolidated current-main receipts pending; never backfill invented owner proof |
| **S08-13** | Registration races last-owner removal | `AssetAcquisitionLeaseCrossProcessAndroidTest`, `AssetAcquisitionLeaseCoordinatorAndroidTest`, D3-A simulated contender (candidate) | Real future last-owner GC race remains unimplemented; multi-process eventual reclaim protocol unresolved |
| **S08-14** | Retry after uncertain DB outcome | `ExportAtomicHoldAdmissionAndroidTest`, `InsertVerifiedImageProcessDeathAndroidTest`, `InsertVerifiedImageUncommittedProcessDeathAndroidTest`, v7 append-only attempt journal | No production UI operation-retry/recovery acceptance; arbitrary SAF attempt remains one-shot ambiguous |
| **S08-15** | Permission and full-storage failure | `ManagedAssetFileStoreAndroidTest`, private-copy failure tests, `ExportExternalDocumentByteHandoffTest`; D2H injected provider refusal tests | Integrated fault matrix including device storage pressure requires separately approved safe, disposable testing |

## Cross-cutting requirements still blocking formal joint closure

- **Production export flow:** D2I is a disposable debug fixture, not a Room-bound end-user archive publication workflow.
- **Durable destination:** generic SAF matching bytes never guarantees remote persistence or atomicity; no trusted completion receipt.
- **Hold release:** v5/v6/v7 immutable triggers intentionally forbid delete/update; no expiration/release migration approved.
- **Ownership certificate:** projection diagnostic, D3-A preflight and observed zero owners never promote `VERIFIED`; legacy/unclassified state blocks any deletion.
- **GC / metadata retirement:** no source-original reclaimer exists. Simulated future-reclaimer lease contention is a *non-destructive ordering test only*.
- **Apple:** repository contains `ios/.gitkeep` rather than Apple application implementation. Review Apple STO-03 evidence from its authoritative source/repository before declaring joint acceptance.
- **Evidence:** record executed JUnit XML receipts for each S08 case against an exact post-merge source tree. Historical prose is not a substitute for current runtime evidence.

**Scope decision:** D3-A may be reviewed and merged as a *read-only diagnostic* after CI and two-device tests. This matrix remains intentionally **OPEN**; neither D3-A nor D2I closes STO-08 issue #31.
