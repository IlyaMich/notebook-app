package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-app isolated Room rows and a dedicated test-only OS lease root; no managed originals. */
@RunWith(AndroidJUnit4::class)
class ExportHoldAdmissionCompositionAndroidTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dbName: String
    private lateinit var lockRoot: File
    private lateinit var noteId: String
    private val requestLimits = SnapshotLimits(4, 16, 8, 100, 800, 50_000, 4, 16)
    private val admissionLimits = ExportCopyAdmissionLimits(8, 100, 800)
    private fun id() = UUID.randomUUID().toString()

    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "sto08-r20-composition-${id()}.sqlite"
        check(dbName != AppDatabaseProvider.DATABASE_NAME)
        check(!context.getDatabasePath(dbName).exists())
        db = AppDatabaseProvider.openIsolated(context, dbName)
        lockRoot = File(context.cacheDir, "sto08-r20-lease-${id()}")
        check(lockRoot.mkdir())
        val notebook = id()
        noteId = id()
        val page = id()
        db.notebookDao().insert(NotebookEntity(notebook, null, "Synthetic", sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L))
        db.noteDao().insert(NoteEntity(noteId, notebook, "Synthetic", 1L, 1L, revision = 2L))
        db.pageDao().insert(PageEntity(page, noteId, 0L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L))
    }

    @After fun teardown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::dbName.isInitialized) {
            check(dbName.startsWith("sto08-r20-composition-") && dbName != AppDatabaseProvider.DATABASE_NAME)
            check(context.deleteDatabase(dbName))
        }
        if (::lockRoot.isInitialized) {
            check(lockRoot.name.startsWith("sto08-r20-lease-"))
            // Only the expected lock created under the fresh test-only directory is removable.
            val entries = lockRoot.listFiles() ?: error("Test lease directory missing")
            check(entries.all { it.name == ".asset-publication.lock" && it.isFile })
            entries.forEach { check(it.delete()) }
            check(lockRoot.delete())
        }
    }

    private fun writer(): ExportAtomicHoldAdmission = ExportHoldAdmissionComposition.forRoom(lockRoot, db)
    private suspend fun admit(op: String = id()): ExportAtomicHoldResult = writer().persist(
        op, SnapshotRequest(noteId, requestLimits), admissionLimits, 123L,
    )
    private fun rowCounts(): Pair<Int, Int> = db.openHelper.writableDatabase.let { sql ->
        val ops = sql.query("SELECT COUNT(*) FROM export_operations").use { it.moveToFirst(); it.getInt(0) }
        val pins = sql.query("SELECT COUNT(*) FROM export_asset_holds").use { it.moveToFirst(); it.getInt(0) }
        ops to pins
    }

    @Test fun isolatedCompositionCommitsMetadataOnlyHoldOperation() = runBlocking {
        val op = id()
        assertEquals(ExportAtomicHoldResult.Committed(op, emptyList()), admit(op))
        assertEquals(1 to 0, rowCounts())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        assertTrue(File(lockRoot, ".asset-publication.lock").isFile)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        assertEquals("COMMITTED_PIN_SET", db.exportHoldAdmissionDao().findOperation(op)?.state)
    }

    @Test fun rejectedSnapshotDoesNotCommitNewOperation() = runBlocking {
        db.noteDao().findById(noteId)!!.let { db.noteDao().update(it.copy(deletedAtMillis = 4L)) }
        assertEquals(ExportAtomicHoldResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE), admit())
        assertEquals(0 to 0, rowCounts())
    }

    @Test fun duplicateOperationIdDoesNotReplaceCommittedOperation() = runBlocking {
        val op = id()
        assertTrue(admit(op) is ExportAtomicHoldResult.Committed)
        val failure = runCatching { admit(op) }.exceptionOrNull()
        assertTrue("Duplicate operation ID must fail", failure != null)
        assertEquals(1 to 0, rowCounts())
    }

    @Test fun cancellationPropagatesWithoutAcknowledgingHoldSet() = runBlocking {
        val cancelled = Job().apply { cancel() }
        val failure = runCatching { withContext(cancelled) { admit() } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0 to 0, rowCounts())
    }
}
