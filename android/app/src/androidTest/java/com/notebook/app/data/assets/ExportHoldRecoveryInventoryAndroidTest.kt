package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ExportAssetHoldEntity
import com.notebook.app.data.local.entity.ExportOperationEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic metadata in fresh isolated test-app databases, never production library.sqlite. */
@RunWith(AndroidJUnit4::class)
class ExportHoldRecoveryInventoryAndroidTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String
    private fun id(): String = UUID.randomUUID().toString()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-i5b7d2-${id()}.sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(name).exists())
        db = AppDatabaseProvider.openIsolated(context, name)
    }

    @After fun teardown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) {
            check(name.startsWith("sto08-i5b7d2-") && name != AppDatabaseProvider.DATABASE_NAME)
            assertTrue(context.deleteDatabase(name))
        }
    }

    private suspend fun operation(
        state: String = "COMMITTED_PIN_SET",
        version: Int = 1,
        created: Long = 123L,
        operationId: String = id(),
    ): String {
        db.exportHoldAdmissionDao().insertOperation(ExportOperationEntity(
            operationId, id(), 3L, state, created, created, version,
        ))
        return operationId
    }

    private suspend fun asset(state: String = "finalized"): String {
        val assetId = id()
        val sha = (id() + id()).replace("-", "")
        db.assetDao().insert(AssetEntity(
            assetId, sha, "image/png", 10L,
            "assets/${sha.take(2)}/${sha.substring(2,4)}/$sha.png", state,
        ))
        return assetId
    }

    private suspend fun hold(operationId: String, assetId: String) {
        db.exportHoldAdmissionDao().insertHold(ExportAssetHoldEntity(operationId, assetId))
    }

    private suspend fun observed(
        limits: ExportHoldRecoveryLimits = ExportHoldRecoveryLimits(),
    ): ExportHoldRecoveryResult.Observed {
        val result = ExportHoldRecoveryInventory(db).inspect(limits)
        assertTrue("Expected bounded inventory, got $result", result is ExportHoldRecoveryResult.Observed)
        return result as ExportHoldRecoveryResult.Observed
    }

    @Test fun emptyInventoryIsUnprovenNotProtected() = runBlocking {
        val inventory = observed()
        assertTrue(inventory.operations.isEmpty())
        assertEquals(0, inventory.observedHoldRows)
        assertTrue(ExportHoldRecoveryIssue.EMPTY_INVENTORY_UNPROVEN in inventory.globalIssues)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun committedPinRemainsRetentionOnlyAcrossDatabaseReopen() = runBlocking {
        val op = operation(); val a = asset(); hold(op, a)
        val before = observed()
        assertEquals(listOf(a), before.operations.single().observedAssetIds)
        assertFalse(before.operations.single().requiresReview)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(before, observed())
        assertEquals(listOf(ExportAssetHoldEntity(op, a)), db.exportHoldAdmissionDao().listHolds(op))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun twoExportOperationsKeepIndependentHoldsForSharedAsset() = runBlocking {
        val a = asset(); val op1 = operation(); val op2 = operation()
        hold(op1, a); hold(op2, a)
        val result = observed()
        assertEquals(2, result.operations.size)
        assertEquals(2, result.observedHoldRows)
        assertTrue(result.operations.all { it.observedAssetIds == listOf(a) && !it.requiresReview })
        assertEquals(1, db.exportHoldAdmissionDao().listHolds(op1).size)
        assertEquals(1, db.exportHoldAdmissionDao().listHolds(op2).size)
    }

    @Test fun zeroHoldsRemainAmbiguousEvenForKnownOperation() = runBlocking {
        val op = operation()
        val finding = observed().operations.single()
        assertEquals(op, finding.operationId)
        assertTrue(ExportHoldRecoveryIssue.ZERO_HOLDS_UNPROVEN in finding.issues)
    }

    @Test fun unknownStateProtocolAndMalformedClockRequireReview() = runBlocking {
        operation(state = "COPYING", version = 99, created = -1L)
        val issues = observed().operations.single().issues
        assertTrue(ExportHoldRecoveryIssue.UNKNOWN_OPERATION_STATE in issues)
        assertTrue(ExportHoldRecoveryIssue.UNKNOWN_PROTOCOL_VERSION in issues)
        assertTrue(ExportHoldRecoveryIssue.INVALID_OPERATION_METADATA in issues)
    }

    @Test fun missingAssetMetadataIsDiagnosedWithoutModification() = runBlocking {
        val op = operation(); val missing = id()
        val sql = db.openHelper.writableDatabase
        // Test-only corruption of isolated DB, never production or managed originals.
        sql.execSQL("PRAGMA foreign_keys=OFF")
        sql.execSQL("INSERT INTO export_asset_holds(operation_id,asset_id) VALUES (?,?)", arrayOf(op,missing))
        val finding = observed().operations.single()
        assertTrue(ExportHoldRecoveryIssue.MISSING_ASSET_METADATA in finding.issues)
        assertEquals(listOf(missing), finding.observedAssetIds)
    }

    @Test fun orphanHoldIsGlobalAmbiguityNotAnExportReceipt() = runBlocking {
        val orphanOp = id(); val a = asset()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys=OFF")
        sql.execSQL("INSERT INTO export_asset_holds(operation_id,asset_id) VALUES (?,?)", arrayOf(orphanOp,a))
        val result = observed()
        assertTrue(ExportHoldRecoveryIssue.ORPHAN_HOLD in result.globalIssues)
        assertTrue(ExportHoldRecoveryIssue.EMPTY_INVENTORY_UNPROVEN in result.globalIssues)
        assertEquals(1, result.observedHoldRows)
    }

    @Test fun operationAndHoldRowBoundsFailClosedWithoutPartialInventory() = runBlocking {
        operation(); operation()
        assertEquals(ExportHoldRecoveryResult.Incomplete(
            ExportHoldRecoveryStopReason.OPERATION_LIMIT_EXCEEDED,
        ), ExportHoldRecoveryInventory(db).inspect(ExportHoldRecoveryLimits(maxOperations = 1)))
        val one = asset(); val two = asset(); val op = operation()
        hold(op, one); hold(op, two)
        assertEquals(ExportHoldRecoveryResult.Incomplete(
            ExportHoldRecoveryStopReason.HOLD_ROW_LIMIT_EXCEEDED,
        ), ExportHoldRecoveryInventory(db).inspect(ExportHoldRecoveryLimits(maxHoldRows = 1)))
    }

    @Test fun cancelledReadPropagatesAndPreservesDatabase() = runBlocking {
        val op = operation(); val a = asset(); hold(op, a)
        val cancelled = Job().also { it.cancel() }
        val failure = runCatching {
            withContext(cancelled) { ExportHoldRecoveryInventory(db).inspect() }
        }.exceptionOrNull()
        assertTrue("Cancellation must propagate", failure is CancellationException)
        assertEquals(listOf(ExportAssetHoldEntity(op, a)), db.exportHoldAdmissionDao().listHolds(op))
    }

    @Test fun databaseSqlFailureReturnsIncompleteNotSuccess() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DROP TABLE export_asset_holds")
        assertEquals(ExportHoldRecoveryResult.Incomplete(
            ExportHoldRecoveryStopReason.DATABASE_READ_FAILED,
        ), ExportHoldRecoveryInventory(db).inspect())
    }
}
