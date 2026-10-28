# STO-02 Checkpoint 3A — Canonical document repository

**Date:** 2026-10-29  
**Scope:** Android document content mutations, relying on the committed Room V2 foundation (Checkpoint 2B)  
**Status:** Proposed implementation. Build/device tests and collaborator review are required before acceptance.

## Architecture

- `com.notebook.app.data.content.repository.DocumentRepository` exposes `load(noteId)` and `commit(noteId, DocumentEdit)`; UI/editor is not allowed to call raw Room DAOs directly.
- `RoomDocumentRepository` is backed by the **existing** `AppDatabase`, `NoteEntity`, `PageDao`, and `ElementDao`. It does not own a second database, create a `NoteEntity`, or implement the LIB-03 lifecycle adapter yet.
- `DocumentEdit` models one completed editor action. Bulk `InsertElements`, `ReplaceElements`, `RemoveElements`, and `TransferElements` change any number of rows within one `withTransaction`. A successful *mutating* command increments `notes.revision` once, and `notes.updated_at` inside the same transaction. Unchanged edit commands return the existing revision.
- The `DocumentCommit` acknowledgment is returned **after** `withTransaction` completes. An exception propagates to the caller and there is no `Saved` acknowledgment. The UI save-status state machine will be handled later.
- `load` provides a consistent snapshot inside a Room read transaction, retaining the original full canonical JSON for each element and background. It does not flatten, rebuild, or serialize entire pages on individual-element edits.
- A `RoomDocumentRepository` may be injected directly for test cases. Production repository wiring will be aligned with LIB-03 lifecycle in Checkpoint 3B.

## IDs, ownership, ordering and revision rules

- Canonical lowercase UUID strings are required on every API entry; note/page/element FK ownership is checked *inside* the mutation transaction. Mutating a trashed note or a note in a trashed notebook is rejected. Read-only `load` remains available for future Trash restoration/backup use.
- Page and element indexes are 0-based and contiguous at every committed repository boundary. Reordering, insertion-at-index, removal, and same-note cross-page transfer use collision-free temporary positions and then normalized final positions inside one transaction. This avoids violating immediate SQLite uniqueness constraints. The list is rejected if it is not an exact permutation of the owner's current IDs.
- The local `notes.revision` is the only authoritative content revision. Updated page/element rows carry the *note* revision of their latest change. Unaffected element payload rows do not change on a single-element replacement. Reordering updates row revision for repositioned siblings. A note revision is not incremented for a no-op command.
- `RemovePage` rejects deletion of the sole remaining page, and deletes child elements before deleting their owning page (the FK uses `RESTRICT`). Soft-delete remains LIB-03/LIB-07's concern; raw DAO operations are for internal/test use only.
- A cross-page move preserves element IDs; a future copy/paste operation must generate new IDs. Cross-note transfers are explicitly not supported by the `TransferElements` command.

## JSON validation scope

`CanonicalContentValidator` validates full V0.2 JSON shapes before content writes: page dimensions and background variants; supported envelope keys, canonical UUID, element type, Boolean lock, finite invertible transforms; sampled stroke points, brush attributes, monotonic timestamps and optional pressure/tilt; parameterized shapes and their geometry/paint; text frame and whole-box style; RGB hex colors, positive dimensions, and opacity bounds. Unknown and missing keys are rejected rather than silently dropped. Raw element JSON is retained without normalization so payload precision and representation survive round-trip.

**Known asset boundary:** `pdfPage` backgrounds and `image` elements are rejected as `UNSUPPORTED_CONTENT` until `assets` and `asset_references` are implemented with staged file integrity and safe garbage collection. This is intentional. It is not a claim that these document kinds are complete. The source-format Python validator remains authoritative for portable ZIP import checks, which are out of scope here.

## Deliberate Checkpoint 3A exclusions

- No production `NoteContentTransactionPort` (first page and duplication are Checkpoint 3B).
- No Room schema changes or V2→V3 migration (the V2 tables already support this behavior).
- No native ink capture, visual editing, undo stack, PDF rendering, media asset storage, package import/export, backup, Trash purge, UI save-state wiring, iOS, or cloud sync.

## Verification and review

1. Apply the source files from the normal repository-relative ZIP on `feature/sto-02-android-persistence` after the committed Checkpoint 2B.
2. Run `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:connectedDebugAndroidTest` from `android/` with the Pixel Tablet emulator running.
3. `RoomDocumentRepositoryIntegrationTest` covers page order/reopen, background, last-page rejection, per-element update/reorder/reopen, mixed-command revision behavior, cross-page transfer, metadata move, invalid canonical payloads, locked/trashed note rejection, and mid-command Room constraint violation with complete rollback.
4. Confirm `git diff --check`, review all source changes with the other developer, and commit this checkpoint **without merging the feature branch**. Checkpoint 3B will wire the existing LIB-03 `NoteContentTransactionPort` and prove real note creation/duplication.

## Remaining integration concern

Checkpoint 2B backfilled one blank A4 page for each legacy note row. This remains a proposed migration policy pending collaborator review. Checkpoint 3A does not modify that policy or change historical exported Room schemas.
