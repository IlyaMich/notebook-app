package com.notebook.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
 * Direct SQLite guard tests. Uses only a unique, isolated test database;
 * never opens or changes AppDatabaseProvider.DATABASE_NAME.
 *
 * v4 Room creation/migration and schema equivalence are tested separately.
 */
@RunWith(AndroidJUnit4::class)
class AssetReferenceIndexGuardsAndroidTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var helper: SupportSQLiteOpenHelper? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "sto08-gate-${UUID.randomUUID()}.sqlite"
        context.deleteDatabase(dbName)
    }

    @After fun tearDown() {
        helper?.close()
        helper = null
        context.deleteDatabase(dbName)
    }

    private fun openTestDatabase(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE asset_reference_index_state (
                       id INTEGER NOT NULL,
                       state TEXT NOT NULL,
                       PRIMARY KEY (id)
                    )"""
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int,
            ) {
                error("Unexpected upgrade of isolated gate test database")
            }
        }
        val opened = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        helper = opened
        return opened.writableDatabase
    }

    private fun state(db: SupportSQLiteDatabase): String =
        db.query("SELECT state FROM asset_reference_index_state WHERE id = 1").use { cursor ->
            assertTrue("Missing singleton gate row", cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun rowCount(db: SupportSQLiteDatabase): Int =
        db.query("SELECT COUNT(*) FROM asset_reference_index_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun mustReject(label: String, action: () -> Unit) {
        assertNotNull(label, runCatching(action).exceptionOrNull())
    }

    @Test fun initializesOnlyUnverifiedSingletonAndSurvivesReopen() {
        val db = openTestDatabase()
        AssetReferenceIndexGuards.initializeEmptyIndex(db)
        assertEquals(1, rowCount(db))
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(db))

        helper!!.close()
        helper = null
        val reopened = openTestDatabase()
        assertEquals(1, rowCount(reopened))
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(reopened))
    }

    @Test fun invalidInsertsUpdatesAndDeletionFailClosed() {
        val db = openTestDatabase()
        AssetReferenceIndexGuards.initializeEmptyIndex(db)

        mustReject("Second singleton row must be rejected") {
            db.execSQL("INSERT INTO asset_reference_index_state (id,state) VALUES (2,'UNVERIFIED')")
        }
        mustReject("Invalid initial state must be rejected") {
            db.execSQL("INSERT INTO asset_reference_index_state (id,state) VALUES (2,'UNRESOLVED')")
        }
        mustReject("Direct VERIFIED promotion must be rejected") {
            db.execSQL("UPDATE asset_reference_index_state SET state='VERIFIED' WHERE id=1")
        }
        mustReject("Unknown state must be rejected") {
            db.execSQL("UPDATE asset_reference_index_state SET state='OTHER' WHERE id=1")
        }
        mustReject("NULL state must be rejected") {
            db.execSQL("UPDATE asset_reference_index_state SET state=NULL WHERE id=1")
        }
        mustReject("Singleton identifier cannot change") {
            db.execSQL("UPDATE asset_reference_index_state SET id=3 WHERE id=1")
        }
        mustReject("Singleton deletion must be rejected") {
            db.execSQL("DELETE FROM asset_reference_index_state WHERE id=1")
        }
        assertEquals(1, rowCount(db))
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(db))

        db.execSQL("UPDATE asset_reference_index_state SET state='UNRESOLVED' WHERE id=1")
        assertEquals(AssetReferenceIndexGuards.UNRESOLVED, state(db))
        db.execSQL("UPDATE asset_reference_index_state SET state='UNVERIFIED' WHERE id=1")
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(db))
    }

    @Test fun duplicateInitializationFailsWithoutDestroyingExistingGate() {
        val db = openTestDatabase()
        AssetReferenceIndexGuards.initializeEmptyIndex(db)
        mustReject("Initialization must not silently overwrite existing state") {
            AssetReferenceIndexGuards.initializeEmptyIndex(db)
        }
        assertEquals(1, rowCount(db))
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(db))
    }

    @Test fun failedTransactionRollsBackGateAndTriggers() {
        val db = openTestDatabase()
        db.beginTransaction()
        try {
            AssetReferenceIndexGuards.initializeEmptyIndex(db)
            // Deliberately omit setTransactionSuccessful() to simulate rollback.
        } finally {
            db.endTransaction()
        }
        assertEquals(0, rowCount(db))
        db.query(
            """SELECT COUNT(*) FROM sqlite_master
               WHERE type='trigger' AND name LIKE 'trg_asset_ref_gate_%'"""
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        AssetReferenceIndexGuards.initializeEmptyIndex(db)
        assertEquals(AssetReferenceIndexGuards.UNVERIFIED, state(db))
    }
}
