# LIB-07 — Checkpoint 3B: Asset-Free Permanent Purge

**Status:** Implementation supplied for local Android verification; **not verified on Windows/Pixel Tablet yet**.
**Depends on:** LIB-07 Checkpoints 1–2 and approved Checkpoint 3A purge safety contract.

## Contract and behavior

- `LibraryTrashRepository.previewPurge(batchId)` returns a read-only confirmation snapshot after validating batch membership, page backgrounds, canonical editable element payloads, ordering and revisions. Malformed, unknown, image, and PDF-backed content prevent a purge preview.
- `purgeBatch(confirmedPreview)` recomputes the preview **within the same `AppDatabase.withTransaction`** that performs all deletion and rehoming. Stale or altered previews are rejected, with no writes.
- The selected deletion batch is the only deletion target. Earlier independently trashed notes retain their own deletion batch and original owner; notes whose notebook is being deleted are rehomed to protected active Quick Notes but remain in Trash. Other separately trashed dependent folders and notebooks become trashed root items.
- All purged note elements are deleted before their pages, note rows, notebooks, and folders (leaf to root). SQLite's `RESTRICT` foreign keys protect unexpected dependents.
- No asset registry, asset reference, file deletion, or Room migration is added. Deletion remains **explicitly confirmed and irreversible**. This is not general-purpose image/PDF asset cleanup, nor a new Empty Trash operation.
- UI invokes repository methods, not DAOs. Cancellation is side-effect free. Errors are surfaced; after successful purge, both Trash and parent library lists refresh.

## Test matrix

`RoomLibraryPermanentPurgeTest`: P01 single-note multi-page/element purge; P02 nested subtree; P03 independent note rehome and restore; P04 independent folder/notebook; P05 batch isolation; P06 unknown/active batch corruption; P07 rollback after rehome/first element/note/folder delete; P08 stale preview; P09 unsupported PDF/image/malformed JSON; P10 database reopen and subsequent restore; P11 Quick Notes protection implicitly across the suite.

`AndroidTrashUiTest.permanentDeleteNeedsConfirmationAndRefreshesTrash`: P12 Cancel vs confirmed permanent deletion on the actual Room-backed UI. Existing non-destructive Trash tests must still pass.

## Local verification gate

From `android/`, with Pixel Tablet emulator started:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Then manually test: move an asset-free note to Trash; cancel permanent deletion; verify it remains; reopen the dialog, confirm deletion; verify it disappears after relaunch and cannot restore. Also test an independent earlier Trash batch survives purging its parent folder and can subsequently restore. Do **not** commit until test results and safety behavior are reviewed.

## Deferred

Image and PDF asset-aware purge, reference-counted asset GC, full-library cleanup, scheduled/automatic Trash purge, and source-file deletion. They must not be represented as implemented or safely supported.
