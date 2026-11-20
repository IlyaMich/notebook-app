package com.notebook.app.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.entity.AssetEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 I5-B6B: v5 fresh-database storage/retention tests, isolated test-app DB only.
 * Raw SQL fixture inserts do NOT create a production export writer or protected receipt.
 * End-to-end v4-to-v5 migration validation is deliberately a SEPARATE checkpoint.
 */
@RunWith(AndroidJUnit4::class)
class Sto08ExportHoldV5SchemaAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    private fun id(): String = UUID.randomUUID().toString()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-i5b6b-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
    }

    private fun count(sql: String, args: Array<String> = emptyArray()): Int =
        db.openHelper.writableDatabase.query(sql, args).use { cursor ->
            assertTrue("Expected one count row", cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun insertOperation(operationId: String = id(), noteId: String = id()): String {
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO export_operations
               (operation_id,note_id,note_revision,state,created_at_ms,updated_at_ms,protocol_version)
               VALUES (?,?,0,'COMMITTED_PIN_SET',1,1,1)""",
            arrayOf(operationId, noteId),
        )
        return operationId
    }

    private suspend fun insertAsset(): String {
        val assetId = id()
        val digest = id().replace("-", "") + id().replace("-", "")
        db.assetDao().insert(AssetEntity(
            id = assetId, sha256 = digest, mimeType = "image/png", byteSize = 16L,
            relativePath = "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.png",
            state = "finalized",
        ))
        return assetId
    }

    private fun insertHold(operationId: String, assetId: String) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO export_asset_holds (operation_id,asset_id) VALUES (?,?)",
            arrayOf(operationId, assetId),
        )
    }

    private fun mustReject(description: String, operation: () -> Unit) {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertNotNull(description, failure)
    }

    @Test fun freshV5HasEmptyHoldTablesAndUnverifiedOwnershipGate() {
        assertEquals(7, db.openHelper.writableDatabase.version)
        assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='export_operations'"))
        assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='export_asset_holds'"))
        assertEquals(0, count("SELECT COUNT(*) FROM export_operations"))
        assertEquals(0, count("SELECT COUNT(*) FROM export_asset_holds"))
        assertEquals(1, count("SELECT COUNT(*) FROM asset_reference_index_state WHERE id=1 AND state='UNVERIFIED'"))
        assertEquals(1, count("PRAGMA foreign_keys"))
    }

    @Test fun freshV5TriggersRejectOperationAndHoldUpdateOrDelete() = runBlocking {
        val assetId = insertAsset()
        val operationId = insertOperation()
        insertHold(operationId, assetId)
        val sql = db.openHelper.writableDatabase
        mustReject("Hold update forbidden") {
            sql.execSQL("UPDATE export_asset_holds SET asset_id=? WHERE operation_id=?", arrayOf(assetId, operationId))
        }
        mustReject("Hold deletion forbidden") {
            sql.execSQL("DELETE FROM export_asset_holds WHERE operation_id=?", arrayOf(operationId))
        }
        mustReject("Operation update forbidden") {
            sql.execSQL("UPDATE export_operations SET updated_at_ms=2 WHERE operation_id=?", arrayOf(operationId))
        }
        mustReject("Operation deletion forbidden") {
            sql.execSQL("DELETE FROM export_operations WHERE operation_id=?", arrayOf(operationId))
        }
        assertEquals(1, count("SELECT COUNT(*) FROM export_operations"))
        assertEquals(1, count("SELECT COUNT(*) FROM export_asset_holds"))
    }

    @Test fun missingOperationOrAssetIsRejectedByForeignKeys() = runBlocking {
        val assetId = insertAsset()
        val operationId = insertOperation()
        mustReject("Missing operation FK") { insertHold(id(), assetId) }
        mustReject("Missing asset FK") { insertHold(operationId, id()) }
        assertEquals(0, count("SELECT COUNT(*) FROM export_asset_holds"))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test fun duplicateHoldIsRejectedAndSharedAssetCanBeHeldByDistinctOperations() = runBlocking {
        val assetId = insertAsset()
        val first = insertOperation()
        val second = insertOperation()
        insertHold(first, assetId)
        mustReject("Duplicate operation+asset") { insertHold(first, assetId) }
        insertHold(second, assetId)
        assertEquals(2, count("SELECT COUNT(*) FROM export_asset_holds WHERE asset_id=?", arrayOf(assetId)))
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test fun pinnedAssetMetadataCannotBeDeletedEvenWithoutCanonicalOwners() = runBlocking {
        val assetId = insertAsset()
        val operationId = insertOperation()
        insertHold(operationId, assetId)
        mustReject("Held asset metadata must remain") {
            db.openHelper.writableDatabase.execSQL("DELETE FROM assets WHERE id=?", arrayOf(assetId))
        }
        assertEquals(1, count("SELECT COUNT(*) FROM assets WHERE id=?", arrayOf(assetId)))
        assertEquals(1, count("SELECT COUNT(*) FROM export_asset_holds WHERE asset_id=?", arrayOf(assetId)))
    }

    @Test fun rolledBackOperationAndHoldsNeverPersistPartialSet() = runBlocking {
        val firstAsset = insertAsset()
        val secondAsset = insertAsset()
        val operationId = id()
        val failure = runCatching {
            db.withTransaction {
                insertOperation(operationId)
                insertHold(operationId, firstAsset)
                insertHold(operationId, secondAsset)
                error("synthetic I5-B6B rollback")
            }
        }.exceptionOrNull()
        assertNotNull("Expected rollback", failure)
        assertEquals(0, count("SELECT COUNT(*) FROM export_operations"))
        assertEquals(0, count("SELECT COUNT(*) FROM export_asset_holds"))
        assertEquals(2, count("SELECT COUNT(*) FROM assets"))
    }

    @Test fun committedOperationAndHoldSurviveDatabaseReopen() = runBlocking {
        val assetId = insertAsset()
        val operationId = insertOperation()
        insertHold(operationId, assetId)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        assertEquals(1, count("SELECT COUNT(*) FROM export_operations WHERE operation_id=?", arrayOf(operationId)))
        assertEquals(1, count("SELECT COUNT(*) FROM export_asset_holds WHERE operation_id=? AND asset_id=?", arrayOf(operationId, assetId)))
        mustReject("Reopened DB still disallows hold release") {
            db.openHelper.writableDatabase.execSQL("DELETE FROM export_asset_holds WHERE operation_id=?", arrayOf(operationId))
        }
        assertEquals(1, count("SELECT COUNT(*) FROM asset_reference_index_state WHERE state='UNVERIFIED'"))
    }

    @Test fun mediaFreeOperationWithoutHoldsDoesNotClaimProtectedReceipt() {
        val operationId = insertOperation()
        assertEquals(1, count("SELECT COUNT(*) FROM export_operations WHERE operation_id=?", arrayOf(operationId)))
        assertEquals(0, count("SELECT COUNT(*) FROM export_asset_holds"))
        assertEquals(0, count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='export_receipts'"))
    }
}
