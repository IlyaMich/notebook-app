# Notebook App - Library, Navigation & Local Data Architecture V0.1

**Date:** 2026-10-21  
**Status:** Architecture planning; library hierarchy confirmed, local storage proposals awaiting final review.  
**Compatible with:** Notebook Document Model V0.2 (provisional portable schema version 1).

## 1. Confirmed product and hierarchy decisions

- Native iPadOS (Swift/SwiftUI with UIKit where needed), Android tablet/phone (Kotlin/Compose). iPad primary. macOS development through rented cloud Mac; Android via Windows.
- Offline first. No V1 account, cloud synchronization, or audio recording.
- `Library → Folder (optional, nested) → Notebook → Note → Page → Editable Element`.
- Root-level notebooks are allowed. Folders can contain folders and notebooks, **not notes**. Every active note belongs to exactly one notebook. A default Quick Notes notebook enables immediate creation.
- Note pages are fixed-size, arranged vertically with continuous scrolling and free zoom/pan, clipping drawing to page boundaries.
- Notes use a structured editable document model. Lasso supports all editable types, within one page for V1. Cross-page movement uses cut/paste; direct cross-page lasso/drag deferred.
- User-defined hierarchical relationships are navigation metadata, not embedded in the portable note geometry.

## 2. Navigation contract

- iPad and large Android screens: hierarchical sidebar + notebook contents; editor uses maximal available screen space.
- Android phones: stack navigation (library → folder/notebook → note editor).
- `All Notes`, `Recent`, `Trash`, and `Search` are queries/views, **not duplicate documents**.
- Sort: last edited (default), creation date, title; stable tie-breaker by ID.
- Open note restores sensible position; view state is not canonical document content.
- Moving a note updates `notebook_id` only, not its page or element records.
- Reject moving a folder into itself or any descendant, including races with concurrent edits.
- One active app installation owns its local library; no cross-device ID reconciliation is required before sync but globally unique IDs prepare for it.

## 3. Storage topology

**Recommended:** a local SQLite database plus app-managed files. Portable `.notebook` exports remain a separate versioned archive (manifest, pages, assets) as defined by Document Model V0.2.

```text
App private storage/
  library.sqlite             # local canonical metadata and structured content
  assets/
    ab/cd/<sha256>.<ext>     # committed original PNG/JPEG/PDF assets
  staging/                    # temporary imports; crash-scannable
  exports/                    # ephemeral finished archives until handed to OS
  caches/                     # regenerable thumbnails and rendered pages
```

These are logical directories; map them to appropriate private app directories per OS. Never put irreplaceable originals into purgeable cache locations. Store only stable relative paths or asset IDs in DB, not absolute sandbox paths or long-lived external provider URIs.

### iPadOS and Android libraries

- iPadOS: SQLite via GRDB, Swift domain objects, protected app storage.
- Android: SQLite via Room, Kotlin domain objects, private app storage.
- Logical concepts and archive serialization must match; Room and GRDB physical schemas can differ.
- Foreign keys enabled; adopt WAL if supported and tested; explicitly choose durability policy after benchmarking. WAL sidecars are part of the live DB, so never backup by blindly copying an open `.sqlite` file.

## 4. Proposed logical entities and relationships

| Entity | Fields / constraints | Relationship |
|---|---|---|
| `folders` | `id`, `parent_folder_id?`, `name`, `sort_order`, `created_at`, `updated_at`, `deleted_at?` | Parent references folders; prevent cycles |
| `notebooks` | `id`, `parent_folder_id?`, `name`, `color?`, `sort_order`, timestamps, `deleted_at?` | Root-level or inside folder |
| `notes` | `id`, `notebook_id`, `title`, timestamps, `last_opened_at?`, `revision`, `deleted_at?` | Exactly one notebook, no direct folder relationship |
| `pages` | `id`, `note_id`, `order_index`, `width`, `height`, `background_payload`, `revision` | Ordered within note; mixed sizes allowed |
| `elements` | `id`, `page_id`, `order_index`, `kind`, `payload`, `revision` | Ordered within page; payload includes canonical geometry/transform |
| `assets` | `id`, `sha256`, `mime_type`, `byte_size`, `relative_path`, `state` | Originals owned by app, potentially deduplicated |
| `asset_references` | `asset_id`, owner (`note/page/element`), owner identifier | Tracks live references for deletion/GC |
| `app_metadata` | local `db_schema_version`, device-local settings | Distinct from portable note schema version |
| `search_index` | title / typed text index | Derived and rebuildable |

