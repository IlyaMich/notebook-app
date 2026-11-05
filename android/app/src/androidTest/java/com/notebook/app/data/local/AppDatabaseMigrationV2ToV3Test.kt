package com.notebook.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.migration.AppDatabaseMigrations
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies upgrades from the checked-in historical Room V2 schema, never a V3 schema. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationV2ToV3Test {
    private lateinit var context: Context
    private lateinit var name: String
    private var opened: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto04-v2-to-v3-${UUID.randomUUID()}.sqlite"
        context.deleteDatabase(name)
    }

    @After fun tearDown() {
        opened?.close()
        context.deleteDatabase(name)
    }

    @Test fun v2DocumentsAndTrashSurviveAndAssetsAreQueryable() = runBlocking {
        val ids = createV2WithContent()
        val db = AppDatabaseProvider.openIsolated(context, name)
        opened = db
        val sql = db.openHelper.writableDatabase
        assertEquals(4, sql.version)

        val active = db.noteDao().findById(ids.activeNote)!!
        assertEquals("Original active note", active.title)
        assertEquals(17L, active.revision)
        assertEquals(234L, active.updatedAtMillis)
        val trash = db.noteDao().findById(ids.trashNote)!!
        assertEquals("Original trashed note", trash.title)
        assertEquals(4L, trash.revision)
        assertEquals(999L, trash.deletedAtMillis)
        assertEquals(ids.batch, trash.deletionBatchId)
        assertEquals(ids.notebook, trash.originalNotebookId)

        val pages = db.pageDao().listByNote(ids.activeNote)
        assertEquals(1, pages.size)
        assertEquals(ids.page, pages.single().id)
        assertEquals(17L, pages.single().revision)
        assertEquals(1, db.elementDao().countByPage(ids.page))
        assertEquals(1, db.pageDao().countByNote(ids.trashNote))
        assertEquals(0, db.assetDao().countAll())

        val digest = "a".repeat(64)
        val path = "assets/aa/aa/$digest.png"
        val asset = AssetEntity(
            id = UUID.randomUUID().toString(), sha256 = digest, mimeType = "image/png",
            byteSize = 42, relativePath = path, state = "finalized",
        )
        db.assetDao().insert(asset)
        assertEquals(asset, db.assetDao().findBySha256(digest))
        assertEquals(asset, db.assetDao().findByRelativePath(path))
        assertEquals(asset, db.assetDao().findById(asset.id))

        val duplicateDigestFailure = runCatching {
            db.assetDao().insert(asset.copy(id = UUID.randomUUID().toString(), relativePath = "assets/other.png"))
        }.exceptionOrNull()
        assertNotNull("SHA-256 uniqueness must be enforced", duplicateDigestFailure)
        val duplicatePathFailure = runCatching {
            db.assetDao().insert(asset.copy(id = UUID.randomUUID().toString(), sha256 = "b".repeat(64)))
        }.exceptionOrNull()
        assertNotNull("Relative-path uniqueness must be enforced", duplicatePathFailure)

        sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_sto04_v2_keep'")
            .use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        sql.query("PRAGMA foreign_key_check").use { c -> assertEquals(0, c.count) }
        sql.query("PRAGMA integrity_check").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0))
        }

        db.close()
        opened = null
        val reopened = AppDatabaseProvider.openIsolated(context, name)
        opened = reopened
        assertEquals(4, reopened.openHelper.writableDatabase.version)
        assertEquals(asset, reopened.assetDao().findById(asset.id))
        assertEquals("Original active note", reopened.noteDao().findById(ids.activeNote)?.title)
        assertEquals(1, reopened.elementDao().countByPage(ids.page))
    }

    @Test fun failedV2ToV3MigrationRollsBackAndNormalRetrySucceeds() = runBlocking {
        val ids = createV2WithContent()
        var invoked = false
        val failing = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppDatabaseMigrations.MIGRATION_2_3.migrate(db)
                invoked = true
                throw IllegalStateException("STO-04 injected V2-to-V3 failure")
            }
        }
        val attempt = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(failing, AppDatabaseMigrations.MIGRATION_3_4)
            .build()
        val failure = try {
            runCatching { attempt.openHelper.writableDatabase }.exceptionOrNull()
        } finally {
            attempt.close()
        }
        assertTrue("Expected injected migration to execute", invoked)
        assertTrue("Expected injected migration to fail", failure != null)
        assertTrue(
            generateSequence(failure) { it.cause }.mapNotNull { it.message }
                .any { it.contains("STO-04 injected V2-to-V3 failure") }
        )

        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            assertEquals(2, raw.version)
            raw.rawQuery("SELECT revision FROM notes WHERE id = ?", arrayOf(ids.activeNote)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(17L, c.getLong(0))
            }
            raw.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='assets'", null
            ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            raw.rawQuery("PRAGMA integrity_check", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0))
            }
        } finally {
            raw.close()
        }

        val recovered = AppDatabaseProvider.openIsolated(context, name)
        opened = recovered
        assertEquals(4, recovered.openHelper.writableDatabase.version)
        assertEquals(17L, recovered.noteDao().findById(ids.activeNote)?.revision)
        assertEquals(0, recovered.assetDao().countAll())
    }

    private data class Seed(
        val notebook: String, val activeNote: String, val trashNote: String,
        val page: String, val batch: String,
    )

    private fun createV2WithContent(): Seed {
        val ids = Seed(
            notebook = UUID.randomUUID().toString(),
            activeNote = UUID.randomUUID().toString(),
            trashNote = UUID.randomUUID().toString(),
            page = UUID.randomUUID().toString(),
            batch = UUID.randomUUID().toString(),
        )
        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            // Generated by the installer directly from checked-in 2.json.
            historicalV2SchemaSql.forEach { old.execSQL(it) }
            old.execSQL(
                """INSERT INTO notebooks(id, parent_folder_id, name, sort_order, system_role,
                    created_at, updated_at) VALUES (?, NULL, 'Original notebook', 0, NULL, 100, 100)""",
                arrayOf(ids.notebook),
            )
            old.execSQL(
                """INSERT INTO notes(id, notebook_id, title, created_at, updated_at, revision)
                    VALUES (?, ?, 'Original active note', 100, 234, 17)""",
                arrayOf(ids.activeNote, ids.notebook),
            )
            old.execSQL(
                """INSERT INTO notes(id, notebook_id, title, created_at, updated_at, revision,
                    deleted_at, deletion_batch_id, original_notebook_id)
                    VALUES (?, ?, 'Original trashed note', 100, 235, 4, 999, ?, ?)""",
                arrayOf(ids.trashNote, ids.notebook, ids.batch, ids.notebook),
            )
            old.execSQL(
                """INSERT INTO pages(id, note_id, order_index, width, height, background_json, revision)
                    VALUES (?, ?, 0, 595.276, 841.890, ?, 17)""",
                arrayOf(ids.page, ids.activeNote, """{"type":"blank","color":"#FFFFFF"}"""),
            )
            old.execSQL(
                """INSERT INTO pages(id, note_id, order_index, width, height, background_json, revision)
                    VALUES (?, ?, 0, 595.276, 841.890, ?, 4)""",
                arrayOf(UUID.randomUUID().toString(), ids.trashNote, """{"type":"blank","color":"#FFFFFF"}"""),
            )
            old.execSQL(
                """INSERT INTO elements(id, page_id, order_index, kind, element_json, revision)
                    VALUES (?, ?, 0, 'stroke', ?, 17)""",
                arrayOf(UUID.randomUUID().toString(), ids.page, """{"type":"stroke"}"""),
            )
            old.execSQL(
                """CREATE TRIGGER trg_sto04_v2_keep BEFORE DELETE ON notes
                    BEGIN SELECT RAISE(ABORT, 'do not delete'); END"""
            )
            old.version = 2
        } finally {
            old.close()
        }
        return ids
    }

    private val historicalV2SchemaSql = listOf(
        """CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE INDEX IF NOT EXISTS `ix_folders_parent_listing` ON `folders` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)""",
        """CREATE INDEX IF NOT EXISTS `ix_folders_deletion_batch` ON `folders` (`deletion_batch_id`)""",
        """CREATE TABLE IF NOT EXISTS `notebooks` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `color` TEXT, `sort_order` INTEGER NOT NULL, `system_role` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE INDEX IF NOT EXISTS `ix_notebooks_parent_listing` ON `notebooks` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)""",
        """CREATE INDEX IF NOT EXISTS `ix_notebooks_deletion_batch` ON `notebooks` (`deletion_batch_id`)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `ux_notebooks_quick_notes_role` ON `notebooks` (`system_role`)""",
        """CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `notebook_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_opened_at` INTEGER, `revision` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_notebook_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`notebook_id`) REFERENCES `notebooks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE INDEX IF NOT EXISTS `ix_notes_notebook_recent` ON `notes` (`notebook_id`, `deleted_at`, `updated_at`, `id`)""",
        """CREATE INDEX IF NOT EXISTS `ix_notes_deletion_batch` ON `notes` (`deletion_batch_id`)""",
        """CREATE TABLE IF NOT EXISTS `pages` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, `background_json` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `ux_pages_note_order` ON `pages` (`note_id`, `order_index`)""",
        """CREATE TABLE IF NOT EXISTS `elements` (`id` TEXT NOT NULL, `page_id` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `kind` TEXT NOT NULL, `element_json` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `ux_elements_page_order` ON `elements` (`page_id`, `order_index`)""",
        """CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)""",
        """INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'da589ec1b3c4da4201ee43ceeb387216')""",
    )
}
