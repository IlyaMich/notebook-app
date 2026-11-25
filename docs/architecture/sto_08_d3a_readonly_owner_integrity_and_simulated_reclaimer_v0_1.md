# STO-08 D3-A — Read-only owner, metadata, byte-integrity and simulated-reclaimer gate

**Checkpoint:** D3-A v0.1 (nonreleasing)  
**Pinned source baseline:** `main` `ca9423fcd0560c27a9403f87fb103f2077f6d6aa` after merged D2I (#164)  
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31)  
**Scope:** Android-only integrity *observations*, no destructive source code, no Room schema migration, no product UI.

## Summary

`Sto08D3aReadOnlyOwnershipPreflight` combines the existing bounded canonical note/page/element/Trash inventory, exact observed typed page/element projection comparator, a separately bounded complete asset metadata scan, and two independent finalized-original SHA-256/signature/size/path checks.

The preflight obtains the existing cross-process `.asset-publication.lock` acquisition lease **before** its first Room transaction. It checks canonical owners and typed edges *inside* the Room transaction; then checks original files outside the transaction but under the same lease; then repeats metadata/owner captures and original-byte integrity checks. All original bytes remain read-only. The implementation never unlinks, updates/retire an asset row, writes an owner edge, acquires an export hold, releases a hold, changes the ownership gate, or enables GC.

### Results: diagnostics only

| Result | Meaning | Prohibited interpretation |
|---|---|---|
| `ObservedConsistentUntrusted(activeOwners,trashOwners,registeredAssets,metadataWithoutRecognizedOwner,ownershipGate)` | Bounded observations and source bytes matched at the inspected instants; the gate remains `UNVERIFIED` | **NOT** a complete-reference certificate, proof of no owners, eligible-to-delete candidate, durable export receipt, or permission for removal |
| `Mismatch(CANONICAL_OR_PROJECTION_MISMATCH / UNSAFE_ASSET_METADATA)` | Observed owner/reference/metadata disagreement | Never repair by deleting a file or orphaning content |
| `Incomplete(...)` | Scan, gate, resource budget, source file or concurrent change prevents positive observation | Retain everything; no estimated owner count or inferred eligibility |

An *observed* metadata row without a recognized owner remains **unclassified**. The preflight deliberately permits `ObservedConsistentUntrusted` for zero owner edges or unowned metadata; it never returns `VERIFIED`, `mayDelete`, `eligible`, or a candidate path. The current `AssetReferenceIndexGuards` still blocks `VERIFIED` promotion.

### Resource and synchronization bounds

- Scan uses existing whole-DB bounds of 2,000 notes / 10,000 pages / 50,000 elements / 10,000 metadata rows.
- D3-A additionally limits the examined metadata set to **128 assets**, each up to **64 MiB**, aggregate claimed file bytes up to **256 MiB** (research/test bounds, **not approved GC quotas**).
- Each original must have canonical private path, finalized state, expected MIME, length, hash and signature; paths use `NOFOLLOW_LINKS`. A missing, linked, changed, unrecognized or over-budget file returns `Incomplete`.
- The shared acquisition lease serializes *cooperating* writers. An uncoordinated Room writer or native file mutation is not made impossible by this lease; second-pass observations can detect some such changes, but no check-to-unlink interval is established here.
- All SQL read paths use `AppDatabase.withTransaction`; file I/O occurs outside Room transactions but inside the OS lease. Cancellation is propagated; the lease releases in `finally`.

## Device acceptance

New isolated Room v7 instrumentation class: `Sto08D3aReadOnlyOwnershipPreflightAndroidTest`.

Cases: shared active+Trash owner, zero-owner observation without GC permit, registered but unowned finalized asset, missing Trash projection, extra projection, malformed canonical media, missing original, linked/symlink original, unresolved ownership gate, asset-count limit, changed file after initial observation, changed metadata after initial observation, failed SQLite transaction rollback, simulated GC/reclaimer contention on the exact shared lease, cancellation and lock release.

All cases operate only against per-test `sto08-d3a-*.sqlite` and UUID-named private fixture directories on disposable emulators. The simulated contender acquires the same lock and asserts ordering; it **does not open or unlink an original for removal**. Tests must demonstrate no deletion, no release, no gate promotion. The production physical delete path remains absent.

**Gate:** GitHub Android+Shared Format CI, then the exact new class on disposable API 26 and API 37. Require JUnit XML with zero failures/errors; no device-test pass may be inferred from compile/CI alone. Do not reset an emulator's manual D2I data without explicit separate authorization.

## Deliberate remaining closure gates

1. Production Room-bound opt-in document export and explicit user-facing ambiguous destination state, not the debug D2I SharedPreferences fixture.
2. Destination-specific, independently restart-verifiable durability/visibility proof. Arbitrary SAF same-URI readback stays untrusted/nonreleasing.
3. Separate approved hold-release and immutable audit history protocol; current v5/v6/v7 immutable SQL triggers are unchanged.
4. A **distinct** owner-completeness certification protocol including historical migration ambiguity and all owners, foreign keys, metadata/filesystem consistency, full multi-process races and separate audit signoff. D3-A *does not implement the transition*.
5. Only after (3)/(4): production reclamation design and simulated check-to-unlink race, then separately approved isolated destructive tests, then enablement decision. D3-A's simulated lease contender is **not** proof of this.
6. Full [S08-01–S08-15](./sto_08_d3a_s08_acceptance_traceability_v0_1.md) mapping and Apple/STO-03 joint acceptance. Android only cannot close the joint issue.

**Never** delete original assets, export holds, app-private retained archives, DB rows, or historical evidence to make an observation pass. No GC or `VERIFIED` promotion is authorized.
