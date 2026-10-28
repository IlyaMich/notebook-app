"""LIB-01 contract tests, phase A: schema, ownership and atomic moves.

Run from the repository root:
    py -3 -m unittest discover -s shared/validators -p test_library_invariants.py -v

These tests load the PROPOSED SQL schema and exercise a tiny, TEST-ONLY
reference implementation of domain transactions. They are not production
Room/GRDB repository tests. Trash, pages, elements and assets remain for later
LIB-01 contract-test phases.
"""

from __future__ import annotations

import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from uuid import uuid4

SCHEMA = (
    Path(__file__).resolve().parents[2]
    / "docs"
    / "architecture"
    / "notebook_library_sqlite_schema_proposal_v1.sql"
)
NOW = 1793210000000  # Synthetic UTC epoch milliseconds; never actual user data.


def uuid() -> str:
    return str(uuid4())


def open_db(path: str = ":memory:", *, timeout: float = 5.0) -> sqlite3.Connection:
    db = sqlite3.connect(path, isolation_level=None, timeout=timeout)
    db.row_factory = sqlite3.Row
    db.executescript(SCHEMA.read_text(encoding="utf-8"))
    if db.execute("PRAGMA foreign_keys").fetchone()[0] != 1:
        raise AssertionError("SQLite foreign-key enforcement is disabled")
    return db


class DomainError(ValueError):
    """Invalid library-domain operation in the reference test model."""


class ReferenceLibrary:
    """Test-only behavioral oracle; not a repository implementation.

    BEGIN IMMEDIATE ensures that ancestry checks and ownership changes see
    a serialized write transaction. Future Room/GRDB implementations must
    independently meet this contract.
    """

    def __init__(self, db: sqlite3.Connection):
        self.db = db

    @contextmanager
    def write(self):
        self.db.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.db.execute("COMMIT")
        except BaseException:
            self.db.execute("ROLLBACK")
            raise

    def active_folder(self, folder_id: str | None):
        if folder_id is None:
            return None  # Root is a valid destination for folders/notebooks.
        row = self.db.execute(
            "SELECT * FROM folders WHERE id = ? AND deleted_at IS NULL",
            (folder_id,),
        ).fetchone()
        if row is None:
            raise DomainError("Folder destination does not exist or is in Trash")
        return row

    def active_notebook(self, notebook_id: str):
        row = self.db.execute(
            "SELECT * FROM notebooks WHERE id = ? AND deleted_at IS NULL",
            (notebook_id,),
        ).fetchone()
        if row is None:
            raise DomainError("Notebook does not exist or is in Trash")
        return row

    def active_note(self, note_id: str):
        row = self.db.execute(
            "SELECT * FROM notes WHERE id = ? AND deleted_at IS NULL",
            (note_id,),
        ).fetchone()
        if row is None:
            raise DomainError("Note does not exist or is in Trash")
        return row

    def create_folder(self, name: str, parent: str | None = None) -> str:
        if not name.strip():
            raise DomainError("Folder name cannot be blank")
        result = uuid()
        with self.write():
            self.active_folder(parent)
            self.db.execute(
                "INSERT INTO folders (id, parent_folder_id, name, "
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (result, parent, name, NOW, NOW),
            )
        return result

    def create_notebook(
        self, name: str, parent: str | None = None, system_role: str | None = None
    ) -> str:
        if not name.strip():
            raise DomainError("Notebook name cannot be blank")
        result = uuid()
        with self.write():
            self.active_folder(parent)
            self.db.execute(
                "INSERT INTO notebooks (id, parent_folder_id, name, "
                "system_role, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                (result, parent, name, system_role, NOW, NOW),
            )
        return result

    def create_note(self, notebook_id: str, title: str = "") -> str:
        result = uuid()
        with self.write():
            self.active_notebook(notebook_id)
            self.db.execute(
                "INSERT INTO notes (id, notebook_id, title, created_at, "
                "updated_at) VALUES (?, ?, ?, ?, ?)",
                (result, notebook_id, title, NOW, NOW),
            )
        return result

    def move_folder(self, folder_id: str, parent: str | None, *, fail=False):
        with self.write():
            moving = self.active_folder(folder_id)
            self.active_folder(parent)
            if parent == folder_id:
                raise DomainError("A folder cannot contain itself")
            seen = set()
            cursor = parent
            while cursor is not None:
                if cursor == folder_id:
                    raise DomainError("Moving under a descendant creates a cycle")
                if cursor in seen:
                    raise DomainError("Existing corrupt folder cycle detected")
                seen.add(cursor)
                row = self.active_folder(cursor)
                cursor = row["parent_folder_id"]
            self.db.execute(
                "UPDATE folders SET parent_folder_id = ? WHERE id = ?",
                (parent, moving["id"]),
            )
            if fail:
                raise RuntimeError("Simulated failure after folder UPDATE")

    def move_notebook(self, notebook_id: str, parent: str | None, *, fail=False):
        with self.write():
            notebook = self.active_notebook(notebook_id)
            if notebook["system_role"] == "quick_notes":
                raise DomainError("Quick Notes cannot be relocated")
            self.active_folder(parent)
            self.db.execute(
                "UPDATE notebooks SET parent_folder_id = ? WHERE id = ?",
                (parent, notebook_id),
            )
            if fail:
                raise RuntimeError("Simulated failure after notebook UPDATE")

    def move_note(self, note_id: str, notebook_id: str, *, fail=False):
        with self.write():
            self.active_note(note_id)
            self.active_notebook(notebook_id)
            self.db.execute(
                "UPDATE notes SET notebook_id = ? WHERE id = ?",
                (notebook_id, note_id),
            )
            if fail:
                raise RuntimeError("Simulated failure after note UPDATE")


