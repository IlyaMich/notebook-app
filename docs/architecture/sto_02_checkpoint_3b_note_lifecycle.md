# STO-02 — Checkpoint 3B: Production note lifecycle integration

**Date:** 2026-10-29  
**Status:** Proposed implementation; not accepted until Android/emulator verification and peer review  
**Branch:** `feature/sto-02-android-persistence`

## Actual source integration

- LIB-03 remains the owner of `NoteEntity`, `NoteDao`, `RoomNoteLifecycleRepository`, and `NoteContentTransactionPort`.
- `RoomNoteContentTransactionAdapter` is STO-02's concrete implementation of the existing port. It operates on the **same supplied** `AppDatabase` instance and does not start a transaction.
- The existing LIB-03 coordinator wraps both metadata and content in `AppDatabase.withTransaction`. A failing content insert or clone aborts the entire outer transaction (including newly provisioned Quick Notes).
- `LibraryRepositoryFactory.noteLifecycle()` provides safe production wiring. Existing `noteLifecycle(port)` remains available for explicit injection/fault testing. `LibraryRepositoryFactory.documents` provides the verified 3A `DocumentRepository` on the same database. Production callers must not use raw DAOs to bypass validation.
- **No database schema or migration changes:** schema remains Room V2, preserving exported `1.json` and `2.json`.

## New note semantics

- Insert one canonical initial page at index 0, with a new UUID, positive finite dimensions, and validated non-asset background.
- Initial `NoteEntity`, `PageEntity` revision values are both `0`; creating the first page is part of note creation, **not a subsequent edit**. The note title, notebook, and timestamps remain owned by LIB-03.
- Reject unsupported PDF backgrounds before page insertion. Missing metadata or any existing pages also fail rather than causing partial creation.

## Duplication semantics

- Validate nonempty, contiguous ordered pages and elements and verify each canonical element envelope's ID/type matches its indexed row before writing target rows.
- Preserve page sizes, backgrounds, element payload geometry/transforms, locked state, and back-to-front ordering. Assign new IDs to all copied pages and elements; the new note ID is assigned by LIB-03. All duplicate rows start at revision `0` with the target note, even if the source has a larger revision.
- Re-serialize an element envelope only to update its `id`; its other **semantic** content is preserved. Original JSON whitespace/key representation is not guaranteed identical after cloning, but document geometry/style values remain intact.
- Asset-backed PDF page backgrounds and image elements are **rejected**. No media is discarded, flattened or partially copied. This limitation must be removed only after coordinated asset/reference persistence is implemented.
- The current canonical format has no internal cross-element references requiring remapping; future reference-bearing formats require an explicit remapping policy before supporting duplication.

## Verification

`RoomNoteContentLifecycleIntegrationTest` uses real on-disk isolated Room databases to cover:

1. Production Quick Notes creation with a real first page and reopen durability.
2. Custom initial-page dimensions/templates; invalid and unsupported backgrounds roll back note metadata and Quick Notes provisioning.
3. Multi-page, mixed stroke/text/shape duplication: new IDs, equivalent editable content and ordering, source unchanged, reset target revision, and reopen durability.
4. A test-only fault adapter throws **after** real first-page or cloned content writes. Verify the outer LIB-03 transaction rolls back every newly created note/page/element and any newly provisioned Quick Notes.
5. Asset-backed and inconsistent raw DAO fixtures explicitly reject duplication without leaving partial rows.

Run `:app:assembleDebug`, `:app:testDebugUnitTest`, and `:app:connectedDebugAndroidTest` on the Android project. `git diff --check`; inspect new test counts/results before committing. Continue to use peer-reviewed PR with Android CI; do not merge solely on local success.

## Deferred beyond this checkpoint

Asset reference lifecycle, editing UI/save-status wiring, drawing/ink rendering, full-library backup/restore, PDF import/rendering, Trash purge and native Apple persistence are separate tasks. The current adapter supports complete duplication for **non-asset** content only; do not advertise V1 full-media duplication as complete.
