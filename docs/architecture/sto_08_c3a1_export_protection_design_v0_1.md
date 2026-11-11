# STO-08 — C3-A1 Export Protection and Ownership-Completeness Design V0.1

**Status:** JOINTLY APPROVED DESIGN DIRECTION — D1 and D3–D7 accepted on 2026-11-11; D2 quotas remain provisional pending profiling. **Planning approval only; no implementation or activation authorized.**
**Date:** 2026-11-11
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31) (reopened)
**Baseline:** `main` at `c58d83476f05fb1b78f5384abbe671275c92021c`; C2C-3C writer squash-merged in PR #128
**Purpose:** Record the jointly approved design direction for a future export source-snapshot protection boundary. This approval does not authorize implementation, importer/exporter enablement, reclamation, file deletion, gate promotion, media-aware purge, schema migration, or UI changes.

## 1. Inspected baseline and source-of-truth

- `AssetAcquisitionLeaseCoordinator.withLease` takes a suspend-safe exclusive OS lock at `filesDir/.asset-publication.lock`, shared with `ManagedAssetFileStore`. It times out *only acquisition*, supports cancellation, releases in `finally`, and enforces **lease → Room transaction** ordering. It coordinates cooperating clients only.
- `ManagedAssetFileStore.stageAndFinalize` publishes immutable content-addressed files under the same OS lock. It can leave an unregistered/partial original after process death. The registrar must not be invoked from within the non-reentrant lease body.
- `RoomAssetOwnerInventory.scan()` scans active and trashed canonical page/element content in a bounded Room transaction; returns `Completed` or `Incomplete`. `Completed` is **not** a GC permit. Its canonical inventory is diagnostic and currently does not certify exact equality with every typed reference edge.
- Room v4 has `page_asset_references`, `element_asset_references`, and an `asset_reference_index_state` gate. `AssetReferenceIndexGuards` prevents `VERIFIED` promotion, and `AssetOwnershipDao.readFailClosedGateStatus` yields only conservative statuses for application consumption.
- `RoomPermanentPurge` rejects indexed media (and unsupported canonical media), and does not delete asset files or asset metadata. `ManagedAssetReconciler` is read-only and is not a reclamation authority.
- No production coordinated media export, durable export pin, asset reclaimer, or end-to-end export/GC concurrency protocol was identified in the inspected Android main source tree. This negative finding is bounded to the inspected tree; it is not a full audit of every future, external, or Apple path.
- Upstream contracts: `docs/architecture/sto_08_asset_reference_lifecycle_contract_v0_1.md`, particularly AR-05, AR-06, AR-07, AR-09, §5, §7–§9; `docs/architecture/sto_08_c2c2_verified_attachment_publication_protocol_v0_1.md`, particularly P2 and lock ordering.

## 2. Required safety properties

**E01 — Consistent document snapshot.** An export captures a single coherent set of note/page/element contents, revisions, and asset identifiers. Never join a file copy against live mutable content without a common protected snapshot.

**E02 — Protection before release.** From before snapshot asset discovery until all required originals have been copied and independently verified to private export-owned locations, a cooperating cleanup operation cannot unlink or retire those originals. Merely opening an FD, observing a zero reference count, or holding a Room read transaction alone does not suffice.

**E03 — Shared synchronization domain.** Every future asset removal candidate, exporter, finalizer, and verified attachment path must follow the reviewed shared lock discipline. Future reclamation must acquire the same exclusive acquisition/publication lease **before** any final eligibility and must hold it through the file-removal boundary. The current system has no reclamation implementation; this is a future condition, not an assertion that deletions are currently possible.

**E04 — No Room→lease inversion.** Obtain the lease before a Room transaction. Never wait for the lease within a Room transaction or call `ManagedAssetRegistrar.register`/`stageAndFinalize` while holding this non-reentrant lease.

**E05 — Independent verified snapshot.** Copy to fresh export-private staging files, verify byte count, SHA-256, MIME/path constraints and expected identity of each source, and durably complete the independent copy before releasing protection. Snapshot files must not be placed into the managed originals tree or interpreted as canonical ownership.

**E06 — Bounded resources.** Explicit limits for asset count, total bytes, time, staging capacity and memory are mandatory. On limits, cancellation or low disk space: fail the export safely, keep originals and owner metadata untouched, clean only demonstrably export-owned temporary files under a separately audited cleanup policy.