def state(db: sqlite3.Connection):
    """Snapshot all included tables so rejected operations prove no mutation."""
    return tuple(
        (
            table,
            tuple(
                tuple(row)
                for row in db.execute(f"SELECT * FROM {table} ORDER BY id")
            ),
        )
        for table in ("folders", "notebooks", "notes")
    )


class SqliteSchemaTests(unittest.TestCase):
    def setUp(self):
        self.db = open_db()
        self.lib = ReferenceLibrary(self.db)

    def tearDown(self):
        self.db.close()

    def test_L01_root_notebook(self):
        notebook = self.lib.create_notebook("Mathematics")
        self.assertIsNone(
            self.db.execute(
                "SELECT parent_folder_id FROM notebooks WHERE id = ?", (notebook,)
            ).fetchone()[0]
        )

    def test_L02_nested_folders(self):
        a = self.lib.create_folder("School")
        b = self.lib.create_folder("Year 1", a)
        self.assertEqual(
            self.db.execute(
                "SELECT parent_folder_id FROM folders WHERE id = ?", (b,)
            ).fetchone()[0],
            a,
        )

    def test_L03_notebook_inside_folder(self):
        folder = self.lib.create_folder("Projects")
        notebook = self.lib.create_notebook("Physics", folder)
        self.assertEqual(
            self.db.execute(
                "SELECT parent_folder_id FROM notebooks WHERE id = ?", (notebook,)
            ).fetchone()[0],
            folder,
        )

    def test_L04_note_has_exactly_one_notebook_owner(self):
        notebook = self.lib.create_notebook("Mathematics")
        note = self.lib.create_note(notebook)
        self.assertEqual(
            self.db.execute(
                "SELECT notebook_id FROM notes WHERE id = ?", (note,)
            ).fetchone()[0],
            notebook,
        )

    def test_L05_note_cannot_be_owned_by_folder(self):
        folder = self.lib.create_folder("Not a notebook")
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.create_note(folder)
        self.assertEqual(state(self.db), before)
        self.assertNotIn("folder_id", [c[1] for c in self.db.execute("PRAGMA table_info(notes)")])

    def test_sql_foreign_key_rejects_orphan_note(self):
        before = state(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO notes (id, notebook_id, title, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (uuid(), uuid(), "Orphan", NOW, NOW),
            )
        self.assertEqual(state(self.db), before)

    def test_sql_self_parent_check_rejects_update(self):
        folder = self.lib.create_folder("Folder")
        before = state(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "UPDATE folders SET parent_folder_id = ? WHERE id = ?",
                (folder, folder),
            )
        self.assertEqual(state(self.db), before)

    def test_quick_notes_rejects_duplicate_move_delete_demote(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        folder = self.lib.create_folder("Folder")
        before = state(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.lib.create_notebook("Another Quick Notes", system_role="quick_notes")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "UPDATE notebooks SET parent_folder_id = ? WHERE id = ?",
                (folder, quick),
            )
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM notebooks WHERE id = ?", (quick,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "UPDATE notebooks SET system_role = NULL WHERE id = ?", (quick,)
            )
        self.assertEqual(state(self.db), before)

    def test_foreign_key_and_integrity_checks(self):
        folder = self.lib.create_folder("Folder")
        notebook = self.lib.create_notebook("Notebook", folder)
        self.lib.create_note(notebook)
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone()[0], "ok")


