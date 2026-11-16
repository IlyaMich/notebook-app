# STO-08 V5 — Export-hold foundation implementation-status handoff v0.1

**Status:** IMPLEMENTATION-STATUS HANDOFF — documentation-only evidence reconciliation; **not an approval for production export**.
**Prepared:** 2026-11-16.
**Integration worktree:** `C:\Proj\notebook-app-sto08-i4b-2b` (`feature/sto-08-c3a2-i4b-2b-byte-inspector`).
**Last verified clean local HEAD:** `a13637d8857562184ee3582941b6008b8ef64efa` (driver-only promotion; parent `155d4f4f5f79375dbd87216ac73256aa370fe2e8`).
**Pinned comparison base:** local `origin/main` `9226f1850fa6c53612ace5bd79f99954a1c77259`. `git ls-remote` confirmed that remote `main` matched this base during V5-R3; this is **not** an ongoing remote-freshness assertion.
**Authority:** none to publish protected exports, issue receipts, release pins, promote ownership to `VERIFIED`, delete managed originals, run GC, push, or merge.

## 1. Purpose and evidence interpretation

This additive handoff documents what later isolated STO-08 checkpoints **implemented and verified**, without retrospectively changing the status, recommendations, or authorization boundaries of the three historical decision packets. The historic phrases “proposed,” “not implemented,” and “not wired” describe **their own audited checkpoints**, not the entire state of the later integration branch.

**Historical packets preserved unchanged:**

| Historical packet (tracked in `docs/architecture/`) | Original scope/baseline | SHA-256 of reviewed copy |
| --- | --- | --- |
| `sto_08_i5b2_export_hold_contract_v0_1.md` | Hold/retention **design candidate** at I5-B2; no persisted owner schema then | `CC923F0036324A31CFDFBD7BE3F2838AC357220915979E0298E0118E7D3D76A6` |
| `sto_08_i5b4_export_hold_persistence_decision_packet_v0_1.md` | **Proposed** migration/transaction and I4-B coordination at I5-B4; AppDatabase v4 then | `13C6E7872EFBF5B5029A050D1793D4D01D5EEC2D8581828466C551A9214162F4` |
| `sto_08_i5b7d_lifecycle_recovery_decision_packet_v0_1.md` | Room v5 and atomic writer **already implemented**; read-only recovery and true process-death tests still **proposed** at that checkpoint | `70F59DDEB56AC5E593DC67106B8F925E2D149EE710662A31F813253845CC03A6` |

**Evidence hierarchy.** The above three documents were read byte-for-byte from the uploaded copies. The implementation inventory below combines their historical assertions with the reported V1–V5 source inventories, test XML verification, process-death host evidence and exact Git/hash gate outputs. Those reported gates are not a substitute for a new independent code audit, full CI run or production acceptance review. No new tests are claimed by writing this handoff.

## 2. Current implemented foundation, narrowly scoped