**E07 — Process death.** OS locks are released on process death. Any incomplete independent export snapshot remains untrusted; a restarted exporter cannot claim a completed export without verifying every snapshot member. An abandoned export staging file is never grounds for deleting a managed original.

**E08 — No false completion.** Publish the final export archive only after validating the entire independent snapshot/package. A partial archive, interrupted copy, or incomplete Room scan must not be advertised as a valid exported note.

**E09 — Diagnostic gate.** Keep `UNVERIFIED`/`UNRESOLVED` fail-closed for cleanup; never promote to `VERIFIED` as a side effect of export. A successful export copy does not establish global owner-index completeness.

**E10 — Platform boundary.** This is an Android proposal. Apple STO-03 and cross-platform `.notebook` packaging/restore semantics need separate review. The current design cannot certify the joint STO-08 issue by itself.

## 3. Options and initial recommendation

| Dimension | A. Durable export pins | B. Coordinated independent copy under shared lease |
|---|---|---|
| Protection | Persist pin rows and coordinate deletion against them | Hold existing cross-process exclusive lease from snapshot capture to independently verified copy |
| Schema | Requires durable pin/lease schema and recovery lifecycle | No new pin table for the first bounded prototype |
| Process death | Pins survive; expiry/reconciliation must be proven safe | OS lease releases; incomplete private export copy is discarded or safely resumed only after full verification |
| Long exports | Potentially less lock contention | Long copies hold global lease, blocking import/attach/future GC |
| Concurrency complexity | Pin commit/release vs GC eligibility, stale pins, multi-process recovery | One lock domain, fixed lock order, independent copy proof |
| Risk | Stale pins, erroneous expiry, mismatch with document snapshot | Excessive lock duration, cancellation/low-storage behavior, export snapshot completeness |

**Jointly approved D1 design direction:** Prefer Option B for a **bounded, internal, UI-unreachable proof-of-concept** using the existing cross-process lease; this is **not permission to implement or activate it** without a separate scoped review. Benchmark lock duration and prove race/failure behavior before implementation. If realistic exports require long global leases, revisit durable pins or a hybrid design. Do **not** weaken copy verification, lock ordering or fail-closed behavior to improve throughput.

## 4. Jointly approved logical design direction — not authorized implementation

1. **Preflight, no mutation:** Validate the export request and target note identity, private staging root, media types, configured quotas, and cancellation. Resolve no arbitrary filesystem path supplied by document JSON or external input.
2. **Acquire shared exclusive lease:** Use `AssetAcquisitionLeaseCoordinator.withLease` before opening the Room transaction. Failure/timeout/cancellation means **no export snapshot**. No nested asset registrar call.
3. **Read one consistent Room snapshot:** Under `AppDatabase.withTransaction` (read-only operations), capture all required canonical note/page/element structures and revisions, active/Trash semantics for the requested export, and asset references. Compare each recognized canonical media owner against the matching Room v4 typed owner edge. Reject mismatches, unknown JSON, missing IDs, unbounded traversal, or incomplete results. Do not alter gate or owner rows. Copy no originals under this Room transaction.
4. **Copy while lease remains held:** After closing the Room snapshot, resolve each referenced registered finalized asset to the expected confined no-follow original. Copy its bytes into a newly created app-private **export-owned** staging location; validate source and destination identity/digest/size and appropriate media decoding/structure policy. Never mutate originals or metadata. Keep a private immutable manifest binding the *captured* document snapshot to its independent copied assets.
5. **Verify independent snapshot:** Ensure all expected assets are copied and re-readable; verify manifest, checksums, byte lengths, uniqueness and quotas. Synchronize data/manifest and required directory entries before considering copy protection complete. If this is not possible, abort safely.
6. **Release shared lease:** Only after independent copies are verified and persisted, or on failure/cancellation through `finally`. After successful release, an exporter may package solely from its independent snapshot; it must not return to live original paths.
7. **Package outside the lease:** Produce the portable archive from captured canonical data and private verified snapshot, revalidate package integrity, then publish to destination via a separately reviewed atomic handoff. The export operation must never imply permission to delete managed originals or change the ownership gate.

