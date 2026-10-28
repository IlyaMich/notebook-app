# Notebook App — Library Hierarchy and Data Schema Contract V1 (Proposed)

- **Status:** Proposed — joint review required; not an accepted migration or production schema
- **Prepared:** 2026-10-28
- **Work item:** LIB-01, GitHub issue #16
- **Prerequisite:** FMT-01, GitHub issue #11 (open at drafting time)
- **Downstream:** LIB-02 (Apple repositories), LIB-03 (Android repositories)
- **Scope:** Cross-platform library domain and logical persistence contract, not platform implementation

## 1. Authority and boundaries

This proposal refines, but does not replace, the approved product baseline in
`docs/product/notebook_v1_prd_and_delivery_plan.md` and the confirmed hierarchy in
`docs/architecture/notebook_library_storage_plan_v0_1.md`. The latter marks local
storage details as proposals. The portable editor document model is specified
separately in `docs/architecture/notebook_document_model_spec_v0_2.md` and remains
provisional pending FMT-01 and cross-platform validation.

Library relationships are local organizational metadata. They do **not** become
part of portable page geometry or require the original notebook to exist when
importing an individual `.notebook` note. V1 has no account, server, or sync.

This document uses **Confirmed** for existing product rules, **Proposed** for
implementation choices awaiting review, and **Open** for unresolved decisions.

## 2. Hierarchy and ownership

```text
Library (virtual root)
├── Folder [0..many at root]
│   ├── Folder [recursive]
│   └── Notebook
│       └── Note
│           └── Page [ordered]
│               └── Editable Element [ordered]
└── Notebook [0..many at root]
    └── Note
        └── Page
            └── Editable Element
```

### 2.1 Confirmed invariants

H-01. A folder has either no parent (library root) or exactly one parent folder.
H-02. A notebook has either no parent (library root) or exactly one parent folder.
H-03. Folders contain folders and notebooks; a folder never directly owns a note.
H-04. Every active note belongs to exactly one notebook, not directly to a folder.
H-05. Every page belongs to exactly one note; every element belongs to exactly one page.
H-06. Folder ancestry is acyclic: no folder is its own parent or an ancestor of itself.
H-07. Moving a note changes its notebook association without changing its pages or elements.
H-08. All Notes, Recent, Trash, and Search are query/views, not duplicate storage.
H-09. Quick Notes is an available default notebook for immediate note creation.
H-10. Library organization and portable editor content have independent schemas and versions.
H-11. IDs are globally unique identifiers; IDs are stable through rename, move, delete-to-Trash, and restore.
H-12. Cross-platform note import/export preserves editable page/element content independently of the source library path.

### 2.2 Proposed supplementary rules

P-01. A new note and its initial page are created in one transaction, honoring the
portable format's at-least-one-page requirement. The exact behavior when deleting
the final page requires review.
P-02. Only active entities may be selected as a new parent/owner. An active child
must never reference a trashed parent or owner.
P-03. Copy/duplicate/import-as-new creates a new note ID and remaps necessary
nested IDs while preserving internal references. An ordinary move preserves IDs.
P-04. Every library mutation has an atomic success-or-failure result; failures
leave the previously committed tree unchanged.
P-05. Empty or whitespace-only folder/notebook names are rejected. Equal display
names among siblings are allowed unless a future product decision changes this.

## 3. Logical data model (proposed)

| Entity | Key and parent/owner | Key fields and constraints |
|---|---|---|
| `folders` | `id` UUID PK; `parent_folder_id` nullable FK → `folders.id` | `name`, `sort_order`, `created_at`, `updated_at`, `deleted_at` nullable, `deletion_batch_id` nullable |
| `notebooks` | `id` UUID PK; `parent_folder_id` nullable FK → `folders.id` | `name`, optional `color`, `sort_order`, timestamps, `deleted_at` nullable, `deletion_batch_id` nullable |
| `notes` | `id` UUID PK; `notebook_id` NOT NULL FK → `notebooks.id` | `title`, timestamps, optional `last_opened_at`, integer `revision`, `deleted_at` nullable, `deletion_batch_id` nullable |
| `pages` | `id` UUID PK; `note_id` NOT NULL FK → `notes.id` | `order_index`, positive `width`/`height`, `background_payload`, `revision` |
| `elements` | `id` UUID PK; `page_id` NOT NULL FK → `pages.id` | `order_index`, `kind`, canonical `payload`, `revision` |
| `assets` | `id` UUID PK | `sha256`, `mime_type`, `byte_size`, `relative_path`, lifecycle `state` |
| `asset_references` | Referencing owner and `asset_id` FK | Explicit asset ownership/references so Trash can retain originals |
| `app_metadata` | Implementation-defined key | Internal database version and local configuration, separate from portable `schemaVersion` |
| `search_index` | Derived from existing entities/content | Rebuildable; not an additional owner of a note |