| Capability | Current integration evidence | Boundary / non-claim |
| --- | --- | --- |
| Bounded asset and source-byte inspection, lease-scoped admission, synthetic staging and stage classification | `BoundedImageBytesInspector`, `ExportLeaseScopedAdmission`, `ExportLeasedSourceByteInspector`, `ExportSyntheticSingleImageCopyCoordinator`, and `ExportUnsealedStageRecoveryClassifier` appear in the integration source inventory and selected tests | Does **not** prove a durable protected destination, sealed manifest, receipt or stage adoption |
| Room schema v5 | `AppDatabase` v5, `MIGRATION_4_5`, entities/DAO for `export_operations` and `export_asset_holds`, exported `5.json` | Does not by itself create operational holds for production exports |
| Retention-only relational protection | Composite `(operation_id, asset_id)` holds, restrictive foreign keys, immutable operation/hold rows enforced by four SQL UPDATE/DELETE-blocking triggers; `COMMITTED_PIN_SET` is persisted as a conservative operation state | No mutable lifecycle phase, unpin, TTL/expiry release or asset reclamation authority |
| Atomic hold admission seam | `ExportAtomicHoldAdmission.persist`: outer acquisition/publication lease, single Room transaction, transactional source capture/ownership checks, and insertion of operation with distinct-asset hold set | Bounded **isolated seam**, not an approved, externally wired production export flow; commit proves a hold set, **not** export completion |
| Read-only hold recovery | `ExportHoldRecoveryInventory.inspect()` with bounded operation/hold-row enumeration and conservative diagnostic outcomes on ambiguity | No startup recovery wiring, persistent phase transition, manifest adoption, release or deletion |
| Real process-death test harness | `ExportCommittedHoldProcessDeathAndroidTest` PREPARE/VERIFY phases: isolated UUID-scoped DB and marker, READY PID, package-scoped host force-stop, different recovery PID and retained operation/two holds | Test-only DB; does not attest to managed asset bytes, protected-copy durability, receipts, release or GC |
| Committed reproducible host entry point | `android/scripts/sto08_i5b7d3c_hold_process_death_host_driver.ps1` promoted by **one-file local commit** `a13637d...`; post-commit default Audit passed on API 26 | The promoted driver **Audit** was run after commit; its Execute path was compared with the previously run R3, **not re-executed after promotion** |

**Production-call-site boundary:** The V5-R2 lexical scan found only the declaration of `ExportAtomicHoldAdmission` and only the declaration of `ExportHoldRecoveryInventory` among tracked `src/main` Kotlin matches for those names. This is evidence that the inspected production source has no external use of these classes, **not a proof about dynamic behavior or uninspected modules**. Room v5 database registration/migration is integrated; protected export orchestration is not established.

## 3. Verification and provenance at the reviewed baseline

| Gate | Verified result and evidence type | Important qualification |
| --- | --- | --- |
| V1 targeted JVM | **85/85** selected tests passed | Selected test classes, not the full project suite |
| V2 Room v5 schema/migration | API 26 **12/12**; API 37 **12/12**: 4 migration + 8 schema per API; separately run and preserved JUnit XML | The prior multi-class filter was corrected to independent single-class runs |
| V3 atomic admission/recovery | API 26 **18/18**; API 37 **18/18**: 8 admission + 10 recovery per API; independently verified JUnit XML | Isolated instrumentation, not production-path acceptance |
| V4 actual process death | API 37 **passed**: PID `7703` → `7806`; API 26 **passed**: PID `6736` → `6898` | Verified isolated operation and two pins in a fresh process, without APK reinstall or app-data clear between phases |
| V5 evidence inventory | V5-R1 re-read all **8 V2/V3 JUnit XML** files and V4 saved outputs, including pinned APK readback, and reported `REVIEW_ISSUES=0` | Host PID correlation was established in original R3 run output; V5-R1 notes the console output should remain preserved |
| V5 source and Git review | V5-R2: `git diff --check` clean; 51 paths and 19 commits versus pinned local main **before driver-only promotion**; V5-R3 remote `main` matched pinned comparison base | Scope counts should be recomputed after any subsequent documentation commit; no full CI/PR approval |
| Promoted driver | V5-R4 candidate parser/hashes/runtime-text equivalence passed; V5-R5 one-file commit `a13637d...`; committed driver Audit passed | Source changes and writes beyond that one driver commit have **not** been authorized |

**Recorded V4 evidence directories (local Windows host):**

- API 37: `C:\Users\levya\Downloads\sto08_v4_process_death_api37_01e2eaac-9cc2-443e-b0c6-1b7b97a846be`
- API 26: `C:\Users\levya\Downloads\sto08_v4_process_death_api26_6119861c-74e9-4ac5-8891-d81993f29156`

