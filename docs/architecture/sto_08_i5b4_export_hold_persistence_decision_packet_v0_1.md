# STO-08 I5-B4 — Export-hold persistence decision packet v0.1

**Status:** PROPOSED COORDINATION HANDOFF — not an approved migration, operational hold, export receipt, or release protocol. **Prepared from:** I5-B2 contract and I5-B3 read-only audit. **STO-08 local baseline:** `7b22caa5c5ea078c05582b297853d05e84ac5be0` on `feature/sto-08-c3a2-i4b-2b-byte-inspector`.

**Safety boundary:** No production Room schema change, DAO writer, GC, managed-original deletion, `VERIFIED` index transition, durable export receipt, stage re-adoption, or hold-release authority follows from this document. The project-wide deletion path remains disabled.

## 1. Verified integration facts (I5-B3)

- `ExportSourceSnapshotReader.capture` returns a Room-transactional detached observation. It does **not** commit an export operation or pin its source assets.
- `ExportLeaseScopedAdmission` holds one acquisition/publication lease across capture, validation and caller callback; its callback result is not a protected export receipt.
- `ExportSyntheticSingleImageCopyCoordinator`, unsealed stage files and the unsealed recovery classifier remain synthetic/untrusted, with no production sealing, recovery adoption, or durable export-owner certification.
- AppDatabase schema is **v4**, with canonical page/element ownership projections and the conservative asset-reference index gate. The I5-B3 inventory identified no implemented `export_operations` or `export_asset_holds` tables in the inspected checkouts. **A lexical/worktree scan does not prove absence from remotes or uninspected branches.**
- I5-B2's `ExportOwnerHoldProtocolModel` describes a retention-only proof obligation; passing its 13 JVM tests is not persisted-hold evidence.

I5-B3 saw these *contextual* sibling worktrees (not integration approvals): `C:/Proj/notebook-app-sto08-i4a` at `522441fe8d800584aa9a09d118c084b2a0584e27`, `C:/Proj/notebook-app-sto08-i4b` at `b36ff27b83326c843508043812cea58f62c8ca5d`, and `C:/Proj/notebook-app` at `583362932523f31a08fcfe7f2c1a9ceb79bb1cc9`. Their working state can change independently; do not edit them from I5-B4.

## 2. Decisions required *before* authorizing a migration

| Topic | Recommended candidate | Required decision or evidence |
|---|---|---|
| Ownership of schema/migration | One nominated owner coordinating STO-08 with I4-B | Named migration owner and acknowledgement that no parallel migration is being developed |
| Schema version | Reserve **v5 only if still free** when integrated | Re-read `AppDatabase`, migrations, exported schemas and concurrent branch changes immediately before implementation; never overwrite another migration |
| Operation identity | Fresh canonical UUID per export attempt, stable after commit; no ID reuse | Confirm I4-B operation/stage ID contract and retry semantics |
| Operation identity linkage | Persist source `note_id` and captured `note_revision` as immutable audit identity; **do not automatically FK to notes** | Decide how Trash/purge coexist with retained export holds and process-death recovery |
| Asset hold key | Composite `(operation_id, asset_id)` key, indexes supporting asset and operation lookup | Approve entity shape and per-operation duplicate policy |
| Referential integrity | Holds use `RESTRICT` FKs to the operation and registered asset | Review behavior under note removal, asset metadata repair and migration |
| Phase representation | Versioned conservative state; unknown state => unsafe, not release | Agree mapping to I5-B2 model (`COMMITTED_PIN_SET`, `COPYING`, `UNSEALED_STAGE`, `COPY_BYTES_OBSERVED`, `NEEDS_REVIEW`) |
| Zero-image exports | Persist an operation with **zero** asset holds if legitimately media-free | Ensure empty set is positively validated; never infer from a failed or partial enumeration |
| Recovery ownership | Read-only startup classification first; committed holds retained under ambiguity | Assign recovery owner and process-death integration testing |
| Release | **No release in first persistence checkpoint** | Requires a separately reviewed receipt, provenance, recovery and GC protocol |

**Do not infer approval from a passing read-only audit.** Record the named I4-B reviewer, schema/migration owner, reviewed commit IDs and date in a follow-up decision before the first implementation script is run.

## 3. Candidate relational design — documentation only

