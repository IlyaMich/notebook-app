# STO-08 I5-B7D — Durable hold lifecycle and recovery decision packet v0.1

**Status:** PROPOSED REVIEW / COORDINATION HANDOFF. No change to production recovery, export publication, schema, release, or GC is approved by this document.

**Local baseline:** `fb147f7c5af71afa25f4552e22d7d3c6578e6feb` on `feature/sto-08-c3a2-i4b-2b-byte-inspector`; clean worktree, I5-B7C read-only audit passed. **Separate sibling worktree owners are not approved merely because their Git metadata was inspected.**

## 1. Evidence carried forward (not new verification)

- Room v5 includes immutable `export_operations` and composite `(operation_id, asset_id)` `export_asset_holds`. Restrictive foreign keys and four SQL triggers prevent operation/hold UPDATE and DELETE. Fresh and migrated database tests passed on API 26 and API 37 in I5-B6.
- I5-B7B-R2 added `ExportAtomicHoldAdmission.persist`, an insert-only DAO and `captureInTransaction` while retaining the public reader's SQL failure behavior. Under one outer acquisition/publication lease, one Room transaction validates canonical note revision, page/element JSON, owner projections and distinct-asset admission before inserting the operation plus complete pin set. All 22 legacy reader and eight hold writer tests passed on both API 26 and API 37 (60 total executions), followed by exact five-file local commit.
- The committed operation state is `COMMITTED_PIN_SET`. `ExportOwnerHoldProtocolModel` defines observational phases including `COPYING`, `UNSEALED_STAGE`, `COPY_BYTES_OBSERVED`, and `NEEDS_REVIEW`, but **Room v5 cannot persist those as updates**. No event table exists at the audited HEAD.
- `ExportLeasedSourceByteInspector` and `BoundedImageBytesInspector` are available separately. Synthetic one-image copy, unsealed stage store and recovery classifier are not proof of protected export durability. I5-B7C scanned 137 production Kotlin files and found no external `ExportAtomicHoldAdmission(` constructor callsite; this is a bounded lexical inventory, not a global absence proof.
- The asset-reference index remains conservatively `UNVERIFIED`/`UNRESOLVED`/`UNSAFE`; neither holds nor inspection authorize `VERIFIED`, deleting managed originals, or GC.

## 2. Recommended decision: keep the retention checkpoint immutable

**Decision candidate D1 (recommended now):** Preserve Room v5 with immutable `COMMITTED_PIN_SET` operations and holds. Do not add mutable phase writes, replace an operation, or weaken trigger protection. Any process interruption, lease expiry, cancellation, source-byte mismatch, unexpected stage shape or restart leaves all committed holds retained. **Persisted phase progress is not a prerequisite for retention safety**, and may be deferred while the protected receipt/recovery contract is undefined.

**Decision candidate D2 (deferred):** If a reviewed protected-export workflow requires durable phase history, prefer an **append-only, bounded, versioned event ledger** with an explicitly reviewed migration over updating an immutable v5 operation row. This is a candidate, not schema authorization. Unknown, duplicate, out-of-order or corrupt events must never authorize stage adoption, completion, release or asset deletion. A ledger is not itself a protected receipt.

**Decision candidate D3 (not recommended):** Dropping or weakening `trg_export_operation_no_update` to permit in-place `state` transitions. This would change the retention-only v5 guarantee and create lifecycle/rollback ambiguity before a receipt contract exists. Do not implement without a separate replacement design and migration review.

No new Room version is reserved by this packet. A future migration must recheck the actual schema and coordinate with sibling worktree owners, rather than assuming v6 is free.

## 3. Conservative recovery contract for the next isolated checkpoint

1. **Read-only first.** A future recovery diagnostic may enumerate operations/holds through a bounded, coherent Room read. It must not update operation states, delete/release any holds, adopt unsealed stages, alter canonical content, or touch managed source bytes. No automatic startup hook without separate authorization.
2. **Do not infer safety from absence.** Empty operation/hold tables, zero holds for an operation, a missing operation, or an incomplete enumeration cannot certify protection or eligibility for reclamation. A media-free operation can legitimately have zero pins, but the v5 operation lacks an independent persisted expected-asset-count/digest; a recovery reader must not infer a protected receipt from zero pins.
3. **Handle ambiguous records conservatively.** Unknown protocol/state/version, missing referenced metadata, SQL failure, pagination limit hit, unexpected duplicated edges, or a partially observed inventory => diagnostic `NEEDS_REVIEW` / `UNSAFE` **in memory only**. No persistent `NEEDS_REVIEW` transition is available in v5. A diagnostic label is not evidence that all historical pins are intact.
4. **Retain under source change.** Note Trash, note purge, later canonical mutation, lease expiration and stage disappearance do not remove an existing operation or its committed hold edges.
5. **Do not substitute for process-death proof.** Same-process database close/reopen demonstrates persistence, not real process death. True kill/restart testing requires a separately designed disposable-test-app harness with deterministic confirmation of halted workers and isolated cleanup boundaries.
6. **Keep the lock order for later integration.** The existing single shared OS acquisition/publication lease precedes Room work. Do not take a second non-reentrant registrar/publisher lease while it is held. Source byte inspection and any destination durability claim need a separately reviewed choreography after pins commit.

