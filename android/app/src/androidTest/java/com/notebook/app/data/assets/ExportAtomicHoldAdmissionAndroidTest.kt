package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only lease + synthetic metadata, isolated test-app Room DB; no originals or export copy. */
@RunWith(AndroidJUnit4::class)
class ExportAtomicHoldAdmissionAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private lateinit var noteId: String
    private lateinit var pageId: String
    private val limits = SnapshotLimits(4, 16, 8, 100, 800, 50_000, 4, 16)
    private val admission = ExportCopyAdmissionLimits(8, 100, 800)
    private fun id() = UUID.randomUUID().toString()
    private val lease = object : ExportOuterLease {
        override suspend fun <T> withLease(action: suspend () -> T): T = action()
    }
    @Before fun setup() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-i5b7b-${UUID.randomUUID()}.sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, name)
        val bookId = id(); noteId = id(); pageId = id()
        db.notebookDao().insert(NotebookEntity(bookId, null, "Synthetic", sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L))
        db.noteDao().insert(NoteEntity(noteId, bookId, "Synthetic", 1L, 1L, revision = 2L))
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 600.0, 800.0,
            """{"type":"blank","color":"#FFFFFF"}""", 1L))
    }
    @After fun teardown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
    }
    private suspend fun addImage(): String {
        val assetId = id()
        val digest = (id() + id()).replace("-", "")
        db.assetDao().insert(AssetEntity(assetId, digest, "image/png", 10L,
            "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.png", "finalized"))
        val elementId = id()
        val order = db.elementDao().countByPage(pageId).toLong()
        val json = """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        db.elementDao().insert(ElementEntity(elementId, pageId, order, "image", json, 1L))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(elementId, assetId))
        return assetId
    }
    private suspend fun persist(op: String = id(), writer: ExportAtomicHoldAdmission = ExportAtomicHoldAdmission(db, lease)) =
        writer.persist(op, SnapshotRequest(noteId, limits), admission, 123L)
    private suspend fun count(): Pair<Int, Int> =
        db.openHelper.writableDatabase.let { sql ->
            val ops = sql.query("SELECT COUNT(*) FROM export_operations").use { it.moveToFirst(); it.getInt(0) }
            val pins = sql.query("SELECT COUNT(*) FROM export_asset_holds").use { it.moveToFirst(); it.getInt(0) }
            ops to pins
        }
    @Test fun twoAssetsCommitAsOneOperationAndSurviveReopen() = runBlocking {
        val a = addImage(); val b = addImage()
        val op = id()
        assertEquals(ExportAtomicHoldResult.Committed(op, listOf(a,b).sorted()), persist(op))
        assertEquals(1 to 2, count())
        db.close(); db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(a,b).sorted(), db.exportHoldAdmissionDao().listHolds(op).map { it.assetId })
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }
    @Test fun sharedCanonicalOwnersProduceOneDistinctAssetHold() = runBlocking {
        val asset = addImage()
        val old = db.elementDao().listByPage(pageId).single()
        val eid = id()
        db.elementDao().insert(old.copy(id = eid, orderIndex = 1L,
            elementJson = old.elementJson.replace(old.id, eid)))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(eid, asset))
        assertTrue(persist() is ExportAtomicHoldResult.Committed)
        assertEquals(1 to 1, count())
    }
    @Test fun missingCanonicalProjectionRejectsWithoutAnyOperation() = runBlocking {
        addImage()
        val element = db.elementDao().listByPage(pageId).single()
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM element_asset_references WHERE element_id=?", arrayOf(element.id))
        assertEquals(ExportAtomicHoldResult.Rejected(SnapshotRejection.OWNER_EDGE_MISMATCH), persist())
        assertEquals(0 to 0, count())
    }
    @Test fun trashOrStaleStructureRejectsWithoutHold() = runBlocking {
        addImage()
        val note = db.noteDao().findById(noteId)!!
        db.noteDao().update(note.copy(deletedAtMillis = 4L))
        assertEquals(ExportAtomicHoldResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE), persist())
        assertEquals(0 to 0, count())
    }
    @Test fun injectedFailureAfterFirstHoldRollsBackWholeTransaction() = runBlocking {
        addImage(); addImage()
        val writer = ExportAtomicHoldAdmission(db, lease, afterHoldInserted = { count ->
            if (count == 1) error("synthetic post-insert failure")
        })
        assertNotNull(runCatching { persist(writer = writer) }.exceptionOrNull())
        assertEquals(0 to 0, count())
    }
    @Test fun duplicateOperationIdDoesNotCreateAnotherHoldSet() = runBlocking {
        addImage()
        val op = id()
        assertTrue(persist(op) is ExportAtomicHoldResult.Committed)
        assertNotNull(runCatching { persist(op) }.exceptionOrNull())
        assertEquals(1 to 1, count())
    }
    @Test fun cancellationDuringFirstHoldRollsBack() = runBlocking {
        addImage(); addImage()
        val writer = ExportAtomicHoldAdmission(db, lease, afterHoldInserted = { count ->
            if (count == 1) throw CancellationException("synthetic cancellation")
        })
        val failure = runCatching { persist(writer = writer) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0 to 0, count())
    }
    @Test fun eligibleMediaFreeNoteCommitsZeroHoldOperation() = runBlocking {
        val result = persist()
        assertTrue(result is ExportAtomicHoldResult.Committed)
        assertEquals(1 to 0, count())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }
}
