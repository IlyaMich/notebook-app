# ADR-0001: Library hierarchy, ownership, and Trash semantics

- **Status:** Proposed
- **Date:** 2026-10-28
- **Decision makers:** Apple and Android developers
- **Related tasks / issues:** LIB-01 #16; prerequisite FMT-01 #11; downstream LIB-02 #17 and LIB-03 #18

## Context

V1 supports root folders and notebooks, recursively nested folders, notes belonging to exactly one notebook, and ordered pages and elements. The canonical portable `.notebook` format does not encode the device's folder/notebook path. The library is offline-first, with native SQLite implementations on both platforms.

SQLite foreign keys enforce *existing* owners, but cannot alone prevent folder cycles, require active parents for active children, or preserve independently trashed descendants while permanently deleting an ancestor. A coherent cross-platform rule set is needed before Room/GRDB repositories are built.

This ADR proposes decisions D-01 through D-07 from `docs/architecture/notebook_library_schema_contract_v1.md`. It does **not** approve a physical schema, complete FMT-01, or authorize production migration.

## Proposed decision

### 1. Ownership and default notebook (D-01)

- Folders have an optional parent folder; notebooks have an optional parent folder; notes have exactly one notebook owner; pages have one note; elements have one page. A folder cannot directly own a note.
- Use UUID identities. Ordinary moves, renames, soft deletions, and restoration preserve entity IDs. Import-as-new or duplication remaps IDs when necessary.
- Provision exactly one distinguished Quick Notes notebook per local library. Record its role structurally (for example, `system_role = 'quick_notes'`), **not** by matching its display name or hardcoding a UUID.
- Protect Quick Notes from movement into folders, soft deletion, and permanent deletion. Keep it at the library root. Whether to permit renaming its displayed name is left for UI review; repository behavior must never depend on that name.
- Creation of a note outside a selected notebook uses Quick Notes. Provisioning and full-library restore must preserve exactly one distinguished instance without dropping restored user notes.

### 2. Explicit Trash lifecycle (D-02)

- A delete-to-Trash operation is soft deletion: it does not delete pages, elements, or referenced original assets.
- No age-based automatic permanent purge in V1. Permanent deletion is initiated explicitly by the user with confirmation identifying the affected content.
- The Trash view is computed from deletion metadata; it does not contain duplicate note/document rows.

### 3. Current ownership and deletion snapshots (D-03)

- Keep normal parent/owner columns as **foreign keys** (for example `parent_folder_id` and non-null `notebook_id`).
- At each active-to-trashed transition, capture a **historical snapshot** of the item's immediate parent/owner UUID and its previous sibling position. Snapshot IDs are not foreign keys: the original parent may later be permanently deleted.
- Proposed fields for folders and notebooks: `deleted_at`, `deletion_batch_id`, `original_parent_folder_id`, `original_sort_order`. For notes: `deleted_at`, `deletion_batch_id`, `original_notebook_id`, `original_sort_order` if notes have user-defined order.
- A snapshot parent of NULL denotes original root placement for folders/notebooks. Use the item's deletion state to distinguish an untouched record from a root snapshot.
- Already-trashed rows retain their original snapshot and batch when a different ancestor is subsequently deleted. Do not silently reset their deletion history.
- Historic snapshot IDs are restoration hints, never proof that a current owner exists. Check current ownership and active state inside the restoration transaction.

### 4. Delete, restore, and permanent-purge transactions (D-04)

- Soft-delete an active folder and its **currently active** descendants in one write transaction, assigning one new deletion batch UUID only to rows changed by that operation. Independently trashed descendants keep their earlier batches.
- Restoring a deletion batch restores only its members. Preserve valid internal parent-child relationships among restored members. If an original parent is gone or still trashed outside the batch, folders/notebooks fall back to the root (or an explicitly chosen active folder); notes fall back to an explicitly selected active notebook or Quick Notes.
- Any restore that cannot establish valid active ownership must fail atomically and leave all batch members in Trash. Never silently omit a batch member or resurrect a separately deleted descendant.
- Permanently purging a deleted subtree removes only content explicitly included in the confirmed purge. If an independently trashed descendant must survive, detach/rehome it **within the same database transaction before its current parent is removed**: folders/notebooks may become trashed roots (`parent_folder_id = NULL`); notes are reassigned to the protected Quick Notes notebook while remaining trashed. Preserve their original-parent snapshots and deletion batches unchanged.
- If the surviving data cannot be safely rehomed, reject the purge with no partial data loss. Hard-delete database content before reference-aware asset garbage collection; never delete an original asset while any committed content (including Trash) references it.
- SQLite writes for moves, delete, restore, rehome, and purge are serialized and transactionally atomic. A folder move checks ancestry inside the transaction before updating ownership; concurrent writes must not create a cycle.