## 4. Failure matrix and required evidence

| Interruption or ambiguity | Conservative effect | Additional proof before production integration |
|---|---|---|
| Before Room hold transaction commits | No acknowledged hold set; global asset GC remains disabled | Failed transaction leaves no operation or partial holds |
| After commit, before source byte inspection | Retain committed operation/holds, even after process death | Kill/restart test using isolated DB and explicit worker-stop confirmation |
| Lease contention, timeout, cancellation | Retain every previously committed hold; no release | Cross-process lease and cancellation regression; no lock inversion |
| Byte not found, length/hash/media mismatch | Retain holds; reject protected copy progression | Inspect original bytes under correctly held lease, verify bounded hashes and decoder results |
| Synthetic stage absent/partial/both files visible | Retain holds; do not infer durability or adopt stage | I4-B-owned durable stage/manifest/provenance/recovery design |
| Copy observation or in-memory model phase | Retain holds; no protected receipt | File force + directory durability + packaging/receipt contract, separately reviewed |
| Unknown protocol/event data or DB read error | Fail closed and signal diagnostic review | Read-only bounded inventory classification tests; preserve global unsafe gates |
| Media-free operation with zero holds | Keep operation; do not treat as completed export | Positive source validation and separate no-media receipt protocol |
| Two exports share one asset | Retain both independent operation holds | Multi-operation/reopen/process-death regression |
| Source moved to Trash or purged | Retain export hold irrespective of note availability | Deletion/Trash durability and cross-owner lifecycle tests |

## 5. Coordination questions for I4-B and STO-08 owners

**Do not treat the following as answered by the audit.** Record owner acknowledgment, reviewed source/commit identifiers, and date before authorizing any event migration or protected copy callsite.

1. Is persistent per-attempt phase history required before a verified destination receipt exists? If yes, which component is the authoritative event producer and which consumes recovery diagnostics?
2. Will I4-B own future stage/manifest/provenance, retry operation UUID mapping, and recovery policy? Confirm no concurrent migration or duplicate recovery hook.
3. Where is the exact API boundary that keeps the single OS lease held while the committed hold set is handed to source-byte inspection and later staging? Specify lock acquisition/release and allowed cancellation points.
4. What qualifies as **durably protected**: file-level fsync, directory fsync, manifest sealing, archive consistency, byte provenance, atomic publication, receipt storage, and crash recovery?
5. Which disposable test harness can prove process death **after** hold commit but **before** first byte copy, and after partial stage writes, on API 26 and 37 without touching production DB/AVDs?
6. If a later migration adds events, what bounded indexes, FK constraints, immutable event semantics, version handling and migration tests are required? No status is allowed to confer release/GC authority.

## 6. Proposed execution order (all gates separately approved)

- **I5-B7D-1:** Approve this decision packet and coordination scope. Documentation-only; not a migration approval.
- **I5-B7D-2:** If agreed, implement a **read-only, bounded recovery inventory diagnostic** on the existing Room v5 schema, using isolated test-app databases. It must report uncertainty and never set a persistent phase, release a hold, or claim a receipt. Compile first; API 26/API 37 runtime tests separately authorized.
- **I5-B7D-3:** Add isolated crash-window tests, including actual process-death only after the harness is reviewed. Record no false claim of process death from simple database reopening.
- **I5-B7E / separate I4-B integration approval:** Define and test the real original-byte inspection and durable stage/receipt contract with the owning collaborators before any production export wiring.
- **Later and independent:** Review event persistence if required, and only after a proven protected receipt/recovery protocol review any hold-release/GC design. Global `VERIFIED` promotion remains explicitly out of scope.

**Non-authorizations:** No schema change, event/phase writer, production export callsite, startup recovery action, unsealed stage adoption, receipt, pin release, `VERIFIED` transition, physical asset deletion, GC, push, merge, or sibling worktree edit.
