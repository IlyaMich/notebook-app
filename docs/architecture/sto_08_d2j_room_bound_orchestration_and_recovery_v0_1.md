# STO-08 D2J-A — Room-bound export orchestration and conservative recovery

**Date:** 2026-11-25  
**Base:** merged D3-A on `main`, `8d369b09b5199c3b038e72f411019c0129d47c73` (PR #165; exact D3-A tested tree)  
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31)  
**Scope:** Android production internal composition and isolated real-Room v7 integration. **No user-facing export button yet.** No Room schema migration.

## 1. The missing orchestration boundary

Prior D2B–D2G production components already implement separate gates for source capture and immutable holds, app-private independent copies, portable draft generation, independently retained private archive, external SAF byte handoff and append-only v7 attempt/readback observations. D2I manually exercised real Android picker/provider behavior through a separate **debug SharedPreferences fixture** and cannot stand in for actual production Room evidence.

D2J-A wires these existing components into `ExportRoomBoundHandoffCoordinator` and exposes an explicit `ExportHoldAdmissionComposition.roomBoundHandoffForApplication(context,snapshotLimits,copyLimits,stageLimits)` factory. It is an **internal service**, not a background worker, startup hook, UI export action, provider-durability receipt or original-reclamation service. Its limits have no silently assumed production defaults.

## 2. Prepare choreography

Only an explicit new invocation may call `prepareNew(noteId)`; the service creates one fresh operation UUID.

1. Reject malformed note IDs and invalid clocks before a write.
2. Under the existing shared asset-publication OS lease, perform the **transactional single-note Room capture with an immutable operation/source binding and complete hold set**, followed by bounded source-file verification and independent private-stage copy. Existing code performs each; D2J does not create its own second transaction or nested lease.
3. After the stage returns, **reconstruct** stage expectations from immutable Room v6 evidence using `ExportBoundPrivateStageRecovery.inspectReadOnly`, not from the caller's in-memory claim.
4. Build the portable `.notebook` draft from the verified private stage only. Reinspect it against the Room binding, with no live note/source-file reread.
5. Create an additional fresh, independent, app-private retained archive using existing D2E coordinator and inspect again. Return only `AwaitingFreshUserDocumentUntrusted(operationId,noteId,noteRevision,archiveSha256,archiveBytes)`.

**No step establishes completion of an export outside the app sandbox.** A failure after immutable pin admission becomes `IncompleteRetainedUntrusted(operationId)`; it is not restarted/cleaned/deleted automatically. Source holds survive and operation IDs are never recycled. No export receipt or release is recorded.

## 3. Fresh-picker handoff choreography (not UI-activated)

The future UI must start `ACTION_CREATE_DOCUMENT` after an explicit user action, and pass the **fresh picker callback** URI to `handoffToFreshUserDocument` exactly once for that operation. Old persisted/restored URIs, arbitrary background calls, and unapproved provider authorities are not acceptable substitutions. D2J itself never launches picker UI.

The existing D2F/D2G coordinator independently revalidates Room-bound private archive, commits one immutable Room v7 external attempt **before touching provider bytes**, writes, syncs, closes and rereads the selected document, and inserts a matching readback observation when possible.

- `ReadbackObservedUntrusted(operationId)` is only historical matching provider bytes. It is **not** atomic visibility, remote upload, provider power-loss durability, or a completion receipt.
- `AttemptOutcomeUnknownUntrusted(operationId)` means no matching provider evidence can be safely returned, including an exception after an attempt may already have committed. An external file could have no, partial or complete bytes; **never retry the same operation** or delete/rewrite the user document automatically.
- The actual user-facing picker callback and visible status/pending-operation UX require a separate reviewed D2J-B activation checkpoint. In particular, **no in-app "Export complete" promise is authorized**.

## 4. Read-only process-restart recovery

`inspectReadOnly(operationId)` classifies immutable persisted v7 history:

| State | Meaning | Allowed action |
|---|---|---|
| `NoAttemptRecordedUntrusted` | A Room v7 attempt was not found and a Room-bound private archive can still be reread | **Diagnostic display only**; no automatic picker, retry or assumed absence of external bytes |
| `AttemptPendingUntrusted` | Committed attempt with no recorded matching readback; provider outcome unknown | Show manual ambiguity, retain holds, never retry or remove destination |
| `ReadbackObservedUntrusted` | Matching bytes were once reread; external durability unproven | Show historical readback only, retain holds |
| `IncompleteRetainedUntrusted` | Invalid legacy/corrupt/incomplete Room or archive evidence | Keep everything; no claimed completion |

No startup hook or automatic replay exists, and no raw document URI or provider grant is stored in Room. The existing no-update/no-delete triggers on holds, bindings and attempt/readback tables remain unchanged. The global `asset_reference_index_state` stays `UNVERIFIED`.

## 5. Evidence scope

New isolated Android Room-v7 class `ExportRoomBoundHandoffCoordinatorAndroidTest` validates **11** cases:

- Media-free source -> private ZIP -> retained source binding; image source -> held original, Room close/reopen and archive reread.
- Invalid note ID, trashed note, missing original after committed hold.
- One-shot matching synthetic provider response, replay rejection, provider write refusal, post-write readback refusal and restart-pending classification.
- Tampered private archive after Room close/reopen, malformed ID, and independently requested second operation with a new UUID.

All tests operate in `sto08-d2j-<UUID>.sqlite` and `sto08-d2j-disposable-<UUID>` under test context, with a test-owned byte-stream provider seam. They do **not** trigger a real picker, write an actual external provider document, touch production `library.sqlite`, or perform asset reclamation.

**Required gates:** Android CI and Shared Format CI on exact candidate; Android test APK compilation; 11/11 device tests on disposable API 26 and API 37; regression suites for D2G/D2H/D3A if feasible; no resets of prior manual evidence without independent approval.

## 6. Open path to full STO-08

1. D2J-B: audited actual production UI invocation/callback and persistent operation-ID/ambiguity UX without fake provider completion.
2. Destination-specific independent durability/visibility proof or a permanently nonreleasing destination class; archive import conformance on real destinations.
3. Separately reviewed, migration-aware hold-release protocol only after a trusted external completion receipt, with process-death/race evidence.
4. Authoritative ownership completeness certification across active/Trash/legacy and a serializable check-to-unlink contract; D3-A is not this certificate.
5. Separately approved GC/metadata retirement, extensive Android S08-01–S08-15 current-main evidence and joint Apple/STO-03 acceptance.

**Strict non-goals:** no new schema/trigger, user picker, actual managed-original deletion, hold removal, ownership `VERIFIED`, GC activation, private export-stage cleanup, provider-document deletion, or production claim of a successful durable external export.