class ReferenceMoveTests(unittest.TestCase):
    def setUp(self):
        self.db = open_db()
        self.lib = ReferenceLibrary(self.db)

    def tearDown(self):
        self.db.close()

    def test_L06_move_folder_into_itself_rejected_without_changes(self):
        folder = self.lib.create_folder("A")
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.move_folder(folder, folder)
        self.assertEqual(state(self.db), before)

    def test_L07_move_folder_under_descendant_rejected(self):
        a = self.lib.create_folder("A")
        b = self.lib.create_folder("B", a)
        c = self.lib.create_folder("C", b)
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.move_folder(a, c)
        self.assertEqual(state(self.db), before)

    def test_L08_move_folder_and_notebook_to_root_and_other_folder(self):
        a = self.lib.create_folder("A")
        b = self.lib.create_folder("B")
        child = self.lib.create_folder("Child", a)
        notebook = self.lib.create_notebook("Notebook", a)
        self.lib.move_folder(child, b)
        self.lib.move_notebook(notebook, b)
        self.assertEqual(
            self.db.execute("SELECT parent_folder_id FROM folders WHERE id = ?", (child,)).fetchone()[0],
            b,
        )
        self.lib.move_folder(child, None)
        self.lib.move_notebook(notebook, None)
        self.assertIsNone(
            self.db.execute("SELECT parent_folder_id FROM folders WHERE id = ?", (child,)).fetchone()[0]
        )
        self.assertIsNone(
            self.db.execute("SELECT parent_folder_id FROM notebooks WHERE id = ?", (notebook,)).fetchone()[0]
        )

    def test_L09_move_note_preserves_id_and_other_metadata(self):
        source = self.lib.create_notebook("A")
        target = self.lib.create_notebook("B")
        note = self.lib.create_note(source, "Example")
        before = self.db.execute("SELECT * FROM notes WHERE id = ?", (note,)).fetchone()
        self.lib.move_note(note, target)
        after = self.db.execute("SELECT * FROM notes WHERE id = ?", (note,)).fetchone()
        self.assertEqual(after["notebook_id"], target)
        for key in before.keys():
            if key != "notebook_id":
                self.assertEqual(after[key], before[key])
        # Pages/elements do not exist in this partial schema; test them later.

    def test_L10_nonexistent_or_trashed_destination_rejected(self):
        folder = self.lib.create_folder("A")
        note = self.lib.create_note(self.lib.create_notebook("Notebook"))
        trashed = self.lib.create_folder("Trashed")
        self.db.execute(
            "UPDATE folders SET deleted_at = ?, deletion_batch_id = ?, "
            "original_sort_order = 0 WHERE id = ?",
            (NOW, uuid(), trashed),
        )
        before = state(self.db)
        for target in (uuid(), trashed):
            with self.assertRaises(DomainError):
                self.lib.move_folder(folder, target)
        with self.assertRaises(DomainError):
            self.lib.move_note(note, uuid())
        self.assertEqual(state(self.db), before)

    def test_cannot_create_active_child_under_trashed_parent(self):
        folder = self.lib.create_folder("Trashed")
        self.db.execute(
            "UPDATE folders SET deleted_at = ?, deletion_batch_id = ?, "
            "original_sort_order = 0 WHERE id = ?",
            (NOW, uuid(), folder),
        )
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.create_folder("Child", folder)
        with self.assertRaises(DomainError):
            self.lib.create_notebook("Child Notebook", folder)
        self.assertEqual(state(self.db), before)

    def test_L16_failed_move_rolls_back_update(self):
        source = self.lib.create_folder("Source")
        destination = self.lib.create_folder("Destination")
        note = self.lib.create_note(self.lib.create_notebook("A"))
        notebook = self.lib.create_notebook("B")
        before = state(self.db)
        with self.assertRaisesRegex(RuntimeError, "Simulated failure"):
            self.lib.move_folder(source, destination, fail=True)
        with self.assertRaisesRegex(RuntimeError, "Simulated failure"):
            self.lib.move_note(note, notebook, fail=True)
        self.assertEqual(state(self.db), before)
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_L17_two_connection_writer_lock_and_cycle_recheck(self):
        # Deterministic two-connection serialization check, not a stress test.
        with tempfile.TemporaryDirectory() as temporary:
            path = str(Path(temporary) / "library.sqlite")
            writer1 = open_db(path, timeout=0.0)
            writer2 = sqlite3.connect(path, isolation_level=None, timeout=0.0)
            writer2.row_factory = sqlite3.Row
            writer2.execute("PRAGMA foreign_keys = ON")
            try:
                a = ReferenceLibrary(writer1).create_folder("A")
                b = ReferenceLibrary(writer1).create_folder("B")
                writer1.execute("BEGIN IMMEDIATE")
                try:
                    with self.assertRaisesRegex(sqlite3.OperationalError, "locked"):
                        writer2.execute("BEGIN IMMEDIATE")
                    writer1.execute(
                        "UPDATE folders SET parent_folder_id = ? WHERE id = ?",
                        (b, a),
                    )
                    writer1.execute("COMMIT")
                except BaseException:
                    if writer1.in_transaction:
                        writer1.execute("ROLLBACK")
                    raise
                before = state(writer2)
                with self.assertRaises(DomainError):
                    ReferenceLibrary(writer2).move_folder(b, a)
                self.assertEqual(state(writer2), before)
                self.assertEqual(writer2.execute("PRAGMA foreign_key_check").fetchall(), [])
            finally:
                writer2.close()
                writer1.close()


if __name__ == "__main__":
    unittest.main()
