"""LIB-01 phase B: test-only reference model for Trash operations.

Run from the repository root:
    py -3 -m unittest discover -s shared/validators -p "test_library_*.py" -v

Depends on the proposed SQL DDL and the phase-A helper module. This is NOT
production deletion/recovery code and does not test Room or GRDB. Assets,
pages and elements are not yet modeled by this partial schema.
"""

from __future__ import annotations

import sqlite3
import unittest

from test_library_invariants import (
    NOW,
    DomainError,
    ReferenceLibrary,
    open_db,
    state,
    uuid,
)


class TrashReferenceLibrary(ReferenceLibrary):
    """Behavioral oracle: all mutations run in a single serialized transaction."""

    def _row(self, table: str, entity_id: str) -> sqlite3.Row:
        # Only trusted constant table names may be passed to this test helper.
        if table not in ("folders", "notebooks", "notes"):
            raise AssertionError("Unexpected table")
        row = self.db.execute(
            f"SELECT * FROM {table} WHERE id = ?", (entity_id,)
        ).fetchone()
        if row is None:
            raise DomainError(f"Missing {table} row")
        return row

    def _descendants(self, folder_id: str) -> tuple[list[sqlite3.Row], list[sqlite3.Row], list[sqlite3.Row]]:
        """Walk the whole tree, INCLUDING independently trashed members."""
        pending = [folder_id]
        seen = set()
        folders = []
        while pending:
            folder = pending.pop()
            if folder in seen:
                raise DomainError("Pre-existing folder cycle")
            seen.add(folder)
            folders.append(self._row("folders", folder))
            pending.extend(
                row["id"] for row in self.db.execute(
                    "SELECT id FROM folders WHERE parent_folder_id = ?", (folder,)
                )
            )
        folder_ids = set(seen)
        notebooks = [
            row for row in self.db.execute("SELECT * FROM notebooks")
            if row["parent_folder_id"] in folder_ids
        ]
        notebook_ids = {row["id"] for row in notebooks}
        notes = [
            row for row in self.db.execute("SELECT * FROM notes")
            if row["notebook_id"] in notebook_ids
        ]
        return folders, notebooks, notes

    def _validate(self):
        """Check domain invariants not expressible with ordinary SQL FKs."""
        folders = {row["id"]: row for row in self.db.execute("SELECT * FROM folders")}
        notebooks = {row["id"]: row for row in self.db.execute("SELECT * FROM notebooks")}
        for folder in folders.values():
            parent_id = folder["parent_folder_id"]
            if folder["deleted_at"] is None and parent_id is not None:
                if parent_id not in folders or folders[parent_id]["deleted_at"] is not None:
                    raise DomainError("Active folder has inactive parent")
            visited = {folder["id"]}
            while parent_id is not None:
                if parent_id in visited:
                    raise DomainError("Folder cycle")
                visited.add(parent_id)
                if parent_id not in folders:
                    raise DomainError("Missing folder parent")
                parent_id = folders[parent_id]["parent_folder_id"]
        for notebook in notebooks.values():
            parent_id = notebook["parent_folder_id"]
            if notebook["deleted_at"] is None and parent_id is not None:
                if parent_id not in folders or folders[parent_id]["deleted_at"] is not None:
                    raise DomainError("Active notebook has inactive folder")
        for note in self.db.execute("SELECT * FROM notes"):
            owner = notebooks.get(note["notebook_id"])
            if owner is None:
                raise DomainError("Note has missing owner")
            if note["deleted_at"] is None and owner["deleted_at"] is not None:
                raise DomainError("Active note has trashed notebook")
        if self.db.execute("PRAGMA foreign_key_check").fetchall():
            raise DomainError("Broken foreign key")

    def _delete_rows(self, folders, notebooks, notes, *, fail=False) -> str:
        batch = uuid()
        for row in notes:
            if row["deleted_at"] is None:
                self.db.execute(
                    "UPDATE notes SET deleted_at=?, deletion_batch_id=?, "
                    "original_notebook_id=? WHERE id=?",
                    (NOW, batch, row["notebook_id"], row["id"]),
                )
        if fail:
            raise RuntimeError("Simulated delete failure")
        for row in notebooks:
            if row["deleted_at"] is None:
                if row["system_role"] == "quick_notes":
                    raise DomainError("Cannot trash Quick Notes")
                self.db.execute(
                    "UPDATE notebooks SET deleted_at=?, deletion_batch_id=?, "
                    "original_parent_folder_id=?, original_sort_order=? WHERE id=?",
                    (NOW, batch, row["parent_folder_id"], row["sort_order"], row["id"]),
                )
        for row in folders:
            if row["deleted_at"] is None:
                self.db.execute(
                    "UPDATE folders SET deleted_at=?, deletion_batch_id=?, "
                    "original_parent_folder_id=?, original_sort_order=? WHERE id=?",
                    (NOW, batch, row["parent_folder_id"], row["sort_order"], row["id"]),
                )
        self._validate()
        return batch

    def trash_note(self, note_id: str, *, fail=False) -> str:
        with self.write():
            note = self.active_note(note_id)
            result = self._delete_rows([], [], [note], fail=fail)
        return result

    def trash_notebook(self, notebook_id: str, *, fail=False) -> str:
        with self.write():
            notebook = self.active_notebook(notebook_id)
            if notebook["system_role"] == "quick_notes":
                raise DomainError("Cannot trash Quick Notes")
            notes = self.db.execute(
                "SELECT * FROM notes WHERE notebook_id=?", (notebook_id,)
            ).fetchall()
            result = self._delete_rows([], [notebook], notes, fail=fail)
        return result

    def trash_folder(self, folder_id: str, *, fail=False) -> str:
        with self.write():
            self.active_folder(folder_id)
            folders, notebooks, notes = self._descendants(folder_id)
            # Existing invalid active-under-trashed relationships must not be
            # silently 'fixed' by another deletion operation.
            self._validate()
            result = self._delete_rows(folders, notebooks, notes, fail=fail)
        return result

    def _quick_id(self) -> str:
        row = self.db.execute(
            "SELECT id FROM notebooks WHERE system_role='quick_notes' "
            "AND deleted_at IS NULL"
        ).fetchone()
        if row is None:
            raise DomainError("Quick Notes is required for note recovery")
        return row["id"]

    def restore_batch(self, batch: str, *, fail=False):
        """Restore only this deletion batch; root/Quick Notes fallback if needed."""
        with self.write():
            rows = {
                t: self.db.execute(
                    f"SELECT * FROM {t} WHERE deletion_batch_id=? AND deleted_at IS NOT NULL",
                    (batch,),
                ).fetchall()
                for t in ("folders", "notebooks", "notes")
            }
            if not any(rows.values()):
                raise DomainError("Deletion batch does not exist")
            restoring_folders = {r["id"] for r in rows["folders"]}
            restoring_notebooks = {r["id"] for r in rows["notebooks"]}

            def folder_destination(original):
                if original is None:
                    return None
                if original in restoring_folders:
                    return original
                candidate = self.db.execute(
                    "SELECT deleted_at FROM folders WHERE id=?", (original,)
                ).fetchone()
                return original if candidate is not None and candidate[0] is None else None

            for folder in rows["folders"]:
                parent = folder_destination(folder["original_parent_folder_id"])
                self.db.execute(
                    "UPDATE folders SET parent_folder_id=?, deleted_at=NULL, "
                    "deletion_batch_id=NULL, original_parent_folder_id=NULL, "
                    "original_sort_order=NULL WHERE id=?",
                    (parent, folder["id"]),
                )
            for notebook in rows["notebooks"]:
                parent = folder_destination(notebook["original_parent_folder_id"])
                self.db.execute(
                    "UPDATE notebooks SET parent_folder_id=?, deleted_at=NULL, "
                    "deletion_batch_id=NULL, original_parent_folder_id=NULL, "
                    "original_sort_order=NULL WHERE id=?",
                    (parent, notebook["id"]),
                )
            if fail:
                raise RuntimeError("Simulated restore failure")
            for note in rows["notes"]:
                original = note["original_notebook_id"]
                owner = self.db.execute(
                    "SELECT deleted_at FROM notebooks WHERE id=?", (original,)
                ).fetchone()
                if original in restoring_notebooks or (owner is not None and owner[0] is None):
                    destination = original
                else:
                    destination = self._quick_id()
                self.db.execute(
                    "UPDATE notes SET notebook_id=?, deleted_at=NULL, "
                    "deletion_batch_id=NULL, original_notebook_id=NULL WHERE id=?",
                    (destination, note["id"]),
                )
            self._validate()

    def purge_batch(self, batch: str, *, fail=False):
        """Hard-delete exactly this batch; retain other Trash batches."""
        with self.write():
            rows = {
                t: self.db.execute(
                    f"SELECT * FROM {t} WHERE deletion_batch_id=? AND deleted_at IS NOT NULL",
                    (batch,),
                ).fetchall()
                for t in ("folders", "notebooks", "notes")
            }
            if not any(rows.values()):
                raise DomainError("Deletion batch does not exist")
            folder_ids = {r["id"] for r in rows["folders"]}
            notebook_ids = {r["id"] for r in rows["notebooks"]}
            note_ids = {r["id"] for r in rows["notes"]}

            for row in self.db.execute("SELECT * FROM folders").fetchall():
                if row["id"] not in folder_ids and row["parent_folder_id"] in folder_ids:
                    if row["deleted_at"] is None:
                        raise DomainError("Cannot purge parent of an active folder")
                    self.db.execute(
                        "UPDATE folders SET parent_folder_id=NULL WHERE id=?", (row["id"],)
                    )
            for row in self.db.execute("SELECT * FROM notebooks").fetchall():
                if row["id"] not in notebook_ids and row["parent_folder_id"] in folder_ids:
                    if row["deleted_at"] is None:
                        raise DomainError("Cannot purge parent of active notebook")
                    self.db.execute(
                        "UPDATE notebooks SET parent_folder_id=NULL WHERE id=?", (row["id"],)
                    )
            for row in self.db.execute("SELECT * FROM notes").fetchall():
                if row["id"] not in note_ids and row["notebook_id"] in notebook_ids:
                    if row["deleted_at"] is None:
                        raise DomainError("Cannot purge notebook owning active note")
                    self.db.execute(
                        "UPDATE notes SET notebook_id=? WHERE id=?",
                        (self._quick_id(), row["id"]),
                    )
            if fail:
                raise RuntimeError("Simulated purge failure")
            
            # Remove dependent content before permanently deleting each note.
            # All changes remain inside the existing transaction.
            for row in rows["notes"]:
                self.db.execute(
                    "DELETE FROM asset_references WHERE note_id=?",
                    (row["id"],)
                )
                self.db.execute(
                    "DELETE FROM elements WHERE page_id IN "
                    "(SELECT id FROM pages WHERE note_id=?)",
                    (row["id"],)
                )
                self.db.execute(
                    "DELETE FROM pages WHERE note_id=?",
                    (row["id"],)
                )
                self.db.execute(
                    "DELETE FROM notes WHERE id=?",
                    (row["id"],)
                )

            for row in rows["notebooks"]:
                if row["system_role"] == "quick_notes":
                    raise DomainError("Cannot purge Quick Notes")
                self.db.execute("DELETE FROM notebooks WHERE id=?", (row["id"],))

            # FK RESTRICT requires leaf folders to be deleted first.
            remaining = set(folder_ids)
            while remaining:
                leaves = [
                    folder_id for folder_id in remaining
                    if not any(
                        child["parent_folder_id"] == folder_id
                        for child in self.db.execute("SELECT parent_folder_id FROM folders")
                    )
                ]
                if not leaves:
                    raise DomainError("Unable to purge folders without orphaning data")
                for folder_id in leaves:
                    self.db.execute("DELETE FROM folders WHERE id=?", (folder_id,))
                    remaining.remove(folder_id)
            self._validate()


