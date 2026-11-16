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
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 I5-A4B: isolated real-Room observations fed to the PURE I5-A4 comparator.
 * No production DB, asset files, export ownership, gate promotion or cleanup.
 * The canonical report and projection queries are separate read snapshots here;
 * this does NOT establish an atomic production scan or reference completeness.
 */
@RunWith(AndroidJUnit4::class)
class AssetProjectionDiagnosticRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase

    private val blank = """{"type":"blank","color":"#FFFFFF"}"""

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "sto08-i5a4b-${UUID.randomUUID()}.sqlite"
        check(dbName != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::dbName.isInitialized) context.deleteDatabase(dbName)
    }

    private fun id(): String = UUID.randomUUID().toString()
    private fun pdf(assetId: String) =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""
    private fun image(elementId: String, assetId: String) =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":20,"height":20},"opacity":1}}"""
    private fun textElement(elementId: String) =
        """{"id":"$elementId","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"Test","frame":{"x":0,"y":0,"width":20,"height":20},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    private suspend fun asset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val ext = if (mime == "application/pdf") "pdf" else "png"
        db.assetDao().insert(AssetEntity(
            id = assetId, sha256 = sha, mimeType = mime, byteSize = 16L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext",
            state = "finalized",
        ))
        return assetId
    }

    private suspend fun page(background: String, trashed: Boolean = false): String {
        val book = id()
        db.notebookDao().insert(NotebookEntity(
            id = book, parentFolderId = null, name = "Isolated I5-A4B",
            sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        val note = id()
        db.noteDao().insert(NoteEntity(
            id = note, notebookId = book, title = "Synthetic owner",
            createdAtMillis = 1L, updatedAtMillis = 1L,
            deletedAtMillis = if (trashed) 2L else null,
        ))
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, note, 0L, 612.0, 792.0, background, 0L))
        return pageId
    }

    private suspend fun element(pageId: String, kind: String, raw: (String) -> String): String {
        val elementId = id()
        db.elementDao().insert(ElementEntity(
            id = elementId, pageId = pageId, orderIndex = 0L,
            kind = kind, elementJson = raw(elementId), revision = 0L,
        ))
        return elementId
    }

    private data class Observation(
        val report: AssetOwnerInventory.Report,
        val diagnostic: AssetProjectionDiagnosticComparator.Diagnostic,
    )

    /** An isolated, quiescent fixture: separate snapshots are not a production scan. */
    private suspend fun observe(): Observation {
        val canonicalScan = RoomAssetOwnerInventory(db).scan()
        assertTrue("Room owner inventory must complete: $canonicalScan",
            canonicalScan is RoomAssetOwnerInventory.Scan.Completed)
        val report = (canonicalScan as RoomAssetOwnerInventory.Scan.Completed).report
        val raw = db.openHelper.readableDatabase
        val pageEdges = mutableListOf<AssetProjectionDiagnosticComparator.PageEdge>()
        raw.query("SELECT page_id, asset_id FROM page_asset_references ORDER BY page_id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                pageEdges += AssetProjectionDiagnosticComparator.PageEdge(
                    cursor.getString(0), cursor.getString(1))
            }
        }
        val elementEdges = mutableListOf<AssetProjectionDiagnosticComparator.ElementEdge>()
        raw.query("SELECT element_id, asset_id FROM element_asset_references ORDER BY element_id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                elementEdges += AssetProjectionDiagnosticComparator.ElementEdge(
                    cursor.getString(0), cursor.getString(1))
            }
        }
        val diagnostic = AssetProjectionDiagnosticComparator.compareObserved(
            report, pageEdges, elementEdges)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        return Observation(report, diagnostic)
    }

    private fun assertFinding(
        observed: Observation,
        kind: AssetProjectionDiagnosticComparator.Kind,
        ownerType: String,
        ownerId: String,
    ) {
        assertTrue("Expected $kind for $ownerType:$ownerId; observed=${observed.diagnostic.findings}",
            observed.diagnostic.findings.any {
                it.kind == kind && it.ownerType == ownerType && it.ownerId == ownerId
            })
    }

    @Test fun matchingPdfAndImageOwnersFromRoomHaveNoObservedMismatch() = runBlocking {
        val pdfAsset = asset("application/pdf")
        val imageAsset = asset("image/png")
        val p = page(pdf(pdfAsset))
        val e = element(p, "image") { image(it, imageAsset) }
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, pdfAsset))
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(e, imageAsset))
        val result = observe()
        assertEquals(2, result.report.owners.size)
        assertFalse(result.diagnostic.hasObservedMismatch)
    }

    @Test fun missingPdfProjectionInTrashIsReported() = runBlocking {
        val pdfAsset = asset("application/pdf")
        val p = page(pdf(pdfAsset), trashed = true)
        val result = observe()
        assertTrue(result.report.owners.single().inTrash)
        assertFinding(result, AssetProjectionDiagnosticComparator.Kind.MISSING_PROJECTION_EDGE, "page", p)
    }

    @Test fun missingImageProjectionIsReported() = runBlocking {
        val imageAsset = asset("image/png")
        val p = page(blank)
        val e = element(p, "image") { image(it, imageAsset) }
        assertFinding(observe(), AssetProjectionDiagnosticComparator.Kind.MISSING_PROJECTION_EDGE, "element", e)
    }

    @Test fun mismatchedPdfProjectionIsReported() = runBlocking {
        val pdfAsset = asset("application/pdf")
        val other = asset("application/pdf")
        val p = page(pdf(pdfAsset))
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, other))
        assertFinding(observe(), AssetProjectionDiagnosticComparator.Kind.MISMATCHED_ASSET_ID, "page", p)
    }

    @Test fun mismatchedImageProjectionIsReported() = runBlocking {
        val imageAsset = asset("image/png")
        val other = asset("image/png")
        val p = page(blank)
        val e = element(p, "image") { image(it, imageAsset) }
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(e, other))
        assertFinding(observe(), AssetProjectionDiagnosticComparator.Kind.MISMATCHED_ASSET_ID, "element", e)
    }

    @Test fun unexpectedPageProjectionOnBlankBackgroundIsReported() = runBlocking {
        val pdfAsset = asset("application/pdf")
        val p = page(blank)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(p, pdfAsset))
        assertFinding(observe(), AssetProjectionDiagnosticComparator.Kind.UNEXPECTED_PROJECTION_EDGE, "page", p)
    }

    @Test fun unexpectedElementProjectionOnTextIsReported() = runBlocking {
        val imageAsset = asset("image/png")
        val p = page(blank)
        val e = element(p, "text") { textElement(it) }
        db.assetOwnershipDao().insertElementReference(ElementAssetReferenceEntity(e, imageAsset))
        assertFinding(observe(), AssetProjectionDiagnosticComparator.Kind.UNEXPECTED_PROJECTION_EDGE, "element", e)
    }

    @Test fun malformedCanonicalImageRemainsUnresolved() = runBlocking {
        val p = page(blank)
        val e = element(p, "image") { "{" }
        val result = observe()
        assertTrue(result.report.issues.any { it.elementId == e })
        assertFinding(result, AssetProjectionDiagnosticComparator.Kind.UNRESOLVED_CANONICAL_CONTENT,
            "canonical", e)
    }

    @Test fun sharedPdfAssetAcrossActiveAndTrashOwnersMatchesBothEdges() = runBlocking {
        val pdfAsset = asset("application/pdf")
        val activePage = page(pdf(pdfAsset))
        val trashPage = page(pdf(pdfAsset), trashed = true)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(activePage, pdfAsset))
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(trashPage, pdfAsset))
        val result = observe()
        assertEquals(2, result.report.owners.size)
        assertEquals(1, result.report.owners.count { it.inTrash })
        assertFalse(result.diagnostic.hasObservedMismatch)
        assertEquals(1, db.assetDao().countAll())
    }
}