The existing storage plan is the source for this entity list. The new
`deletion_batch_id` fields are **proposals**, not yet ratified fields.

**Relationships:** `folders.parent_folder_id` and `notebooks.parent_folder_id`
are nullable for root placement. `notes.notebook_id`, `pages.note_id`, and
`elements.page_id` are required relationships. No `notes.folder_id` exists.

**Identifiers:** generate UUIDs at entity creation. Do not use UI list positions
or titles as identity. Retain identity during ordinary relocations, including
Trash restoration. Import collision/remapping belongs to a separate import
operation contract.

**Time and revisions:** logical timestamps are UTC; `created_at` is immutable,
`updated_at` changes on meaningful mutations, and `last_opened_at` is separate.
The local integer `revision` is increased only after committed document changes;
it is not a distributed-sync protocol. Exact database time encoding and update
triggers are deferred to the SQLite DDL review.

**Ordering:** maintain explicit order within each owner's page list and each
page's element list. Library sibling ordering must be deterministic with ID as
a stable tie-breaker. Whether folders and notebooks share one interleaved order
or have independent ordering is still **Open**. If choosing unique order keys,
define a safe transactional renumbering algorithm and root-level uniqueness
before applying SQL constraints.

**Indexes (proposal):** index all referencing FK columns, active-note retrieval
by `(notebook_id, deleted_at, updated_at)`, pages by `(note_id, order_index)`,
and elements by `(page_id, order_index)`. Physical index definitions belong to
the follow-up DDL artifact.

## 4. Enforcement boundaries

### 4.1 Constraints enforced by SQLite

- Primary keys and FK existence; enable and verify SQLite foreign keys on every connection.
- `NOT NULL` ownership for notes, pages, and elements.
- Simple checks such as nonnegative revision and positive page sizes.
- Uniqueness and indexes where their null/ordering semantics are explicitly defined.
- Safe referential actions for hard deletion (avoid accidental content cascades).

### 4.2 Constraints enforced by transactional domain operations

- Parent/owner is active, not merely present in the database.
- Folder ancestry remains acyclic after create or move.
- A delete/restore/move affects every required row atomically.
- Deleted ancestors and separately trashed descendants are treated correctly.
- The last-page rule, library sort renumbering, and default notebook protections.
- Asset reference removal and filesystem cleanup occur in safe sequence;
  SQLite commits cannot atomically include filesystem modifications.

Do not rely on UI validation alone. Every platform repository must provide the
same domain error behavior regardless of how the operation was initiated.

## 5. Move contract (proposed)

M-01. All moves operate inside a single serialized SQLite write transaction.
M-02. Validate the source exists and is active, and the destination exists and
is active (or NULL only for folder/notebook moves to the library root).
M-03. For a folder move, reject destination equal to source, and reject any
new parent whose ancestor chain contains the moving folder.
M-04. Validate and update `parent_folder_id`, `notebook_id`, and sibling ordering
as one logical operation; do not change page/element IDs during note moves.
M-05. Perform the cycle check inside the same write transaction as the update.
SQLite permits one concurrent writer; do not pre-check against a snapshot and
then write without validating again in the write transaction.
M-06. Invalid IDs, deleted destinations, detected cycles, write errors, and
concurrent conflicts fail without any partial persisted modification.
M-07. Reordering does not alter ownership and follows the same atomicity rule.

The implementation may use an ancestor walk or a recursive common table
expression to validate the destination. The algorithm and SQL fixture will be
reviewed with the proposed schema.

## 6. Trash, restoration, and permanent deletion

### 6.1 Confirmed product outcomes

T-01. Delete-to-Trash is non-destructive for content: pages and referenced
original assets remain recoverable.
T-02. Deleting a folder moves its eligible subtree to Trash.
T-03. A subtree can be restored; missing original ancestors must not silently
cause loss of the subtree.
T-04. Permanent deletion requires user confirmation; asset cleanup must never
remove bytes still referenced by surviving content.
T-05. Trash is a view, not a second set of copied notes.

### 6.2 Proposed operation semantics

