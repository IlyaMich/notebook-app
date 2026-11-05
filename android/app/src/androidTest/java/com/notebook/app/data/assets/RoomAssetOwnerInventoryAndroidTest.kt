package com.notebook.app.data.assets

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
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

/** STO-08 C1A-2: read-only inventory against an isolated Room v3 database. */
@RunWith(AndroidJUnit4::class)
class RoomAssetOwnerInventoryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-owner-inventory-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun sharedPdfAndImageReferencesIncludeActiveAndTrashedNotes() = runBlocking {
        val notebookId = createNotebook()
        val activeNote = createNote(notebookId, deleted = false)
        val trashedNote = createNote(notebookId, deleted = true)
        val pdfAssetId = id()
        val imageAssetId = id()
        db.assetDao().insert(asset(pdfAssetId, "application/pdf", "pdf"))
        db.assetDao().insert(asset(imageAssetId, "image/png", "png"))

        val activePage = createPage(activeNote, pdfBackground(pdfAssetId))
        val trashedPage = createPage(trashedNote, pdfBackground(pdfAssetId))
        createImage(activePage, imageAssetId)
        createImage(trashedPage, imageAssetId)

        val beforeNotes = db.noteDao().countAll()
        val beforePages = db.pageDao().countAll()
        val beforeElements = db.elementDao().countAll()
        val beforeAssets = db.assetDao().countAll()

        val scan = RoomAssetOwnerInventory(db).scan()
        assertTrue("Expected completed scan: $scan", scan is RoomAssetOwnerInventory.Scan.Completed)
        val report = (scan as RoomAssetOwnerInventory.Scan.Completed).report

        assertEquals(4, report.owners.size)
        assertEquals(2, report.owners.count { it.assetId == pdfAssetId })
        assertEquals(2, report.owners.count { it.assetId == imageAssetId })
        assertEquals(2, report.owners.count { it.inTrash })
        assertEquals(setOf(activeNote, trashedNote), report.owners.map { it.noteId }.toSet())
        assertFalse(report.hasUnresolved)
        assertTrue(report.metadataWithoutRecognizedOwner.isEmpty())

        // The reader must never change the canonical DB or registered metadata.
        assertEquals(beforeNotes, db.noteDao().countAll())
        assertEquals(beforePages, db.pageDao().countAll())
        assertEquals(beforeElements, db.elementDao().countAll())
        assertEquals(beforeAssets, db.assetDao().countAll())
    }

    @Test
    fun malformedRowsAndMissingMetadataRemainDiagnosticNotSafeToDelete() = runBlocking {
        val noteId = createNote(createNotebook(), deleted = true)
        val missingAssetId = id()
        val unrelatedMetadataId = id()
        db.assetDao().insert(asset(unrelatedMetadataId, "image/png", "png"))
        val pageId = createPage(noteId, pdfBackground(missingAssetId))
        db.elementDao().insert(ElementEntity(id(), pageId, 0L, "image", "{", 0L))

        val scan = RoomAssetOwnerInventory(db).scan()
        assertTrue("Expected completed scan: $scan", scan is RoomAssetOwnerInventory.Scan.Completed)
        val report = (scan as RoomAssetOwnerInventory.Scan.Completed).report

        assertTrue(report.hasUnresolved)
        assertTrue(report.owners.any { it.assetId == missingAssetId && it.inTrash })
        assertTrue(report.issues.any { it.reason == "referenced asset absent from metadata" })
        assertTrue(report.issues.any { it.elementId != null })
        assertEquals(setOf(unrelatedMetadataId), report.metadataWithoutRecognizedOwner)
        // The last assertion describes an inventory mismatch, not a deletion allowance.
    }

    @Test
    fun moreThanMaximumNotesReturnsIncompleteInsteadOfPartialReport() = runBlocking {
        val notebookId = createNotebook()
        db.withTransaction {
            repeat(2_001) { index ->
                db.noteDao().insert(NoteEntity(id(), notebookId, "Synthetic $index", 1L, 1L))
            }
        }
        val scan = RoomAssetOwnerInventory(db).scan()
        assertTrue("Expected incomplete scan: $scan", scan is RoomAssetOwnerInventory.Scan.Incomplete)
        assertEquals(2_001, db.noteDao().countAll())
    }

    private suspend fun createNotebook(): String {
        val notebookId = id()
        db.notebookDao().insert(
            NotebookEntity(
                id = notebookId,
                parentFolderId = null,
                name = "Synthetic",
                sortOrder = 0L,
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            )
        )
        return notebookId
    }

    private suspend fun createNote(notebookId: String, deleted: Boolean): String {
        val noteId = id()
        db.noteDao().insert(
            NoteEntity(
                id = noteId,
                notebookId = notebookId,
                title = "Synthetic note",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
                deletedAtMillis = if (deleted) 2L else null,
            )
        )
        return noteId
    }

    private suspend fun createPage(noteId: String, background: String): String {
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 612.0, 792.0, background, 0L))
        return pageId
    }

    private suspend fun createImage(pageId: String, assetId: String) {
        val elementId = id()
        val envelope = """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        db.elementDao().insert(ElementEntity(elementId, pageId, 0L, "image", envelope, 0L))
    }

    private fun asset(assetId: String, mimeType: String, extension: String): AssetEntity {
        val sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
        return AssetEntity(
            id = assetId,
            sha256 = sha,
            mimeType = mimeType,
            byteSize = 10L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$extension",
            state = "ready",
        )
    }

    private fun pdfBackground(assetId: String) =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private fun id() = UUID.randomUUID().toString()
}
