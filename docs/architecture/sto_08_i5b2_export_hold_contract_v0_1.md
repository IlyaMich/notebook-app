# STO-08 I5-B2 — Proposed durable export-owner hold contract v0.1

**Status:** design candidate only; no persisted export-owner schema or production writer is implemented by this checkpoint. **Authority:** none for `VERIFIED`, export receipts, backfill, reclamation, or physical asset deletion.

## 1. Problem and sources

STO-08 requires an asset to remain protected while referenced by an active note, Trash, **or an in-progress export**. I5-B1 audited the clean local HEAD `6c5ef351bf4a7691d33e922b28298eb00d8f5fea` and found source snapshot capture, lease-scoped admission, bounded source-byte inspection, unsealed synthetic staging, and a read-only stage-recovery classifier. None of those is a durable export-owner record. Existing ownership diagnostics and the `UNVERIFIED` index remain nonauthoritative for cleanup.

This contract is a proposed design to review against concurrent I4-B export work. It must not be treated as an implemented guarantee.

## 2. Retention invariants

1. **Committed pins predate reliance on source bytes.** Before the first future protected copy or any hypothetical reclamation, a DB transaction must atomically validate the source revision/canonical owner edges and persist the operation plus the complete set of distinct source-asset pins. If the transaction aborts, no pin has been created and no copying may be acknowledged. No file cleanup is enabled in this state.
2. **A pin survives the process and any lease.** OS publication/acquisition leases coordinate cooperating operations but are not the durable record. Lease release, process death, cancellation, timeout, and application restart cannot imply pin release.
3. **Source protection cannot depend on stage appearance.** An unsealed synthetic stage, two visible payload files, an independently reread byte sequence, or an in-memory `SnapshotResult.Complete` is not a protected export receipt. Staging cleanup is not part of this contract.
4. **Uncertainty retains.** Missing/partial operation metadata, missing pin edges, failure during read, schema ambiguity, recovery uncertainty, unexpected files, or mismatched canonical projection requires fail-closed retention. No TTL-based removal or best-effort orphan deletion.
5. **Shared sources retain per-operation holds.** The same asset may be referenced by multiple owners and multiple exports. A proposed `export_asset_holds` row is unique per `(operation_id, asset_id)`; source owner occurrences remain separately verifiable. Finishing one export never releases another export's hold.
6. **Index and GC remain locked down.** Neither a recorded export hold nor a successful snapshot promotes `asset_reference_index_state` to `VERIFIED`. No new asset delete or GC code is authorized.

## 3. Proposed persistent shape — NOT implemented

Future Room migration, reviewed separately:

- `export_operations`: canonical UUID `operation_id` primary key; `note_id`, captured `note_revision`, state, creation/update timestamps, and schema/protocol version. States are conservative. No string state accepted without a migration/transition audit.
- `export_asset_holds`: `(operation_id, asset_id)` composite key; foreign keys to operation and registered asset with **RESTRICT** deletion behavior; indexes for `asset_id` and operation enumeration; insert of operation and every edge in the **same Room transaction**. An asset's live hold is the existence of its committed edge, not a transient state name.
- The ordered canonical image owner occurrences and validated content/metadata identities are an **independent proof obligation**. A distinct-asset hold set cannot be inferred from one projection alone. PDF and unsupported media must be rejected until their capture/lease pathway is reviewed.
- No new table, migration, DAO method, startup hook, or production callsite is added in I5-B2.

## 4. Proposed lock and transaction choreography — NOT wired

1. Caller obtains the existing shared asset publication/acquisition lease **before** entering Room, preserving the present lock order. No non-reentrant registrar/publisher call inside the lease.
2. Inside one Room transaction, revalidate an eligible canonical source and exact ownership projections, verify the bounded asset set, then atomically insert the operation and all pin edges. Any ambiguous source fails the transaction with no success acknowledgment. A later phase is never allowed to invent a missing pin.
3. After commit, while authorized lease coordination remains, verify source bytes and independently verify destination copy/durability. A detached source observation is not a durable receipt.
4. On any error, cancellation, process death, interrupted file copy, or untrusted recovery observation, keep all committed pin edges. A separate audited recovery protocol must resolve operations; never infer release from elapsed time or temporary-file shape.
5. Future reclamation (not authorized) would require its own lock/transaction sequencing, global canonical/Trash consistency proof, and an atomic check that **no committed export hold exists** before any asset-byte deletion. A DB read followed by uncoordinated deletion is explicitly insufficient.

## 5. Failure / recovery matrix

| Point of interruption | Required conservative effect | Claim explicitly prohibited |
|---|---|---|
| Before pin transaction commits | No acknowledged export; global GC still disabled | No-pin means safe to delete |
| After pin transaction, before copying | Retain committed holds | Lease expiry releases holds |
| Copy started, cancelled, or I/O fails | Retain holds; review needed | Partial copy protects originals |
| Unsealed stage has one or both files | Retain holds; untrusted recovery shape | File presence proves receipt |
| Source bytes independently observed | Retain holds until separately approved release | Source inspection alone protects export |
| Process killed or app restarted | Retain persisted holds; ambiguous recovery -> review | Process end automatically unpins |
| Missing/corrupt pin rows or invalid migration | Fail closed globally; never reclaim | Empty table proves no exports |
| A second operation shares an asset | Each operation holds its own edge | Completing one releases the other |

## 6. I5-B2 model scope and future gates

`ExportOwnerHoldProtocolModel` is a **pure hypothetical protocol model**. Its entry phase, `COMMITTED_PIN_SET`, describes a prerequisite for future implementation; invoking the model does **not** persist anything. Its transitions only classify observations (`COPYING`, `UNSEALED_STAGE`, `COPY_BYTES_OBSERVED`, `NEEDS_REVIEW`) and retain the requirement to hold. It has no API for success receipts, unpinning, deletion, or GC eligibility. Even `COPY_BYTES_OBSERVED` does not prove fsync, crash durability, or a protected export.

Before any production wiring: agree on schema/migration and constraints with I4-B; add isolated Room transaction/crash-window tests; audit cross-process lock order and lifecycle; independently verify protected destination durability; define an explicit conservative restart recovery protocol; only then separately review pin release and GC eligibility. The `VERIFIED` gate remains prohibited throughout this checkpoint.

**Checkpoint success:** the contract and pure-model JVM tests compile and pass. This does *not* satisfy the STO-08 acceptance criterion or close the issue.
