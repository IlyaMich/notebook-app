package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests upgrade from the exported V1 table/index schema (1.json), not V2-created fixtures. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationV1ToV2Test {
    private lateinit var context: Context
    private lateinit var name: String
    private var opened: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto02-migration-${UUID.randomUUID()}.sqlite"
        context.deleteDatabase(name)
    }

    @After fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    @Test fun v1MetadataSurvivesAndLegacyNotesReceiveOneInitialPage() = runBlocking {
        val notebookId = UUID.randomUUID().toString()
        val activeNoteId = UUID.randomUUID().toString()
        val trashedNoteId = UUID.randomUUID().toString()
        val oldFolderId = UUID.randomUUID().toString()

        // Build a real V1 database. The table/index statements are copied from
        // the checked-in Room schema 1.json, which must never be overwritten.
        val sqlite = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            legacySchemaSql.forEach { sqlite.execSQL(it) }
            sqlite.execSQL(
                """INSERT INTO folders(id,parent_folder_id,name,sort_order,created_at,updated_at)
                   VALUES(?,NULL,?,0,100,101)""",
                arrayOf(oldFolderId, "Old folder"),
            )
            sqlite.execSQL(
                """INSERT INTO notebooks(id,parent_folder_id,name,sort_order,system_role,created_at,updated_at)
                   VALUES(?,NULL,?,0,'quick_notes',100,102)""",
                arrayOf(notebookId, "Quick Notes"),
            )
            sqlite.execSQL(
                """INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision)
                   VALUES(?,?,?,100,234,7)""",
                arrayOf(activeNoteId, notebookId, "Pre-upgrade note"),
            )
            sqlite.execSQL(
                """INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision,deleted_at)
                   VALUES(?,?,?,100,235,3,999)""",
                arrayOf(trashedNoteId, notebookId, "Trashed old note"),
            )
            // V1 supplement: triggers are created on Room onCreate only, not
            // represented in 1.json. Their presence must survive the upgrade.
            sqlite.execSQL(
                """CREATE TRIGGER trg_quick_notes_no_delete BEFORE DELETE ON notebooks
                   WHEN OLD.system_role = 'quick_notes'
                   BEGIN SELECT RAISE(ABORT, 'Quick Notes cannot be deleted'); END"""
            )
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL(
                "INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42, ?)",
                arrayOf("ec7999347ded6c2d9c465a283c362cb3"),
            )
            sqlite.version = 1
        } finally {
            sqlite.close()
        }

        val db = AppDatabaseProvider.openIsolated(context, name)
        opened = db
        val previous = db.noteDao().findById(activeNoteId)!!
        assertEquals(7L, previous.revision)
        assertEquals(234L, previous.updatedAtMillis)
        assertEquals(notebookId, previous.notebookId)
        assertEquals("Pre-upgrade note", previous.title)
        assertEquals("Old folder", db.folderDao().findById(oldFolderId)?.name)
        assertEquals("quick_notes", db.notebookDao().findQuickNotes()?.systemRole)

        val pages = db.pageDao().listByNote(activeNoteId)
        assertEquals(1, pages.size)
        assertEquals(0L, pages.single().orderIndex)
        assertEquals(595.276, pages.single().width, 0.000001)
        assertEquals(841.890, pages.single().height, 0.000001)
        assertEquals("""{"type":"blank","color":"#FFFFFF"}""", pages.single().backgroundJson)
        assertEquals(7L, pages.single().revision)
        assertEquals(1, db.pageDao().countByNote(trashedNoteId))
        assertEquals(3L, db.noteDao().findById(trashedNoteId)!!.revision)
        assertNotEquals(pages.single().id, db.pageDao().listByNote(trashedNoteId).single().id)

        // Legacy safety trigger survives; migration must not dismantle it.
        val legacyTrigger = db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_quick_notes_no_delete'"
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
        assertEquals(1, legacyTrigger)

        db.close()
        opened = null
        val reopened = AppDatabaseProvider.openIsolated(context, name)
        opened = reopened
        assertEquals(pages, reopened.pageDao().listByNote(activeNoteId))
        assertEquals(2, reopened.noteDao().countAll())
        assertEquals(0, reopened.elementDao().countByPage(pages.single().id))
        assertEquals(2, reopened.openHelper.writableDatabase.version)
    }

    private val legacySchemaSql = listOf(
            """CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
            """CREATE INDEX IF NOT EXISTS `ix_folders_parent_listing` ON `folders` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)""",
            """CREATE INDEX IF NOT EXISTS `ix_folders_deletion_batch` ON `folders` (`deletion_batch_id`)""",
            """CREATE TABLE IF NOT EXISTS `notebooks` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `color` TEXT, `sort_order` INTEGER NOT NULL, `system_role` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
            """CREATE INDEX IF NOT EXISTS `ix_notebooks_parent_listing` ON `notebooks` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)""",
            """CREATE INDEX IF NOT EXISTS `ix_notebooks_deletion_batch` ON `notebooks` (`deletion_batch_id`)""",
            """CREATE UNIQUE INDEX IF NOT EXISTS `ux_notebooks_quick_notes_role` ON `notebooks` (`system_role`)""",
            """CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `notebook_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_opened_at` INTEGER, `revision` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_notebook_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`notebook_id`) REFERENCES `notebooks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
            """CREATE INDEX IF NOT EXISTS `ix_notes_notebook_recent` ON `notes` (`notebook_id`, `deleted_at`, `updated_at`, `id`)""",
            """CREATE INDEX IF NOT EXISTS `ix_notes_deletion_batch` ON `notes` (`deletion_batch_id`)"""
    )
}
