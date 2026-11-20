package com.notebook.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genuine migrated Room v5 database reconstructed from the COMMITTED 5.json
 * schema (identity hash cbb9ed5588a80c70be9a3468a28d271f); no production DB touched.
 * Tests legacy hold retention, zero binding backfill, and irreversible v6 guards.
 */
@RunWith(AndroidJUnit4::class)
class Sto08ExportSourceBindingV5ToV6MigrationAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private var database: AppDatabase? = null
    private val operation = UUID.randomUUID().toString()
    private val note = UUID.randomUUID().toString()
    private val asset = UUID.randomUUID().toString()
    private val digest = "a".repeat(64)

    private val v5Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_folders_parent_listing` ON `folders` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)",
        "CREATE INDEX IF NOT EXISTS `ix_folders_deletion_batch` ON `folders` (`deletion_batch_id`)",
        "CREATE TABLE IF NOT EXISTS `notebooks` (`id` TEXT NOT NULL, `parent_folder_id` TEXT, `name` TEXT NOT NULL, `color` TEXT, `sort_order` INTEGER NOT NULL, `system_role` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_parent_folder_id` TEXT, `original_sort_order` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`parent_folder_id`) REFERENCES `folders`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_notebooks_parent_listing` ON `notebooks` (`parent_folder_id`, `deleted_at`, `sort_order`, `id`)",
        "CREATE INDEX IF NOT EXISTS `ix_notebooks_deletion_batch` ON `notebooks` (`deletion_batch_id`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `ux_notebooks_quick_notes_role` ON `notebooks` (`system_role`)",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `notebook_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_opened_at` INTEGER, `revision` INTEGER NOT NULL, `deleted_at` INTEGER, `deletion_batch_id` TEXT, `original_notebook_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`notebook_id`) REFERENCES `notebooks`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_notes_notebook_recent` ON `notes` (`notebook_id`, `deleted_at`, `updated_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `ix_notes_deletion_batch` ON `notes` (`deletion_batch_id`)",
        "CREATE TABLE IF NOT EXISTS `pages` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, `background_json` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `ux_pages_note_order` ON `pages` (`note_id`, `order_index`)",
        "CREATE TABLE IF NOT EXISTS `elements` (`id` TEXT NOT NULL, `page_id` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `kind` TEXT NOT NULL, `element_json` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `ux_elements_page_order` ON `elements` (`page_id`, `order_index`)",
        "CREATE TABLE IF NOT EXISTS `assets` (`id` TEXT NOT NULL, `sha256` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `byte_size` INTEGER NOT NULL, `relative_path` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_sha256` ON `assets` (`sha256`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_relative_path` ON `assets` (`relative_path`)",
        "CREATE TABLE IF NOT EXISTS `page_asset_references` (`page_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, PRIMARY KEY(`page_id`), FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_page_asset_references_asset` ON `page_asset_references` (`asset_id`)",
        "CREATE TABLE IF NOT EXISTS `element_asset_references` (`element_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, PRIMARY KEY(`element_id`), FOREIGN KEY(`element_id`) REFERENCES `elements`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_element_asset_references_asset` ON `element_asset_references` (`asset_id`)",
        "CREATE TABLE IF NOT EXISTS `asset_reference_index_state` (`id` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `export_operations` (`operation_id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `note_revision` INTEGER NOT NULL, `state` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, `protocol_version` INTEGER NOT NULL, PRIMARY KEY(`operation_id`))",
        "CREATE TABLE IF NOT EXISTS `export_asset_holds` (`operation_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, PRIMARY KEY(`operation_id`, `asset_id`), FOREIGN KEY(`operation_id`) REFERENCES `export_operations`(`operation_id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `ix_export_asset_holds_asset` ON `export_asset_holds` (`asset_id`)",
    )

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-v5-v6-" + UUID.randomUUID().toString() + ".sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        val file = context.getDatabasePath(name)
        assertFalse(file.exists())
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            for (sql in v5Ddl) db.execSQL(sql)
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42,'cbb9ed5588a80c70be9a3468a28d271f')")
            db.execSQL("INSERT INTO asset_reference_index_state(id,state) VALUES(1,'UNVERIFIED')")
            db.execSQL("INSERT INTO assets(id,sha256,mime_type,byte_size,relative_path,state) VALUES(?,?,'image/png',4,?,'finalized')",
                arrayOf(asset, digest, "assets/aa/aa/" + digest + ".png"))
            db.execSQL(
                "INSERT INTO export_operations(operation_id,note_id,note_revision,state,created_at_ms,updated_at_ms,protocol_version) VALUES(?,?,2,'COMMITTED_PIN_SET',123,123,1)",
                arrayOf(operation, note))
            db.execSQL("INSERT INTO export_asset_holds(operation_id,asset_id) VALUES(?,?)",
                arrayOf(operation, asset))
            db.version = 5
        }
    }

    @After fun teardown() {
        database?.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
    }

    @Test fun legacyV5HoldAndUnverifiedGateSurviveWithoutInventedSourceBinding() {
        val db = AppDatabaseProvider.openIsolated(context, name)
        database = db
        val sql = db.openHelper.writableDatabase
        assertEquals(6, sql.version)
        assertEquals(1, count(sql, "SELECT COUNT(*) FROM export_operations WHERE operation_id=?", operation))
        assertEquals(1, count(sql, "SELECT COUNT(*) FROM export_asset_holds WHERE operation_id=? AND asset_id=?", operation, asset))
        assertEquals(0, count(sql, "SELECT COUNT(*) FROM export_source_bindings"))
        assertEquals(1, count(sql, "SELECT COUNT(*) FROM asset_reference_index_state WHERE state='UNVERIFIED'"))
        assertEquals(0, sql.query("PRAGMA foreign_key_check").use { it.count })
        assertNotNull(runCatching {
            sql.execSQL("DELETE FROM export_asset_holds WHERE operation_id=?", arrayOf(operation))
        }.exceptionOrNull())
        assertNotNull(runCatching {
            sql.execSQL("UPDATE export_operations SET updated_at_ms=999 WHERE operation_id=?",
                arrayOf(operation))
        }.exceptionOrNull())
        assertEquals(1, count(sql, "SELECT COUNT(*) FROM export_asset_holds"))
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String,
                      vararg args: String): Int =
        db.query(sql, args).use {
            assertTrue(it.moveToFirst())
            it.getInt(0)
        }
}
