# STO-08 — C2C-2 Verified Asset Attachment and Publication Protocol V0.1

**Status:** APPROVED — P1–P7 jointly accepted on 2026-11-05; authorizes only separately reviewed C2C-2B/C primitives, not media writes

**Baseline inspected:** `03323bd` (`main`, C2C-1 squash merge, PR #117)

**Working branch:** `feature/sto-08-c2c2-attachment-protocol`

**Issue:** STO-08 #31

**Upstream approved contract:** `docs/architecture/sto_08_c2a_transactional_ownership_writer_contract_v0_1.md` (C2-01 through C2-09; D1–D7)

**Objective:** Specify how a verified, immutable original could become the direct asset of a canonical PDF-backed page or image element while preserving SQLite atomicity, file durability, concurrent import safety, and conservative legacy ownership handling. **This document does not authorize or implement production PDF/image writes.**

## 1. Observed implementation baseline

1. `ManagedAssetFileStore.stageAndFinalize` stages and hashes in app-private persistent storage; it checks MIME signatures, exclusively creates content-addressed destinations, syncs final file and parent directory, and rechecks size/hash. It serializes publication using a per-process monitor plus an OS file lock. The final destination is visible during copying; cooperating importers must not accept it until verification completes. A process crash can leave a partial destination that subsequent attempts reject rather than overwrite.
2. `ManagedAssetRegistrar.register` invokes file finalization first, then separately resolves/inserts an `assets` row. It handles concurrent digest registration by re-reading a matching winner. It does not attach an owner, verify a full PDF/image decode, or coordinate a document transaction. A finalized but unregistered original is possible after failure.
3. `AssetDao` has metadata reads and `INSERT ABORT`, but no delete/replace. `AssetOwnershipDao` exposes typed owner lookups, `INSERT ABORT`, compare-and-set edge updates, guarded removals, and a fail-closed completeness-state read.
4. Canonical `pages.background_json` (`pdfPage.assetId`) and `elements.element_json` (`image.payload.assetId`) are the source of truth; typed Room v4 reference tables are projections. Room v4 ownership completeness remains `UNVERIFIED` (or conservatively `UNRESOLVED`), not a complete legacy ledger.
5. `RoomDocumentRepository.commit` owns the document-and-revision `withTransaction`; note creation/duplication and purge have their own existing transaction owners. C2C-1 added fail-closed guards for unexpected ownership edges, without enabling new media mutations. The canonical validator still rejects PDF backgrounds and image elements.
6. `ManagedAssetReconciler` and recovery inventories are diagnostic-only in this scope. Export pins and deletion eligibility are unimplemented.

**Critical gap:** Finalization and metadata registration do **not** constitute a durable owner attachment. A metadata row, a matching hash name, or an empty edge table alone does not permit publishing canonical media content.

## 2. Required invariants for any future writer

- **A1 — Canonical authority:** A committed media-bearing JSON envelope and exactly one matching typed owner edge are written in the *same existing owner transaction* with the applicable revision. Reject mismatches; never silently infer no owner from missing edges.
- **A2 — Verified original:** Before committing any new reference, resolve its ID to an immutable `finalized` metadata row and verify the actual persistent original: constrained private path, no symlink traversal, expected content-addressed path, regular file, length, SHA-256 and MIME/signature. **Full PDF or raster decoding and associated bounded-content validation must be added and approved before any actual PDF/image attachment**; current signature-only checks are insufficient.
- **A3 — Acquisition serialization:** Verification cannot be a stand-alone read followed by a race with file publication, deletion, or mutation. All cooperating finalizers, prospective attachers, and any future reclamation path must use one reviewed cross-process acquisition/publication synchronization protocol. Define and test lock ownership, order, process death, timeouts/cancellation and releases before enabling media writes.
- **A4 — No blocking-monitor across suspension:** `ManagedAssetFileStore` currently uses `synchronized` for synchronous publication. Do not hold a JVM monitor across a suspending Room transaction. A future lease/coordinator needs an explicit suspend-safe implementation and interoperable locking with the current registrar. The approved logical lock order is **asset acquisition lease → existing Room transaction**; never invert it. Exact lock implementation and compatibility with the existing file publisher remain subject to C2C-2C concurrency tests, not authorized by this document alone.
- **A5 — Conservative failure:** On an attachment failure, canonical owner, edge and revision roll back together. Finalized originals and potentially metadata may remain unreferenced; this is allowed and must **not** trigger automatic deletion, rewriting, or unreviewed repair.
- **A6 — Shared assets and stale writes:** Multiple distinct owners may refer to one verified asset. An owner has at most one direct typed edge. Conflicting owners, CAS mismatches, revision conflicts, missing assets and corrupt bytes reject rather than overwrite. No reference counters.
- **A7 — Legacy and gate:** Keep `asset_reference_index_state` `UNVERIFIED`/`UNRESOLVED` as conservative diagnostics. Never promote to `VERIFIED`; never interpret a missing edge as safe cleanup. A missing/duplicate/invalid gate is unsafe. For future independently verified new attachments, `UNVERIFIED` is not by itself a blocker if every other precondition passes; `UNRESOLVED`, `UNSAFE`, missing/invalid and unexpected states block attachment. Neither state authorizes cleanup, and C2C-2 performs no owner attachments.
- **A8 — Existing behavior:** C2C-1 rejection guards remain; reordering and Trash/restore retain edges. Media-aware duplication, purge, edge release, export pins, GC, and audited legacy backfill are outside C2C-2.

## 3. Approved logical publication sequence (not yet executable)

| Phase | Preconditions / action | Failure disposition |
|---|---|---|
| 0. Input validation | Validate typed command, owner identity, active note, permitted operation, MIME and decoder constraints; validate canonical envelope and expected prior state. | Reject; no durable owner mutation |
| 1. Original finalization | Stage, hash, signature-check, exclusively publish and synchronize original via `ManagedAssetFileStore`; register metadata via `ManagedAssetRegistrar` as a separate earlier step. | No owner reference. A complete or partial orphan may require *read-only* recovery inventory; no automatic delete |
| 2. Asset acquisition | Obtain reviewed cross-process lease compatible with file publication. Resolve `assetId` to `AssetEntity`; recheck state, path, digest, size, signature and full decode under the agreed synchronization boundary. | Reject. Do not trust a metadata-only success or a partial final file |
| 3. Owner transaction | Within **existing** `RoomDocumentRepository.commit` transaction (or separately approved existing owner), check an explicit caller-supplied expected note revision against the current persisted revision, revalidate owner and expected prior canonical content/edge, and atomically write canonical JSON, exact edge insert/CAS and revision. The existing public edit API is not assumed to expose an expected revision; that API contract requires review before C2C-3. | Any exception, cancellation, FK conflict or stale CAS rolls back the complete DB transaction |
| 4. Acknowledgement | Acknowledge only after successful transaction completion; release lease in `finally` on all paths. | Never report success before commit. No compensating file/asset deletion |
| 5. Reopen/inventory | Test durable reopened content/edge/metadata/file consistency. Keep incomplete gate and diagnostics. | Fail closed on inconsistency, preserve originals |

**Boundary warning:** `ManagedAssetFileStore` guarantees its own synchronous finalization protocol, not a lock/lease held across suspending Room commits. The future implementation must establish and test a shared lock lifecycle, not assume that `stageAndFinalize`'s return value or a second `sha256` read alone closes all time-of-check/time-of-use windows. The project currently has no app-authorized original deletion, which reduces—but does not eliminate—corruption and future reclamation risks.

## 4. Failure and concurrency matrix required before enabling writers

| ID | Scenario | Required outcome |
|---|---|---|
| F01 | Stage failure, bad signature, oversized input | No new metadata/edge/owner; staged failure does not become an acknowledged original |
| F02 | Crash/failure after final file sync but before metadata registration | Unreferenced finalized file may survive; no owner; read-only inventory; no auto-GC |
| F03 | Crash while copying final destination | Incomplete final never accepted by importer/attacher; no overwrite or auto-cleanup |
| F04 | Existing content-addressed path with corrupt bytes, wrong size or symlink | Reject attachment; preserve evidence; do not rewrite the existing path |
| F05 | Concurrent registrars of identical digest | One stable matching metadata identity; no replacement of conflicting metadata |
| F06 | Metadata row `finalized` but bytes absent/mismatched, MIME/path inconsistent | Reject before owner transaction; no canonical media publication |
| F07 | Unsupported or undecodable PDF/image despite matching signature/hash | Reject attachment; current signature test alone insufficient |
| F08 | Two independent owners attach the same verified original | Distinct correctly typed edges share stable asset metadata; no reference counting |
| F09 | Second writer races on same owner/revision/edge | One valid outcome; stale CAS or other conflict rolls back all owner JSON/edge/revision writes |
| F10 | DB error after some owner writes | Full SQLite rollback; finalized original/metadata may remain unowned |
| F11 | Cancellation/process death during lock acquisition or Room transaction | No acknowledged partial attachment; lease released on cancellation/process death; committed state reopens consistently |
| F12 | Invalid/missing gate or unresolved legacy mismatch | Fail closed for affected operations; no index certification, export/GC permit or legacy auto-repair |
| F13 | Trash/restore/reorder following a successful future attachment | Preserve owner IDs and matching edges without duplicating or releasing originals |
| F14 | Future cleanup races with attachment | Must be prevented by separately approved C3/C4 synchronization; cleanup remains disabled in C2C-2 |

## 5. Explicit non-goals

No production PDF/image editor command, import-to-note action, relaxed `CanonicalContentValidator`, Room schema migration, gate promotion, legacy backfill, asset deletion, metadata deletion, orphan cleanup, reference-count-based GC, export pin or media-aware purge is part of this design checkpoint.

## 6. Approved sequencing, with separate implementation gates

1. **C2C-2A — Documentation-only approval:** This document records the joint P1–P7 decision and clarifications. No production code change.
2. **C2C-2B — Verification primitive (separate PR):** Add a read-only, tightly scoped finalized-asset verifier and path/MIME/content checks with tests for corrupt files and metadata; no owner writes and no new editor commands.
3. **C2C-2C — Synchronization primitive (separate PR):** Design and test a suspend-safe cross-process acquisition lease interoperable with the existing synchronous publisher, including cancellation, contention, process death and lock order. No owner writes or cleanup.
4. **C2C-3 — Controlled writer proposal:** Only after review, consider the first narrowly scoped asset-backed owner transaction with canonical JSON + typed edge + revision and targeted end-to-end tests on disposable isolated databases. Keep other media operations disabled until individually approved.

## 7. Jointly approved decisions — 2026-11-05

Both developers accepted the P1–P7 design direction and the three clarifications below. The approval is limited to planning and isolated implementation/testing of C2C-2B and C2C-2C; further review remains mandatory before any production owner attachment.

- **P1 (verification) — APPROVED:** Before a future canonical media reference is published, require finalized metadata, deterministic confined private path, regular non-symlink file, length, SHA-256, MIME/signature and **full bounded media decoding**. Signature-only verification does not suffice. C2C-2B is read-only and creates no owner references.
- **P2 (synchronization) — APPROVED WITH CLARIFICATION:** Prefer one shared cross-process acquisition/publication lock domain initially, unless measured and reviewed tests justify finer granularity. It must interoperate with the existing file publisher, support suspend-safe acquisition/release and coroutine cancellation, and release on process death. Enforce **lease → existing Room transaction** ordering; never acquire the lease while holding a Room transaction, hold a Java monitor across suspension, or call a nested registrar operation under a non-reentrant acquisition lock. Exact OS/coroutine implementation is deferred to C2C-2C and must be proven with contention, crash and cancellation tests.
- **P3 (transaction and revisions) — APPROVED WITH CLARIFICATION:** The first future writer uses the existing `RoomDocumentRepository.commit` transaction owner. A reviewed command contract must explicitly supply the **expected note revision** and, for changes, expected old canonical/edge state; check against persisted state inside that transaction. Treat stale revision, conflicting CAS, missing asset and mismatched canonical references as failures that roll back JSON, edge and revision together. The current public edit API is not assumed to enforce optimistic concurrency; design/test any required API extension before C2C-3.
- **P4 (failures) — APPROVED:** Finalized files or metadata may remain unreferenced after a failed DB publication. Preserve them and use read-only diagnostics; no automatic orphan deletion, overwrite or repair in C2C-2.
- **P5 (gate) — APPROVED WITH EXPLICIT POLICY:** `UNVERIFIED` may permit only a **future independently verified new attachment** once every other precondition, synchronization protocol and a separately approved writer are implemented. It is never a cleanup permit. `UNRESOLVED`, `UNSAFE`, missing, duplicated, malformed or unexpected gate states **reject new attachments**. Do not promote to `VERIFIED` during C2C-2. Existing legacy owners must not be inferred from missing edges.
- **P6 (scope) — APPROVED:** C2C-2 is protocol documentation, a read-only verifier and a synchronization primitive with isolated tests only. No media writer activation, export pins, legacy audit/backfill, gate certification, asset metadata/file deletion, garbage collection or media-aware purge.
- **P7 (tests) — APPROVED:** F01–F14 define the required failure/concurrency acceptance coverage. Implement an appropriate subset for C2C-2B/C, with remaining end-to-end and owner transaction cases separately reviewed under C2C-3.

**Review outcome:** P1–P7 APPROVED with P2, P3 and P5 clarifications recorded above. Approval of this document **does not** approve production PDF/image writes, verified ownership completeness, legacy reconciliation or asset cleanup.