`export_operations` (proposed): `operation_id` canonical UUID primary key, `note_id`, `captured_note_revision`, protocol/schema version, conservative state, `created_at` and `updated_at` timestamps. The operation record itself **does not imply** that all source references have been captured or protected. A coherent, version-checked state contract and migration strategy must be reviewed before storage.

`export_asset_holds` (proposed): composite primary key `(operation_id, asset_id)`, foreign key to `export_operations` with `ON DELETE RESTRICT`, foreign key to `assets` with `ON DELETE RESTRICT`, and an `asset_id` index. This is the full *distinct asset ID* pin set for a validated source. It does not replace the ordered individual canonical image-owner occurrences needed to verify the capture.

**Critical atomicity rule:** The operation and every required hold edge are inserted inside **one Room transaction** that also revalidates the source note revision, canonical JSON, projected ownership and admissible asset set. No separate `capture()` followed by an unrelated transaction may be represented as the same atomic snapshot. A failed transaction cannot acknowledge an export or begin any protected copy. Media unsupported by the reviewed snapshot pathway (including PDFs) must be rejected, not implicitly pinned or omitted.

The durable safety invariant, once implemented and separately verified, is that a committed operation's pins remain present through cancellation, lease expiry, process death, incomplete staging, source-byte read failure and restart. A benign-looking unsealed directory or an observed byte copy cannot substitute for that record. **No deletion eligibility follows from a missing pin table while the index is `UNVERIFIED`.**

## 4. Proposed integration sequence — not wired

1. Obtain the existing shared acquisition/publication OS lease **before** any Room transaction. Do not call a non-reentrant registrar/publisher under this lease.
2. In one bounded Room transaction, verify an eligible source, canonical and projection consistency, captured revision, owner occurrences, distinct assets and metadata; insert one operation and its full hold set; commit together.
3. Only after the transaction commits may a future protected-copy workflow continue under a reviewed lease and source-byte verification contract. A returned snapshot or callback completion alone must not mark receipt or completion.
4. Cancellation, I/O failure, ambiguous metadata, unexpectedly absent pins or process death conservatively retain any committed holds. `NEEDS_REVIEW` is absorbing in the I5-B2 model; implement no retry/adoption/release from stage appearance.
5. Do not add release, cleanup, unpin, TTL, orphan pruning or asset GC paths in the first persistence iteration.

## 5. First persistence checkpoint: acceptance-test matrix (after approval)

| Test boundary | Required result |
|---|---|
| Fresh database and **v4-to-next-version** migration | Exact schema constraints/indexes; no data loss; conservative existing gate intact |
| Atomic operation + 2 distinct asset holds | All three rows visible after commit/reopen, never partial |
| Inject failure between operation and hold inserts | Entire transaction rolls back; no acknowledged copy |
| Shared asset in two operations | Two independent hold edges survive reopening; one operation cannot release the other's pin |
| Multiple owners of the same asset | One distinct hold per operation, but all canonical owner occurrences validated |
| Stale note revision or changed projection | Transaction rejects; no operation/pin accepted |
| Missing metadata/invalid content or unsupported PDF | Fail closed, no partial write or protected-copy acknowledgment |
| Empty eligible media-free note | Deliberate zero-hold operation, not a scan-failure fallback |
| Cancellation before/during transaction | Cancellation propagates; atomic rollback or complete committed set only |
| Process death after commit, before byte copy | Pin set survives app/database reopen; no automatic release |
| Unknown state or malformed operation/hold data | Unsafe/review; never interpreted as unowned asset |
| API 26 and API 37 isolated databases | Targeted Room migrations, FK behavior, transaction rollback, restart and cancellation tests pass |

No device/production implementation is authorized by this test matrix. If the migration owner changes the candidate design, first update and re-review the contract and expected tests.

## 6. Handoff request to I4-B owner

Please confirm: **(1)** which branch/person owns the next Room schema version and migration; **(2)** the canonical export operation ID and retry semantics; **(3)** whether an export pin is retained independently of note/Trash deletion; **(4)** the single transaction that must capture and persist the complete hold set; **(5)** lock ordering with the existing OS publication lease; and **(6)** the process-death/restart test boundary. Please identify any concurrent schema or export-recovery work not present in the inspected I5-B3 inventory.

**Decision gate:** Do not implement Room tables, DAO writers or application wiring until these points are reviewed against current I4-B code and an exact fresh Git baseline. A subsequent isolated persistence checkpoint may begin with schema/DAO transaction tests, but **no pin release, protected receipt, `VERIFIED` promotion or asset reclamation**.
