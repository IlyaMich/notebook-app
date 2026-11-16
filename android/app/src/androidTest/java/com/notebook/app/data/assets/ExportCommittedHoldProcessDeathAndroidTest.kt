package com.notebook.app.data.assets

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I5-B7D-3C: HOST-DRIVEN, test-app-only actual process-death proof for committed
 * Room v5 export holds. Ordinary Gradle/CI runs SKIP both methods. A separate,
 * explicitly authorized host driver must start prepare, validate its PID-bearing
 * marker, kill only the disposable target process, then invoke verify in a NEW
 * process without reinstalling or clearing app data.
 *
 * No managed asset bytes, export staging, protected receipt, release or GC.
 */
@RunWith(AndroidJUnit4::class)
class ExportCommittedHoldProcessDeathAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("sto08-pd-hold-fixture", Context.MODE_PRIVATE)

    private fun requirePhase(phase: String): String {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Only the guarded host driver can run process-death phases",
            arguments.getString("sto08HoldPdPhase") == phase)
        assertEquals("Unexpected target application", "com.notebook.app", context.packageName)
        val nonce = arguments.getString("sto08HoldRun")
        assertNotNull("Missing host-generated run nonce", nonce)
        require(nonce != null && canonicalUuid(nonce)) { "Noncanonical run nonce" }
        return nonce
    }

    private fun canonicalUuid(raw: String): Boolean =
        try { UUID.fromString(raw).toString() == raw } catch (_: IllegalArgumentException) { false }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun recorded(key: String): String =
        prefs.getString(key, null) ?: error("Missing dedicated hold fixture marker: $key")

    private fun safeDbName(name: String): Boolean {
        val prefix = "sto08-pd-hold-"
        val suffix = ".sqlite"
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) return false
        return canonicalUuid(name.removePrefix(prefix).removeSuffix(suffix))
    }

    private val lease = object : ExportOuterLease {
        override suspend fun <T> withLease(action: suspend () -> T): T = action()
    }

    @Test
    fun prepareCommittedHoldsAndAwaitExternalProcessDeath() {
        val nonce = requirePhase("hold-prepare")
        check(prefs.all.isEmpty()) {
            "Previous hold process-death marker exists; preserve evidence, do not overwrite"
        }
        val dbName = "sto08-pd-hold-$nonce.sqlite"
        check(safeDbName(dbName) && dbName != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(dbName).exists()) {
            "Isolated hold fixture database already exists; preserve evidence"
        }

        val operationId = newId()
        val notebookId = newId()
        val noteId = newId()
        val pageId = newId()
        val assetIds = listOf(newId(), newId()).sorted()
        val db = AppDatabaseProvider.openIsolated(context, dbName)
        runBlocking {
            db.notebookDao().insert(NotebookEntity(
                id = notebookId, parentFolderId = null,
                name = "Isolated hold process-death notebook", sortOrder = 0L,
                createdAtMillis = 100L, updatedAtMillis = 100L,
            ))
            db.noteDao().insert(NoteEntity(
                noteId, notebookId, "Isolated hold process-death note", 100L, 100L, revision = 2L,
            ))
            db.pageDao().insert(PageEntity(
                pageId, noteId, 0L, 600.0, 800.0,
                """{"type":"blank","color":"#FFFFFF"}""", 1L,
            ))
            assetIds.forEachIndexed { index, assetId ->
                // Metadata only. No asset file is created, opened, read or inspected.
                val digest = (newId() + newId()).replace("-", "")
                db.assetDao().insert(AssetEntity(
                    assetId, digest, "image/png", 10L,
                    "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.png", "finalized",
                ))
                val elementId = newId()
                val json = """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
                db.elementDao().insert(ElementEntity(
                    elementId, pageId, index.toLong(), "image", json, 1L,
                ))
                db.assetOwnershipDao().insertElementReference(
                    ElementAssetReferenceEntity(elementId, assetId),
                )
            }

            val result = ExportAtomicHoldAdmission(db, lease).persist(
                operationId,
                SnapshotRequest(noteId, SnapshotLimits(4, 16, 8, 100, 800, 50_000, 4, 16)),
                ExportCopyAdmissionLimits(8, 100, 800),
                createdAtMillis = 123L,
            )
            assertEquals(ExportAtomicHoldResult.Committed(operationId, assetIds), result)
            // Independent post-transaction Room reads: marker MUST NOT precede this proof.
            val op = db.exportHoldAdmissionDao().findOperation(operationId)
            assertNotNull(op)
            assertEquals("COMMITTED_PIN_SET", op!!.state)
            assertEquals(noteId, op.noteId)
            assertEquals(2L, op.noteRevision)
            assertEquals(1, op.protocolVersion)
            assertEquals(assetIds,
                db.exportHoldAdmissionDao().listHolds(operationId).map { it.assetId })
            val inventory = ExportHoldRecoveryInventory(db).inspect()
            assertTrue(inventory is ExportHoldRecoveryResult.Observed)
            assertEquals(listOf(operationId),
                (inventory as ExportHoldRecoveryResult.Observed).operations.map { it.operationId })
            assertEquals(2, inventory.observedHoldRows)
            assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        }

        // Commit synchronously AFTER the Room transaction and independent reads.
        // The marker is only a fixture locator; it is NOT an export receipt.
        assertTrue("Cannot durably record dedicated hold process-death fixture",
            prefs.edit()
                .putString("runNonce", nonce)
                .putString("dbName", dbName)
                .putString("operationId", operationId)
                .putString("noteId", noteId)
                .putString("assetA", assetIds[0])
                .putString("assetB", assetIds[1])
                .putInt("preparedPid", Process.myPid())
                .commit())

        Log.i("STO08_PD_HOLD",
            "READY_TO_KILL run=$nonce pid=${Process.myPid()} operation=$operationId")
        // No asynchronous fixture worker and no more SQL writes exist at this boundary.
        // The host MUST terminate the process externally before this wait expires.
        CountDownLatch(1).await(120L, TimeUnit.SECONDS)
        fail("Host did not terminate committed-hold process before timeout")
    }

    @Test
    fun verifyCommittedHoldsInFreshProcess() {
        val nonce = requirePhase("hold-verify")
        assertEquals("Wrong recovery fixture nonce", nonce, recorded("runNonce"))
        val dbName = recorded("dbName")
        check(safeDbName(dbName) && dbName == "sto08-pd-hold-$nonce.sqlite") {
            "Unsafe or mismatched isolated hold database name"
        }
        val oldPid = prefs.getInt("preparedPid", -1)
        assertTrue("Missing original process PID", oldPid > 0)
        val newPid = Process.myPid()
        assertNotEquals("PID was reused; crash-window result inconclusive", oldPid, newPid)
        val operationId = recorded("operationId")
        val noteId = recorded("noteId")
        val expectedAssets = listOf(recorded("assetA"), recorded("assetB")).sorted()
        assertTrue("Unexpected operation UUID", canonicalUuid(operationId))
        assertTrue("Unexpected note UUID", canonicalUuid(noteId))
        assertTrue("Unexpected asset UUIDs", expectedAssets.all(::canonicalUuid))
        assertEquals("Duplicate expected asset IDs", 2, expectedAssets.toSet().size)
        assertTrue("Missing original isolated database; do not create one as recovery evidence",
            context.getDatabasePath(dbName).isFile)

        val db = AppDatabaseProvider.openIsolated(context, dbName)
        runBlocking {
            val dao = db.exportHoldAdmissionDao()
            val op = dao.findOperation(operationId)
            assertNotNull("Committed operation disappeared after process death", op)
            assertEquals("COMMITTED_PIN_SET", op!!.state)
            assertEquals(noteId, op.noteId)
            assertEquals(2L, op.noteRevision)
            assertEquals(1, op.protocolVersion)
            assertEquals(123L, op.createdAtMillis)
            assertEquals(123L, op.updatedAtMillis)
            assertEquals(expectedAssets, dao.listHolds(operationId).map { it.assetId })
            val inventory = ExportHoldRecoveryInventory(db).inspect()
            assertTrue(inventory is ExportHoldRecoveryResult.Observed)
            val observed = inventory as ExportHoldRecoveryResult.Observed
            assertEquals(1, observed.operations.size)
            assertEquals(operationId, observed.operations.single().operationId)
            assertEquals(expectedAssets, observed.operations.single().observedAssetIds)
            assertEquals(2, observed.observedHoldRows)
            assertTrue(observed.globalIssues.isEmpty())
            // A non-review diagnostic still is NOT evidence of complete historical pins,
            // verified source bytes, durable export, a receipt or release authority.
            assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        }
        // Delete only the exact UUID-scoped test database after all assertions pass.
        db.close()
        assertTrue("Isolated test database cleanup failed", context.deleteDatabase(dbName))
        assertFalse("Isolated test DB remains after cleanup", context.getDatabasePath(dbName).exists())
        assertTrue("Dedicated fixture marker cleanup failed", prefs.edit().clear().commit())
        Log.i("STO08_PD_HOLD",
            "VERIFY_PASSED run=$nonce oldPid=$oldPid newPid=$newPid operation=$operationId")
    }
}
