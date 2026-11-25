# STO-08 D2I-A — Opt-in external SAF picker and mid-write process-death probe

**Base:** merged D2H on `main` at `3764f537569e1c8f2799c0fd9324c632f30415d3`.

**Scope:** new **debug-build-only, disposable-emulator-only** opt-in manual probe and a test-only diagnostic. This is neither a user-facing export feature nor a durable completion protocol.

## 1. What gap it closes

D2H exercises real `ContentResolver`, `DocumentsProvider`, descriptors, sync, close and readback, but the fixture provider resides in the same app UID; tests bypass the system picker. D2I-A offers an interactive `ACTION_CREATE_DOCUMENT` flow and requires the resulting provider authority to be **Android External Storage** (`com.android.externalstorage.documents`) or **Android Downloads** (`com.android.providers.downloads.documents`). This deliberately excludes the D2H debug provider, arbitrary authorities, third-party cloud providers, and non-SAF/file URIs. Picking a cloud or other unsupported provider can still create an empty document: no bytes are written; the tester must remove that file manually if appropriate.

The test is gated on emulator hardware (`ranchu` or `goldfish`) and a user-initiated click; the activity is registered **only in the debug manifest**. It is never invoked by the normal Notebook export UI.

## 2. Test data and isolation

The probe generates a **fresh random ~96 KiB ZIP fixture** with a marker entry. This is synthetic data, *not a user's note*, and it resides in app-private `filesDir/sto08_d2i_manual_picker_probe/retained-private-export-archives/<UUID>.notebook`, separate from all production managed assets.

The app invokes the *actual production* `ExportSafCreatedDocumentDestination` and `ExportExternalDocumentByteHandoff` classes on the new picker-returned URI. It never opens `library.sqlite`, reads originals, obtains source holds, releases pins, promotes `VERIFIED` or runs GC.

A dedicated debug-only preferences ledger is separately one-shot for the success and kill modes. It commits expected bytes/hash and the **SHA-256 of the destination URI identity, not the raw URI**, before provider write. On a matching independent provider readback it may record only `ReadbackObservedUntrusted`. It is **not Room v7**, not cross-process transactionally synchronized, not an export receipt and not a provider durability claim.

## 3. Manual modes on a disposable API 26 or 37 emulator

**Normal mode (once):** launch `STO-08 D2I Disposable SAF Probe` from the emulator's debug app launcher, tap the normal mode button and choose **emulator-local** storage (e.g. Downloads). System picker creates a fresh `sto08-d2i-disposable-success-<UUID>.notebook` file. The probe uses the production adapter for copy, fd sync, close, separate readback and SHA-256 verification, then stores the one-shot `ReadbackObservedUntrusted` test diagnostic. Reopen the probe to see the same untrusted marker; the normal button stays disabled.

**Process-kill mode (once):** tap the clearly labeled deliberate-kill button; after the user approves a second new file in emulator-local storage, the app commits `AttemptPendingUntrusted`, writes the first provider chunk, and immediately kills its own process **before** writer sync, close and reread. Reopen the probe from the launcher. Its pending record must survive, the kill mode stays disabled, and no provider write is automatically resumed. The destination could be empty, partial, complete or missing; the test never interprets its state as a completion receipt or deletes it.

Tests create **two new documents** only following explicit user picker approval. Manual inspection/cleanup of those two disposable exported documents, if desired, happens outside this app. The app never deletes selected provider documents, even after errors or process death.

**Prerequisites:** a disposable emulator only; no personal or real device; test-app data must not be reset between a mode's write and its reopen/recovery check. Do not run the existing all-classes acceptance script in between the manual writer and reopen, since that script resets app data.

## 4. Important limitations

- The picker and URI grant are genuine, and the local ExternalStorageProvider / Downloads provider runs outside this app. The test is limited to these provider authorities, not cloud services or arbitrary cross-UID vendors.
- No user-created provider document is guaranteed atomic, persistent across physical power loss, durable after sync, or remotely uploaded. Even a matching independent readback is historical **UNTRUSTED** evidence.
- The process-kill test uses *deliberate* `Process.killProcess` after a chunk, not a realistic power cut or provider crash.
- The debug SharedPreferences ledger is a test fixture and may not be promoted to a production Room completion protocol. Test activity uses no production Room evidence and cannot justify release.
- The active export hold protocol remains immutable and nonreleasing. No source deletion, no external document cleanup, no ownership completeness gate promotion or GC.

## 5. Quality gates and stop conditions

1. Exact reviewed branch/head and four/five-file scope; no source changes outside debug Activity, debug ledger, debug manifest, Android tests and this document.
2. Both Android and Shared Format GitHub CI green.
3. API 37 and API 26: run **5 new one-shot ledger Android tests** on the disposable emulator; existing 63 D2H tests should remain unchanged.
4. Manually verify the picker authority, opt-in UI, normal readback and process-kill/restart outcome **on at least API 37** before merging. API 26 manual acceptance is preferred before declaring full D2I.
5. Do **not** automate the picker or choose a destination without human action. Do not auto-delete an external document, reinstall SDKs, mutate protected worktrees, or alter main until review.

Any unexpected provider authority, emulator identity, missing permission, hash mismatch, missing app-private fixture, or journal corruption must fail closed. Retain old exports and holds.