**Keys and indexing:** globally unique IDs (UUID); foreign-key indexes; indexes for `parent_folder_id`, `notebook_id + deleted_at + updated_at`, `page.note_id + order_index`, `elements.page_id + order_index`. Enforce unique sibling `order_index` after transactional renumbering; equal display names are allowed initially. Persist ordered element/page arrays semantically when exporting. Normalize timestamps to UTC.

**Element payload format:** preserve the Document Model V0.2 canonical typed envelopes and geometry rules. For V1, a compact serialized JSON (or versioned binary if measured necessary) per element is simpler than tables for every sampled stroke point. `bounds` are derived/cached rather than an independent authoritative property. Store viewport zoom/pan in an optional per-device navigation table/preferences, not in element transforms.

## 5. Folder operations and Trash

- Create, rename, move, reorder, and soft-delete folders/notebooks/notes transactionally.
- Deleting a folder places its subtree in Trash without destroying pages or assets. The tree's deletion batch and original parent associations should be recoverable.
- Restoring a subtree recreates its location when possible; if an ancestor was permanently deleted, restore to root or an explicitly chosen destination.
- Permanent deletion is user-confirmed and may be asynchronous; transactional DB deletion precedes asset GC.
- **Proposed V1 rule:** Trash is emptied explicitly by the user; no automatic timed purge. Prevent silently losing notes due to expiry.
- Importing a standalone note creates it in a chosen/default notebook; its original notebook path is not required to exist.

## 6. Editing, revisions, and autosave

- UI and ink engine construct in-progress gestures in memory. No per-pointer-move database writes.
- Completed strokes/shape operations and mixed transforms emit one command; commit through an isolated per-document mutation queue or equivalent serialization.
- Persist each completed command or a bounded coalesced batch transactionally. Maintain monotonically increasing note `revision`, increment on successful commit.
- Undo/redo stacks are in-memory only, but undo/redo each produce ordinary durable inverse document mutations.
- Text can be buffered/debounced briefly; flush promptly on edit completion, navigation, and lifecycle transitions. Background execution is **best effort**, not an absolute guarantee.
- Expose `Saving / Saved / Save failed` based on the last successfully committed revision. Never present uncommitted input as safely saved.
- When storage is full or a write fails, preserve pending edits in memory where feasible, display actionable error state, and retry. No silent drop or false success.
- Database operations must not block the stylus rendering path.

## 7. Filesystem + SQLite crash consistency

SQLite transactions do not atomically include arbitrary filesystem operations. Use a crash-tolerant two-phase reference protocol:

**Importing an image/PDF:** (1) copy to private `staging/`; (2) validate MIME, bounds/size, hash; (3) ensure immutable committed asset bytes exist in final content-hash path via safe same-volume rename/replacement; (4) within one SQLite transaction add `assets`, references, and document modifications; (5) cleanup abandoned staging and unreferenced finalized files later. If the app dies before DB commit, it may leave an **orphan** file that GC can remove, but a committed note must never reference a nonexistent file.

**Deleting assets:** commit reference removal in DB before deleting a now-unreferenced file. A crash may leave removable orphan data, never a dangling reference in committed document content. Prevent GC while live exports/imports hold references.

**Startup recovery:** scan bounded staging work, reconcile asset references/file presence/hashes, retain intact committed data, quarantine suspicious or missing assets, report rather than silently erase broken notes. Log recovery failures without leaking user contents.

**Export:** read a consistent snapshot of note/page/element state; pin/copy referenced assets before packing; write to temporary archive; validate package; replace/rename into user destination only after successful completion. Export never mutates the canonical note.

## 8. Portable backups

- V1 minimum: export and reimport individual editable `.notebook` documents; separate PDF export is presentation-only and may flatten.
- **Strong V1 recommendation:** support a user-initiated **full-library backup archive** containing the hierarchy, note packages and assets, with a tested restore pathway. This is especially important because there is no app-level sync or account backup in V1.
- Full-library backup design needs its own versioned manifest and consistent snapshot; avoid simply copying a live WAL SQLite database without its WAL and sidecars. Use logical export or SQLite Backup API plus coordinated asset snapshot.
- Offer explicit files-provider/share-sheet destinations (iPadOS Files; Android Storage Access Framework); no automatic cloud sync is implied by writing to a user-chosen cloud-backed folder.
- Reimport conflict policy: individual `Import as new` creates new note ID; duplication of internal page/element IDs is resolved while preserving internal references. Restore full-library archive may offer `replace library` only after verifying backup and giving a strong warning, or merge with well-defined collision rules. Prefer simpler safe modes in V1.
- Test actual export → delete app → reinstall → restore path; device backups from OS are not a substitute for manual portable export.

