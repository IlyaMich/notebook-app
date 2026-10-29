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
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notebook.app.data.local.migration.AppDatabaseMigrations

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
        val childFolderId = UUID.randomUUID().toString()
        val trashedNotebookId = UUID.randomUUID().toString()
        val deletionBatchId = UUID.randomUUID().toString()
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
                """INSERT INTO folders(id, parent_folder_id, name, sort_order, created_at, updated_at, deleted_at, deletion_batch_id, original_parent_folder_id, original_sort_order)
                    VALUES(?,?,?,1,100,103,999,?,?,1)""",
                arrayOf(childFolderId, oldFolderId, "Trashed child folder", deletionBatchId, oldFolderId,),
            )
            sqlite.execSQL(
                """INSERT INTO notebooks(id,parent_folder_id,name,sort_order,system_role,created_at,updated_at)
                   VALUES(?,NULL,?,0,'quick_notes',100,102)""",
                arrayOf(notebookId, "Quick Notes"),
            )
            sqlite.execSQL(
                """INSERT INTO notebooks(id, parent_folder_id, name, sort_order, system_role, created_at, updated_at, deleted_at, deletion_batch_id, original_parent_folder_id, original_sort_order)
                    VALUES(?,?,?,1,NULL,100,104,999,?,?,1)""",
                arrayOf(trashedNotebookId, childFolderId, "Trashed notebook", deletionBatchId, childFolderId,),
            )
            sqlite.execSQL(
                """INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision)
                   VALUES(?,?,?,100,234,7)""",
                arrayOf(activeNoteId, notebookId, "Pre-upgrade note"),
            )
            sqlite.execSQL(
                """INSERT INTO notes(id, notebook_id, title, created_at, updated_at, revision, deleted_at, deletion_batch_id, original_notebook_id)
                    VALUES(?,?,?,100,235,3,999,?,?)""",
                arrayOf(trashedNoteId, trashedNotebookId, "Trashed old note", deletionBatchId, trashedNotebookId,),
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

        // M02: Verify nested hierarchy and Trash recovery metadata.
        val upgradedSql = db.openHelper.writableDatabase

        upgradedSql.query(
            """SELECT parent_folder_id, deleted_at,
              deletion_batch_id, original_parent_folder_id,
              original_sort_order
       FROM folders WHERE id = ?""",
            arrayOf(childFolderId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(oldFolderId, cursor.getString(0))
            assertEquals(999L, cursor.getLong(1))
            assertEquals(deletionBatchId, cursor.getString(2))
            assertEquals(oldFolderId, cursor.getString(3))
            assertEquals(1L, cursor.getLong(4))
        }

        upgradedSql.query(
            """SELECT parent_folder_id, deleted_at,
              deletion_batch_id, original_parent_folder_id,
              original_sort_order
       FROM notebooks WHERE id = ?""",
            arrayOf(trashedNotebookId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(childFolderId, cursor.getString(0))
            assertEquals(999L, cursor.getLong(1))
            assertEquals(deletionBatchId, cursor.getString(2))
            assertEquals(childFolderId, cursor.getString(3))
            assertEquals(1L, cursor.getLong(4))
        }

        upgradedSql.query(
            """SELECT notebook_id, deleted_at, deletion_batch_id,
              original_notebook_id, revision
       FROM notes WHERE id = ?""",
            arrayOf(trashedNoteId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(trashedNotebookId, cursor.getString(0))
            assertEquals(999L, cursor.getLong(1))
            assertEquals(deletionBatchId, cursor.getString(2))
            assertEquals(trashedNotebookId, cursor.getString(3))
            assertEquals(3L, cursor.getLong(4))
        }

        // SQLite must report no orphan foreign-key relationships.
        upgradedSql.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(
                "Historical migration introduced foreign-key violations",
                0,
                cursor.count,
            )
        }

        // Verify the database structure remains internally consistent.
        upgradedSql.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }

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

    @Test
    fun failedMigrationRollsBackAndNormalRetrySucceeds() = runBlocking {
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()

        // Step 1: Construct a historical V1 database.
        val legacy = context.openOrCreateDatabase(
            name,
            Context.MODE_PRIVATE,
            null,
        )

        try {
            legacySchemaSql.forEach { legacy.execSQL(it) }

            legacy.execSQL(
                """INSERT INTO notebooks(
                id, parent_folder_id, name, sort_order,
                system_role, created_at, updated_at
            ) VALUES (?, NULL, ?, 0, 'quick_notes', 100, 100)""",
                arrayOf(notebookId, "Quick Notes"),
            )

            legacy.execSQL(
                """INSERT INTO notes(
                id, notebook_id, title,
                created_at, updated_at, revision
            ) VALUES (?, ?, ?, 100, 200, 7)""",
                arrayOf(noteId, notebookId, "Important V1 note"),
            )

            legacy.execSQL(
                """CREATE TABLE IF NOT EXISTS room_master_table (
                id INTEGER PRIMARY KEY,
                identity_hash TEXT
            )"""
            )

            legacy.execSQL(
                """INSERT OR REPLACE INTO room_master_table(
                id, identity_hash
            ) VALUES(42, ?)""",
                arrayOf("ec7999347ded6c2d9c465a283c362cb3"),
            )

            legacy.version = 1
        } finally {
            legacy.close()
        }

        // Step 2: Execute the real migration, then deliberately
        // fail before the enclosing upgrade transaction commits.
        var migrationWasInvoked = false

        val failingMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppDatabaseMigrations.MIGRATION_1_2.migrate(db)

                migrationWasInvoked = true

                throw IllegalStateException(
                    "STO-07 injected migration failure"
                )
            }
        }

        val failingDatabase = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            name,
        )
            .addCallback(LibrarySchemaGuards.callback)
            .addMigrations(failingMigration)
            .build()

        val failure = try {
            runCatching {
                // Room opens lazily: force the migration.
                failingDatabase.openHelper.writableDatabase
            }.exceptionOrNull()
        } finally {
            failingDatabase.close()
        }

        assertTrue(
            "The injected migration must execute",
            migrationWasInvoked,
        )

        val failureMessages = generateSequence(failure) {
            it.cause
        }.mapNotNull {
            it.message
        }.joinToString(" | ")

        assertTrue(
            "Expected injected failure, got: $failureMessages",
            failureMessages.contains(
                "STO-07 injected migration failure"
            ),
        )

        // Step 3: Inspect the database without Room, before retry.
        val verify = context.openOrCreateDatabase(
            name,
            Context.MODE_PRIVATE,
            null,
        )

        try {
            // The failed transaction must not upgrade the DB.
            assertEquals(1, verify.version)

            // The original note and revision must survive.
            verify.rawQuery(
                "SELECT title, revision FROM notes WHERE id = ?",
                arrayOf(noteId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "Important V1 note",
                    cursor.getString(0),
                )
                assertEquals(7L, cursor.getLong(1))
            }

            // Tables created during the failed migration must
            // have been rolled back.
            verify.rawQuery(
                """SELECT COUNT(*) FROM sqlite_master
               WHERE type = 'table'
               AND name IN ('pages', 'elements')""",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }

            // SQLite must remain internally consistent.
            verify.rawQuery("PRAGMA integrity_check", null).use {
                    cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }

            verify.rawQuery("PRAGMA foreign_key_check", null).use {
                    cursor ->
                assertEquals(0, cursor.count)
            }
        } finally {
            verify.close()
        }

        // Step 4: Retry using the normal production provider.
        val recovered = AppDatabaseProvider.openIsolated(
            context,
            name,
        )
        opened = recovered

        // Force normal migration and verify V2.
        assertEquals(
            2,
            recovered.openHelper.writableDatabase.version,
        )

        val recoveredNote = recovered.noteDao().findById(noteId)!!

        assertEquals("Important V1 note", recoveredNote.title)
        assertEquals(7L, recoveredNote.revision)

        val pages = recovered.pageDao().listByNote(noteId)

        assertEquals(1, pages.size)
        assertEquals(noteId, pages.single().noteId)
        assertEquals(7L, pages.single().revision)
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

    @Test
    fun unsupportedNewerDatabaseIsRejectedWithoutDataLoss() {
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()

        // Step 1: Create a genuine Room V2 database containing
        // existing notebook, note, and page records.
        val v2 = AppDatabaseProvider.openIsolated(context, name)
        try {
            val sql = v2.openHelper.writableDatabase

            sql.execSQL(
                """INSERT INTO notebooks
                (id, parent_folder_id, name, sort_order,
                 system_role, created_at, updated_at)
               VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
                arrayOf(notebookId, "Existing notebook"),
            )

            sql.execSQL(
                """INSERT INTO notes
                (id, notebook_id, title,
                 created_at, updated_at, revision)
               VALUES (?, ?, ?, 100, 200, 9)""",
                arrayOf(noteId, notebookId, "Important note"),
            )

            sql.execSQL(
                """INSERT INTO pages
                (id, note_id, order_index, width, height,
                 background_json, revision)
               VALUES (?, ?, 0, 595.276, 841.890, ?, 9)""",
                arrayOf(
                    pageId,
                    noteId,
                    """{"type":"blank","color":"#FFFFFF"}""",
                ),
            )
        } finally {
            v2.close()
        }

        // Step 2: Simulate an application update that introduces
        // a future schema version and additional data.
        val future = context.openOrCreateDatabase(
            name, Context.MODE_PRIVATE, null
        )
        try {
            future.execSQL(
                """CREATE TABLE future_v3_marker (
                id INTEGER PRIMARY KEY NOT NULL,
                payload TEXT NOT NULL
            )"""
            )
            future.execSQL(
                """INSERT INTO future_v3_marker (id, payload)
               VALUES (1, 'must survive downgrade attempt')"""
            )
            future.version = 3
        } finally {
            future.close()
        }

        // Step 3: Try to open the V3 database using the current
        // V2 provider. No downgrade migration exists.
        val incompatible = AppDatabaseProvider.openIsolated(
            context, name
        )

        val failure = try {
            runCatching {
                // Room opens lazily, so force the database open.
                incompatible.openHelper.writableDatabase
            }.exceptionOrNull()
        } finally {
            incompatible.close()
        }

        assertTrue(
            "Room V2 must reject an unsupported V3 database",
            failure != null,
        )

        val errorMessages = generateSequence(failure) {
            it.cause
        }.mapNotNull {
            it.message
        }.joinToString(" | ")

        assertTrue(
            "Expected migration/downgrade rejection, got: $errorMessages",
            errorMessages.contains("migration", ignoreCase = true) ||
                errorMessages.contains("downgrade", ignoreCase = true),
        )

        // Step 4: Verify the rejected database was not reset,
        // downgraded, or stripped of its existing records.
        val verify = context.openOrCreateDatabase(
            name, Context.MODE_PRIVATE, null
        )
        try {
            assertEquals(3, verify.version)

            verify.rawQuery(
                "SELECT title, revision FROM notes WHERE id = ?",
                arrayOf(noteId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Important note", cursor.getString(0))
                assertEquals(9L, cursor.getLong(1))
            }

            verify.rawQuery(
                "SELECT note_id FROM pages WHERE id = ?",
                arrayOf(pageId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(noteId, cursor.getString(0))
            }

            verify.rawQuery(
                "SELECT payload FROM future_v3_marker WHERE id = 1",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "must survive downgrade attempt",
                    cursor.getString(0),
                )
            }

            verify.rawQuery("PRAGMA quick_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
        } finally {
            verify.close()
        }
    }

}
