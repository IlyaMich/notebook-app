# STO-08 B6 — Android retention acceptance evidence (candidate)

Baseline: `45d345788675277b8b909cb01209f1c6b22b4ee6` (pinned main, 2026-11-19).
Scope: Android **only**. Apple development and validation are explicitly deferred to a separate phase. This document does not alter the formal cross-platform issue acceptance contract.

## Production boundaries

- RoomPermanentPurge is a confirmed, transactional **asset-free-only** purge; indexed media, malformed or unsupported canonical content fail closed. Library/content SQL deletions must not be conflated with deletion of asset originals.
- ManagedAssetFileStore deletes only its own incomplete freshly-created final file following a failed publication and its temporary staging file. Neither is a general original-asset GC path.
- AssetDao has no asset metadata delete method. The conservative ownership gate cannot promote to VERIFIED.
- ExportAtomicHoldAdmission and its composition/recovery facilities provide retention-only, isolated admission/recovery foundations. No production export publication/receipt or safe hold release is established.
- No asset-original GC, export hold release, ownership VERIFIED promotion, or release-hold removal is authorized by B6.

## S08 evidence status (Android only)

| ID | Current evidence | Closure gap / limitation |
|---|---|---|
| S08-01 | Shared-asset writer regression and owner-edge inventory | Two active notes / one removed owner not proven through supported production delete |
| S08-02 | Image Trash/restore preservation regression | Shared active+Trash independent-owner scenario needs explicit traceability |
| S08-03 | Asset-bearing clone is deliberately rejected | Positive asset-backed duplication remains a separate feature gap |
| S08-04 | Asset-bearing remove/replace/transfer deliberately rejected | Positive atomic owner-edge mutation unsupported |
| S08-05 | Transactional Trash/restore tests | Asset-backed folder batch scenario not fully evidenced |
| S08-06 | Independent trashed descendant purge tests | Media-bearing descendant purge/restore must remain protected |
| S08-07 | Stale purge preview and rollback tests including p11/p12 | Verified within reported API26/API37 PR#145 device scope; full broader matrix separate |
| S08-08 | Stage/commit ordering and read-only orphan diagnostics | Exact interrupted post-finalization attachment scenario evidence incomplete |
| S08-09 | Committed retention-only export holds and isolation tests | No production export callsite / verified independent copy / real purge race |
| S08-10 | Actual hold process-death tests on API26/API37 | Unlink and release are disabled and cannot be claimed runtime-tested |
| S08-11 | Missing projection, bad bytes and unsafe-gate rejection suites | Aggregate failure-matrix receipts / source-to-test mapping pending |
| S08-12 | Historical Room migration, v4/v5 schema tests | Full ambiguous legacy case coverage needs traced receipts |
| S08-13 | Cross-process lease, concurrent insertion, snapshot research | Complete registration vs last-owner removal race unavailable while deletion disabled |
| S08-14 | Rollback and duplicate operation-ID regressions | Complete uncertain-outcome user-facing Saved/retry scenario separate |
| S08-15 | Storage fault/recovery and byte-verification regressions | Full permission/disk-full asset-backed matrix requires platform-specific receipts |

## B6 candidate changes and authorization

- Added two isolated `ExportAtomicHoldAdmissionAndroidTest` regressions for retention across a synthetic deleted-note state/reopen and a rejected later admission; no production source modifications.
- Running compile/unit tests does **not** prove device runtime safety. API26 and API37 instrumentation must run only on explicitly verified disposable emulators and results must be recorded before claiming these tests passed.
- Formal Android retention status: PARTIAL until test results and evidence are reconciled. Formal joint STO-08 status: OPEN, with Apple deferred.
- No asset reclamation, hold release, VERIFIED promotion, GC, push or merge.
## B6 focused Android runtime verification — 2026-11-19

- Pinned baseline: `45d345788675277b8b909cb01209f1c6b22b4ee6`.
- Instrumentation class: `com.notebook.app.data.assets.ExportAtomicHoldAdmissionAndroidTest` (8 pre-existing tests + 2 B6 regressions).
- API 26 `STO08_API26_Disposable` (`emulator-5556`): **PASS 10/10**, zero failures.
- API 37 `STO07_S03B_Disposable` (`emulator-5554`): **PASS 10/10**, zero failures.
- App APK SHA-256: `4046BBE6A4E9E9963BB88B36657098430B365FE4FD237701514593912A21C343`.
- Android-test APK SHA-256: `3564C2525254FEB52EDE9D68DF4007BCD2CB35EF3F71D4D8EDC627FF10D5DA3D`.
- External local receipts (not included in this commit): `Downloads/sto08-b6-android-runtime-evidence/API26-emulator-5556-ad14fb3a8db844df9ba3b6d72e11b1f7/` and `Downloads/sto08-b6-android-runtime-evidence/API37-emulator-5554-8e3d05d5ab994735bc04b20d2b530e75/`.
- This verifies **isolated retention-only hold regressions**, not production export wiring, original-file GC, hold release, complete S08-01–S08-15 acceptance, or joint Android/Apple closure.