T-06. A delete command assigns a `deletion_batch_id` to rows that transition
from active to trashed in that operation. Rows previously trashed retain their
existing deletion batches and are not re-deleted.
T-07. The complete active subtree transition occurs in one SQLite transaction.
Its original parent/owner relationships are retained where referentially safe.
T-08. Restoring a batch restores only its members, not unrelated previously
trashed descendants. All restored rows must end with valid active parents.
T-09. When the original folder parent no longer exists, a folder/notebook may
be restored at library root or at an explicit valid destination.
T-10. When the original notebook for a note is permanently gone, the note must
be assigned an active notebook before it can become active; Quick Notes is a
proposed fallback, subject to the Quick Notes protection decision.
T-11. No user content is hard-deleted or asset-garbage-collected solely because
it was moved to Trash.
T-12. Proposed V1 policy: no timed automatic purge; emptying Trash is explicit.
T-13. Permanent purge must preserve remaining trashed content. When a parent is
purged but an independently trashed child is retained, the operation must safely
rehome/detach the child without an orphan FK and retain enough restoration
metadata to explain the missing original parent. Exact snapshot fields and
purge ordering remain **Open**; do not implement hard delete until resolved.

### 6.3 Open decisions required before approval

D-01. Is Quick Notes protected from moving, deleting, and permanent purging?
D-02. Is automatic Trash expiry explicitly forbidden in V1 (recommended), and
what confirmation language is required for permanent purge?
D-03. What stored original-parent snapshot is required when a parent is
permanently deleted? Should it be a non-FK UUID, deletion-root record, or both?
D-04. Are deleted subtrees restored as a unit by default, and what is the UX
fallback when a former parent is missing but an alternative parent exists?
D-05. Should folders and notebooks share an interleaved sibling ordering?
D-06. Which operation is permitted when the final page of a note is removed?
D-07. What UTC timestamp representation and ordering-gap/renumber strategy are
used by both implementations?

None of D-01 through D-07 may be silently treated as approved merely because
this document records a recommended direction.

## 7. Initial LIB-01 acceptance matrix

| ID | Scenario | Expected outcome |
|---|---|---|
| L01 | Create root notebook | Accepted, `parent_folder_id = NULL` |
| L02 | Create nested folders | Accepted, valid ancestry |
| L03 | Create notebook in folder | Accepted |
| L04 | Create note in notebook | Accepted, exactly one notebook owner |
| L05 | Create note directly in folder | Rejected by domain model/interface |
| L06 | Move folder into itself | Rejected, unchanged state |
| L07 | Move folder under its descendant | Rejected, unchanged state |
| L08 | Move folder/notebook to another valid parent or root | Accepted, IDs preserved |
| L09 | Move note to another notebook | Accepted, pages/elements unchanged |
| L10 | Move into deleted/nonexistent parent | Rejected, unchanged state |
| L11 | Delete folder subtree | Entire eligible active subtree becomes trashed atomically |
| L12 | Restore subtree | Only eligible deletion-batch members restored |
| L13 | Restore when original folder parent was purged | Safe root/explicit-destination fallback |
| L14 | Restore note when original notebook was purged | Requires active destination notebook |
| L15 | Purge parent with separately trashed descendant | No lost content or dangling FK |
| L16 | Fail partway through move/delete/restore | Entire transaction rolled back |
| L17 | Concurrent conflicting folder moves | No cycle possible after both finish |
| L18 | Delete note with referenced asset | Original asset retained while referenced, including Trash |

For every rejected transaction, tests must compare state before and after the
attempt. Test data is synthetic. The executable SQLite contract tests, physical
schema proposal, and ADR are included in this LIB-01 review change.

## 8. Completion gates and handoff

LIB-01 is **not done** until:

1. FMT-01's relevant ID/ownership and format boundaries are reviewed or an
   explicit dependency resolution is recorded.
2. Both developers review the open decisions and record the accepted policy in
   an ADR using `docs/decisions/0000-template.md`.
3. SQLite DDL implements the accepted keys/FKs/checks/indexes without weakening
   the logical rules.
4. Executable tests demonstrate L01–L18 (with exact expected outcomes amended
   as decisions are accepted), including atomic failure cases.
5. The PR linked to #16 is reviewed and CI/test evidence is attached.

After acceptance: LIB-02 implements the Apple/GRDB repository; LIB-03 implements
the Android/Room repository. Neither implementation should treat a draft
physical schema as an already-shipped, migration-compatible database version.

## 9. References

- `docs/product/notebook_v1_prd_and_delivery_plan.md` — R-LIB and R-DAT requirements.
- `docs/architecture/notebook_library_storage_plan_v0_1.md` — confirmed hierarchy and proposed storage topology.
- `docs/architecture/notebook_document_model_spec_v0_2.md` — provisional portable content boundaries.
- `docs/decisions/README.md` and `docs/decisions/0000-template.md` — ADR process.
- GitHub issues: #11 (FMT-01), #16 (LIB-01), #17 (LIB-02), #18 (LIB-03).
