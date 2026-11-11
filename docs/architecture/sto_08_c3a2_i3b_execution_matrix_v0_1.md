# STO-08 C3-A2-I3-B B1 — Research-Only Execution Matrix V0.1

**Status:** JOINTLY AUTHORIZED FOR FOUR-FILE AUTHORING ONLY (2026-11-11). NOT EXECUTED. B2 JVM/build execution and B3 device/ADB execution are NOT authorized. D2 quotas remain provisional.
**Baseline:** `main` at `4ad1f1727255cc6feb2b0470e558ede46a8e6b89`.
**Issue:** STO-08 #31 (open). **Authority:** C3-A1 design direction, C3-A2-I2 design direction, I3-A W00–W13 provisional research assumptions and the B1 first-slice joint authorization.

## Exact four-file allowlist

1. `android/app/src/test/java/com/notebook/app/data/assets/ExportSnapshotResearchBoundsTest.kt`
2. `android/app/src/test/java/com/notebook/app/data/assets/ExportSnapshotResearchOwnerEdgesTest.kt`
3. `android/app/src/test/java/com/notebook/app/data/assets/ExportSnapshotResearchStateMachineTest.kt`
4. `docs/architecture/sto_08_c3a2_i3b_execution_matrix_v0_1.md`

All three classes are **model and pure JVM only**. No test execution has occurred; source inspection is not a compiled/tested verdict. All helper logic is contained inside the authorized test files; there are no new production interfaces.

## W00–W13 traceability

| Workload | B1 first-slice coverage | Future separately authorized evidence |
|---|---|---|
| W00 — media-free control | Bounds zero-copy and owner model absence | Isolated Room snapshot and Android timing |
| W01 — small image | One image counted and matching edge | Real copied-byte hash/durability |
| W02 — shared image owners | Distinct owner occurrences vs one copy key | Exact Room edge snapshot and independent copy |
| W03 — image count boundary | 16 allowed / 17 rejected *as provisional fixture assumptions* | On-device memory/disk/hold measurements |
| W04 — byte boundary | Per-image 16 MiB; aggregate 64 MiB | Real bounded streaming and measured space |
| W05 — large document | 32 pages, 2,048 elements, 8 MiB JSON ceilings | Isolated Room traversal/transaction timing |
| W06 — unsupported media | PDF/unknown/hidden/invalid rejected by model | Source and metadata verification |
| W07 — cancellation | State model invalidates all partial stages | Coroutine cancellation with real temp files |
| W08 — lease contention | Not represented by pure JVM models | Cross-process OS lock/device timing |
| W09 — process death | Restart state cannot trust marker without all evidence | Host-driven approved disposable-device run |
| W10 — concurrent edits | Not represented by pure JVM models | One coherent Room snapshot while editor commits |
| W11 — owner-edge completeness | Exact canonical matching, phantom/missing edges | Atomic Room transaction for all referenced rows |
| W12 — marker and tamper | Model rejects missing/changed copy/manifest or durability evidence | Real restart and independent re-open/re-hash |
| W13 — cleanup boundary | Model requires fixture path ownership and original-preservation flags | Audited test-local cleanup, no managed original deletion |

## Provisional limits (NOT approved export quotas)

- One active note; 32 pages; 2,048 elements; 16 distinct PNG/JPEG assets; aggregate source bytes 64 MiB.
- Individual PNG/JPEG original at most 16 MiB under existing decoder policy; actual media validation must be separate.
- Total canonical JSON UTF-8 at most 8 MiB; manifest at most 1 MiB; fixed stream buffer at most 64 KiB; per-fixture staging at most 160 MiB.
- These models do not allocate real buffers/files. Page and element limits do not authorize production export or memory use.
- In the bounds test, deduplication counts one copied source per asset ID but retains distinct canonical owner occurrences. Checked `Long` arithmetic must reject overflow.

## B1 expected observations and limitations

- **Bounds test:** deterministic accept/reject accounting with duplicate owner preservation, malformed metadata rejection and checked overflow. No measured resource safety.
- **Owner-edge test:** verifies the real diagnostic `DocumentAssetReferenceInspector` against a *test-local* owner projection model. It does **not** show complete semantic JSON validation, database transactionality, orphan-row detection, or global typed-edge completeness. String-based envelope discriminator checks are intentionally insufficient for real implementation; the later Room probe must parse/validate structurally.
- **State-machine test:** simulated stage transitions, cancellation, untrusted completion marker, tampering and recovery. Booleans representing `force` or directory durability are inputs to the model—not Android evidence. A model candidate is **never** a trusted export capability.
- Any discovered failure to compile must be reported at a future **B2** authorization, not resolved by changing unauthorized files.

## Required future verification schema — NOT RUN

| Field | Planned recording |
|---|---|
| Source state | Exact commit, four-file hashes, changed-file list, test class and test identifier |
| Workload | W00–W13 ID, synthetic fixture variant, image/page/element/edge counts |
| Metrics | Monotonic lease wait/hold, transaction, copy/re-hash, force/sync times; peak memory and disk observations |
| Device | Explicit user-approved disposable AVD serial/API/filesystem; never assume device permission |
| Result | Pass/fail/skip with exact report and failure evidence, no unsupported success claims |
| Safety | Original/owner/DB byte preservation, isolated fixture ownership, no gate mutation |

**B2:** only after separate explicit authorization may Gradle compile or JVM tests run. **B3:** only after separate explicit authorization and approved emulator identity may instrumentation/ADB/device tests run. No production source, Room schema, exporting, originals cleanup, migration, GC, or `VERIFIED` promotion is permitted here.
