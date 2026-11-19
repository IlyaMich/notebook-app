# STO-08 Deliverable 1 — Consolidated Android ownership acceptance

Status: **implementation integration candidate; verification pending**  
Baseline: merged `main` `800ae57ed3309f213ba4ff28f4b3feb2682787bf` (PR #152)  
Candidate: `feature/sto-08-d1g-production-media-repository-integration`  
Scope: Android document/Trash ownership lifecycle only. **Not** export hold completion, GC eligibility, or Apple closure.

## Summary

PRs #147–#152 have merged transactional ownership changes for element removal and transfer, asset-backed page removal, confirmed permanent purge, PDF background owner release, verified image replacement, and indexed PDF/image note duplication. D1G exposes the existing leased verified-image insert/replace operations through the typed `DocumentRepository` contract and configures the production application-private files directory via `LibraryRepositoryFactory.from(context)`.

This does **not** add a media picker, editor image UI, PDF import, production export, export-hold release, global ownership-index verification, or cleanup. Production code still rejects generic image JSON insertion and replacement and generic PDF attachment. The verified media API remains explicitly guarded by original-byte inspection, the asset acquisition lease, expected revision and transactional owner updates. Standalone `LibraryRepositoryFactory(db)` deliberately remains unable to write media without a configured root.

## Acceptance matrix: one consolidated gate

| Supported case | Main implementation | Existing / new regression coverage | Runtime evidence |
| --- | --- | --- | --- |
| Verified image insertion and replacement, owner/revision atomicity | `RoomDocumentRepository` + `DocumentRepository` (D1G) | `InsertVerifiedImageWriterAndroidTest`, process-death suites | Pending D1G run |
| Image deletion and page transfer | PR #147 | `AssetOwnershipTransactionBoundaryAndroidTest` | CI passed for #147; aggregate runtime pending |
| Asset-bearing page deletion and PDF-background release | PRs #148, #150 | `AssetOwnershipTransactionBoundaryAndroidTest` | CI passed; aggregate runtime pending |
| Media-bearing note duplication, distinct IDs, shared original owner preservation | PR #152 | `Sto08AssetBackedNoteDuplicationAndroidTest` | CI passed; aggregate runtime pending |
| Confirmed batch purge, shared active/Trash owners, rollback and stale preview | PR #149 | `Sto08AssetAwarePermanentPurgeAndroidTest`, `Sto08MissingProjectionPurgeAndroidTest` | CI passed; aggregate runtime pending |
| Trash and restore retain indexed owners | Existing Room Trash lifecycle | `AssetOwnershipTransactionBoundaryAndroidTest` and Trash suites | Aggregate runtime pending |
| Production composition exposes verified operations, but no silent generic fallback | D1G | `InsertVerifiedImageWriterAndroidTest` configured/unconfigured factory regressions | Pending D1G run |

**Tests are not considered passed by compilation alone.** Verify the executed JUnit class counts and failure/error counts from instrumented test XML/results. Repeat on approved disposable API 26 and API 37 environments where available; never reset or install onto a protected device.

Suggested targeted Android instrumentation classes for one run:
- `com.notebook.app.data.content.InsertVerifiedImageWriterAndroidTest`
- `com.notebook.app.data.content.InsertVerifiedImageProcessDeathAndroidTest`
- `com.notebook.app.data.content.InsertVerifiedImageUncommittedProcessDeathAndroidTest`
- `com.notebook.app.data.local.AssetOwnershipTransactionBoundaryAndroidTest`
- `com.notebook.app.data.local.Sto08AssetBackedNoteDuplicationAndroidTest`
- `com.notebook.app.data.library.Sto08AssetAwarePermanentPurgeAndroidTest`
- `com.notebook.app.data.library.Sto08MissingProjectionPurgeAndroidTest`
- `com.notebook.app.data.library.RoomLibraryPermanentPurgeTest`
- `com.notebook.app.data.library.RoomLibraryTrashRepositoryIntegrationTest`

## Deliberate boundaries and fail-closed behavior

1. The normal editor UI does not yet offer insertion or replacement of managed media. D1G makes the verified API available to production callers, **not a new UI feature**.
2. Indexed duplication checks the canonical document model, durable edge and finalized metadata; it does not independently re-open/verify all source bytes and is not a GC authorization.
3. Export hold admission remains disconnected from a complete production export workflow; unreleased holds stay protected.
4. The global ownership gate remains `UNVERIFIED` / `UNRESOLVED`; `VERIFIED` is prohibited. No committed original deletion or garbage collection is enabled.
5. Apple implementation is absent from the current Android-focused repository. Joint issue #31 cannot be declared closed on Android evidence alone.
6. The source snapshot inspector and diagnostic owner inventory cannot independently permit deletion.

## Deliverable 1 exit decision

Only after D1G CI, targeted device regression execution, and production-call-site review should the Android *ownership-lifecycle implementation* be marked complete for its **supported** operation set. Explicitly document any unsupported media editor/UI and PDF creation paths. Do not mark overall STO-08 completed: Deliverable 2 requires protected export release and safe reclamation, and Deliverable 3 requires cross-platform/acceptance closure.

## Safety and repository handling

Protect all existing `C:\Proj\notebook-app*` worktrees, especially the research checkout with its uncommitted `android/gradle/libs.versions.toml`. Verify branch/base/HEAD and a clean separate worktree before running builds. No local source/DB/original-file deletion, no main writes, no `VERIFIED` promotion, hold release, or GC.
