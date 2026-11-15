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
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I5-A6: diagnostic-only boundary regressions in uniquely named test-app Room DBs. */
@RunWith(AndroidJUnit4::class)
class RoomAssetProjectionDiagnosticSnapshotBoundaryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    private fun id(): String = UUID.randomUUID().toString()
    private val blank = """{"type":"blank","color":"#FFFFFF"}"""

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-i5a6-${UUID.randomUUID()}.sqlite"
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

    private suspend fun registeredImage(): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        db.assetDao().insert(AssetEntity(
            assetId, sha, "image/png", 16L,
            "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.png", "finalized"
        ))
        return assetId
    }

    private suspend fun blankPage(trash: Boolean): String {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(
            id = notebookId, parentFolderId = null, name = "isolated",
            sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L
        ))
        val noteId = id()
        db.noteDao().insert(NoteEntity(
            id = noteId, notebookId = notebookId, title = "synthetic",
            createdAtMillis = 1L, updatedAtMillis = 1L,
            deletedAtMillis = if (trash) 2L else null
        ))
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 612.0, 792.0, blank, 0L))
        return pageId
    }

    private fun imageJson(elementId: String, assetId: String): String =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":20,"height":20},"opacity":1}}"""

    private suspend fun insertImage(pageId: String, assetId: String): String {
        val elementId = id()
        db.elementDao().insert(ElementEntity(
            elementId, pageId, 0L, "image", imageJson(elementId, assetId), 0L
        ))
        return elementId
    }

    private suspend fun completed(): RoomAssetProjectionDiagnosticSnapshot.Result.Completed {
        val scan = RoomAssetProjectionDiagnosticSnapshot(db).scan()
        assertTrue("Expected completed diagnostic: $scan",
            scan is RoomAssetProjectionDiagnosticSnapshot.Result.Completed)
        return scan as RoomAssetProjectionDiagnosticSnapshot.Result.Completed
    }

    @Test fun matchingImageOwnersInActiveAndTrashAreObservedAtomically() = runBlocking {
        val assetId = registeredImage()
        val active = insertImage(blankPage(false), assetId)
        val trashed = insertImage(blankPage(true), assetId)
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(active, assetId))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(trashed, assetId))
        val report = completed()
        assertEquals(2, report.canonical.owners.size)
        assertEquals(1, report.canonical.owners.count { it.inTrash })
        assertFalse(report.diagnostic.hasObservedMismatch)
        assertEquals("UNVERIFIED", report.gateStatus)
    }

    @Test fun missingImageProjectionInTrashFailsObservedComparison() = runBlocking {
        val assetId = registeredImage()
        val elementId = insertImage(blankPage(true), assetId)
        val report = completed()
        assertTrue(report.canonical.owners.any { it.elementId == elementId && it.inTrash })
        assertTrue(report.diagnostic.findings.any {
            it.kind == AssetProjectionDiagnosticComparator.Kind.MISSING_PROJECTION_EDGE &&
                it.ownerType == "element" && it.ownerId == elementId
        })
        assertEquals("UNVERIFIED", report.gateStatus)
    }

    @Test fun mismatchedImageProjectionDoesNotBecomeObservedAgreement() = runBlocking {
        val actualAsset = registeredImage()
        val wrongAsset = registeredImage()
        val elementId = insertImage(blankPage(false), actualAsset)
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(elementId, wrongAsset))
        val report = completed()
        assertTrue(report.diagnostic.findings.any {
            it.kind == AssetProjectionDiagnosticComparator.Kind.MISMATCHED_ASSET_ID &&
                it.ownerType == "element" && it.ownerId == elementId
        })
        assertEquals("UNVERIFIED", report.gateStatus)
    }

    @Test fun malformedImageWithProjectionRemainsUnresolved() = runBlocking {
        val assetId = registeredImage()
        val pageId = blankPage(false)
        val elementId = id()
        db.elementDao().insert(ElementEntity(elementId, pageId, 0L, "image", "{", 0L))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(elementId, assetId))
        val report = completed()
        assertTrue(report.canonical.hasUnresolved)
        assertTrue(report.diagnostic.hasObservedMismatch)
        assertTrue(report.diagnostic.findings.any {
            it.kind == AssetProjectionDiagnosticComparator.Kind.UNEXPECTED_PROJECTION_EDGE &&
                it.ownerType == "element" && it.ownerId == elementId
        })
        assertEquals("UNVERIFIED", report.gateStatus)
    }

    @Test fun preCancelledScanPropagatesCancellationWithoutChangingGate() = runBlocking {
        val priorNotes = db.noteDao().countAll()
        val priorAssets = db.assetDao().countAll()
        val cancelledJob = Job().apply { cancel() }
        try {
            withContext(cancelledJob) {
                RoomAssetProjectionDiagnosticSnapshot(db).scan()
            }
            fail("Cancelled scan must not be converted to diagnostic completion")
        } catch (_: CancellationException) {
            // Expected: cancellation propagates rather than converting to Incomplete.
        }
        assertEquals(priorNotes, db.noteDao().countAll())
        assertEquals(priorAssets, db.assetDao().countAll())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }
}
