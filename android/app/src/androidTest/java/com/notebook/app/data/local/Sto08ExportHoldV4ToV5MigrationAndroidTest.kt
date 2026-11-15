package com.notebook.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I5-B6C: genuine Room v4 -> v5 upgrade from committed exported v4 schema DDL.
 * Isolated TEST-APP databases only. No existing app DB, no managed file, no export writer.
 * The v4 identity hash and DDL correspond to committed schemas/.../4.json.
 */
@RunWith(AndroidJUnit4::class)
class Sto08ExportHoldV4ToV5MigrationAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var opened: AppDatabase? = null
    private fun id(): String = UUID.randomUUID().toString()
    private val v4SchemaSql = listOf(
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
        """CREATE TABLE IF NOT EXISTS `assets` (`id` TEXT NOT NULL, `sha256` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `byte_size` INTEGER NOT NULL, `relative_path` TEXT NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`id`))""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_sha256` ON `assets` (`sha256`)""",
        """CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_relative_path` ON `assets` (`relative_path`)""",
        """CREATE TABLE IF NOT EXISTS `page_asset_references` (`page_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, PRIMARY KEY(`page_id`), FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE INDEX IF NOT EXISTS `ix_page_asset_references_asset` ON `page_asset_references` (`asset_id`)""",
        """CREATE TABLE IF NOT EXISTS `element_asset_references` (`element_id` TEXT NOT NULL, `asset_id` TEXT NOT NULL, PRIMARY KEY(`element_id`), FOREIGN KEY(`element_id`) REFERENCES `elements`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT , FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT )""",
        """CREATE INDEX IF NOT EXISTS `ix_element_asset_references_asset` ON `element_asset_references` (`asset_id`)""",
        """CREATE TABLE IF NOT EXISTS `asset_reference_index_state` (`id` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`id`))""",
    )

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-i5b6c-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(databaseName).exists())
    }
    @After fun tearDown() {
        opened?.close()
        if (::context.isInitialized && ::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }
    private fun createV4(populate: Boolean, initialGate: String = "UNVERIFIED"): Fixture {
        val dbFile = context.getDatabasePath(databaseName)
        dbFile.parentFile?.mkdirs()
        val fixture = Fixture(id(), id(), id(), id(), id(), id(), id())
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { raw ->
            raw.execSQL("PRAGMA foreign_keys=ON")
            for (statement in v4SchemaSql) raw.execSQL(statement)
            raw.execSQL("INSERT INTO asset_reference_index_state(id,state) VALUES(1,?)", arrayOf(initialGate))
            // The v4 index gate had immutable/non-VERIFIED SQLite guards.
            raw.execSQL("CREATE TRIGGER trg_asset_ref_gate_insert BEFORE INSERT ON asset_reference_index_state WHEN NEW.id IS NOT 1 OR NEW.state IS NOT 'UNVERIFIED' BEGIN SELECT RAISE(ABORT, 'Invalid initial asset reference gate'); END")
            raw.execSQL("CREATE TRIGGER trg_asset_ref_gate_update BEFORE UPDATE ON asset_reference_index_state WHEN NEW.id IS NOT 1 OR NEW.state IS NULL OR NEW.state NOT IN ('UNVERIFIED','UNRESOLVED') BEGIN SELECT RAISE(ABORT, 'Invalid asset reference gate transition'); END")
            raw.execSQL("CREATE TRIGGER trg_asset_ref_gate_delete BEFORE DELETE ON asset_reference_index_state BEGIN SELECT RAISE(ABORT, 'Asset reference gate cannot be deleted'); END")
            raw.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            raw.execSQL("INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42,'c3f37d8eae29d65668a1a382aceacbe0')")
            if (populate) {
                raw.execSQL("INSERT INTO notebooks(id,name,sort_order,created_at,updated_at) VALUES(?,'Trashed book',0,1,2)", arrayOf(fixture.book))
                raw.execSQL("INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision,deleted_at) VALUES(?,?,'Trashed note',1,2,3,100)", arrayOf(fixture.note,fixture.book))
                raw.execSQL("INSERT INTO pages(id,note_id,order_index,width,height,background_json,revision) VALUES(?,?,0,612,792,?,3)", arrayOf(fixture.page,fixture.note,"""{"type":"pdfPage","assetId":"${fixture.pdf}","pageIndex":0,"displayBox":"crop"}"""))
                raw.execSQL("INSERT INTO elements(id,page_id,order_index,kind,element_json,revision) VALUES(?,?,0,'image',?,3)", arrayOf(fixture.element,fixture.page,"""{"id":"${fixture.element}","type":"image","payload":{"assetId":"${fixture.image}"}}"""))
                for ((asset,sha,mime,ext) in listOf(
                    listOf(fixture.pdf,"a".repeat(64),"application/pdf","pdf"),
                    listOf(fixture.image,"b".repeat(64),"image/png","png"))) {
                    raw.execSQL("INSERT INTO assets(id,sha256,mime_type,byte_size,relative_path,state) VALUES(?,?,?,16,?,'finalized')", arrayOf(asset,sha,mime,"assets/${sha.take(2)}/${sha.substring(2,4)}/$sha.$ext"))
                }
                raw.execSQL("INSERT INTO page_asset_references(page_id,asset_id) VALUES(?,?)", arrayOf(fixture.page,fixture.pdf))
                raw.execSQL("INSERT INTO element_asset_references(element_id,asset_id) VALUES(?,?)", arrayOf(fixture.element,fixture.image))
            }
            raw.version = 4
            raw.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals(0, it.count) }
        }
        return fixture
    }
    private data class Fixture(val book: String, val note: String, val page: String, val element: String, val pdf: String, val image: String, val spare: String)
    private fun migrate(): AppDatabase = AppDatabaseProvider.openIsolated(context,databaseName).also {
        opened = it
        assertEquals(5,it.openHelper.writableDatabase.version)
    }
    private fun count(sql: String, vararg args: String): Int =
        opened!!.openHelper.writableDatabase.query(sql, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
    private fun mustReject(label: String, action: () -> Unit) {
        assertNotNull(label, runCatching { action() }.exceptionOrNull())
    }

    @Test fun populatedV4MigrationPreservesTrashCanonicalAndBothOwnershipEdges() {
        val f = createV4(true)
        val db = migrate()
        assertEquals(1,count("SELECT COUNT(*) FROM notes WHERE id=? AND deleted_at=100 AND revision=3", f.note))
        assertEquals(1,count("SELECT COUNT(*) FROM pages WHERE id=? AND note_id=?", f.page,f.note))
        assertEquals(1,count("SELECT COUNT(*) FROM elements WHERE id=? AND page_id=?", f.element,f.page))
        assertEquals(1,count("SELECT COUNT(*) FROM page_asset_references WHERE page_id=? AND asset_id=?", f.page,f.pdf))
        assertEquals(1,count("SELECT COUNT(*) FROM element_asset_references WHERE element_id=? AND asset_id=?", f.element,f.image))
        assertEquals(2,count("SELECT COUNT(*) FROM assets"))
        assertEquals(0,count("SELECT COUNT(*) FROM export_operations"))
        assertEquals(0,count("SELECT COUNT(*) FROM export_asset_holds"))
        assertEquals(1,count("SELECT COUNT(*) FROM asset_reference_index_state WHERE id=1 AND state='UNVERIFIED'"))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
    }

    @Test fun migratedV5RetainsNewTriggersAndRejectsReleaseAfterReopen() {
        val f = createV4(true)
        var db = migrate()
        val raw = db.openHelper.writableDatabase
        val operationId = id()
        raw.execSQL("INSERT INTO export_operations(operation_id,note_id,note_revision,state,created_at_ms,updated_at_ms,protocol_version) VALUES(?,?,3,'COMMITTED_PIN_SET',1,1,1)",arrayOf(operationId,f.note))
        raw.execSQL("INSERT INTO export_asset_holds(operation_id,asset_id) VALUES(?,?)",arrayOf(operationId,f.image))
        db.close()
        opened = null
        db = migrate()
        assertEquals(1,count("SELECT COUNT(*) FROM export_asset_holds WHERE operation_id=? AND asset_id=?",operationId,f.image))
        mustReject("Migrated hold cannot be released") {
            db.openHelper.writableDatabase.execSQL("DELETE FROM export_asset_holds WHERE operation_id=?",arrayOf(operationId))
        }
        mustReject("Held asset cannot be deleted") {
            db.openHelper.writableDatabase.execSQL("DELETE FROM assets WHERE id=?",arrayOf(f.image))
        }
        assertEquals(1,count("SELECT COUNT(*) FROM asset_reference_index_state WHERE state='UNVERIFIED'"))
    }

    @Test fun emptyV4MigratesWithoutBackfillOrGatePromotion() {
        createV4(false)
        migrate()
        assertEquals(0,count("SELECT COUNT(*) FROM assets"))
        assertEquals(0,count("SELECT COUNT(*) FROM export_operations"))
        assertEquals(0,count("SELECT COUNT(*) FROM export_asset_holds"))
        assertEquals(1,count("SELECT COUNT(*) FROM asset_reference_index_state WHERE id=1 AND state='UNVERIFIED'"))
    }

    @Test fun unresolvedV4GateRemainsUnresolvedAfterUpgrade() {
        createV4(false,"UNRESOLVED")
        migrate()
        assertEquals(1,count("SELECT COUNT(*) FROM asset_reference_index_state WHERE id=1 AND state='UNRESOLVED'"))
        mustReject("VERIFIED transition remains forbidden") {
            opened!!.openHelper.writableDatabase.execSQL("UPDATE asset_reference_index_state SET state='VERIFIED' WHERE id=1")
        }
    }
}
