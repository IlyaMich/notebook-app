# STO-08 D2J-B — Read-only Room v7 export-attempt history in the note editor

**Baseline:** `main` `1c5ab9aa45f0cf6416081e46108b115419dcdcc5`, exact squash merge of tested D2J-A PR #166.  
**Issue:** [STO-08 #31](https://github.com/IlyaMich/notebook-app/issues/31) (remains OPEN).  
**Scope:** Android-only, bounded read-only persisted export history; no schema migration, new export operation, user-created SAF document, external file access, hold release, original-file removal, or GC.

## Reason for this checkpoint

D2J-A creates production Room-v7-bound export operations and immutably records one external attempt plus at most one matching historical readback. Its reusable coordinator is not yet invoked by production UI. D2I manually tested the Android file picker on disposable emulators but used a separate debug-only SharedPreferences fixture; that diagnostic must never be confused with Room v7 data. The existing editor has autosave, pending revisions and navigation state that make directly enabling user export unsafe without a separate admission and lifecycle UX review.

**D2J-B exposes only the existing app-local evidence.** The editor has an **Export history** action which opens a read-only dialog. There is no new export/prepare button and no actual picker callback.

## Read-only data flow

1. Only the ordinary production `NotebookApp` wires `ExportNoteHistoryReader(AppDatabaseProvider.get(context), ...)` into the editor. Existing navigation tests with `repositoryOverride` receive no production DB callback, avoiding unrelated `library.sqlite` access.
2. `ExportNoteHistoryReader.inspect(noteId)` accepts only canonical UUIDs, retrieves at most **21** newest operations for a **20-row ceiling** through a new `@Query` on the existing DAO, and returns `IncompleteUntrusted` on overflow, invalid data or database failure.
3. The reader observes one outer Room read transaction; nested `ExportExternalAttemptJournal.inspectReadOnly` classifies the immutable per-operation v7 history. It never reads source files, private export archives, original bytes, provider URIs or URI grants.
4. The UI displays the bounded timestamp, shortened operation ID and conservative state. Historical observations are not a live provider status and cannot independently grant completion.
5. The UI offers **Close only**. No button to retry, resume, repair, remove files, release holds or mark export complete. Room may append an attempt or readback after a snapshot, so reopening the dialog is an explicit fresh read, not automatic background reconciliation.

### Status semantics

| UI status | Interpretation | Forbidden conclusion |
|---|---|---|
| `NO_ATTEMPT_RECORDED_UNTRUSTED` | No v7 external attempt observed for the given operation at inspection time | This **does not** prove no external file exists; especially relevant for older v5/v6 operations |
| `ATTEMPT_PENDING_UNTRUSTED` | Immutable attempt exists without a matching recorded readback | Provider could contain no bytes, partial bytes or complete bytes; no auto-retry for this operation |
| `READBACK_OBSERVED_UNTRUSTED` | Matching bytes once reread from the selected provider | Not atomicity, independent power-loss durability, cloud persistence, portable restoration or a completion receipt |
| `INCOMPLETE_UNTRUSTED` | Inconsistent operation/binding/hold/journal observations | Retain all protected source originals, no automatic repair |
| `IncompleteUntrusted` report | Invalid UUID, broken scan or resource limit | No inferred count, empty-history claim, cleanup or deletion |

An empty successful history means only that *this local Room v7 scan returned zero matching operations*; it does not imply that other devices/providers or old exports have no bytes.

### Explicit non-activation

The user cannot initiate a new export from this checkpoint. **No production `ACTION_CREATE_DOCUMENT` callback is installed.** The existing D2J-A `prepareNew` is not called by this UI, so viewing history creates **no new immutable holds**, private archives or provider documents.

The 20-row bound is intentionally conservative. When exceeded, the UI says history is incomplete instead of showing misleadingly partial data. The reader uses the broadest allowed history-only internal stage limits; this is **not** an approved writer quota.

## Acceptance

- GitHub Android CI and Shared Format CI green on exact head.
- Compile `:app:assembleDebugAndroidTest` in a separate clean worktree, verify exact diff; no emulator installation in Compile mode.
- **8** new isolated Room v7 Android tests: empty history, bad ID, no attempt, pending after Room reopen, matching historical readback after reopen, note-scope isolation, plus-one overflow, legacy operation order/safety.
- **6** new Compose Android tests: pending, readback, no attempt, incomplete, empty and close-only action.
- Run **14/14** on explicitly verified disposable API 26 and API 37. Consider targeted earlier D2J-A and D3-A regression classes if feasible, but never infer device success from compilation alone.
- Review production NoteEditorScreen / NotebookApp UI integration for no fallback connection to real app database with injected isolated repository.
- No original deletion, `VERIFIED`, export hold release, external provider cleanup, automatic retry, stage adoption or GC.

## Remaining gates for full STO-08 closure

1. **D2J-C:** explicit UI export admission and fresh SAF picker callback tied to a verified saved revision; durable operation-ID display and process-death behavior; no silent retries, no misleading success claim, no new indefinite holds without a reviewed product policy.
2. **Destination trust:** independently durable/visible portable backup proof or a deliberately nonreleasing destination class, plus archive import conformance.
3. **Hold release and ownership certification:** separately reviewed safety protocols and migrations; current immutable SQL triggers prevent release and `VERIFIED` promotion.
4. **GC and Apple:** safe check-to-unlink and metadata retirement if explicitly approved, S08-01–S08-15 consolidated evidence, and Apple STO-03 cross-platform acceptance.

This checkpoint is a **practical read-only UX step**, not evidence that live export is ready for users.
