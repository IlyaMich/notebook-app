package com.notebook.app.data.local

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 C2B-2: transaction-boundary regression tests against existing repositories.
 *
 * Every test opens a uniquely named isolated Room v4 database, never the production
 * library.sqlite. Media metadata/JSON are synthetic and are inserted by test DAOs;
 * no asset files are published, and no production PDF/image writer is enabled.
 */
@RunWith(AndroidJUnit4::class)
class AssetOwnershipTransactionBoundaryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-c2b2-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        context.deleteDatabase(databaseName)
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
    }

    private data class Owner(
        val noteId: String,
        val pageId: String,
        val secondPageId: String,
        val imageElementId: String,
        val textElementId: String,
    )

    private fun id(): String = UUID.randomUUID().toString()
    private fun pdfJson(asset: String) =
        """{"type":"pdfPage","assetId":"$asset","pageIndex":0,"displayBox":"crop"}"""
    private fun imageJson(element: String, asset: String) =
        """{"id":"$element","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$asset","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
    private fun textJson(element: String) =
        """{"id":"$element","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"Keep","frame":{"x":0,"y":0,"width":100,"height":30},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    private suspend fun asset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val extension = if (mime == "application/pdf") "pdf" else "png"
        db.assetDao().insert(AssetEntity(
            id = assetId, sha256 = sha, mimeType = mime, byteSize = 10L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$extension",
            state = "finalized",
        ))
        return assetId
    }

    private suspend fun mediaOwner(pdf: String, image: String): Owner {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(
            id = notebookId, parentFolderId = null, name = "C2B-2 isolated",
            sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        val noteId = id()
        db.noteDao().insert(NoteEntity(noteId, notebookId, "Synthetic media", 1L, 1L))
        val firstPage = id()
        val secondPage = id()
        db.pageDao().insert(PageEntity(firstPage, noteId, 0L, 612.0, 792.0, pdfJson(pdf), 0L))
        db.pageDao().insert(PageEntity(secondPage, noteId, 1L, 612.0, 792.0, BLANK, 0L))
        val imageElement = id()
        val textElement = id()
        db.elementDao().insert(ElementEntity(
            imageElement, firstPage, 0L, "image", imageJson(imageElement, image), 0L,
        ))
        db.elementDao().insert(ElementEntity(
            textElement, firstPage, 1L, "text", textJson(textElement), 0L,
        ))
        val dao = db.assetOwnershipDao()
        dao.insertPageReference(PageAssetReferenceEntity(firstPage, pdf))
        dao.insertElementReference(ElementAssetReferenceEntity(imageElement, image))
        return Owner(noteId, firstPage, secondPage, imageElement, textElement)
    }

    private suspend fun blankOwner(): Pair<String, String> {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(
            id = notebookId, parentFolderId = null, name = "Synthetic blank",
            sortOrder = 0L, createdAtMillis = 1L, updatedAtMillis = 1L,
        ))
        val noteId = id()
        db.noteDao().insert(NoteEntity(noteId, notebookId, "Blank", 1L, 1L))
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 612.0, 792.0, BLANK, 0L))
        return noteId to pageId
    }

    private suspend fun gateUnverified() {
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun staleSecondCompareAndSetRollsBackEarlierJsonAndEdgeUpdates() = runBlocking {
        val pdfBefore = asset("application/pdf")
        val pdfAfter = asset("application/pdf")
        val imageBefore = asset("image/png")
        val imageAfter = asset("image/png")
        val owner = mediaOwner(pdfBefore, imageBefore)
        val originalNote = db.noteDao().findById(owner.noteId)!!
        val originalPage = db.pageDao().findById(owner.pageId)!!
        val originalElement = db.elementDao().findById(owner.imageElementId)!!
        val dao = db.assetOwnershipDao()

        val failure = runCatching {
            db.withTransaction {
                check(db.noteDao().update(originalNote.copy(revision = 1L)) == 1)
                check(db.pageDao().update(originalPage.copy(
                    backgroundJson = pdfJson(pdfAfter), revision = 1L,
                )) == 1)
                check(db.elementDao().update(originalElement.copy(
                    elementJson = imageJson(owner.imageElementId, imageAfter), revision = 1L,
                )) == 1)
                check(dao.changePageAssetIfMatches(owner.pageId, pdfBefore, pdfAfter) == 1)
                // Stale expectation: the image still belongs to imageBefore.
                check(dao.changeElementAssetIfMatches(
                    owner.imageElementId, imageAfter, imageBefore,
                ) == 1)
            }
        }.exceptionOrNull()

        assertNotNull("The stale compare-and-set must abort the outer transaction", failure)
        assertEquals(originalNote, db.noteDao().findById(owner.noteId))
        assertEquals(originalPage, db.pageDao().findById(owner.pageId))
        assertEquals(originalElement, db.elementDao().findById(owner.imageElementId))
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdfBefore), dao.findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.imageElementId, imageBefore),
            dao.findElementReference(owner.imageElementId))
        gateUnverified()
    }

    @Test fun existingRepositoryReordersMediaOwnersWithoutRewritingTheirEdges() = runBlocking {
        val pdf = asset("application/pdf")
        val image = asset("image/png")
        val owner = mediaOwner(pdf, image)
        val repo = RoomDocumentRepository(db, clock = { 2_000L })
        val originalPdf = db.pageDao().findById(owner.pageId)!!.backgroundJson
        val originalImage = db.elementDao().findById(owner.imageElementId)!!.elementJson

        assertEquals(1L, repo.commit(owner.noteId, DocumentEdit.ReorderPages(
            listOf(owner.secondPageId, owner.pageId),
        )).revision)
        assertEquals(2L, repo.commit(owner.noteId, DocumentEdit.ReorderElements(
            owner.pageId, listOf(owner.textElementId, owner.imageElementId),
        )).revision)

        assertEquals(listOf(owner.secondPageId, owner.pageId),
            db.pageDao().listByNote(owner.noteId).map { it.id })
        assertEquals(listOf(owner.textElementId, owner.imageElementId),
            db.elementDao().listByPage(owner.pageId).map { it.id })
        assertEquals(originalPdf, db.pageDao().findById(owner.pageId)!!.backgroundJson)
        assertEquals(originalImage, db.elementDao().findById(owner.imageElementId)!!.elementJson)
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf),
            db.assetOwnershipDao().findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.imageElementId, image),
            db.assetOwnershipDao().findElementReference(owner.imageElementId))
        assertEquals(2L, db.noteDao().findById(owner.noteId)!!.revision)
        gateUnverified()
    }

    @Test fun existingImageTransferGuardPreservesIndexedEdgesAndRevision() = runBlocking {
        val pdf = asset("application/pdf")
        val image = asset("image/png")
        val owner = mediaOwner(pdf, image)
        val repo = RoomDocumentRepository(db)
        val before = repo.load(owner.noteId)

        val error = runCatching {
            repo.commit(owner.noteId, DocumentEdit.TransferElements(
                owner.pageId, owner.secondPageId, listOf(owner.imageElementId),
            ))
        }.exceptionOrNull()

        assertTrue("Image transfer must remain unsupported", error is LibraryOperationException)
        assertEquals(LibraryErrorCode.UNSUPPORTED_CONTENT, (error as LibraryOperationException).code)
        assertEquals(before, repo.load(owner.noteId))
        assertEquals(ElementAssetReferenceEntity(owner.imageElementId, image),
            db.assetOwnershipDao().findElementReference(owner.imageElementId))
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf),
            db.assetOwnershipDao().findPageReference(owner.pageId))
        gateUnverified()
    }

    @Test fun trashAndRestoreRetainBothEdgesAcrossDatabaseReopen() = runBlocking {
        val pdf = asset("application/pdf")
        val image = asset("image/png")
        val owner = mediaOwner(pdf, image)
        val originalNote = db.noteDao().findById(owner.noteId)!!
        val originalPage = db.pageDao().findById(owner.pageId)!!
        val originalElement = db.elementDao().findById(owner.imageElementId)!!
        var trash = LibraryRepositoryFactory(db).trash

        val batch = trash.trashNote(owner.noteId)
        assertEquals(batch, db.noteDao().findById(owner.noteId)?.deletionBatchId)
        assertEquals(originalPage, db.pageDao().findById(owner.pageId))
        assertEquals(originalElement, db.elementDao().findById(owner.imageElementId))
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf),
            db.assetOwnershipDao().findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.imageElementId, image),
            db.assetOwnershipDao().findElementReference(owner.imageElementId))

        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        trash = LibraryRepositoryFactory(db).trash
        trash.restoreBatch(batch)

        assertEquals(originalNote, db.noteDao().findById(owner.noteId))
        assertEquals(originalPage, db.pageDao().findById(owner.pageId))
        assertEquals(originalElement, db.elementDao().findById(owner.imageElementId))
        assertEquals(PageAssetReferenceEntity(owner.pageId, pdf),
            db.assetOwnershipDao().findPageReference(owner.pageId))
        assertEquals(ElementAssetReferenceEntity(owner.imageElementId, image),
            db.assetOwnershipDao().findElementReference(owner.imageElementId))
        assertEquals(2, db.assetDao().countAll())
        gateUnverified()
    }

    @Test fun inconsistentNonMediaOwnerEdgeBlocksPageDeletionWithoutPartialCommit() = runBlocking {
        val (noteId, pageId) = blankOwner()
        val extraPageId = id()
        db.pageDao().insert(PageEntity(extraPageId, noteId, 1L, 612.0, 792.0, BLANK, 0L))
        val syntheticAsset = asset("application/pdf")
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(pageId, syntheticAsset))
        val repo = RoomDocumentRepository(db)
        val before = repo.load(noteId)

        val failure = runCatching { repo.commit(noteId, DocumentEdit.RemovePage(pageId)) }
            .exceptionOrNull()

        assertNotNull("RESTRICT must block owner deletion even for mismatched JSON", failure)
        assertEquals(before, repo.load(noteId))
        assertEquals(PageAssetReferenceEntity(pageId, syntheticAsset),
            db.assetOwnershipDao().findPageReference(pageId))
        assertNotNull(db.pageDao().findById(pageId))
        assertEquals(1, db.assetDao().countAll())
        gateUnverified()
    }

    @Test fun confirmedPurgeWithInconsistentIndexedOwnerRollsBackEntireDeletion() = runBlocking {
        val (noteId, pageId) = blankOwner()
        val syntheticAsset = asset("application/pdf")
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(pageId, syntheticAsset))
        val trash = LibraryRepositoryFactory(db).trash
        val batch = trash.trashNote(noteId)
        val before = db.noteDao().findById(noteId)!!
        val beforePage = db.pageDao().findById(pageId)!!
        val preview = trash.previewPurge(batch)

        val failure = runCatching { trash.purgeBatch(preview) }.exceptionOrNull()

        assertNotNull("RESTRICT must abort destructive purge", failure)
        assertEquals(before, db.noteDao().findById(noteId))
        assertEquals(beforePage, db.pageDao().findById(pageId))
        assertEquals(PageAssetReferenceEntity(pageId, syntheticAsset),
            db.assetOwnershipDao().findPageReference(pageId))
        assertEquals(1, db.assetDao().countAll())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertEquals(0, it.count)
        }
        gateUnverified()
    }

    private companion object {
        const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
