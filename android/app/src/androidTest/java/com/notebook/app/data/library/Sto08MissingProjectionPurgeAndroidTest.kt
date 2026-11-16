package com.notebook.app.data.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import com.notebook.app.data.local.entity.ElementEntity
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

/** I5-A2 regression: absent projections never permit purging canonical media owners.
 * Unique isolated Room databases, synthetic metadata only; no asset files or GC.
 */
@RunWith(AndroidJUnit4::class)
class Sto08MissingProjectionPurgeAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "sto08-i5a2-${UUID.randomUUID()}.sqlite"
        check(databaseName != AppDatabaseProvider.DATABASE_NAME)
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    private suspend fun registeredAsset(mime: String): String {
        val id = UUID.randomUUID().toString()
        val sha = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "")
        val ext = if (mime == "application/pdf") "pdf" else "png"
        db.assetDao().insert(AssetEntity(
            id = id, sha256 = sha, mimeType = mime, byteSize = 16L,
            relativePath = "assets/${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.$ext",
            state = "finalized",
        ))
        return id
    }

    private suspend fun freshNote(): Pair<String, String> {
        val repos = LibraryRepositoryFactory(db)
        val book = repos.notebooks.create("Isolated I5-A2")
        val note = repos.noteLifecycle().createNote(book.id, "Synthetic owner")
        return note.id to db.pageDao().listByNote(note.id).single().id
    }

    private fun pdf(assetId: String): String =
        """{"type":"pdfPage","assetId":"$assetId","pageIndex":0,"displayBox":"crop"}"""

    private fun image(elementId: String, assetId: String): String =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"$assetId","frame":{"x":0,"y":0,"width":20,"height":20},"opacity":1}}"""

    private suspend fun expectPurgeRejected(noteId: String, pageId: String) {
        val trash = LibraryRepositoryFactory(db).trash
        val pageBefore = db.pageDao().findById(pageId)!!
        val elementsBefore = db.elementDao().listByPage(pageId)
        val batch = trash.trashNote(noteId)
        val trashed = db.noteDao().findById(noteId)!!
        val error = runCatching { trash.previewPurge(batch) }.exceptionOrNull()
        assertTrue("Expected unsupported media preflight", error is LibraryOperationException)
        assertEquals(LibraryErrorCode.UNSUPPORTED_CONTENT, (error as LibraryOperationException).code)
        assertEquals(trashed, db.noteDao().findById(noteId))
        assertEquals(pageBefore, db.pageDao().findById(pageId))
        assertEquals(elementsBefore, db.elementDao().listByPage(pageId))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test fun canonicalPdfWithMissingPageProjectionCannotBePurged() = runBlocking {
        val (noteId, pageId) = freshNote()
        val assetId = registeredAsset("application/pdf")
        val page = db.pageDao().findById(pageId)!!
        assertEquals(1, db.pageDao().update(page.copy(backgroundJson = pdf(assetId))))
        assertNull(db.assetOwnershipDao().findPageReference(pageId))
        expectPurgeRejected(noteId, pageId)
        assertEquals(1, db.assetDao().countAll())
    }

    @Test fun canonicalImageWithMissingElementProjectionCannotBePurged() = runBlocking {
        val (noteId, pageId) = freshNote()
        val assetId = registeredAsset("image/png")
        val elementId = UUID.randomUUID().toString()
        val raw = image(elementId, assetId)
        db.elementDao().insert(ElementEntity(elementId, pageId, 0L, "image", raw, 0L))
        assertNull(db.assetOwnershipDao().findElementReference(elementId))
        expectPurgeRejected(noteId, pageId)
        assertEquals(raw, db.elementDao().findById(elementId)?.elementJson)
        assertEquals(1, db.assetDao().countAll())
    }

    @Test fun restoringTrashedUnindexedMediaPreservesBytesAndStillBlocksPurge() = runBlocking {
        val (noteId, pageId) = freshNote()
        val assetId = registeredAsset("application/pdf")
        val page = db.pageDao().findById(pageId)!!
        val raw = pdf(assetId)
        assertEquals(1, db.pageDao().update(page.copy(backgroundJson = raw)))
        val trash = LibraryRepositoryFactory(db).trash
        val firstBatch = trash.trashNote(noteId)
        assertEquals(raw, db.pageDao().findById(pageId)?.backgroundJson)
        trash.restoreBatch(firstBatch)
        assertNull(db.noteDao().findById(noteId)?.deletedAtMillis)
        assertEquals(raw, db.pageDao().findById(pageId)?.backgroundJson)
        assertNull(db.assetOwnershipDao().findPageReference(pageId))
        expectPurgeRejected(noteId, pageId)
        assertNotNull(db.noteDao().findById(noteId))
        assertEquals(1, db.assetDao().countAll())
    }
}