class TrashInvariantTests(unittest.TestCase):
    def setUp(self):
        self.db = open_db()
        self.lib = TrashReferenceLibrary(self.db)

    def tearDown(self):
        self.db.close()

    def assert_valid(self):
        self.lib._validate()
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])
        self.assertEqual(self.db.execute("PRAGMA integrity_check").fetchone()[0], "ok")

    def row(self, table, entity_id):
        return self.lib._row(table, entity_id)

    def test_L11_folder_subtree_one_batch_with_original_locations(self):
        root = self.lib.create_folder("School")
        child = self.lib.create_folder("Year 1", root)
        book = self.lib.create_notebook("Math", child)
        note = self.lib.create_note(book)
        batch = self.lib.trash_folder(root)
        for table, entity_id in (("folders", root), ("folders", child),
                                 ("notebooks", book), ("notes", note)):
            self.assertEqual(self.row(table, entity_id)["deletion_batch_id"], batch)
            self.assertIsNotNone(self.row(table, entity_id)["deleted_at"])
        self.assertEqual(self.row("folders", child)["original_parent_folder_id"], root)
        self.assertEqual(self.row("notebooks", book)["original_parent_folder_id"], child)
        self.assertEqual(self.row("notes", note)["original_notebook_id"], book)
        self.assert_valid()

    def test_L12_restore_batch_without_reviving_independent_trash(self):
        root = self.lib.create_folder("School")
        child = self.lib.create_folder("Archive", root)
        child_book = self.lib.create_notebook("Archived", child)
        old_note = self.lib.create_note(child_book)
        independent = self.lib.trash_folder(child)
        active_book = self.lib.create_notebook("Math", root)
        active_note = self.lib.create_note(active_book)
        newer = self.lib.trash_folder(root)
        self.assertNotEqual(independent, newer)
        self.assertEqual(self.row("folders", child)["deletion_batch_id"], independent)
        self.lib.restore_batch(newer)
        for table, entity_id in (("folders", root), ("notebooks", active_book),
                                 ("notes", active_note)):
            self.assertIsNone(self.row(table, entity_id)["deleted_at"])
        for table, entity_id in (("folders", child), ("notebooks", child_book),
                                 ("notes", old_note)):
            self.assertEqual(self.row(table, entity_id)["deletion_batch_id"], independent)
        self.assert_valid()

    def test_L13_restore_after_parent_was_purged_goes_to_root(self):
        ancestor = self.lib.create_folder("Ancestor")
        child = self.lib.create_folder("Child", ancestor)
        old_batch = self.lib.trash_folder(child)
        ancestor_batch = self.lib.trash_folder(ancestor)
        self.lib.purge_batch(ancestor_batch)
        self.assertIsNone(self.row("folders", child)["parent_folder_id"])
        self.assertEqual(self.row("folders", child)["original_parent_folder_id"], ancestor)
        self.lib.restore_batch(old_batch)
        self.assertIsNone(self.row("folders", child)["deleted_at"])
        self.assertIsNone(self.row("folders", child)["parent_folder_id"])
        self.assert_valid()

    def test_L13_notebook_restore_after_original_folder_purged_goes_to_root(self):
        folder = self.lib.create_folder("Folder")
        book = self.lib.create_notebook("Book", folder)
        earlier = self.lib.trash_notebook(book)
        later = self.lib.trash_folder(folder)
        self.lib.purge_batch(later)
        self.assertIsNone(self.row("notebooks", book)["parent_folder_id"])
        self.assertEqual(self.row("notebooks", book)["original_parent_folder_id"], folder)
        self.lib.restore_batch(earlier)
        self.assertIsNone(self.row("notebooks", book)["parent_folder_id"])
        self.assertIsNone(self.row("notebooks", book)["deleted_at"])
        self.assert_valid()

    def test_L14_restoring_note_after_original_notebook_purge_uses_quick_notes(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        book = self.lib.create_notebook("Book")
        note = self.lib.create_note(book)
        independent = self.lib.trash_note(note)
        parent = self.lib.trash_notebook(book)
        self.lib.purge_batch(parent)
        self.assertEqual(self.row("notes", note)["notebook_id"], quick)
        self.assertEqual(self.row("notes", note)["original_notebook_id"], book)
        self.lib.restore_batch(independent)
        self.assertEqual(self.row("notes", note)["notebook_id"], quick)
        self.assertIsNone(self.row("notes", note)["deleted_at"])
        self.assert_valid()

    def test_L15_purge_parent_preserves_independent_folder_and_note(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        root = self.lib.create_folder("Root")
        child = self.lib.create_folder("Child", root)
        old_folder_batch = self.lib.trash_folder(child)
        book = self.lib.create_notebook("Book", root)
        keep_note = self.lib.create_note(book)
        old_note_batch = self.lib.trash_note(keep_note)
        purge_batch = self.lib.trash_folder(root)
        self.lib.purge_batch(purge_batch)
        self.assertIsNone(self.db.execute("SELECT id FROM folders WHERE id=?", (root,)).fetchone())
        self.assertIsNone(self.db.execute("SELECT id FROM notebooks WHERE id=?", (book,)).fetchone())
        self.assertEqual(self.row("folders", child)["deletion_batch_id"], old_folder_batch)
        self.assertIsNone(self.row("folders", child)["parent_folder_id"])
        self.assertEqual(self.row("notes", keep_note)["deletion_batch_id"], old_note_batch)
        self.assertEqual(self.row("notes", keep_note)["notebook_id"], quick)
        self.assertEqual(self.row("notes", keep_note)["original_notebook_id"], book)
        self.assert_valid()

    def test_L16_delete_restore_purge_failure_rolls_back_exact_state(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        root = self.lib.create_folder("Root")
        book = self.lib.create_notebook("Book", root)
        note = self.lib.create_note(book)
        before = state(self.db)
        with self.assertRaisesRegex(RuntimeError, "Simulated delete failure"):
            self.lib.trash_folder(root, fail=True)
        self.assertEqual(state(self.db), before)
        original = self.lib.trash_folder(root)
        before_restore = state(self.db)
        with self.assertRaisesRegex(RuntimeError, "Simulated restore failure"):
            self.lib.restore_batch(original, fail=True)
        self.assertEqual(state(self.db), before_restore)
        with self.assertRaisesRegex(RuntimeError, "Simulated purge failure"):
            self.lib.purge_batch(original, fail=True)
        self.assertEqual(state(self.db), before_restore)
        self.assert_valid()

    def test_no_quick_notes_note_restore_fallback_rejected_without_mutation(self):
        book = self.lib.create_notebook("Book")
        note = self.lib.create_note(book)
        independent = self.lib.trash_note(note)
        self.lib.trash_notebook(book)
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.restore_batch(independent)
        self.assertEqual(state(self.db), before)
        self.assert_valid()

    def test_purge_parent_without_safe_rehome_rejected_without_mutation(self):
        book = self.lib.create_notebook("Book")
        note = self.lib.create_note(book)
        independent = self.lib.trash_note(note)
        purge = self.lib.trash_notebook(book)
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.purge_batch(purge)
        self.assertEqual(self.row("notes", note)["deletion_batch_id"], independent)
        self.assertEqual(state(self.db), before)
        self.assert_valid()

    def test_purge_of_active_or_missing_batch_rejected_without_mutation(self):
        book = self.lib.create_notebook("Book")
        self.lib.create_note(book)
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.purge_batch(uuid())
        with self.assertRaises(DomainError):
            self.lib.restore_batch(uuid())
        self.assertEqual(state(self.db), before)
        self.assert_valid()

    def test_cannot_trash_quick_notes(self):
        quick = self.lib.create_notebook("Quick Notes", system_role="quick_notes")
        before = state(self.db)
        with self.assertRaises(DomainError):
            self.lib.trash_notebook(quick)
        self.assertEqual(state(self.db), before)
        self.assert_valid()


if __name__ == "__main__":
    unittest.main()
