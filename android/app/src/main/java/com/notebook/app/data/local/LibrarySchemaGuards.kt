package com.notebook.app.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Supplemental SQLite triggers not expressible via Room @Entity CHECK metadata.
 * Created once alongside Room's schema; every future migration must preserve
 * these invariants. Domain repositories still own full cycle/Trash validation.
 */
internal object LibrarySchemaGuards {
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            val triggers = listOf(
                """CREATE TRIGGER IF NOT EXISTS trg_quick_notes_no_delete
                   BEFORE DELETE ON notebooks WHEN OLD.system_role = 'quick_notes'
                   BEGIN SELECT RAISE(ABORT, 'Quick Notes cannot be deleted'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_quick_notes_no_demote
                   BEFORE UPDATE OF system_role ON notebooks
                   WHEN OLD.system_role = 'quick_notes' AND NEW.system_role IS NOT 'quick_notes'
                   BEGIN SELECT RAISE(ABORT, 'Quick Notes role cannot be removed'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_notebook_role_insert
                   BEFORE INSERT ON notebooks
                   WHEN NEW.system_role IS NOT NULL AND NEW.system_role != 'quick_notes'
                   BEGIN SELECT RAISE(ABORT, 'Invalid notebook system role'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_notebook_role_update
                   BEFORE UPDATE OF system_role ON notebooks
                   WHEN NEW.system_role IS NOT NULL AND NEW.system_role != 'quick_notes'
                   BEGIN SELECT RAISE(ABORT, 'Invalid notebook system role'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_quick_notes_root_insert
                   BEFORE INSERT ON notebooks
                   WHEN NEW.system_role = 'quick_notes'
                     AND (NEW.parent_folder_id IS NOT NULL OR NEW.deleted_at IS NOT NULL)
                   BEGIN SELECT RAISE(ABORT, 'Quick Notes must be active at root'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_quick_notes_root_update
                   BEFORE UPDATE OF parent_folder_id, deleted_at, system_role ON notebooks
                   WHEN NEW.system_role = 'quick_notes'
                     AND (NEW.parent_folder_id IS NOT NULL OR NEW.deleted_at IS NOT NULL)
                   BEGIN SELECT RAISE(ABORT, 'Quick Notes must be active at root'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_folder_no_self_insert
                   BEFORE INSERT ON folders WHEN NEW.parent_folder_id = NEW.id
                   BEGIN SELECT RAISE(ABORT, 'Folder cannot parent itself'); END""",
                """CREATE TRIGGER IF NOT EXISTS trg_folder_no_self_update
                   BEFORE UPDATE OF parent_folder_id ON folders WHEN NEW.parent_folder_id = NEW.id
                   BEGIN SELECT RAISE(ABORT, 'Folder cannot parent itself'); END""",
            )
            triggers.forEach(db::execSQL)
        }
    }
}
