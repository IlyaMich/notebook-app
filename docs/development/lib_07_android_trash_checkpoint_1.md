# LIB-07 Android Trash — Checkpoint 1: Non-destructive Room transactions

Status: **Implementation candidate; requires Android emulator verification and peer review**.

## Starting point and ownership

Uses the LIB-03 `LibraryTrashRepository` interface, the already-exported Room V2 schema, and the existing `LibraryRepositoryFactory`. No new `AppDatabase`, table, entity or schema migration. It does not alter STO-02 page/element persistence or note revision state.

## Supported operations

- `trashNote`: soft-delete only the active note metadata; record deletion timestamp, fresh batch UUID and original notebook ID.
- `trashNotebook`: soft-delete one ordinary active notebook and its *currently active* notes in the same transaction, preserving independently trashed notes and their original batches.
- `trashFolder`: soft-delete an active folder subtree (active folders, notebooks and notes), using one fresh deletion batch, without enrolling previously trashed descendants.
- `restoreBatch`: restore only the rows belonging to that batch. Recreate folder ancestry in parent-first order; restore notebooks after folders and notes after notebooks.
- If the prior folder parent is not active, restore the folder/notebook to root. If the original notebook for a restored note is inactive, use the distinguished Quick Notes notebook.
- Never change note, page or element identity, canonical element data, or note content revision while trashing/restoring.
- Retain saved original parent/order hints only while an item is trashed. Upon successful restore, clear these deletion snapshots.
- On restoring an occupied sibling `sort_order`, append the restored item within its destination. The existing schema does not require dense library sibling positions.

## Deliberate exclusions

- No hard-delete/permanent purge, asset garbage collection or deletion confirmation UI. Those require safe reference-aware asset lifetime policy.
- No Trash listing, Restore button, or UI navigation yet; those belong to subsequent checkpoints.
- No automatic age-based deletion.
- `LibraryTrashRepository` contract remains unchanged. This checkpoint adds its first production implementation and factory registration.

## Tests and review gate

`RoomLibraryTrashRepositoryIntegrationTest` covers:

1. Soft-delete and restore a real note created through the LIB-03/STO-02 lifecycle; first-page persistence across Room reopen.
2. Nested folder subtree with separately trashed note; batch isolation and correct parent relationships.
3. Restore note to Quick Notes without resurrecting a separately trashed notebook.
4. Restore a previously trashed child folder at root when its parent is still trashed.
5. Quick Notes protection, rejection of repeated deletion, unknown batch rejection.
6. Trigger-injected write failure after note/notebook writes rolls back the whole folder deletion.
7. Foreign-key integrity after each completed or rejected flow.

### Commands (Android project directory)

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

**Do not report this checkpoint as verified until all three pass on the Android emulator.** Existing library UI is intentionally unchanged because Trash has no user-facing actions yet.

## Open concerns for peer review

- Reconcile the draft `ADR-0001` status with the joint approved LIB-01 decisions before finalizing LIB-07.
- Confirm whether deleting a folder with an already-trashed child should leave the previous owner FK untouched (current behavior), with restoration of that independent child falling back to root if its old parent remains trashed.
- Define separate Trash listing UX and permanent-purge/asset-reference lifecycle before implementing those operations.
