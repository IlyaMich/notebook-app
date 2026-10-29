# STO-07 — Local Database Migration and Recovery Contract V0.1

**Status:** Proposed for joint Android/Apple review; not evidence of completed STO-07 acceptance  
**Date:** 2026-10-29  
**Issue:** [STO-07 (#30)](https://github.com/IlyaMich/notebook-app/issues/30)  
**Prerequisites:** STO-05 (Apple autosave) and STO-06 (Android autosave) for full acceptance  
**Scope:** Native local SQLite storage, user-visible save acknowledgments, and coordination with private filesystem assets. The portable `.notebook` archive format has separate versioning and compatibility rules.

## 1. Authority, baseline, and non-goals

This contract refines, but does not replace:

- `docs/product/notebook_v1_prd_and_delivery_plan.md` (R-DAT and release durability requirements).
- `docs/architecture/notebook_library_storage_plan_v0_1.md` (sections 3, 6, 7, 8, 11, 12).
- `docs/architecture/notebook_document_model_spec_v0_2.md` (sections 2, 10, 12).
- `docs/architecture/notebook_library_schema_contract_v1.md` and `docs/decisions/0001-library-hierarchy-and-trash-semantics.md` (ownership and Trash invariants).

**Verified Android baseline at `33d6f24`:** Room `AppDatabase` version 2; versioned schema exports `1.json` and `2.json`; explicit `MIGRATION_1_2`; production `AppDatabaseProvider` has no destructive migration fallback; `AppDatabaseMigrationV1ToV2Test` covers migration of active and trashed notes. The Android instrumentation suite passed on the Pixel Tablet emulator on 2026-10-29. On the STO-07 feature branch, the additional M02/M03/M04 migration assertions were reported passing locally on 2026-10-29; they have **not** yet been peer-reviewed or merged. These facts do **not** prove process-death, disk-full, or Apple recovery acceptance.

**Out of this checkpoint:** selecting an ink engine, building an Apple app, implementing STO-05/STO-06 autosave UI, designing portable library backup format, introducing an asset repository, and bumping the Room schema version merely for tests.

## 2. Versioning and compatibility

1. **Independent versions.** The SQLite schema version is maintained by each native database implementation. The `.notebook` archive `manifest.schemaVersion` is independent. The planning-document version (e.g. V0.2) is neither of these. Android Room and Apple GRDB physical schema versions need not numerically match.
2. **Upgrade.** Every released source schema version requires a reviewed, explicit, tested path to the supported target version. Preserve all user-controlled IDs, hierarchy/ownership, pages, elements, ordering, note revisions, timestamps, Trash/deletion snapshots, and committed asset references; deliberate transformations must be documented.
3. **No destructive fallback.** Never use Room `fallbackToDestructiveMigration`, `fallbackToDestructiveMigrationOnDowngrade`, or equivalent GRDB database recreation on the user's library. A missing migration must fail without erasing the library.
4. **Unsupported newer database/downgrade.** An older application must not attempt to reinterpret, overwrite, or recreate a database written by a newer incompatible app. Refuse mutable access and show an actionable compatibility message. A read-only recovery mode may be added only after it is proven to preserve unknown fields and data. Do not promise automatic downgrade.
5. **Failed migration.** Run database schema/data transformations in an appropriate SQLite transaction. On migration error, opening must fail safely; retain the previous valid database state, or use a separately documented and validated recovery-snapshot strategy where a migration cannot be transactionally rolled back. Never silently create a blank replacement database.
6. **Before irreversible changes.** Design and test a consistent backup/recovery snapshot or a repair/rollback plan before introducing a genuinely irreversible migration. Copying a live SQLite file without accounting for WAL and sidecars is not a valid backup procedure.
7. **Historical tests.** Keep exported Room schemas in version control without modifying history. Construct fixture databases from authentic historical schema definitions, including triggers not represented in exported Room schemas, and run actual upgrade/downgrade-failure paths.

### Current Android V1 → V2 specifics

- V1 stored note metadata but had no page/element rows. The migration creates the page/element tables, then inserts **one blank A4 page** for every existing note, including notes in Trash.
- The generated page ID is new and unique; the existing note ID, revision, update timestamp, notebook ownership, and Trash status must remain unchanged. The new page revision equals the pre-migration note revision.
- Existing hierarchy and safety triggers must survive. Migration of V1 metadata is not a user edit and must not increment `notes.revision`.
- The existing migration test is a starting point, not a complete migration-failure suite.

## 3. Save acknowledgement and revision semantics

For an editing session, distinguish three values/concepts:

- **Editing state:** in-memory changes made by the user, including a completed command not yet written to SQLite.
- **Pending revision/command:** a completed logical edit queued for persistence, but not yet confirmed committed.
- **Committed revision:** the latest revision read from or successfully committed to the canonical local database.

The UI must follow this state model:

| State | Entry condition | User-facing meaning |
|---|---|---|
| `Unsaved` / `Pending` | A completed edit exists that has not committed | Changes may not survive termination |
| `Saving` | Persistence work is in progress | No success has been acknowledged |
| `Saved` | All edits covered by this status have committed successfully, and no newer pending edits remain | The displayed version has been stored in the local database; **not** a cloud/backup claim |
| `Save failed` | A write/flush failed or cannot be confirmed | Preserve the pending edit in memory where feasible, retain last committed content, and give an actionable retry path |

A command that succeeds in memory is **not** saved. A database operation must report success only after its transaction has completed successfully, and the UI must update the saved revision only then. When a new edit arrives while a prior commit is finishing, an acknowledgement for the older revision may advance the committed-revision counter but must **not** change the overall status to `Saved` while a newer edit remains pending.

A successful user edit increments the note revision **once per committed logical command or documented coalesced transaction**. Failed/rolled-back commits do not advance the committed revision. Undo/redo of persisted content are ordinary new commits, not a rollback of saved history. Serialized commands or equivalent conflict control must prevent stale completion callbacks from overwriting newer state.

Retry must be **idempotent at the data boundary**: retrying the same pending operation after uncertain outcome must not duplicate pages/elements or increment the committed revision twice. Use stable entity IDs and, when necessary, a durable operation identity/revision check. The precise mechanism belongs to STO-05/STO-06 implementation review; this contract does not introduce a second persistence layer.

SQLite commit success is the current application-level acknowledgement boundary. Whether the selected SQLite journal/synchronous settings also support stronger **power-loss durability** is a separate implementation decision requiring configuration inspection and hardware/OS testing. A passing transaction test or process-death test alone does not prove sudden-power-loss durability.

## 4. Crash and interrupted-operation rules

- **SQLite-only mutation:** create/update/delete operations touching related note, page, element, revision, and Trash rows must be one transaction where the logical command requires atomicity. A crash before commit leaves the prior committed state; a crash after commit preserves the new committed state. Never show success before the commit.
- **App exits with pending edits:** request a flush on navigation/lifecycle transitions where possible. Suspension and forced termination are not guaranteed flush opportunities. If pending work is lost, it must not previously have been shown as `Saved`. Reopening displays the last committed revision and any separately proven recoverable work.
- **Migration interruption:** opening the database must not produce a partially upgraded, writable library; a failed/open-interrupted migration is retried or rejected safely according to the validated transaction/recovery plan.
- **Filesystem imports:** SQLite transactions do not include filesystem rename/write/delete. Asset import uses: stage bytes → validate/hash → install immutable final bytes safely → commit database asset and owner references → clean abandoned staging/orphans later. A committed owner must never depend on missing staged bytes.
- **Asset deletion:** first commit removal of references, including references from Trash; delete an unreferenced original only afterward, with concurrency guards against imports/exports. Crash leftovers may be reclaimable **orphans**, never justification for removing still-referenced assets.
- **Startup reconciliation:** bound any scan; inspect incomplete staging/finalized orphans and reference-to-file integrity as appropriate. Missing/corrupt referenced assets should be reported/quarantined, not fixed by deleting notes or silently replacing original bytes. Cleanup must not race active operations.
- **Export and backup:** read a consistent database state and pin/copy assets, write a temporary archive, validate it, and publish it only on success. Never treat a partially written archive as a valid backup. Do not directly copy an open SQLite database as a full backup.

**Implementation status:** the above asset protocol is an agreed architectural target, **not** an assertion that Room V2 already contains asset tables, filesystem integration, or a startup reconciler. Integrate with STO-08 and portability tasks rather than inventing a parallel asset subsystem in STO-07.

## 5. Failure handling and user-visible behavior

| Failure | Required behavior |
|---|---|
| SQLite disk full, I/O error, transaction abort | Never show `Saved` for the failed edit; retain previously committed data; show persistent `Save failed` with retry/space guidance. |
| App termination during pending edit | Restore the last committed revision; do not infer success from a queued in-memory command. |
| Missing migration/unsupported newer DB | Abort mutable access and preserve library files; show compatibility/recovery guidance, not an empty new library. |
| Migration fails partway | No partly upgraded user-visible state; surface failure and retain a validated rollback/recovery path. |
| Missing or corrupt committed asset | Preserve note/page/element metadata; surface a specific recoverable problem; no silent content deletion. |
| Failed stage/rename | Never commit a document reference to bytes that were not safely installed. Clean safe leftovers later. |
| Corrupt SQLite database | Fail non-destructively, preserve available files for diagnosis/backup/repair, and do not auto-reset the library. |

Errors must remain visible beyond a transient toast or color alone. Recovery actions must be labeled accurately; `Retry` must not imply that an unknown outcome has been safely deduplicated until that behavior is tested.

## 6. Platform responsibilities and current implementation status

### Android (Room/SQLite)

- **Exists:** `AppDatabase` V2; `MIGRATION_1_2`; exported schemas; `AppDatabaseProvider` without destructive fallback; repository-level transactional document operations; historical V1→V2 instrumentation test.
- **Locally verified on STO-07 branch (pending review/merge):** unsupported-newer-version open rejection with preservation of sampled records; V1 hierarchy/Trash and foreign-key checks; injected migration failure rollback and successful V1→V2 retry.
- **To verify or implement:** STO-06 save-state/queue/retry; genuine forced-process-death and controlled low-storage evidence; STO-08 asset coordination. Existing migration tests do not establish these behaviors.
- Use isolated test databases and do not clear, reset, or replace the user's actual app library to make tests pass.

### Apple (GRDB/SQLite — deferred)

- Register ordered, immutable, named migrations; do not use automatic recreation as a downgrade/error response.
- Implement equivalent preservation, revision acknowledgement, failed-write, crash recovery, staged-asset, and compatibility semantics against **Apple's actual schema**, not by copying Android table layouts verbatim.
- Add real GRDB upgrade/unsupported-version/rollback fixtures and Apple process-kill/low-storage simulations once the native implementation exists.
- Treat current Apple requirements as contract-only until tests run on its implementation.

## 7. Acceptance evidence matrix

| ID | Scenario / invariant | Minimum appropriate evidence | Status on STO-07 branch (2026-10-29) |
|---|---|---|---|
| M01 | V1→V2 preserves active/Trash metadata, revisions, pages, and Quick Notes guard | Existing Android migration instrumentation test | Passed locally; pending review/merge |
| M02 | More complex folder/notebook/Trash snapshots and FK checks survive V1→V2 | Expanded `v1MetadataSurvivesAndLegacyNotesReceiveOneInitialPage` instrumentation test; SQLite foreign-key/integrity checks | Passed locally; pending review/merge |
| M03 | Unsupported newer database is rejected without loss of tested records | `unsupportedNewerDatabaseIsRejectedWithoutDataLoss`: isolated V3 database rejected by V2 provider; note/page/future marker and integrity checked | Passed locally; pending review/merge; **not** a byte-for-byte file comparison |
| M04 | Injected migration failure rolls back to valid V1 and later upgrade succeeds | `failedMigrationRollsBackAndNormalRetrySucceeds`: throw after real migration SQL inside Room upgrade transaction; inspect V1 before retry | Passed locally; pending review/merge; **not** an actual process kill |
| S01 | Acknowledged revision survives relaunch | Android integration + forced process-death/reopen test | Depends on STO-06 |
| S02 | Uncommitted edit is never falsely `Saved` | Save-state unit tests + instrumentation | Depends on STO-06 |
| S03 | Disk full/commit failure surfaces persistent error; retry is nonduplicating | Deterministic injection + controlled emulator low-storage scenario | Depends on STO-06 |
| A01 | Crash between asset-file and DB boundaries never leaves a committed dangling reference | Boundary injection + filesystem/DB integrity check | Depends on STO-08 |
| A02 | Missing/corrupt asset does not erase existing notes | Recovery instrumentation/fixtures | Depends on STO-08 |
| X01 | Apple matches logical semantics | GRDB tests + Apple process/degradation scenarios | Deferred / STO-05 |

Test reports must identify whether evidence is a **pure unit simulation**, **Android instrumented test**, **actual forced process death**, **controlled low-storage condition**, or **Apple test**. One type is not a substitute for another. Use synthetic notes only.

**M01–M04 evidence note (2026-10-29):** The developer reported successful `:app:connectedDebugAndroidTest` execution for the migration class on a Pixel Tablet API 37 emulator and confirmed `failedMigrationRollsBackAndNormalRetrySucceeds` appeared in the generated XML report. The injected exception is a deterministic rollback test; it does not demonstrate physical process termination, power loss, or filesystem recovery. M03 checks logical records and SQLite integrity after refusal, not byte-for-byte unchanged database files. STO-07 remains open.

## 8. Open decisions for peer review

1. Confirm Android/Apple SQLite journal and `synchronous` policy; specify process-death vs. power-loss guarantee and test method.
2. For an irreversible future migration, choose and validate a consistent recovery-snapshot/rollback mechanism before shipping it.
3. Decide the exact unsupported-schema UI message and whether lossless read-only mode is feasible (not assumed).
4. Agree how autosave handles uncertain outcome and idempotent replay without introducing duplicate revisions.
5. Align asset staging/GC ownership with STO-08 and full-library backup/restore tasks.

**Completion rule:** This document plus passing existing Android tests constitutes contract progress, **not** completion of STO-07. The GitHub issue remains open until the exact cross-platform acceptance criterion — documented upgrades/downgrades and non-false acknowledgements under killed-app and low-storage simulations — has been demonstrated on both implementations or the issue scope/dependencies have been formally changed by the team.