### 5. Ordering and document boundaries (D-05, D-06, D-07)

- Initial V1 library hierarchy uses separate folder and notebook `sort_order` columns within a given parent, rather than a shared interleaved order. UI may group folders before notebooks; display sorting by last edited, creation time, or title remains available according to the PRD.
- Store `created_at`, `updated_at`, `deleted_at`, and `last_opened_at` (where applicable) as UTC Unix epoch **milliseconds** in signed 64-bit integers in local SQLite. Convert to the portable schema's RFC3339 timestamps when exporting. Do not assume wall-clock timestamps are globally monotonic.
- Page and element order indexes are zero-based, unique within their owner, and dense after a successful reorder. Reordering uses a safe transactional algorithm that avoids transient unique-key collisions. Library order ties use stable ID tie-breaking; a precise manual-order renumbering rule can be specified alongside the SQL schema.
- A live note must contain at least one page. Creating a note and its first page is atomic. Reject attempts to delete its last page unless deleting the note itself. Page/element geometry remains in the document-content domain, not in folder/notebook metadata.

## Alternatives considered

| Topic | Alternative | Why not preferred for V1 |
|---|---|---|
| Quick Notes | Identify by name or constant UUID | Breaks on rename, import, or restore across devices |
| Trash expiry | Automatically purge after N days | Adds avoidable offline data-loss risk |
| Original location | Retain only parent foreign keys | Parent purge can destroy original-location information or block safe recovery |
| Deletion grouping | Restore every descendant currently marked deleted | Resurrects independently deleted items |
| Purge with retained descendants | Cascade-delete everything or reject all such purges | Cascade loses user content; unconditional rejection complicates cleanup |
| Sibling order | Shared mixed folder/notebook order | Needs additional global sibling-position model and reordering rules |
| Timestamp storage | Store local-time strings | Ambiguous across time zones and less convenient for indexing |
| Final page | Allow empty active note | Conflicts with the provisional portable manifest's minimum-one-page rule |

## Consequences and tradeoffs

- Additional deletion metadata and transaction code are required, especially when a parent is permanently purged but independently trashed descendants must survive.
- A current ownership FK and an original-parent snapshot can legitimately differ while an item is in Trash; tests and UI should distinguish them.
- Quick Notes becomes a protected root-level recovery destination. It must be provisioned and kept available during library restore and migrations.
- Restored items might land at root or Quick Notes when their historical parent has disappeared. This must be visible to the user, not presented as an exact original-location restoration.
- This proposal does not define a complete full-library backup manifest, native Room/GRDB implementation, asset filesystem protocol, or final portable wire schema.

## Verification / review plan

1. Review this ADR jointly against the PRD and `notebook_library_schema_contract_v1.md`; explicitly accept or amend D-01 through D-07.
2. Confirm FMT-01's required ID/page/element ownership and version boundaries, or record a documented dependency exception before closing LIB-01.
3. Create a reviewable SQLite DDL proposal with foreign keys enabled, checks, ordering indexes, and deletion snapshot fields.
4. Run synthetic SQLite contract tests for root notebooks, nested folders, note ownership, cycle rejection, concurrent conflicting moves, independent Trash batches, missing-parent restores, and parent purge with retained descendants.
5. For every rejected operation, verify database state is unchanged. Verify `PRAGMA foreign_key_check` returns no rows after successful and rejected operations.
6. Have the peer developer review failure semantics and restoration behavior before marking this ADR Accepted.

## Follow-up work

- LIB-01: amend the schema contract and test matrix after joint decision; add SQLite DDL and executable invariants tests.
- LIB-02 / LIB-03: implement identical domain semantics in GRDB / Room without treating this draft as a shipped migration.
- Later storage/backup work: ensure full-library restore remaps collisions, provisions Quick Notes safely, and retains historical Trash metadata when appropriate.
