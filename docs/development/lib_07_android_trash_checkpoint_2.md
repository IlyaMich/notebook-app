# LIB-07 Android Trash UI — Checkpoint 2

## Contract

This checkpoint connects the previously tested `LibraryTrashRepository` to Android Compose library, notebook and Trash screens. It does not alter Room schema, note revisions or STO-02 page/element records.

- Users may confirm moving a note, notebook or folder to Trash.
- Quick Notes is never offered for deletion, and its repository protection remains authoritative.
- `LibraryTrashRepository.listTrashedBatches()` groups all rows with the same `deletion_batch_id` into one user-visible action. One restore restores that batch transactionally; independently trashed records retain their own batch.
- Trash shows the primary deleted item and descendant counts. It is **not** a flattened list of all deleted descendants.
- Restore returns notes to the original active notebook when available, or Quick Notes if the notebook is unavailable. Folders and notebooks restore to an active original folder or root, following Checkpoint 1 semantics.
- Errors remain visible; confirmation dialogs do not close on failure. The route-level library version invalidates stale listings after Trash operations.
- No permanent deletion, automatic expiration, asset garbage collection, or cloud sync is introduced.

## Review attention

- The added DAO queries are read-only and require no Room migration; V2 remains unchanged.
- `LibraryTrashRepository` exposes read-only typed batch summaries, not raw Room entities.
- Old `LibraryScreen`/`NotebookContentsScreen` tests can omit the new optional parameters; production `NotebookApp` injects the same repository factory.
- Disabled controls while writes run prevent duplicate clicks. Failed mutations preserve the visible dialog and allow retry.
- Tests use isolated databases and exercise confirmation cancellation, folder cascade grouping, independent note batches, Quick Notes protection, restore, and saved first-page retention.

## Verification

From `android/` run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Test manually on Pixel Tablet: delete/restore a note, notebook and nested folder; verify Quick Notes cannot be deleted, independently trashed notes are not restored with another batch, and saved pages remain after relaunch. Do not commit until checks pass.