**Important consistency condition:** After the Room snapshot closes, new note edits or Trash/purge operations may change live document state. That does not change the *already captured* logical export. The shared lease must still prevent any future deletion from invalidating originals until independent copies are verified. The lock is not a substitute for correctly capturing a single Room snapshot and exact referenced asset set.

**Important implementation caveat:** The current `RoomAssetOwnerInventory` is a whole-database bounded diagnostic, not a ready-made single-note export snapshot reader and not an exact canonical↔edge certification tool. Reuse its fail-closed principles, not its `Completed` flag as a shortcut. Review the DB reader API and reconciliation logic separately.

## 5. Required failure/race acceptance matrix

| ID | Scenario | Required result |
|---|---|---|
| X01 | Media-free note export | One consistent snapshot; no original reads; valid independent archive or typed unsupported result |
| X02 | PNG/JPEG note with one owner | Correct captured element/edge/revision; independent verified copy; original untouched |
| X03 | Two owners share one original, one in Trash | No owner loss; deduplicated or separate independent copies remain valid; no original reclamation |
| X04 | Export snapshot races with an ordinary document edit | Exactly one coherent revision/content snapshot; no mixed live state |
| X05 | Export snapshot races with a Trash or restored note | Consistent captured state; no metadata or owner changes caused by export |
| X06 | Future GC requests lock during source snapshot/copy | GC cannot inspect final eligibility or unlink before verified independent copy and lease release |
| X07 | Import/finalizer contends with export lease | No acceptance of half-published files; no deadlock or inverse lock acquisition |
| X08 | Cancellation while waiting for lease | No Room snapshot or copy; no leaked lock |
| X09 | Cancellation after copying first of several originals | No claimed final export; lock released; originals unchanged; owned temps treated conservatively |
| X10 | Actual process death after Room snapshot and during copy | New process sees no completed export; live originals intact; lock reacquirable |
| X11 | Disk full, permissions error, partial copy or fsync failure | No successful export; no original/metadata deletion; deterministic diagnostics |
| X12 | Missing metadata, digest mismatch, symlink, corrupt original or unsupported MIME | Fail closed before final package; no source mutation |
| X13 | Canonical JSON↔typed edge mismatch or unknown/legacy media | Reject/incomplete; never infer no references from empty projection |
| X14 | Limit exceeded (count, byte budget, time, scan bounds) | Typed incomplete/failure; no package published or source deletion |
| X15 | Verified independent copy followed by last-owner purge (future approved implementation) | Export remains valid from independent snapshot, regardless of subsequent source eligibility |
| X16 | Export restart observes abandoned or tampered staging | Never treat it as verified; no original cleanup; safe isolation/quarantine policy |
| X17 | Multi-process exporter, publisher and future reclaimer | Verified lock serialization and crash release on API 26 and API 37 disposable devices |
| X18 | No references reported by bounded inventory while gate is UNVERIFIED | No deletion authorization or gate promotion |

X06, X15 and X17 need a **safe simulated GC contender** first; actual deletion must stay disabled until C4 separately approves isolated destructive testing.

## 6. Joint decision record — 2026-11-11

**Joint approval:** Both developers accept **D1 and D3–D7 as design directions**. **D2 export quotas are explicitly provisional pending profiling.** These decisions authorize further design planning only. They do **not** authorize Kotlin implementation, a PR claiming operational export protection, production UI activation, any cleanup/reclaimer, asset metadata/file deletion, ownership-gate promotion, or schema migration.

