package com.notebook.app.data.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.PurgeStep
import com.notebook.app.data.library.impl.RoomLibraryTrashRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 Deliverable 1: confirmed asset-bearing Trash batch purge.
 * Synthetic Room metadata only; no asset files, production database or GC.
 */
@RunWith(AndroidJUnit4::class)
class Sto08AssetAwarePermanentPurgeAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-d1c-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    private fun id(): String = UUID.randomUUID().toString()

    private suspend fun asset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val ext = when (mime) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            else -> "png"
        }
        db.assetDao().insert(AssetEntity(
            id = assetId, sha256 = sha, mimeType = mime, byteSize = 17L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext",
            state = "finalized",
        ))
        return assetId
    }

    private fun pdf(assetId: String): String =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private fun image(elementId: String, assetId: String): String =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":20,"height":20},"opacity":1}}"""

    private data class MediaOwner(val noteId: String, val pageId: String, val elementId: String)

    private suspend fun mediaOwner(
        bookId: String,
        title: String,
        imageAsset: String,
        pdfAsset: String?,
    ): MediaOwner {
        val note = LibraryRepositoryFactory(db).noteLifecycle().createNote(bookId, title)
        val page = db.pageDao().listByNote(note.id).single()
        if (pdfAsset != null) {
            check(db.pageDao().update(page.copy(backgroundJson = pdf(pdfAsset))) == 1)
            db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(page.id, pdfAsset))
        }
        val elementId = id()
        db.elementDao().insert(ElementEntity(
            elementId, page.id, 0L, "image", image(elementId, imageAsset), 0L,
        ))
        db.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(elementId, imageAsset))
        return MediaOwner(note.id, page.id, elementId)
    }

    private fun fkClean() {
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertEquals(0, it.count)
        }
    }

    @Test fun confirmedPdfImageBatchReleasesOnlyItsEdgesAndNeverDeletesMetadata() = runBlocking {
        val book = LibraryRepositoryFactory(db).notebooks.create("Purge media")
        val imageAsset = asset("image/png")
        val pdfAsset = asset("application/pdf")
        val doomed = mediaOwner(book.id, "Doomed", imageAsset, pdfAsset)
        val trash = LibraryRepositoryFactory(db).trash
        val batch = trash.trashNote(doomed.noteId)

        val preview = trash.previewPurge(batch)
        assertEquals(1, preview.noteCount)
        assertEquals(1, preview.pageCount)
        assertEquals(1, preview.elementCount)
        trash.purgeBatch(preview)

        assertNull(db.noteDao().findById(doomed.noteId))
        assertNull(db.pageDao().findById(doomed.pageId))
        assertNull(db.elementDao().findById(doomed.elementId))
        assertNull(db.assetOwnershipDao().findPageReference(doomed.pageId))
        assertNull(db.assetOwnershipDao().findElementReference(doomed.elementId))
        assertNotNull(db.assetDao().findById(pdfAsset))
        assertNotNull(db.assetDao().findById(imageAsset))
        assertEquals(2, db.assetDao().countAll())
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        fkClean()
    }

    @Test fun purgingOneOwnerPreservesSharedActiveAndIndependentlyTrashedOwners() = runBlocking {
        val book = LibraryRepositoryFactory(db).notebooks.create("Shared originals")
        val imageAsset = asset("image/jpeg")
        val pdfAsset = asset("application/pdf")
        val doomed = mediaOwner(book.id, "Doomed", imageAsset, pdfAsset)
        val active = mediaOwner(book.id, "Active", imageAsset, pdfAsset)
        val separatelyTrashed = mediaOwner(book.id, "Separate Trash", imageAsset, pdfAsset)
        val trash = LibraryRepositoryFactory(db).trash
        val separateBatch = trash.trashNote(separatelyTrashed.noteId)
        val batch = trash.trashNote(doomed.noteId)
        trash.purgeBatch(trash.previewPurge(batch))

        assertNull(db.noteDao().findById(doomed.noteId))
        for (owner in listOf(active, separatelyTrashed)) {
            assertNotNull(db.noteDao().findById(owner.noteId))
            assertEquals(PageAssetReferenceEntity(owner.pageId, pdfAsset),
                db.assetOwnershipDao().findPageReference(owner.pageId))
            assertEquals(ElementAssetReferenceEntity(owner.elementId, imageAsset),
                db.assetOwnershipDao().findElementReference(owner.elementId))
        }
        assertNotNull(db.assetDao().findById(pdfAsset))
        assertNotNull(db.assetDao().findById(imageAsset))
        trash.restoreBatch(separateBatch)
        assertNull(db.noteDao().findById(separatelyTrashed.noteId)?.deletedAtMillis)
        fkClean()
    }

    @Test fun purgeFaultAfterFirstElementDeleteRollsBackOwnerReleases() = runBlocking {
        val book = LibraryRepositoryFactory(db).notebooks.create("Rollback")
        val imageAsset = asset("image/png")
        val pdfAsset = asset("application/pdf")
        val doomed = mediaOwner(book.id, "Rollback note", imageAsset, pdfAsset)
        val normalTrash = LibraryRepositoryFactory(db).trash
        val batch = normalTrash.trashNote(doomed.noteId)
        val preview = normalTrash.previewPurge(batch)
        val brokenTrash = RoomLibraryTrashRepository(
            db, onPurgeStep = { step ->
                if (step == PurgeStep.AFTER_FIRST_ELEMENT_DELETE) {
                    throw IllegalStateException("Synthetic purge crash")
                }
            },
        )

        val failure = runCatching { brokenTrash.purgeBatch(preview) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNotNull(db.noteDao().findById(doomed.noteId))
        assertNotNull(db.pageDao().findById(doomed.pageId))
        assertNotNull(db.elementDao().findById(doomed.elementId))
        assertEquals(PageAssetReferenceEntity(doomed.pageId, pdfAsset),
            db.assetOwnershipDao().findPageReference(doomed.pageId))
        assertEquals(ElementAssetReferenceEntity(doomed.elementId, imageAsset),
            db.assetOwnershipDao().findElementReference(doomed.elementId))
        normalTrash.purgeBatch(preview)
        assertNull(db.noteDao().findById(doomed.noteId))
        fkClean()
    }

    @Test fun stalePreviewAfterOwnershipMutationIsRejectedWithoutPartialPurge() = runBlocking {
        val book = LibraryRepositoryFactory(db).notebooks.create("Stale")
        val imageAsset = asset("image/png")
        val secondAsset = asset("image/png")
        val doomed = mediaOwner(book.id, "Stale owner", imageAsset, null)
        val trash = LibraryRepositoryFactory(db).trash
        val batch = trash.trashNote(doomed.noteId)
        val stale = trash.previewPurge(batch)
        val row = db.elementDao().findById(doomed.elementId)!!
        check(db.elementDao().update(row.copy(elementJson = image(doomed.elementId, secondAsset))) == 1)
        check(db.assetOwnershipDao().changeElementAssetIfMatches(
            doomed.elementId, imageAsset, secondAsset) == 1)

        val failure = runCatching { trash.purgeBatch(stale) }.exceptionOrNull()
        assertNotNull(failure)
        assertNotNull(db.noteDao().findById(doomed.noteId))
        assertEquals(ElementAssetReferenceEntity(doomed.elementId, secondAsset),
            db.assetOwnershipDao().findElementReference(doomed.elementId))
        assertNotNull(db.assetDao().findById(imageAsset))
        assertNotNull(db.assetDao().findById(secondAsset))
        fkClean()
    }
}