## 9. Security and privacy baseline

- Store originals and SQLite in private app storage, benefiting from platform sandboxing and device protection/encryption; document required device state for file protection on iOS.
- Do not request broad storage permissions to work with a user-selected external document. Restrict imports to supported MIME/types and validate ZIP/JSON/PDF/image content.
- Refuse `../`, symlinks, absolute paths, malformed archives, oversized decompression, invalid hashes, duplicate archive entries, unsupported schema versions, and excessive element counts. Use resource limits and cancelable parsing.
- Thumbnails and text search indexes contain user data; keep them private and deletable. Telemetry, if introduced, must not send note contents by default.
- Full-document encryption beyond OS protection is an optional future product decision, not an implied V1 guarantee.

## 10. Search and cache policy

- Query note titles, folder/notebook names, and typed-text elements. No handwriting recognition/OCR in V1.
- SQLite FTS5 is an option once native packaged SQLite capability is verified on supported OS versions; maintain an ordinary basic query fallback. The index is derived and rebuildable.
- Note previews/thumbnails and rendered PDF/ink tiles are **caches**, not canonical data. Cap cache size and safely regenerate after eviction.
- Avoid eager loading all pages/elements when browsing library; load note metadata for lists, visible pages for editor.

## 11. Database migrations and schema/version separation

- Maintain two independent version systems: internal **database migration version** and portable **`.notebook` schemaVersion**.
- All migration steps are explicit and tested with old fixture databases on both native platforms. Never use destructive migration/fallback on production user notes.
- Before irreversible migrations, create an appropriate recovery snapshot or provide a rollback/repair strategy; verify low-storage paths.
- `updated_at` tracks content edits and meaningful metadata changes; `last_opened_at` tracks recency separately; increasing `revision` helps local change tracking and possible future sync. It is *not* by itself a multi-device conflict resolution protocol.

## 12. Reliability acceptance tests before V1 release

1. App killed during drawing / after gesture end / during DB commit: last acknowledged durable revision preserved; pending state never reported as saved.
2. App killed during PDF/image import at each staging boundary: no dangling references; orphan files eventually reclaimed.
3. App killed during export: original note unchanged; no partial archive mistaken for finished export.
4. Disk-full errors, invalid MIME, corrupted asset, schema mismatch, malformed ZIP: visible failure, no existing note damaged.
5. Undo/redo after autosaves, move/duplicate/reorder, delete/restore folder subtree: exact expected hierarchy/content.
6. Long notes with thousands of strokes / hundreds of pages: bounded memory and responsive browsing; measurable performance budget to be set on actual devices.
7. Cross-platform `.notebook` import/export round trips preserve geometry, ID references, page order, assets and styles; ink fidelity evaluated later.
8. App update migrates existing DB; downgrade/unsupported format does not silently discard data.
9. Full-library backup/restore (if included): verify folder hierarchy, notes, pages, assets and search rebuild; compare hashes/counts.
10. OS backup policy and data-protection directories audited; thumbnails must not be required for recovery.

## 13. Decisions and next actions

**Confirmed:** structured folder/notebook/note hierarchy, root-level notebooks, page-local selection, local-first note documents, portable cross-platform document model, engine evaluation deferred.

**Recommended, not yet product-confirmed:** explicit-user-only Trash purge; full-library export/restore in V1; root Quick Notes notebook; FTS5 when available; revisions + status model; content-addressed immutable originals; initially exclude end-to-end custom encryption.

**Next technical deliverables:** SQL schema migration V1; Swift GRDB repository contract; Kotlin Room entity/DAO contract; portable note importer/exporter interface; asset staging/recovery flow; reliability test fixtures; backup/restore UX.

## Authoritative technical references

- SQLite WAL: https://www.sqlite.org/wal.html
- SQLite transactions: https://www.sqlite.org/transactional.html
- SQLite backup: https://www.sqlite.org/backup.html
- SQLite FTS5: https://www.sqlite.org/fts5.html
- Android Room database migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Android backup overview: https://developer.android.com/identity/data/backup
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Apple backup directories: https://developer.apple.com/documentation/foundation/optimizing-your-app-s-data-for-icloud-backup
- Apple file replacement: https://developer.apple.com/documentation/foundation/filemanager