- **D1 — APPROVED DIRECTION (mechanism):** Prefer bounded coordinated copy under the existing exclusive acquisition/publication lease for an isolated first prototype. Keep durable pins/hybrid as alternatives if contention or quota evidence makes a global copy lease impractical. Separate review and authorization precede implementation.
- **D2 — PROVISIONAL (scope and numeric quotas):** Candidate first scope is **one note**, up to **16 distinct source assets** and **64 MiB aggregate encoded bytes**. These are **new, unapproved export quotas**, not constraints already enforced by the app. Per-image decoder policy currently caps encoded PNG/JPEG to **16 MiB**; that existing decoder limit does not automatically define PDF or general export limits. Time budget, minimum available disk space, temporary storage budget, overall memory, per-format handling, and large-export user-visible failure behavior require profiling and a separate decision. Never release the lease early, publish a partial archive, or treat exceeding a quota as permission to delete an original. **D2 remains open.**
- **D3 — APPROVED DIRECTION (snapshot semantics):** Capture one consistent Room snapshot of canonical content, revision and the exact referenced-asset set; cross-check typed owner edges in the same transaction. Unknown/legacy, malformed, absent or mismatched media/metadata are fail-closed. The exact read API and error contract require separate implementation review; diagnostic inventory completion is not enough.
- **D4 — APPROVED DIRECTION (crash and staging):** Use isolated export-owned UUID staging directories and confined, no-follow file operations; verify and durably persist independent copies before treating a snapshot as protected. Never trust incomplete or abandoned staging after process death. Concrete safe temp-cleanup rules and failure recovery require separate review; never delete originals.
- **D5 — APPROVED DIRECTION (shared lock):** Export source snapshot/copy and any **future** reclaimer must coordinate in the same exclusive cross-process lock domain. Lease comes before Room; a future reclaimer must acquire the lock before its final eligibility decision and hold it through unlink. No reclaimer exists or is authorized; multi-process, contention, cancellation and process-death tests remain mandatory.
- **D6 — APPROVED DIRECTION (packaging):** Package solely from the captured canonical snapshot and independently verified, durable export-owned asset copies **after** releasing the lease. Full portable-format validation, destination handoff and low-storage failure policies require separate review. Export must not reread mutable managed original paths after protection is released.
- **D7 — APPROVED DIRECTION (completeness):** Keep global ownership state `UNVERIFIED`/conservative `UNRESOLVED`; do not certify `VERIFIED` from export success, inventory scans or edge counts. A separate canonical↔edge legacy audit and backfill/certification process is required before any cleanup eligibility decision.

**Review evidence still outstanding:** benchmark realistic lease hold times and source-copy capacity (D2), specify and review exact Room export snapshot API, independently verified copy/durability and staging policy, simulated future-reclaimer race tests, and API 26/API 37 multi-process recovery. Approval of design direction is **not** evidence that these tests have passed.

## 7. Proposed gates and deliverables

**C3-A1 (current):** D1 and D3–D7 jointly approved on 2026-11-11; D2 quotas provisional. Documentation-only review of X01–X18; no Kotlin, Room, CI, original files or UI changes. Approval authorizes **planning only**, not implementation or production export activation.

**C3-A2 (future, separate PR and separate explicit approval):** Minimal, read-only export-source-snapshot and copy-protection prototype in isolated app-private sandboxes, unreachable from production UI. Resolve D2 quotas and exact API/staging contracts in review before implementation. Test lease ordering, cancellation and independently verified copies; no GC methods or gate writes.

**C3-B (future):** Implement selected export/packaging integration with deterministic API26/API37 concurrency and actual process-death tests; complete source audits. Keep GC disabled.

**C3-C (future):** Separately design canonical↔edge reconciliation and asset-aware purge (database ownership transitions only), including independent Trash survivors and historical ambiguous data. No file deletion.

**C4 (future):** Only after acceptance of C3-B/C3-C and explicit cleanup-policy decision: read-only dry-run eligibility review, then separately approved isolated destructive reclaimer tests; no production GC by implication.

## 8. Non-goals / reviewer checklist

- [ ] No `VERIFIED` promotion, ownership index repair/backfill, or interpretation of `UNVERIFIED` as GC readiness.
- [ ] No file or asset-row deletion, candidate marking, metadata retirement or Trash-purge loosening.
- [ ] No production UI activation, import/export action wiring or media editing beyond the merged insert-only writer.
- [ ] Cross-process lease use, lock ordering, cancellation, interruption and resource bounds adequately specified.
- [ ] Snapshot consistent with canonical source and exact typed edge projection; ambiguous records fail closed.
- [ ] Completed private copy is independent of managed original paths and verifies integrity/durability.
- [ ] Realistic lock-duration and disk-capacity risks compared against durable-pin alternative.
- [x] Joint design-direction approval for D1 and D3–D7 recorded; no implementation authorization implied.
- [ ] D2 quotas, exact APIs, staging cleanup rules and race/failure verification reviewed before implementation starts.

**Decision status:** D1 and D3–D7 **jointly approved as design directions** on 2026-11-11; **D2 remains PROVISIONAL**, pending profiling and separate acceptance. No Kotlin implementation, export UI/packaging activation, GC, gate promotion, schema change, or commit is implied by this document.