The original V2/V3 JUnit XML files are in their preserved run-specific directories under Downloads. V5-R1 verified their names, suite counts and failure/error/skip totals. Keep those evidence folders unchanged for PR review.

## 4. Reconciliation of historical proposals with today's checkpoint

**I5-B2:** Its retention invariants still apply. Its “persistent shape NOT implemented” and “transaction choreography NOT wired” statements were accurate at its original checkpoint. Later isolated implementation added the v5 tables, writer and tests; **production copy/wiring remains out of scope**.

**I5-B4:** Its migration ownership, schema-version and conservative transaction decisions were proposed before v5 existed. Current branch contains `MIGRATION_4_5`, insert-only hold persistence and migration tests. The original open coordination questions **are not automatically resolved** for I4-B's later protected export, retry, and source-byte/receipt integration.

**I5-B7D:** This packet already recognizes immutable v5 holds and atomic admission. Its planned bounded recovery inventory and true process-death harness have subsequently been implemented and verified on both API 26 and 37. Its recommendation to **retain immutable holds**, to **defer append-only event history**, and to **reject in-place mutable state** remains a proposal/decision boundary for future work; no event-ledger schema/version or release transition is authorized.

**No contradiction should be “fixed” by erasing or rewriting these historical snapshots.** Any new decision after the current evidence must cite a fresh baseline, named owners, reviewed source, and separately approved scope.

## 5. Open integration and product-safety gates

1. **I4-B/STO-08 ownership and handoff:** Confirm the owner of production export orchestration, operation UUID/retry mapping, stage/manifest/provenance, recovery hook, and any future schema version; avoid concurrent migrations across sibling worktrees.
2. **Single lease and transaction boundary:** Review exactly how a production caller holds the shared acquisition/publication lease through a committed pin set and then reaches source-byte inspection and destination staging without lock inversion or a post-commit unprotected gap.
3. **Durable destination proof:** Define file/directory durability (`fsync`/directory sync as applicable), sealed manifest and metadata, original-byte provenance, archive/publication consistency, post-crash recovery and receipt semantics; synthetic copies or an unsealed stage are insufficient.
4. **Recovery lifecycle:** Design explicit conservative startup behavior, unexpected file/DB states, bounded retry/idempotence, and interrupted copy handling. `ExportHoldRecoveryInventory` is **diagnostic only**; it does not adopt stages or mark complete.
5. **Persisted phase history:** Decide whether required. If so, review version allocation, bounded append-only event model, migrations and corruption handling separately. Do not weaken v5 immutable triggers merely to advance phases.
6. **Release and reclamation:** Only after independently proven protected receipts and recovery should a separately reviewed transaction/lock protocol consider hold release, source deletion, ownership `VERIFIED`, or GC. **All remain disabled/unauthorized now.**
7. **Integration PR readiness:** Reassess remote freshness at PR time; run the required full project CI and review 51+ changed paths and migration impact; decide whether to split foundation changes for review. The locally clean branch and selected green tests are not a full integration sign-off.

## 6. Recommended bounded next actions (not approvals)

1. Review this **additive status handoff** with STO-08 and I4-B owners; correct any evidence attribution against the preserved outputs and exact source revision.
2. If approved separately, add **only this new Markdown file** under `docs/architecture/` in Worktree D; leave I5-B2, I5-B4, I5-B7D and production source unchanged; create at most one controlled local documentation commit with independent Git/file-hash verification.
3. Validate the resulting branch with a fresh read-only source/commit inventory and (when specifically authorized) full CI/PR preparation. No push or merge follows from writing this handoff.
4. Plan production-export orchestration, receipt/recovery, and release/GC decisions as later, independent design/implementation gates.

**End-state assertion for this handoff:** STO-08 has a tested **retention-only export-hold foundation and isolated recovery proof**, but **not** a production-protected export pipeline. Neither this handoff nor any cited test is evidence of a protected export receipt, released hold, `VERIFIED` ownership state, or permission to delete original assets.
