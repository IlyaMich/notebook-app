package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-08 Deliverable 1: metadata-only duplication of indexed PDF/image owners.
 * Unique isolated databases and synthetic asset metadata; no managed files or GC.
 */
@RunWith(AndroidJUnit4::class)
class Sto08AssetBackedNoteDuplicationAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "sto08-d1f-${UUID.randomUUID()}.sqlite"
        check(name != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
    }

    private fun id() = UUID.randomUUID().toString()

    private suspend fun asset(mime: String): String {
        val assetId = id()
        val sha = id().replace("-", "") + id().replace("-", "")
        val ext = when (mime) {
            "application/pdf" -> "pdf"
            "image/jpeg" -> "jpg"
            else -> "png"
        }
        db.assetDao().insert(AssetEntity(
            id = assetId, sha256 = sha, mimeType = mime, byteSize = 16L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext",
            state = "finalized",
        ))
        return assetId
    }

    private fun pdf(assetId: String) =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private fun image(elementId: String, assetId: String) =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":20,"height":20},"opacity":1}}"""

    private data class Fixture(
        val noteId: String,
        val pageId: String,
        val elementId: String,
        val pdfId: String,
        val imageId: String,
    )

    private suspend fun mediaFixture(): Fixture {
        val book = LibraryRepositoryFactory(db).notebooks.create("Shared media")
        val note = LibraryRepositoryFactory(db).noteLifecycle().createNote(book.id, "Original")
        val page = db.pageDao().listByNote(note.id).single()
        val pdfId = asset("application/pdf")
        val imageId = asset("image/png")
        check(db.pageDao().update(page.copy(backgroundJson = pdf(pdfId))) == 1)
        db.assetOwnershipDao().insertPageReference(PageAssetReferenceEntity(page.id, pdfId))
        val elementId = id()
        db.elementDao().insert(ElementEntity(
            elementId, page.id, 0L, "image", image(elementId, imageId), 0L,
        ))
        db.assetOwnershipDao().insertElementReference(
            ElementAssetReferenceEntity(elementId, imageId))
        return Fixture(note.id, page.id, elementId, pdfId, imageId)
    }

    private fun foreignKeysValid() {
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertEquals(0, it.count)
        }
    }

    @Test fun duplicatePdfAndImageSharesAssetsWithDistinctOwnersAndSurvivesReopen() = runBlocking {
        val f = mediaFixture()
        val cloned = LibraryRepositoryFactory(db).noteLifecycle().duplicateNote(f.noteId)
        assertNotEquals(f.noteId, cloned.id)
        assertEquals(0L, cloned.revision)
        val pages = db.pageDao().listByNote(cloned.id)
        assertEquals(1, pages.size)
        val copyPage = pages.single()
        assertNotEquals(f.pageId, copyPage.id)
        assertEquals(pdf(f.pdfId), copyPage.backgroundJson)
        assertEquals(PageAssetReferenceEntity(copyPage.id, f.pdfId),
            db.assetOwnershipDao().findPageReference(copyPage.id))
        val elements = db.elementDao().listByPage(copyPage.id)
        assertEquals(1, elements.size)
        val copyElement = elements.single()
        assertNotEquals(f.elementId, copyElement.id)
        assertEquals(copyElement.id, JSONObject(copyElement.elementJson).getString("id"))
        assertEquals(f.imageId, JSONObject(copyElement.elementJson)
            .getJSONObject("payload").getString("assetId"))
        assertEquals(ElementAssetReferenceEntity(copyElement.id, f.imageId),
            db.assetOwnershipDao().findElementReference(copyElement.id))
        assertEquals(PageAssetReferenceEntity(f.pageId, f.pdfId),
            db.assetOwnershipDao().findPageReference(f.pageId))
        assertEquals(ElementAssetReferenceEntity(f.elementId, f.imageId),
            db.assetOwnershipDao().findElementReference(f.elementId))
        assertEquals(2, db.assetDao().countAll())
        foreignKeysValid()
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        assertEquals(ElementAssetReferenceEntity(copyElement.id, f.imageId),
            db.assetOwnershipDao().findElementReference(copyElement.id))
        assertEquals(PageAssetReferenceEntity(copyPage.id, f.pdfId),
            db.assetOwnershipDao().findPageReference(copyPage.id))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun missingImageOwnerProjectionRollsBackEntireDuplicate() = runBlocking {
        val f = mediaFixture()
        check(db.assetOwnershipDao().removeElementReferenceIfMatches(f.elementId, f.imageId) == 1)
        val beforeNotes = db.noteDao().countAll()
        val beforePages = db.pageDao().countAll()
        val error = runCatching {
            LibraryRepositoryFactory(db).noteLifecycle().duplicateNote(f.noteId)
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException)
        assertEquals(LibraryErrorCode.UNSUPPORTED_CONTENT,
            (error as LibraryOperationException).code)
        assertEquals(beforeNotes, db.noteDao().countAll())
        assertEquals(beforePages, db.pageDao().countAll())
        assertNotNull(db.noteDao().findById(f.noteId))
        foreignKeysValid()
    }

    @Test fun mismatchedPdfOwnerProjectionRejectsDuplicateWithoutNewNote() = runBlocking {
        val f = mediaFixture()
        val other = asset("application/pdf")
        check(db.assetOwnershipDao().changePageAssetIfMatches(f.pageId, f.pdfId, other) == 1)
        val beforeNotes = db.noteDao().countAll()
        val error = runCatching {
            LibraryRepositoryFactory(db).noteLifecycle().duplicateNote(f.noteId)
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException)
        assertEquals(LibraryErrorCode.UNSUPPORTED_CONTENT,
            (error as LibraryOperationException).code)
        assertEquals(beforeNotes, db.noteDao().countAll())
        assertEquals(PageAssetReferenceEntity(f.pageId, other),
            db.assetOwnershipDao().findPageReference(f.pageId))
        assertNotNull(db.assetDao().findById(f.pdfId))
        foreignKeysValid()
    }

    @Test fun cloneFaultOnNewImageReferenceInsertRollsBackAllCopiedRows() = runBlocking {
        val f = mediaFixture()
        val beforeNotes = db.noteDao().countAll()
        val trigger = "sto08_d1f_abort_clone_image"
        val marker = "sto08_d1f_abort_edge"
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $trigger BEFORE INSERT ON element_asset_references " +
                "BEGIN SELECT RAISE(ABORT, '$marker'); END")
        val error = try {
            runCatching {
                LibraryRepositoryFactory(db).noteLifecycle().duplicateNote(f.noteId)
            }.exceptionOrNull()
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
        }
        assertNotNull(error)
        assertTrue(generateSequence(error) { it.cause }
            .any { it.toString().contains(marker) })
        assertEquals(beforeNotes, db.noteDao().countAll())
        assertEquals(listOf(f.pageId), db.pageDao().listByNote(f.noteId).map { it.id })
        assertEquals(PageAssetReferenceEntity(f.pageId, f.pdfId),
            db.assetOwnershipDao().findPageReference(f.pageId))
        assertEquals(ElementAssetReferenceEntity(f.elementId, f.imageId),
            db.assetOwnershipDao().findElementReference(f.elementId))
        foreignKeysValid()
    }
}
