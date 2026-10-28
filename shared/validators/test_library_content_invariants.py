"""LIB-01 phase C: page/element ownership and note-level asset retention.

Run from repository root:
    py -3 -m unittest discover -s shared/validators -p "test_library_*.py" -v

This module extends the TEST-ONLY behavioral oracle. It is not production
Room/GRDB code. Filesystem existence, canonical JSON semantics and ZIP package
validation are separate contracts; SQLite cannot prove them.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import unittest

from test_library_invariants import NOW, DomainError, open_db, uuid
from test_library_trash_invariants import TrashReferenceLibrary


class ContentReferenceLibrary(TrashReferenceLibrary):
    """Minimal reference methods to exercise the proposed SQLite contract."""

    def create_note_with_page(self, notebook_id: str, *, fail: bool = False):
        """A new note and its initial page must be committed together."""
        note_id, page_id = uuid(), uuid()
        with self.write():
            self.active_notebook(notebook_id)
            self.db.execute(
                "INSERT INTO notes (id, notebook_id, title, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (note_id, notebook_id, "", NOW, NOW),
            )
            if fail:
                raise RuntimeError("Simulated initial-page failure")
            self._insert_page(page_id, note_id, 0)
        return note_id, page_id

    def _insert_page(self, page_id: str, note_id: str, index: int):
        self.db.execute(
            "INSERT INTO pages (id, note_id, order_index, width, height, "
            "background_payload) VALUES (?, ?, ?, ?, ?, ?)",
            (page_id, note_id, index, 595.276, 841.890,
             json.dumps({"type": "blank", "color": "#FFFFFF"})),
        )

    def add_page(self, note_id: str) -> str:
        page_id = uuid()
        with self.write():
            self.active_note(note_id)
            next_index = self.db.execute(
                "SELECT COALESCE(MAX(order_index) + 1, 0) FROM pages WHERE note_id=?",
                (note_id,),
            ).fetchone()[0]
            self._insert_page(page_id, note_id, next_index)
        return page_id

    def add_element(self, page_id: str, kind: str = "shape") -> str:
        element_id = uuid()
        with self.write():
            owner = self.db.execute(
                "SELECT n.deleted_at FROM pages p JOIN notes n ON p.note_id=n.id "
                "WHERE p.id=?", (page_id,),
            ).fetchone()
            if owner is None or owner["deleted_at"] is not None:
                raise DomainError("Page is missing or its note is in Trash")
            next_index = self.db.execute(
                "SELECT COALESCE(MAX(order_index) + 1, 0) "
                "FROM elements WHERE page_id=?", (page_id,),
            ).fetchone()[0]
            payload = json.dumps({
                "id": element_id, "type": kind,
                "transform": [1, 0, 0, 1, 0, 0],
                "locked": False, "payload": {},
            })
            self.db.execute(
                "INSERT INTO elements (id, page_id, order_index, kind, payload) "
                "VALUES (?, ?, ?, ?, ?)",
                (element_id, page_id, next_index, kind, payload),
            )
        return element_id

    def delete_page(self, page_id: str):
        with self.write():
            row = self.db.execute(
                "SELECT p.note_id FROM pages p JOIN notes n ON n.id=p.note_id "
                "WHERE p.id=? AND n.deleted_at IS NULL", (page_id,),
            ).fetchone()
            if row is None:
                raise DomainError("Cannot delete page from missing/trashed note")
            count = self.db.execute(
                "SELECT COUNT(*) FROM pages WHERE note_id=?", (row["note_id"],),
            ).fetchone()[0]
            if count <= 1:
                raise DomainError("Cannot delete the last page of an active note")
            # Deleting page-owned objects is a distinct explicit operation.
            self.db.execute("DELETE FROM elements WHERE page_id=?", (page_id,))
            self.db.execute("DELETE FROM pages WHERE id=?", (page_id,))
            # A real implementation must also close any ordering gap.

    def register_asset(self, content: bytes = b"synthetic PNG fixture") -> str:
        asset_id = uuid()
        sha = hashlib.sha256(content).hexdigest()
        with self.write():
            self.db.execute(
                "INSERT INTO assets (id, sha256, mime_type, byte_size, "
                "relative_path) VALUES (?, ?, ?, ?, ?)",
                (asset_id, sha, "image/png", len(content),
                 f"assets/{sha}.png"),
            )
        return asset_id

    def reference_asset(self, note_id: str, asset_id: str):
        with self.write():
            self.active_note(note_id)
            asset = self.db.execute(
                "SELECT state FROM assets WHERE id=?", (asset_id,),
            ).fetchone()
            if asset is None or asset["state"] != "committed":
                raise DomainError("Only committed assets may be referenced")
            self.db.execute(
                "INSERT INTO asset_references (note_id, asset_id) VALUES (?, ?)",
                (note_id, asset_id),
            )

    def purge_single_trashed_note(self, note_id: str, *, fail: bool = False):
        """Demonstrate safe child-first deletion for one trashed note ONLY.

        This is not a replacement for the batch-wide purge operation.
        It does not remove asset files or their registry rows: later GC must
        check committed reference counts and filesystem safety.
        """
        with self.write():
            note = self.db.execute(
                "SELECT deleted_at FROM notes WHERE id=?", (note_id,),
            ).fetchone()
            if note is None or note["deleted_at"] is None:
                raise DomainError("Only a trashed note can be purged")
            self.db.execute(
                "DELETE FROM asset_references WHERE note_id=?", (note_id,),
            )
            if fail:
                raise RuntimeError("Simulated purge after reference removal")
            self.db.execute(
                "DELETE FROM elements WHERE page_id IN "
                "(SELECT id FROM pages WHERE note_id=?)", (note_id,),
            )
            self.db.execute("DELETE FROM pages WHERE note_id=?", (note_id,))
            self.db.execute("DELETE FROM notes WHERE id=?", (note_id,))
            self._validate()


def full_state(db: sqlite3.Connection):
    """Snapshot ALL persistent tables, including page and asset references."""
    return tuple(
        (table, tuple(tuple(row) for row in db.execute(f"SELECT * FROM {table}")))
        for table in ("folders", "notebooks", "notes", "pages", "elements",
                      "assets", "asset_references")
    )


class ContentInvariantTests(unittest.TestCase):
    def setUp(self):
        self.db = open_db()
        self.lib = ContentReferenceLibrary(self.db)

    def tearDown(self):
        self.db.close()

    def assert_integrity(self):
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def test_note_and_first_page_created_atomically(self):
        notebook = self.lib.create_notebook("Math")
        before = full_state(self.db)
        with self.assertRaisesRegex(RuntimeError, "initial-page failure"):
            self.lib.create_note_with_page(notebook, fail=True)
        self.assertEqual(full_state(self.db), before)
        note, page = self.lib.create_note_with_page(notebook)
        self.assertEqual(self.db.execute(
            "SELECT note_id FROM pages WHERE id=?", (page,),
        ).fetchone()[0], note)
        self.assertEqual(self.db.execute(
            "SELECT COUNT(*) FROM pages WHERE note_id=?", (note,),
        ).fetchone()[0], 1)
        self.assert_integrity()

    def test_page_foreign_key_geometry_and_unique_order(self):
        note, page = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        before = full_state(self.db)
        with self.assertRaises(sqlite3.IntegrityError):
            self.lib._insert_page(uuid(), uuid(), 0)  # missing note
        with self.assertRaises(sqlite3.IntegrityError):
            self.lib._insert_page(uuid(), note, 0)  # duplicate order
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "UPDATE pages SET width=-1 WHERE id=?", (page,),
            )
        self.assertEqual(full_state(self.db), before)
        self.assert_integrity()

    def test_element_foreign_key_kind_and_order(self):
        note, page = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        element = self.lib.add_element(page)
        before = full_state(self.db)
        for page_id, index, kind in (
            (uuid(), 0, "shape"),  # missing page
            (page, 0, "shape"),  # duplicate position
            (page, 1, "unknown_kind"),
        ):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(
                    "INSERT INTO elements (id,page_id,order_index,kind,payload) "
                    "VALUES (?,?,?,?,?)", (uuid(), page_id, index, kind, "{}"),
                )
        self.assertEqual(full_state(self.db), before)
        self.assertEqual(self.db.execute(
            "SELECT kind FROM elements WHERE id=?", (element,),
        ).fetchone()[0], "shape")
        self.assert_integrity()

    def test_L09_move_note_does_not_change_pages_or_elements(self):
        book1 = self.lib.create_notebook("A")
        book2 = self.lib.create_notebook("B")
        note, page = self.lib.create_note_with_page(book1)
        second_page = self.lib.add_page(note)
        element = self.lib.add_element(page)
        before = tuple(tuple(row) for row in self.db.execute(
            "SELECT * FROM pages ORDER BY id"))
        before_elements = tuple(tuple(row) for row in self.db.execute(
            "SELECT * FROM elements ORDER BY id"))
        self.lib.move_note(note, book2)
        self.assertEqual(self.db.execute(
            "SELECT notebook_id FROM notes WHERE id=?", (note,),
        ).fetchone()[0], book2)
        self.assertEqual(tuple(tuple(row) for row in self.db.execute(
            "SELECT * FROM pages ORDER BY id")), before)
        self.assertEqual(tuple(tuple(row) for row in self.db.execute(
            "SELECT * FROM elements ORDER BY id")), before_elements)
        self.assertEqual(self.db.execute(
            "SELECT note_id FROM pages WHERE id=?", (second_page,),
        ).fetchone()[0], note)
        self.assert_integrity()

    def test_last_page_protected_by_domain_rule(self):
        note, first = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        before = full_state(self.db)
        with self.assertRaisesRegex(DomainError, "last page"):
            self.lib.delete_page(first)
        self.assertEqual(full_state(self.db), before)
        second = self.lib.add_page(note)
        self.lib.delete_page(first)
        self.assertEqual(self.db.execute(
            "SELECT id FROM pages WHERE note_id=?", (note,),
        ).fetchone()[0], second)
        self.assert_integrity()

    def test_restrict_hard_deletes_while_referenced(self):
        note, page = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        self.lib.add_element(page)
        asset = self.lib.register_asset()
        self.lib.reference_asset(note, asset)
        before = full_state(self.db)
        for query, args in (
            ("DELETE FROM notes WHERE id=?", (note,)),
            ("DELETE FROM pages WHERE id=?", (page,)),
            ("DELETE FROM assets WHERE id=?", (asset,)),
        ):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(query, args)
        self.assertEqual(full_state(self.db), before)
        self.assert_integrity()

    def test_L18_soft_delete_preserves_pages_elements_assets_and_refs(self):
        note, page = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        self.lib.add_element(page, "image")
        asset = self.lib.register_asset()
        self.lib.reference_asset(note, asset)
        before = tuple((table, tuple(tuple(r) for r in self.db.execute(f"SELECT * FROM {table}")))
                       for table in ("pages", "elements", "assets", "asset_references"))
        self.lib.trash_note(note)
        after = tuple((table, tuple(tuple(r) for r in self.db.execute(f"SELECT * FROM {table}")))
                      for table in ("pages", "elements", "assets", "asset_references"))
        self.assertEqual(before, after)
        self.assertIsNotNone(self.db.execute(
            "SELECT deleted_at FROM notes WHERE id=?", (note,),
        ).fetchone()[0])
        self.assert_integrity()

    def test_L18_trash_restore_subtree_keeps_content(self):
        folder = self.lib.create_folder("School")
        book = self.lib.create_notebook("Math", folder)
        note, page = self.lib.create_note_with_page(book)
        element = self.lib.add_element(page)
        asset = self.lib.register_asset()
        self.lib.reference_asset(note, asset)
        before = tuple((table, tuple(tuple(r) for r in self.db.execute(f"SELECT * FROM {table}")))
                       for table in ("pages", "elements", "assets", "asset_references"))
        batch = self.lib.trash_folder(folder)
        self.lib.restore_batch(batch)
        after = tuple((table, tuple(tuple(r) for r in self.db.execute(f"SELECT * FROM {table}")))
                      for table in ("pages", "elements", "assets", "asset_references"))
        self.assertEqual(before, after)
        self.assertEqual(self.db.execute(
            "SELECT page_id FROM elements WHERE id=?", (element,),
        ).fetchone()[0], page)
        self.assert_integrity()

    def test_L18_shared_asset_survives_one_note_purge(self):
        book = self.lib.create_notebook("Book")
        one, _ = self.lib.create_note_with_page(book)
        two, _ = self.lib.create_note_with_page(book)
        asset = self.lib.register_asset()
        self.lib.reference_asset(one, asset)
        self.lib.reference_asset(two, asset)
        self.lib.trash_note(one)
        self.lib.purge_single_trashed_note(one)
        self.assertEqual(self.db.execute(
            "SELECT COUNT(*) FROM asset_references WHERE asset_id=?", (asset,),
        ).fetchone()[0], 1)
        self.assertIsNotNone(self.db.execute(
            "SELECT id FROM assets WHERE id=?", (asset,),
        ).fetchone())
        self.lib.trash_note(two)
        self.lib.purge_single_trashed_note(two)
        self.assertEqual(self.db.execute(
            "SELECT COUNT(*) FROM asset_references WHERE asset_id=?", (asset,),
        ).fetchone()[0], 0)
        # Asset registry/file cleanup is a separate safe garbage-collection step.
        self.assertIsNotNone(self.db.execute(
            "SELECT id FROM assets WHERE id=?", (asset,),
        ).fetchone())
        self.assert_integrity()

    def test_L15_batch_purge_preserves_independent_note_and_its_content(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        root = self.lib.create_folder("Root")
        book = self.lib.create_notebook("Book", root)
        keep, keep_page = self.lib.create_note_with_page(book)
        keep_element = self.lib.add_element(keep_page)
        purge, purge_page = self.lib.create_note_with_page(book)
        self.lib.add_element(purge_page)
        asset = self.lib.register_asset()
        self.lib.reference_asset(keep, asset)
        self.lib.reference_asset(purge, asset)
        independent = self.lib.trash_note(keep)
        parent_batch = self.lib.trash_folder(root)
        self.lib.purge_batch(parent_batch)
        self.assertEqual(self.db.execute(
            "SELECT notebook_id FROM notes WHERE id=?", (keep,),
        ).fetchone()[0], quick)
        self.assertEqual(self.db.execute(
            "SELECT deletion_batch_id FROM notes WHERE id=?", (keep,),
        ).fetchone()[0], independent)
        self.assertEqual(self.db.execute(
            "SELECT page_id FROM elements WHERE id=?", (keep_element,),
        ).fetchone()[0], keep_page)
        self.assertIsNone(self.db.execute(
            "SELECT id FROM notes WHERE id=?", (purge,),
        ).fetchone())
        self.assertIsNone(self.db.execute(
            "SELECT id FROM pages WHERE id=?", (purge_page,),
        ).fetchone())
        self.assertEqual(self.db.execute(
            "SELECT note_id FROM asset_references WHERE asset_id=?", (asset,),
        ).fetchone()[0], keep)
        self.assertIsNotNone(self.db.execute(
            "SELECT id FROM assets WHERE id=?", (asset,),
        ).fetchone())
        self.lib.restore_batch(independent)
        self.assertEqual(self.db.execute(
            "SELECT notebook_id FROM notes WHERE id=?", (keep,),
        ).fetchone()[0], quick)
        self.assert_integrity()

    def test_L18_purge_failure_rolls_back_all_content_and_refs(self):
        book = self.lib.create_notebook("Book")
        note, page = self.lib.create_note_with_page(book)
        self.lib.add_element(page)
        asset = self.lib.register_asset()
        self.lib.reference_asset(note, asset)
        self.lib.trash_note(note)
        before = full_state(self.db)
        with self.assertRaisesRegex(RuntimeError, "after reference removal"):
            self.lib.purge_single_trashed_note(note, fail=True)
        self.assertEqual(full_state(self.db), before)
        self.assert_integrity()

    def test_invalid_asset_reference_and_hash_rejected(self):
        note, _ = self.lib.create_note_with_page(self.lib.create_notebook("Book"))
        asset = self.lib.register_asset()
        before = full_state(self.db)
        for ref_note, ref_asset in ((uuid(), asset), (note, uuid())):
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(
                    "INSERT INTO asset_references (note_id,asset_id) "
                    "VALUES (?,?)", (ref_note, ref_asset),
                )
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO assets (id,sha256,mime_type,byte_size,relative_path) "
                "VALUES (?,?,?,?,?)", (uuid(), "bad-hash", "image/png", 7,
                                         "assets/bad.png"),
            )
        self.assertEqual(full_state(self.db), before)
        self.assert_integrity()


if __name__ == "__main__":
    unittest.main()
