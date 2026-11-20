# STO-08 D2H — Android DocumentsProvider runtime acceptance (debug-only) v0.1

**Base:** merged `main` at `aae6493614b28579b1672953dd901e5eca6802e3`, after D2F SAF handoff and D2G append-only Room v7 journal. **Scope:** Android instrumented validation, no new production export activation or durability receipt.

## Why this checkpoint

D2F/D2G had synthetic `ExportExternalDocumentDestination` tests and URI-shape rejection, but did not exercise Android's real ContentResolver → DocumentsProvider → ParcelFileDescriptor path. A matching synthetic readback alone cannot reveal provider descriptor, document URI, close/sync or permission behavior.

D2H introduces an **isolated, debug-build-only** `DocumentsProvider` and three actual provider-backed end-to-end Android tests. The provider is registered only under `src/debug`, protected with the system-only `android.permission.MANAGE_DOCUMENTS` permission, and stores only test-generated files under `filesDir/sto08_disposable_saf_test_documents`. Its authority deliberately differs from `com.notebook.app` so the production SAF adapter treats it as an external provider. It does **not** use the app's managed assets tree or production `library.sqlite`.

## Device assertions (API 26 and API 37)

1. Test data is created in an isolated Room on-disk database and separate managed-asset fixture. The production source snapshot, immutable holds, private draft, and retained archive are built with existing integration coordinators.
2. An actual `DocumentsContract.createDocument` creates a new test document. `DocumentsContract.isDocumentUri` must accept its URI. The production `ExportSafCreatedDocumentDestination` opens the provider's real file descriptor, syncs, closes, independently reopens and hashes returned bytes.
3. After Room close/reopen, an exact source-to-destination hash gives `ReadbackObservedUntrusted` only; replay is refused without rewriting the document. The original asset bytes, committed pins, and global `UNVERIFIED` gate remain unchanged.
4. A provider-injected write refusal, after one immutable attempt commits, leaves `AttemptPendingUntrusted` on Room reopen. It cannot retry the same export.
5. A provider-injected *post-write readback refusal* leaves a nonempty, ambiguous test-provider document; Room retains only `AttemptPendingUntrusted` and cannot retry. The provider document is cleaned up *solely by explicit test fixture teardown*.
6. The tests must not dispatch `ACTION_CREATE_DOCUMENT` UI, request access to a user's Downloads/Drive/files, open production `library.sqlite`, delete an original, release a hold, promote `VERIFIED`, or enable GC.

## Scope distinction and unresolved acceptance

This is a **real Android framework provider transport check**, but it is **not evidence for user picker grants, cross-UID authorization, third-party provider persistence, cloud synchronization, process kill, power-loss durability or atomic publication**. The test provider is in the app's debug APK and uses the same application UID. A real external provider and user-picked URI must be validated later, with separate user permission and disposable destination constraints. Even successful real-provider readback remains untrusted.

Do not claim `EXPORT_COMPLETED` or release `export_asset_holds` from this checkpoint. Stop fail-closed on any unexpected installed emulator, Kotlin/Gradle failure, instrumented failure, cleanup ambiguity, or changed Git baseline. Keep all protected worktrees unchanged.

## Acceptance deliverables

- GitHub CI: Android debug and unit verification, Android test compilation and lint; Shared Format CI.
- API 37 disposable AVD `STO07_S03B_Disposable`: three new provider-backed tests, existing Room/recovery tests and URI-negative tests.
- API 26 disposable AVD `STO08_API26_Disposable`: same classes and counts; no production devices.
- Exact SHA and Git tree diff readback after PR merge. No generated Room schema change is expected (Room stays v7).
