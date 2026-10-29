# Android M1 — Library UI-to-Room vertical slice, Checkpoint 1

## Why

STO-02 was merged after LIB-03. The existing `LibraryScreen`, `NotebookContentsScreen` and `NoteEditorScreen` still use sample IDs and placeholder data. M1's first persisted note should be demonstrable through the running Android app, not just repository instrumentation tests.

## This checkpoint

- Root-level notebooks are read from the existing production `LibraryRepositoryFactory`.
- Quick Notes is provisioned using `ensureQuickNotes()`; no hardcoded note/notebook IDs.
- A user can create a root-level notebook and open it.
- A user can open a notebook, list its real active notes and create a note through `NoteLifecycleRepository.createNote`, which creates the initial persisted page atomically.
- The editor route uses the real note ID and reads the durable `DocumentRepository.load()` snapshot. It is deliberately **read-only** and explicitly labeled as a preview, not a functioning editor or save-status implementation.
- Reopening the app demonstrates persistence and the same note/page IDs.
- Repository failures show a user-visible message. No fallback to sample data or metadata-only note creation.

## Deferred

Nested folder navigation/creation, notebook and note rename/move/duplicate UI, recent/all notes, Trash, search, editing gestures, canvas rendering, save-status UI, and full-library backup. No new Room tables or migrations.

## Verification gate

1. Build, unit tests and connected Android instrumentation tests all pass.
2. On an emulator, launch app: Quick Notes exists and is clickable; no sample Mathematics notebook unless user-created.
3. Create a notebook, enter it, create an untitled note, and see one persisted page with revision 0 in read-only preview.
4. Back out, force-stop app, relaunch, reopen the same notebook/note: note and page ID still match.
5. Ensure no `1.json`/`2.json` changes, no database reset, and no duplicate Quick Notes after relaunch.
6. Review with the peer developer before merging.

The checkpoint intentionally avoids guessing a GitHub issue number. Associate with the existing Android library UI issue if present, or create a scoped issue before merging.
