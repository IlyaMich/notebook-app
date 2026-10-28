-- Notebook App / LIB-01 (#16): local SQLite schema proposal, iteration 2.
-- Status: PROPOSED. Joint ADR review and FMT-01 (#11) are still required.
-- Scope: library ownership, page/element ownership, and note-level asset
-- references. Domain algorithms remain test-only contract examples.
-- This is a review/contract artifact, NOT a shipped migration or Room schema.
-- Never apply it directly to an existing user database.
--
-- Connection contract: every connection MUST enable foreign keys before
-- beginning any transaction, and verify PRAGMA foreign_keys returns 1.
PRAGMA foreign_keys = ON;

BEGIN;

CREATE TABLE folders (
    id TEXT NOT NULL PRIMARY KEY,
    parent_folder_id TEXT NULL,
    name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER NULL,
    deletion_batch_id TEXT NULL,
    original_parent_folder_id TEXT NULL,
    original_sort_order INTEGER NULL,

    FOREIGN KEY (parent_folder_id) REFERENCES folders(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CHECK (length(trim(id)) > 0),
    CHECK (length(trim(name)) > 0),
    CHECK (parent_folder_id IS NULL OR parent_folder_id <> id),
    CHECK (typeof(sort_order) = 'integer' AND sort_order >= 0),
    CHECK (typeof(created_at) = 'integer'),
    CHECK (typeof(updated_at) = 'integer'),
    CHECK (deleted_at IS NULL OR typeof(deleted_at) = 'integer'),
    CHECK (original_sort_order IS NULL OR
           (typeof(original_sort_order) = 'integer' AND original_sort_order >= 0)),
    CHECK (
        (deleted_at IS NULL AND deletion_batch_id IS NULL AND
         original_parent_folder_id IS NULL AND original_sort_order IS NULL)
        OR
        (deleted_at IS NOT NULL AND deletion_batch_id IS NOT NULL AND
         length(trim(deletion_batch_id)) > 0 AND original_sort_order IS NOT NULL)
    )
);

CREATE TABLE notebooks (
    id TEXT NOT NULL PRIMARY KEY,
    parent_folder_id TEXT NULL,
    name TEXT NOT NULL,
    color TEXT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    system_role TEXT NULL,                -- NULL or 'quick_notes'
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    deleted_at INTEGER NULL,
    deletion_batch_id TEXT NULL,
    original_parent_folder_id TEXT NULL,
    original_sort_order INTEGER NULL,

    FOREIGN KEY (parent_folder_id) REFERENCES folders(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CHECK (length(trim(id)) > 0),
    CHECK (length(trim(name)) > 0),
    CHECK (typeof(sort_order) = 'integer' AND sort_order >= 0),
    CHECK (typeof(created_at) = 'integer'),
    CHECK (typeof(updated_at) = 'integer'),
    CHECK (deleted_at IS NULL OR typeof(deleted_at) = 'integer'),
    CHECK (original_sort_order IS NULL OR
           (typeof(original_sort_order) = 'integer' AND original_sort_order >= 0)),
    CHECK (system_role IS NULL OR system_role = 'quick_notes'),
    CHECK (system_role IS NULL OR
           (parent_folder_id IS NULL AND deleted_at IS NULL)),
    CHECK (
        (deleted_at IS NULL AND deletion_batch_id IS NULL AND
         original_parent_folder_id IS NULL AND original_sort_order IS NULL)
        OR
        (deleted_at IS NOT NULL AND deletion_batch_id IS NOT NULL AND
         length(trim(deletion_batch_id)) > 0 AND original_sort_order IS NOT NULL)
    )
);

CREATE TABLE notes (
    id TEXT NOT NULL PRIMARY KEY,
    notebook_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',       -- unnamed notes allowed
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_opened_at INTEGER NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER NULL,
    deletion_batch_id TEXT NULL,
    original_notebook_id TEXT NULL,       -- historical UUID, intentionally NO FK

    FOREIGN KEY (notebook_id) REFERENCES notebooks(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CHECK (length(trim(id)) > 0),
    CHECK (typeof(created_at) = 'integer'),
    CHECK (typeof(updated_at) = 'integer'),
    CHECK (last_opened_at IS NULL OR typeof(last_opened_at) = 'integer'),
    CHECK (typeof(revision) = 'integer' AND revision >= 0),
    CHECK (deleted_at IS NULL OR typeof(deleted_at) = 'integer'),
    CHECK (
        (deleted_at IS NULL AND deletion_batch_id IS NULL AND
         original_notebook_id IS NULL)
        OR
        (deleted_at IS NOT NULL AND deletion_batch_id IS NOT NULL AND
         length(trim(deletion_batch_id)) > 0 AND
         original_notebook_id IS NOT NULL AND
         length(trim(original_notebook_id)) > 0)
    )
);

-- The unique partial index guarantees AT MOST one Quick Notes notebook.
-- The app's initial provisioning / restore transaction must ensure EXACTLY one.
CREATE UNIQUE INDEX ux_notebooks_quick_notes_role
    ON notebooks(system_role) WHERE system_role = 'quick_notes';

-- A distinguished Quick Notes row cannot be hard-deleted or demoted.
-- Its other protections (no trashed state; root-only) are CHECK constraints.
CREATE TRIGGER trg_quick_notes_no_delete
BEFORE DELETE ON notebooks
WHEN OLD.system_role = 'quick_notes'
BEGIN
    SELECT RAISE(ABORT, 'Quick Notes cannot be permanently deleted');
END;

CREATE TRIGGER trg_quick_notes_no_demote
BEFORE UPDATE OF system_role ON notebooks
WHEN OLD.system_role = 'quick_notes' AND NEW.system_role IS NOT 'quick_notes'
BEGIN
    SELECT RAISE(ABORT, 'Quick Notes system role cannot be removed');
END;

-- Page and editable-element local ownership. Ordering is UNIQUE within an
-- owner to match the canonical ordered-page and ordered-element contracts.
-- Reorder operations must use a collision-safe transactional strategy (for
-- example, temporary positive indexes outside the active range).
-- Payload JSON is validated by domain/portable validators, NOT by this DDL.
CREATE TABLE pages (
    id TEXT NOT NULL PRIMARY KEY,
    note_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL,
    background_payload TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,

    FOREIGN KEY (note_id) REFERENCES notes(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CHECK (length(trim(id)) > 0),
    CHECK (typeof(order_index) = 'integer' AND order_index >= 0),
    CHECK (width > 0 AND height > 0),
    CHECK (length(trim(background_payload)) > 0),
    CHECK (typeof(revision) = 'integer' AND revision >= 0),
    UNIQUE (note_id, order_index)
);

CREATE TABLE elements (
    id TEXT NOT NULL PRIMARY KEY,
    page_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    kind TEXT NOT NULL,
    payload TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,

    FOREIGN KEY (page_id) REFERENCES pages(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,

    CHECK (length(trim(id)) > 0),
    CHECK (typeof(order_index) = 'integer' AND order_index >= 0),
    CHECK (kind IN ('stroke', 'shape', 'text', 'image')),
    CHECK (length(trim(payload)) > 0),
    CHECK (typeof(revision) = 'integer' AND revision >= 0),
    UNIQUE (page_id, order_index)
);

-- Original asset bytes live in managed private filesystem storage, not SQLite.
-- A committed asset MUST exist and match its SHA-256; this is verified by the
-- filesystem staging/recovery protocol, not by a database constraint.
CREATE TABLE assets (
    id TEXT NOT NULL PRIMARY KEY,
    sha256 TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    relative_path TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL DEFAULT 'committed',

    CHECK (length(trim(id)) > 0),
    CHECK (length(sha256) = 64 AND sha256 NOT GLOB '*[^0-9a-f]*'),
    CHECK (mime_type IN ('image/png', 'image/jpeg', 'application/pdf')),
    CHECK (typeof(byte_size) = 'integer' AND byte_size > 0),
    CHECK (length(trim(relative_path)) > 0),
    CHECK (state IN ('committed', 'pending_gc'))
);

-- References belong to a note's asset registry and survive soft deletion.
-- Both FKs use RESTRICT, so a permanent purge must explicitly remove the
-- purged note's references before its content, inside one DB transaction.
-- Never delete asset bytes until the committed reference count is zero AND
-- filesystem garbage-collection safety checks have passed.
CREATE TABLE asset_references (
    note_id TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    PRIMARY KEY (note_id, asset_id),

    FOREIGN KEY (note_id) REFERENCES notes(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    FOREIGN KEY (asset_id) REFERENCES assets(id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
);
CREATE INDEX ix_asset_references_by_asset
    ON asset_references(asset_id, note_id);

-- Separate folder/notebook sibling sequences, with ID as a stable tie-breaker.
-- No UNIQUE(parent_folder_id, sort_order): draft ADR allows ties; it also
-- avoids the NULL-root uniqueness trap and transient renumber collisions.
CREATE INDEX ix_folders_parent_listing
    ON folders(parent_folder_id, deleted_at, sort_order, id);
CREATE INDEX ix_notebooks_parent_listing
    ON notebooks(parent_folder_id, deleted_at, sort_order, id);
CREATE INDEX ix_notes_notebook_recent
    ON notes(notebook_id, deleted_at, updated_at DESC, id);
CREATE INDEX ix_folders_deletion_batch ON folders(deletion_batch_id);
CREATE INDEX ix_notebooks_deletion_batch ON notebooks(deletion_batch_id);
CREATE INDEX ix_notes_deletion_batch ON notes(deletion_batch_id);

COMMIT;

-- Intentionally NOT enforced by this partial DDL:
-- * Recursive folder cycle checks (other than immediate self-parenting).
-- * Active child -> active parent/owner, including within Trash subtrees.
-- * Serialized, atomic move/delete/restore/purge operations and ordering edits.
-- * Existence/provisioning of Quick Notes, UUID syntax and global ID uniqueness
--   across tables; these are domain/bootstrap responsibilities.
-- * At least one page per active note, including atomic note + initial-page
--   creation; the SQL foreign key does not enforce that child-row minimum.
-- * JSON payload semantics, PDF page/element asset IDs, canonical geometry,
--   finite coordinate checks, and filesystem asset integrity/GC.
-- * Content-containing purge order: reference removal, element/page removal,
--   note removal, and only later safe asset GC (a domain contract).
-- * Cross-platform import/export identity remapping and migration policy.
-- All domain writes must run in a serialized write transaction (for example
-- BEGIN IMMEDIATE on raw SQLite) and check invariants before committing.
