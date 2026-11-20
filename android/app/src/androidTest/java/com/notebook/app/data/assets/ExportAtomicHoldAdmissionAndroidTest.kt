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
    @Test fun oneLeaseProtectsCaptureCommitAndObservedSources() = runBlocking {
        val first = addImage()
        val second = addImage()
        var depth = 0
        var enters = 0
        var inspections = 0
        val shared = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T {
                check(depth == 0) { "Nested shared lease" }
                depth++
                enters++
                return try { action() } finally { depth-- }
            }
        }
        val coordinator = ExportRetainedSourceInspectionCoordinator(
            shared, ExportAtomicHoldAdmission(db, shared),
            inspectSource = { plan ->
                assertEquals(1, depth)
                inspections++
                ExportVerifiedSourceObservation(plan.images.map {
                    VerifiedDecodedImageInfo(it.id, it.mimeType, it.sha256,
                        it.encodedBytes, 1, 1, 1L)
                }, plan.totalEncodedBytes)
            },
        )
        val op = id()
        val result = coordinator.inspectAndRetain(op, SnapshotRequest(noteId, limits), admission, 44L)
        assertTrue(result is ExportRetainedSourceInspectionCoordinator.Result.SourceObserved)
        assertEquals(1, enters)
        assertEquals(1, inspections)
        assertEquals(0, depth)
        assertEquals(listOf(first, second).sorted(),
            db.exportHoldAdmissionDao().listHolds(op).map { it.assetId })
        assertEquals(1 to 2, count())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun failedInspectionRetainsBothPinsAfterRoomReopen() = runBlocking {
        val first = addImage()
        val second = addImage()
        val shared = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T = action()
        }
        val coordinator = ExportRetainedSourceInspectionCoordinator(
            shared, ExportAtomicHoldAdmission(db, shared),
            inspectSource = { throw java.io.IOException("simulated source verification failure") },
        )
        val op = id()
        assertTrue(runCatching {
            coordinator.inspectAndRetain(op, SnapshotRequest(noteId, limits), admission, 44L)
        }.exceptionOrNull() is java.io.IOException)
        assertEquals(1 to 2, count())
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(first, second).sorted(),
            db.exportHoldAdmissionDao().listHolds(op).map { it.assetId })
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun ineligibleNoteNeverCommitsHoldOrInspectsBytes() = runBlocking {
        addImage()
        val row = db.noteDao().findById(noteId)!!
        db.noteDao().update(row.copy(deletedAtMillis = 7L))
        var inspections = 0
        val shared = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T = action()
        }
        val coordinator = ExportRetainedSourceInspectionCoordinator(
            shared, ExportAtomicHoldAdmission(db, shared),
            inspectSource = { inspections++; error("No inspection expected") },
        )
        assertEquals(
            ExportRetainedSourceInspectionCoordinator.Result.Rejected(SnapshotRejection.INELIGIBLE_NOTE),
            coordinator.inspectAndRetain(id(), SnapshotRequest(noteId, limits), admission, 44L),
        )
        assertEquals(0, inspections)
        assertEquals(0 to 0, count())
    }

    @Test fun inconsistentSourceObservationFailsAndLeavesPins() = runBlocking {
        val asset = addImage()
        val shared = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T = action()
        }
        val coordinator = ExportRetainedSourceInspectionCoordinator(
            shared, ExportAtomicHoldAdmission(db, shared),
            inspectSource = { plan ->
                ExportVerifiedSourceObservation(emptyList(), plan.totalEncodedBytes)
            },
        )
        val op = id()
        assertTrue(runCatching {
            coordinator.inspectAndRetain(op, SnapshotRequest(noteId, limits), admission, 44L)
        }.exceptionOrNull() is java.io.IOException)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(op).map { it.assetId })
        assertEquals(1 to 1, count())
    }

    @Test fun immutableV6BindingMatchesExactlyCapturedSnapshotAndPinSetAfterReopen() = runBlocking {
        val first = addImage()
        val second = addImage()
        val operation = id()
        assertEquals(
            ExportAtomicHoldResult.Committed(operation, listOf(first, second).sorted()),
            persist(operation),
        )
        val stored = db.exportHoldAdmissionDao().findSourceBinding(operation)!!
        val captured = ExportSourceSnapshotReader(db).capture(
            SnapshotRequest(noteId, limits),
        ) as SnapshotResult.Complete
        assertEquals(ExportSourceBindingDigests.sha256(
            ExportSourceBindingDigests.snapshot(operation, captured)), stored.snapshotSha256)
        assertEquals(ExportSourceBindingDigests.assetSetDigest(captured.assets), stored.assetSetSha256)
        assertEquals(2, stored.expectedAssetCount)
        assertEquals(20L, stored.expectedAssetBytes)
        assertEquals(1, stored.bindingVersion)
        assertEquals(2, db.exportHoldAdmissionDao().findOperation(operation)!!.protocolVersion)

        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(stored, db.exportHoldAdmissionDao().findSourceBinding(operation))
        assertEquals(listOf(first, second).sorted(),
            db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun v6SourceBindingAndHoldCannotBeUpdatedOrDeleted() = runBlocking {
        val asset = addImage()
        val operation = id()
        assertTrue(persist(operation) is ExportAtomicHoldResult.Committed)
        val raw = db.openHelper.writableDatabase
        val old = db.exportHoldAdmissionDao().findSourceBinding(operation)!!
        assertNotNull(runCatching {
            raw.execSQL("UPDATE export_source_bindings SET expected_asset_count=0 WHERE operation_id=?",
                arrayOf(operation))
        }.exceptionOrNull())
        assertNotNull(runCatching {
            raw.execSQL("DELETE FROM export_source_bindings WHERE operation_id=?", arrayOf(operation))
        }.exceptionOrNull())
        assertNotNull(runCatching {
            raw.execSQL("DELETE FROM export_asset_holds WHERE operation_id=?", arrayOf(operation))
        }.exceptionOrNull())
        assertEquals(old, db.exportHoldAdmissionDao().findSourceBinding(operation))
        assertEquals(listOf(asset),
            db.exportHoldAdmissionDao().listHolds(operation).map { it.assetId })
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun failedV6AdmissionRollsBackSourceBindingWithAllPins() = runBlocking {
        addImage()
        addImage()
        val operation = id()
        val writer = ExportAtomicHoldAdmission(db, lease, afterHoldInserted = { count ->
            if (count == 1) throw java.io.IOException("simulated pin transaction interruption")
        })
        assertTrue(runCatching { persist(operation, writer) }.exceptionOrNull() is java.io.IOException)
        assertNull(db.exportHoldAdmissionDao().findSourceBinding(operation))
        assertNull(db.exportHoldAdmissionDao().findOperation(operation))
        assertEquals(0 to 0, count())
    }

    /** B6: two retained holds survive a synthetic deleted-note state and Room reopen. */
    @Test fun twoIndependentHoldsSurviveDeletedNoteStateAndDatabaseReopen() = runBlocking {
        val asset = addImage()
        val first = id()
        val second = id()
        assertEquals(ExportAtomicHoldResult.Committed(first, listOf(asset)), persist(first))
        assertEquals(ExportAtomicHoldResult.Committed(second, listOf(asset)), persist(second))
        assertEquals(2 to 2, count())
        val beforeAsset = db.assetDao().findById(asset)
        val beforeEdge = db.assetOwnershipDao().listElementReferencesByAsset(asset)
        val note = db.noteDao().findById(noteId)!!
        assertEquals(1, db.noteDao().update(note.copy(deletedAtMillis = 4L)))
        assertEquals(2 to 2, count())
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(first).map { it.assetId })
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(second).map { it.assetId })
        assertEquals(beforeAsset, db.assetDao().findById(asset))
        assertEquals(beforeEdge, db.assetOwnershipDao().listElementReferencesByAsset(asset))
        assertEquals(4L, db.noteDao().findById(noteId)!!.deletedAtMillis)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    /** B6: failed new admission must not revoke a pre-existing committed export hold. */
    @Test fun rejectedLaterExportCannotReleaseExistingHold() = runBlocking {
        val asset = addImage()
        val committedOp = id()
        assertEquals(ExportAtomicHoldResult.Committed(committedOp, listOf(asset)), persist(committedOp))
        val beforeAsset = db.assetDao().findById(asset)
        val note = db.noteDao().findById(noteId)!!
        assertEquals(1, db.noteDao().update(note.copy(deletedAtMillis = 4L)))
        assertEquals(
            ExportAtomicHoldResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE), persist(id())
        )
        assertEquals(1 to 1, count())
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(listOf(asset), db.exportHoldAdmissionDao().listHolds(committedOp).map { it.assetId })
        assertEquals(beforeAsset, db.assetDao().findById(asset))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }
}
