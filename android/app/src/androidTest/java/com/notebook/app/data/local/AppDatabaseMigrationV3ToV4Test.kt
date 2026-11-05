package com.notebook.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.migration.AppDatabaseMigrations
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C1B-2B: isolated Room v3 -> v4 migration tests.
 *
 * The fixture is copied verbatim from the committed Room 3.json into
 * androidTest/assets/sto08_room_v3_schema.json. Never generate a historical
 * fixture from the current (v4) Room schema.
 *
 * Every test uses a fresh, uniquely named database. This class never opens
 * AppDatabaseProvider.DATABASE_NAME (the user's library.sqlite).
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationV3ToV4Test {

    private lateinit var context: Context
    private lateinit var name: String
    private var opened: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-v3-to-v4-${UUID.randomUUID()}.sqlite"
        context.deleteDatabase(name)
    }

    @After fun tearDown() {
        opened?.close()
        opened = null
        context.deleteDatabase(name)
    }

    private data class Seed(
        val notebook: String,
        val activeNote: String,
        val trashNote: String,
        val activePage: String,
        val trashPage: String,
        val activeElement: String,
        val trashElement: String,
        val pdfAsset: String,
        val imageAsset: String,
        val batch: String,
        val pdfBackground: String,
        val imageElement: String,
    )

    private fun seedV3(): Seed {
        val ids = Seed(
            notebook = UUID.randomUUID().toString(),
            activeNote = UUID.randomUUID().toString(),
            trashNote = UUID.randomUUID().toString(),
            activePage = UUID.randomUUID().toString(),
            trashPage = UUID.randomUUID().toString(),
            activeElement = UUID.randomUUID().toString(),
            trashElement = UUID.randomUUID().toString(),
            pdfAsset = UUID.randomUUID().toString(),
            imageAsset = UUID.randomUUID().toString(),
            batch = UUID.randomUUID().toString(),
            pdfBackground = "", imageElement = "",
        )
        val pdfJson = """{"type":"pdfPage","assetId":"${ids.pdfAsset}","pageIndex":0,"displayBox":"crop"}"""
        val imageJson = """{"id":"${ids.activeElement}","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${ids.imageAsset}","frame":{"x":0,"y":0,"width":40,"height":40},"opacity":1}}"""
        val seeded = ids.copy(pdfBackground = pdfJson, imageElement = imageJson)

        val rawSchema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sto08_room_v3_schema.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val database = JSONObject(rawSchema).getJSONObject("database")
        assertEquals("Fixture must remain Room v3", 3, database.getInt("version"))
        assertEquals(
            "299264f1e5dc791bde332b2ace6b9ab0",
            database.getString("identityHash"),
        )

        val old = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            // Create exactly the tables and indexes exported by Room v3.
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    old.execSQL(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("${'$'}{TABLE_NAME}", table)
                    )
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))

            old.execSQL(
                """INSERT INTO notebooks(id,parent_folder_id,name,sort_order,system_role,created_at,updated_at)
                   VALUES (?,NULL,'Existing notebook',0,NULL,100,101)""",
                arrayOf(seeded.notebook),
            )
            old.execSQL(
                """INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision)
                   VALUES (?,?,'Active PDF note',100,201,11)""",
                arrayOf(seeded.activeNote, seeded.notebook),
            )
            old.execSQL(
                """INSERT INTO notes(id,notebook_id,title,created_at,updated_at,revision,
                   deleted_at,deletion_batch_id,original_notebook_id)
                   VALUES (?,?,'Trashed PDF note',100,202,7,999,?,?)""",
                arrayOf(seeded.trashNote, seeded.notebook, seeded.batch, seeded.notebook),
            )
            old.execSQL(
                """INSERT INTO pages(id,note_id,order_index,width,height,background_json,revision)
                   VALUES (?,?,0,595.276,841.890,?,11)""",
                arrayOf(seeded.activePage, seeded.activeNote, seeded.pdfBackground),
            )
            old.execSQL(
                """INSERT INTO pages(id,note_id,order_index,width,height,background_json,revision)
                   VALUES (?,?,0,595.276,841.890,?,7)""",
                arrayOf(seeded.trashPage, seeded.trashNote, seeded.pdfBackground),
            )
            old.execSQL(
                """INSERT INTO elements(id,page_id,order_index,kind,element_json,revision)
                   VALUES (?,?,0,'image',?,11)""",
                arrayOf(seeded.activeElement, seeded.activePage, seeded.imageElement),
            )
            // Another image reference to the same asset from Trash.
            val trashJson = seeded.imageElement.replace(seeded.activeElement, seeded.trashElement)
            old.execSQL(
                """INSERT INTO elements(id,page_id,order_index,kind,element_json,revision)
                   VALUES (?,?,0,'image',?,7)""",
                arrayOf(seeded.trashElement, seeded.trashPage, trashJson),
            )
            old.execSQL(
                """INSERT INTO assets(id,sha256,mime_type,byte_size,relative_path,state)
                   VALUES (? ,?,'application/pdf',42,?,'finalized')""",
                arrayOf(seeded.pdfAsset, "a".repeat(64), "assets/aa/aa/${"a".repeat(64)}.pdf"),
            )
            old.execSQL(
                """INSERT INTO assets(id,sha256,mime_type,byte_size,relative_path,state)
                   VALUES (? ,?,'image/png',43,?,'finalized')""",
                arrayOf(seeded.imageAsset, "b".repeat(64), "assets/bb/bb/${"b".repeat(64)}.png"),
            )
            // A legacy, unrelated trigger must survive the additive upgrade.
            old.execSQL(
                """CREATE TRIGGER trg_sto08_v3_keep BEFORE DELETE ON assets
                   BEGIN SELECT RAISE(ABORT, 'legacy asset sentinel'); END"""
            )
            old.version = 3
        } finally {
            old.close()
        }
        return seeded
    }

    private fun openRoom(): SupportSQLiteDatabase {
        val db = AppDatabaseProvider.openIsolated(context, name)
        opened = db
        return db.openHelper.writableDatabase
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue("Missing count for $table", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun gateState(db: SupportSQLiteDatabase): String =
        db.query("SELECT state FROM asset_reference_index_state WHERE id = 1").use { cursor ->
            assertTrue("Missing gate row", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun mustReject(label: String, action: () -> Unit) {
        assertNotNull(label, runCatching(action).exceptionOrNull())
    }

    private fun assertSafeEmptyIndex(db: SupportSQLiteDatabase) {
        assertEquals(4, db.version)
        assertEquals(1, count(db, "asset_reference_index_state"))
        assertEquals("UNVERIFIED", gateState(db))
        assertEquals(0, count(db, "page_asset_references"))
        assertEquals(0, count(db, "element_asset_references"))
        mustReject("Direct VERIFIED promotion must remain prohibited") {
            db.execSQL("UPDATE asset_reference_index_state SET state='VERIFIED' WHERE id=1")
        }
        mustReject("Gate row must not be deletable") {
            db.execSQL("DELETE FROM asset_reference_index_state WHERE id=1")
        }
        assertEquals("UNVERIFIED", gateState(db))
    }

    @Test fun freshV4StartsUnverifiedWithEmptyOwnershipTables() {
        val sql = openRoom()
        assertSafeEmptyIndex(sql)
        sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        opened!!.close()
        opened = null
        val reopened = openRoom()
        assertSafeEmptyIndex(reopened)
    }

    @Test fun v3DataAndTrashSurviveUpgradeWithoutInventedOwnershipEdges() {
        val ids = seedV3()
        val sql = openRoom()
        assertSafeEmptyIndex(sql)
        assertEquals(1, count(sql, "notebooks"))
        assertEquals(2, count(sql, "notes"))
        assertEquals(2, count(sql, "pages"))
        assertEquals(2, count(sql, "elements"))
        assertEquals(2, count(sql, "assets"))
        sql.query("SELECT title,revision,deleted_at,deletion_batch_id,original_notebook_id FROM notes WHERE id=?",
            arrayOf(ids.trashNote)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Trashed PDF note", c.getString(0))
            assertEquals(7L, c.getLong(1))
            assertEquals(999L, c.getLong(2))
            assertEquals(ids.batch, c.getString(3))
            assertEquals(ids.notebook, c.getString(4))
        }
        sql.query("SELECT background_json,revision FROM pages WHERE id=?",
            arrayOf(ids.activePage)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ids.pdfBackground, c.getString(0))
            assertEquals(11L, c.getLong(1))
        }
        sql.query("SELECT element_json FROM elements WHERE id=?",
            arrayOf(ids.activeElement)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ids.imageElement, c.getString(0))
        }
        sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_sto08_v3_keep'")
            .use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        sql.query("PRAGMA integrity_check").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0))
        }

        opened!!.close()
        opened = null
        val reopened = openRoom()
        assertSafeEmptyIndex(reopened)
        assertEquals(2, count(reopened, "notes"))
        assertEquals(2, count(reopened, "assets"))
    }

    @Test fun typedOwnershipEdgesSupportSharingAndEnforceRestrictiveForeignKeys() {
        val ids = seedV3()
        val sql = openRoom()
        assertSafeEmptyIndex(sql)
        for (page in listOf(ids.activePage, ids.trashPage)) {
            sql.execSQL(
                "INSERT INTO page_asset_references(page_id,asset_id) VALUES (?,?)",
                arrayOf(page, ids.pdfAsset),
            )
        }
        for (element in listOf(ids.activeElement, ids.trashElement)) {
            sql.execSQL(
                "INSERT INTO element_asset_references(element_id,asset_id) VALUES (?,?)",
                arrayOf(element, ids.imageAsset),
            )
        }
        assertEquals(2, count(sql, "page_asset_references"))
        assertEquals(2, count(sql, "element_asset_references"))
        assertEquals("UNVERIFIED", gateState(sql))

        mustReject("Duplicate page owner is forbidden") {
            sql.execSQL("INSERT INTO page_asset_references(page_id,asset_id) VALUES (?,?)",
                arrayOf(ids.activePage, ids.pdfAsset))
        }
        mustReject("Unknown page cannot own an asset") {
            sql.execSQL("INSERT INTO page_asset_references(page_id,asset_id) VALUES (?,?)",
                arrayOf(UUID.randomUUID().toString(), ids.pdfAsset))
        }
        mustReject("Unknown asset cannot be referenced") {
            sql.execSQL("INSERT INTO element_asset_references(element_id,asset_id) VALUES (?,?)",
                arrayOf(ids.activeElement, UUID.randomUUID().toString()))
        }
        mustReject("Referenced PDF asset cannot be deleted") {
            sql.execSQL("DELETE FROM assets WHERE id=?", arrayOf(ids.pdfAsset))
        }
        mustReject("Referenced image asset cannot change identity") {
            sql.execSQL("UPDATE assets SET id=? WHERE id=?",
                arrayOf(UUID.randomUUID().toString(), ids.imageAsset))
        }
        mustReject("Referenced image element cannot be deleted") {
            sql.execSQL("DELETE FROM elements WHERE id=?", arrayOf(ids.activeElement))
        }
        mustReject("Referenced PDF page cannot be deleted") {
            sql.execSQL("DELETE FROM pages WHERE id=?", arrayOf(ids.activePage))
        }
        assertEquals(2, count(sql, "assets"))
        assertEquals(2, count(sql, "page_asset_references"))
        assertEquals(2, count(sql, "element_asset_references"))
        sql.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun failedV3ToV4UpgradeRollsBackAndCleanRetrySucceeds() {
        val ids = seedV3()
        var invoked = false
        val failing = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppDatabaseMigrations.MIGRATION_3_4.migrate(db)
                invoked = true
                throw IllegalStateException("STO-08 deliberate v3-v4 failure")
            }
        }
        val attempt = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(failing)
            .build()
        val failure = try {
            runCatching { attempt.openHelper.writableDatabase }.exceptionOrNull()
        } finally {
            attempt.close()
        }
        assertTrue("Failure must occur after the real migration SQL", invoked)
        assertNotNull("Injected upgrade must fail", failure)
        assertTrue(
            generateSequence(failure) { it.cause }.mapNotNull { it.message }
                .any { it.contains("STO-08 deliberate v3-v4 failure") }
        )
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            assertEquals(3, raw.version)
            raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='asset_reference_index_state'", null)
                .use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='page_asset_references'", null)
                .use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            raw.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'trg_asset_ref_gate_%'", null)
                .use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
            raw.rawQuery("SELECT revision FROM notes WHERE id=?", arrayOf(ids.activeNote)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(11L, c.getLong(0))
            }
            raw.rawQuery("PRAGMA integrity_check", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0))
            }
        } finally {
            raw.close()
        }
        val recovered = openRoom()
        assertSafeEmptyIndex(recovered)
        assertEquals(2, count(recovered, "assets"))
        assertEquals(2, count(recovered, "notes"))
    }
}
