package com.notebook.app.data.content

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.assets.RegisteredFinalizedImageDecoder
import com.notebook.app.data.content.impl.matchesVerifiedImageMetadata
import com.notebook.app.data.assets.ManagedAssetFileStore
import com.notebook.app.data.assets.ManagedAssetRegistrar
import com.notebook.app.data.library.impl.RoomLibraryTrashRepository
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.ImageFrame
import com.notebook.app.data.content.model.InsertVerifiedImageCommand
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** First I2B writer gates. Real final originals and isolated Room v4 only. */
@RunWith(AndroidJUnit4::class)
class InsertVerifiedImageWriterAndroidTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var root: File
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        dbName = "sto08-i2b-writer-$suffix.sqlite"
        root = File(context.filesDir, "sto08-i2b-writer-$suffix")
        assertTrue(root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 2_000L }, verifiedImageRoot = root)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized && ::dbName.isInitialized) context.deleteDatabase(dbName)
        if (::root.isInitialized && root.name.startsWith("sto08-i2b-writer-")) {
            assertTrue(root.deleteRecursively())
        }
    }

    private fun id(): String = UUID.randomUUID().toString()

    private suspend fun noteAndPage(): Pair<String,String> {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(notebookId, null, "Notebook", sortOrder = 0,
            createdAtMillis = 100L, updatedAtMillis = 100L))
        val noteId = id()
        val pageId = id()
        db.noteDao().insert(NoteEntity(noteId, notebookId, "Note", 100L, 100L))
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 595.0, 842.0,
            """{"type":"blank","color":"#FFFFFF"}""", 0L))
        return noteId to pageId
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(38, 102, 189))
            ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun imageAsset() = ManagedAssetRegistrar(
        ManagedAssetFileStore(root), db.assetDao()
    ).register(ByteArrayInputStream(png()), "image/png")

    private fun command(note: String, page: String, asset: String, element: String = id(), rev: Long = 0L,
                        at: Int? = null) = InsertVerifiedImageCommand(
        noteId = note, pageId = page, elementId = element, assetId = asset,
        expectedNoteRevision = rev, at = at,
        transform = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        frame = ImageFrame(10.0, 12.0, 80.0, 60.0), opacity = 1.0,
    )

    @Test fun validImageInsertsMatchingElementEdgeAndRevisionAndSurvivesReopen() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val commit = repo.insertVerifiedImage(command(note, page, asset.id, element))
        assertEquals(1L, commit.revision)
        val row = db.elementDao().findById(element)!!
        assertEquals("image", row.kind)
        assertEquals(page, row.pageId)
        val json = JSONObject(row.elementJson)
        assertEquals(element, json.getString("id"))
        assertEquals("image", json.getString("type"))
        assertEquals(asset.id, json.getJSONObject("payload").getString("assetId"))
        assertEquals(false, json.getBoolean("locked"))
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(element)!!.assetId)
        assertEquals(1L, db.noteDao().findById(note)!!.revision)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        repo = RoomDocumentRepository(db, clock = { 2_000L }, verifiedImageRoot = root)
        assertEquals(element, db.elementDao().listByPage(page).single().id)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(element)!!.assetId)
        assertEquals(1L, repo.load(note).revision)
    }

    @Test fun staleExpectedRevisionRejectsWithoutElementOrEdge() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val noteRow = db.noteDao().findById(note)!!
        db.noteDao().update(noteRow.copy(revision = 1L))
        val failure = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element, rev = 0L))
        }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException && failure.code == LibraryErrorCode.INVALID_INPUT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(1L, db.noteDao().findById(note)!!.revision)
    }

    @Test fun existingMediaOnTargetPageRejectsWithoutOwnerMutation() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val legacy = id()
        db.elementDao().insert(com.notebook.app.data.local.entity.ElementEntity(
            legacy, page, 0L, "image", """{"id":"$legacy","type":"image"}""", 0L
        ))
        val element = id()
        val failure = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException && failure.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(0L, db.noteDao().findById(note)!!.revision)
    }


    /** SQLite test triggers abort at precise writer stages; no production hooks. */
    private fun addAbortTrigger(name: String, sql: String) {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name $sql")
    }

    private fun removeAbortTrigger(name: String) {
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
    }

    private fun assertInjectedAbort(error: Throwable?, marker: String) {
        val thrown = error ?: throw AssertionError("Expected injected SQLite failure: $marker")
        val messageChain = generateSequence(thrown) { it.cause }
            .joinToString(" / ") { it.toString() }
        assertTrue("Expected $marker in $messageChain", messageChain.contains(marker))
    }

    private fun canonicalText(element: String) =
        """{"id":"$element","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"Existing","frame":{"x":0,"y":0,"width":100,"height":30},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    private fun reopen() {
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        repo = RoomDocumentRepository(db, clock = { 2_000L }, verifiedImageRoot = root)
    }

    @Test fun failureBeforeOwnerEdgeInsertRollsBackPriorElementInsert() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val beforeNote = db.noteDao().findById(note)
        val beforeAsset = db.assetDao().findById(asset.id)
        val beforeElements = db.elementDao().listByPage(page)
        val marker = "sto08_forced_owner_edge_abort"
        val trigger = "sto08_i2b_abort_owner_edge"
        addAbortTrigger(trigger,
            "BEFORE INSERT ON element_asset_references BEGIN SELECT RAISE(ABORT, '$marker'); END")
        try {
            val failure = runCatching {
                repo.insertVerifiedImage(command(note, page, asset.id, element))
            }.exceptionOrNull()
            assertInjectedAbort(failure, marker)
        } finally {
            removeAbortTrigger(trigger)
        }
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
    }

    @Test fun failureOnNoteRevisionUpdateRollsBackBothImageAndOwnerEdge() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val beforeNote = db.noteDao().findById(note)
        val beforeAsset = db.assetDao().findById(asset.id)
        val beforeElements = db.elementDao().listByPage(page)
        val marker = "sto08_forced_revision_abort"
        val trigger = "sto08_i2b_abort_revision"
        addAbortTrigger(trigger,
            "BEFORE UPDATE OF revision ON notes WHEN NEW.revision != OLD.revision BEGIN SELECT RAISE(ABORT, '$marker'); END")
        try {
            val failure = runCatching {
                repo.insertVerifiedImage(command(note, page, asset.id, element))
            }.exceptionOrNull()
            assertInjectedAbort(failure, marker)
        } finally {
            removeAbortTrigger(trigger)
        }
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
    }

    @Test fun failureDuringReorderRollsBackImageEdgeAndPriorText() = runBlocking {
        val (note, page) = noteAndPage()
        val existing = id()
        assertEquals(1L, repo.commit(note, DocumentEdit.InsertElements(page,
            listOf(canonicalText(existing)))).revision)
        val asset = imageAsset()
        val image = id()
        val beforeNote = db.noteDao().findById(note)
        val beforeAsset = db.assetDao().findById(asset.id)
        val beforeElements = db.elementDao().listByPage(page)
        val marker = "sto08_forced_reorder_abort"
        val trigger = "sto08_i2b_abort_reorder"
        addAbortTrigger(trigger,
            "BEFORE UPDATE OF order_index ON elements WHEN NEW.order_index != OLD.order_index BEGIN SELECT RAISE(ABORT, '$marker'); END")
        try {
            val failure = runCatching {
                repo.insertVerifiedImage(command(note, page, asset.id, image, rev = 1L, at = 0))
            }.exceptionOrNull()
            assertInjectedAbort(failure, marker)
        } finally {
            removeAbortTrigger(trigger)
        }
        assertNull(db.elementDao().findById(image))
        assertNull(db.assetOwnershipDao().findElementReference(image))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        assertNull(db.elementDao().findById(image))
        assertNull(db.assetOwnershipDao().findElementReference(image))
        assertEquals(beforeElements, db.elementDao().listByPage(page))
        assertEquals(beforeNote, db.noteDao().findById(note))
        assertEquals(beforeAsset, db.assetDao().findById(asset.id))
    }

    @Test fun insertAtFrontReordersMediaFreePageAndAdvancesRevisionOnce() = runBlocking {
        val (note, page) = noteAndPage()
        val existing = id()
        val textJson = canonicalText(existing)
        assertEquals(1L, repo.commit(note, DocumentEdit.InsertElements(page,
            listOf(textJson))).revision)
        val asset = imageAsset()
        val image = id()
        val committed = repo.insertVerifiedImage(command(note, page, asset.id, image,
            rev = 1L, at = 0))
        assertEquals(2L, committed.revision)
        assertEquals(2L, db.noteDao().findById(note)!!.revision)
        val rows = db.elementDao().listByPage(page)
        assertEquals(listOf(image, existing), rows.map { it.id })
        assertEquals(listOf(0L, 1L), rows.map { it.orderIndex })
        assertEquals(textJson, rows[1].elementJson)
        assertEquals("image", rows[0].kind)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(image)!!.assetId)
        assertNull(db.assetOwnershipDao().findElementReference(existing))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        val reopened = db.elementDao().listByPage(page)
        assertEquals(listOf(image, existing), reopened.map { it.id })
        assertEquals(listOf(0L, 1L), reopened.map { it.orderIndex })
        assertEquals(textJson, reopened[1].elementJson)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(image)!!.assetId)
        assertEquals(2L, repo.load(note).revision)
    }


    @Test fun baselineJpegAttachesWithMatchingEdgeWithoutMutatingOriginal() = runBlocking {
        val (note, page) = noteAndPage()
        val bytes = Bitmap.createBitmap(10, 7, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.rgb(70, 120, 180))
                ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val asset = ManagedAssetRegistrar(ManagedAssetFileStore(root), db.assetDao())
            .register(ByteArrayInputStream(bytes), "image/jpeg")
        val before = db.assetDao().findById(asset.id)
        val element = id()
        assertEquals(1L, repo.insertVerifiedImage(command(note, page, asset.id, element)).revision)
        assertEquals("image", db.elementDao().findById(element)!!.kind)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(element)!!.assetId)
        assertEquals(before, db.assetDao().findById(asset.id))
        reopen()
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(element)!!.assetId)
        assertEquals(before, db.assetDao().findById(asset.id))
    }

    @Test fun oneFinalizedOriginalCanHaveTwoIndependentPageOwners() = runBlocking {
        val (note, first) = noteAndPage()
        val second = id()
        db.pageDao().insert(PageEntity(second, note, 1L, 595.0, 842.0,
            """{"type":"blank","color":"#FFFFFF"}""", 0L))
        val asset = imageAsset()
        val a = id()
        val b = id()
        assertEquals(1L, repo.insertVerifiedImage(command(note, first, asset.id, a)).revision)
        assertEquals(2L, repo.insertVerifiedImage(command(note, second, asset.id, b, rev = 1L)).revision)
        val edges = db.assetOwnershipDao().listElementReferencesByAsset(asset.id)
        assertEquals(setOf(a, b), edges.map { it.elementId }.toSet())
        assertEquals(2, edges.size)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(a)!!.assetId)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(b)!!.assetId)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        assertEquals(2, db.assetOwnershipDao().listElementReferencesByAsset(asset.id).size)
        assertEquals(2L, repo.load(note).revision)
    }

    @Test fun unresolvedOwnershipGateRejectsWithoutMutatingNoteOrEdge() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val before = db.noteDao().findById(note)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE asset_reference_index_state SET state = \'UNRESOLVED\' WHERE id = 1")
        assertEquals("UNRESOLVED", db.assetOwnershipDao().readFailClosedGateStatus())
        val error = runCatching { repo.insertVerifiedImage(command(note, page, asset.id, element)) }
            .exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        assertEquals("UNRESOLVED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun unexpectedIndexedPageAssetRejectsWithoutMutation() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        db.assetOwnershipDao().insertPageReference(
            com.notebook.app.data.local.entity.PageAssetReferenceEntity(page, asset.id))
        val element = id()
        val before = db.noteDao().findById(note)
        val error = runCatching { repo.insertVerifiedImage(command(note, page, asset.id, element)) }
            .exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(asset.id, db.assetOwnershipDao().findPageReference(page)!!.assetId)
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun elementIdAlreadyOnAnotherPageRejectsBeforeAnyWrite() = runBlocking {
        val (note, target) = noteAndPage()
        val other = id()
        db.pageDao().insert(PageEntity(other, note, 1L, 595.0, 842.0,
            """{"type":"blank","color":"#FFFFFF"}""", 0L))
        val occupied = id()
        val text = canonicalText(occupied)
        assertEquals(1L, repo.commit(note, DocumentEdit.InsertElements(other, listOf(text))).revision)
        val asset = imageAsset()
        val before = db.noteDao().findById(note)
        val error = runCatching {
            repo.insertVerifiedImage(command(note, target, asset.id, occupied, rev = 1L))
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.INVALID_INPUT)
        assertTrue(db.elementDao().listByPage(target).isEmpty())
        assertEquals(text, db.elementDao().findById(occupied)!!.elementJson)
        assertNull(db.assetOwnershipDao().findElementReference(occupied))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun middleInsertionPreservesBothExistingCanonicalEnvelopes() = runBlocking {
        val (note, page) = noteAndPage()
        val first = id()
        val last = id()
        val a = canonicalText(first)
        val b = canonicalText(last)
        assertEquals(1L, repo.commit(note, DocumentEdit.InsertElements(page, listOf(a, b))).revision)
        val asset = imageAsset()
        val image = id()
        assertEquals(2L, repo.insertVerifiedImage(
            command(note, page, asset.id, image, rev = 1L, at = 1)).revision)
        val rows = db.elementDao().listByPage(page)
        assertEquals(listOf(first, image, last), rows.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), rows.map { it.orderIndex })
        assertEquals(a, rows[0].elementJson)
        assertEquals(b, rows[2].elementJson)
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(image)!!.assetId)
        reopen()
        assertEquals(listOf(first, image, last), db.elementDao().listByPage(page).map { it.id })
    }

    @Test fun attachedImageCannotBeRemovedReplacedOrTransferredByGenericEdits() = runBlocking {
        val (note, page) = noteAndPage()
        val second = id()
        db.pageDao().insert(PageEntity(second, note, 1L, 595.0, 842.0,
            """{"type":"blank","color":"#FFFFFF"}""", 0L))
        val asset = imageAsset()
        val image = id()
        assertEquals(1L, repo.insertVerifiedImage(command(note, page, asset.id, image)).revision)
        val beforeNote = db.noteDao().findById(note)
        val beforeImage = db.elementDao().findById(image)
        val beforeEdge = db.assetOwnershipDao().findElementReference(image)
        val attempts = listOf<DocumentEdit>(
            DocumentEdit.RemoveElements(page, listOf(image)),
            DocumentEdit.ReplaceElements(page, listOf(canonicalText(image))),
            DocumentEdit.TransferElements(page, second, listOf(image)),
            DocumentEdit.RemovePage(page),
        )
        for (edit in attempts) {
            val failure = runCatching { repo.commit(note, edit) }.exceptionOrNull()
            assertTrue("Expected protected media rejection for $edit",
                failure is LibraryOperationException && failure.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
            assertEquals(beforeImage, db.elementDao().findById(image))
            assertEquals(beforeEdge, db.assetOwnershipDao().findElementReference(image))
            assertEquals(beforeNote, db.noteDao().findById(note))
        }
        reopen()
        assertEquals(beforeImage, db.elementDao().findById(image))
        assertEquals(beforeEdge, db.assetOwnershipDao().findElementReference(image))
    }


    @Test fun missingFinalizedOriginalRejectsBeforeOwnerTransaction() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val original = File(root, asset.relativePath)
        assertTrue("Test fixture original must exist", original.isFile)
        assertTrue("Test fixture original must be removable", original.delete())
        val before = db.noteDao().findById(note)
        val error = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertNotNull("Missing original must reject insertion", error)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        reopen()
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun tamperedFinalizedOriginalDigestRejectsWithoutOwnerMutation() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val original = File(root, asset.relativePath)
        val altered = original.readBytes()
        assertTrue("Test fixture PNG must be nonempty", altered.isNotEmpty())
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        original.writeBytes(altered)
        assertEquals(asset.byteSize, original.length())
        val before = db.noteDao().findById(note)
        val error = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertNotNull("Digest mismatch must reject insertion", error)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        reopen()
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun pdfBackgroundRejectsFirstImageWithoutChangingPageOrNote() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val originalPage = db.pageDao().findById(page)!!
        val pdf = originalPage.copy(backgroundJson =
            """{"type":"pdfPage","assetId":"${id()}","pageIndex":0,"displayBox":"crop"}""")
        assertEquals(1, db.pageDao().update(pdf))
        val element = id()
        val before = db.noteDao().findById(note)
        val error = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertEquals(pdf, db.pageDao().findById(page))
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        reopen()
        assertEquals(pdf, db.pageDao().findById(page))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun legacyImageJsonWithWrongIndexedKindRejectsWithoutRepair() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val legacyId = id()
        val legacyJson = """{"id":"$legacyId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${asset.id}","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        val legacy = com.notebook.app.data.local.entity.ElementEntity(
            legacyId, page, 0L, "text", legacyJson, 0L)
        db.elementDao().insert(legacy)
        val element = id()
        val before = db.noteDao().findById(note)
        val error = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertEquals(legacy, db.elementDao().findById(legacyId))
        assertNull(db.assetOwnershipDao().findElementReference(legacyId))
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        reopen()
        assertEquals(legacy, db.elementDao().findById(legacyId))
        assertNull(db.assetOwnershipDao().findElementReference(legacyId))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun missingOwnershipGateSingletonRejectsWithoutMutation() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val before = db.noteDao().findById(note)
        // Deliberately damage only this isolated test database; production gate deletion
        // remains prohibited by its schema trigger.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER trg_asset_ref_gate_delete")
        db.openHelper.writableDatabase.execSQL("DELETE FROM asset_reference_index_state WHERE id = 1")
        assertEquals("UNSAFE", db.assetOwnershipDao().readFailClosedGateStatus())
        val failure = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException &&
            failure.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        reopen()
        assertEquals("UNSAFE", db.assetOwnershipDao().readFailClosedGateStatus())
        assertNull(db.elementDao().findById(element))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun invalidOwnershipGateStateRejectsWithoutMutation() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val before = db.noteDao().findById(note)
        // Production schema rejects an invalid state. Simulate legacy corruption only
        // inside this UUID-named isolated test DB to verify the read-side fail closure.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER trg_asset_ref_gate_update")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE asset_reference_index_state SET state = 'INVALID' WHERE id = 1")
        assertEquals("UNSAFE", db.assetOwnershipDao().readFailClosedGateStatus())
        val failure = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException &&
            failure.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        reopen()
        assertEquals("UNSAFE", db.assetOwnershipDao().readFailClosedGateStatus())
        assertNull(db.elementDao().findById(element))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun registeredTruncatedPngWithMatchingDigestRejectsFullDecode() = runBlocking {
        val (note, page) = noteAndPage()
        val validPng = png()
        assertTrue("PNG fixture must exceed signature", validPng.size > 32)
        // ManagedAssetRegistrar checks signature and hash but not full image decode.
        // Its finalized row and disk bytes are consistent; the writer must still
        // refuse the truncated PNG before entering its owner transaction.
        val truncated = validPng.copyOfRange(0, 24)
        val asset = ManagedAssetRegistrar(
            ManagedAssetFileStore(root), db.assetDao()
        ).register(ByteArrayInputStream(truncated), "image/png")
        assertEquals(24L, asset.byteSize)
        val element = id()
        val before = db.noteDao().findById(note)
        val failure = runCatching {
            repo.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertNotNull("Truncated PNG must fail full decode", failure)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
        assertEquals(asset, db.assetDao().findById(asset.id))
        assertTrue(File(root, asset.relativePath).isFile)
        reopen()
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(before, db.noteDao().findById(note))
    }

    @Test fun cancellationWhileLeaseContendedCreatesNoOwnerAndLaterInsertWorks() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val cancelledElement = id()
        val beforeNote = db.noteDao().findById(note)
        // The outer lease forces the writer to await acquisition, never opening
        // a Room owner transaction. Cancel it while this lease is held.
        com.notebook.app.data.assets.AssetAcquisitionLeaseCoordinator(root).withLease {
            val pending = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Default
            ).async {
                repo.insertVerifiedImage(command(note, page, asset.id, cancelledElement))
            }
            try {
                // Give the writer a scheduling opportunity without relying on
                // a wall-clock delay or exposing a production fault hook.
                kotlinx.coroutines.yield()
            } finally {
                pending.cancelAndJoin()
            }
            assertTrue(pending.isCancelled)
            assertNull(db.elementDao().findById(cancelledElement))
            assertNull(db.assetOwnershipDao().findElementReference(cancelledElement))
            assertEquals(beforeNote, db.noteDao().findById(note))
        }
        val successful = id()
        assertEquals(1L,
            repo.insertVerifiedImage(command(note, page, asset.id, successful)).revision)
        assertNull(db.elementDao().findById(cancelledElement))
        assertNull(db.assetOwnershipDao().findElementReference(cancelledElement))
        assertEquals(asset.id, db.assetOwnershipDao().findElementReference(successful)!!.assetId)
        reopen()
        assertEquals(1L, db.noteDao().findById(note)!!.revision)
        assertEquals(listOf(successful), db.elementDao().listByPage(page).map { it.id })
    }

    @Test fun concurrentSameRevisionImageInsertsHaveExactlyOneWinner() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val leftId = id()
        val rightId = id()
        val results = kotlinx.coroutines.coroutineScope {
            val left = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { repo.insertVerifiedImage(command(note, page, asset.id, leftId)) }
            }
            val right = async(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { repo.insertVerifiedImage(command(note, page, asset.id, rightId)) }
            }
            listOf(left.await(), right.await())
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(1L, results.single { it.isSuccess }.getOrThrow().revision)
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException &&
            failure.code == LibraryErrorCode.INVALID_INPUT)
        val rows = db.elementDao().listByPage(page)
        assertEquals(1, rows.size)
        assertTrue(rows.single().id == leftId || rows.single().id == rightId)
        assertEquals(asset.id,
            db.assetOwnershipDao().findElementReference(rows.single().id)!!.assetId)
        val loser = if (rows.single().id == leftId) rightId else leftId
        assertNull(db.elementDao().findById(loser))
        assertNull(db.assetOwnershipDao().findElementReference(loser))
        assertEquals(1L, db.noteDao().findById(note)!!.revision)
        reopen()
        assertEquals(1L, db.noteDao().findById(note)!!.revision)
        assertEquals(rows, db.elementDao().listByPage(page))
        assertEquals(asset.id,
            db.assetOwnershipDao().findElementReference(rows.single().id)!!.assetId)
    }

    @Test fun verifiedAssetMetadataMatcherRejectsPathAndIdentityChanges() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val verified = RegisteredFinalizedImageDecoder(root, db.assetDao()).inspect(asset.id)
        assertTrue(matchesVerifiedImageMetadata(asset, verified))
        // These assertions exercise the EXACT predicate called inside the writer's
        // Room transaction, after the image has been fully decoded under lease.
        // They do not claim to simulate a real concurrent metadata update.
        assertFalse(matchesVerifiedImageMetadata(
            asset.copy(relativePath = "assets/incorrect/original.png"), verified))
        assertFalse(matchesVerifiedImageMetadata(asset.copy(state = "staged"), verified))
        assertFalse(matchesVerifiedImageMetadata(asset.copy(byteSize = asset.byteSize + 1), verified))
        assertFalse(matchesVerifiedImageMetadata(asset.copy(mimeType = "image/jpeg"), verified))
        assertFalse(matchesVerifiedImageMetadata(asset.copy(sha256 = "0".repeat(64)), verified))
        assertTrue(db.elementDao().listByPage(page).isEmpty())
        assertEquals(0L, db.noteDao().findById(note)!!.revision)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun attachedImageSurvivesTrashRestoreWithOriginalOwnerAndRevision() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        assertEquals(1L, repo.insertVerifiedImage(command(note, page, asset.id, element)).revision)
        val originalNote = db.noteDao().findById(note)!!
        val originalElement = db.elementDao().findById(element)!!
        val originalEdge = db.assetOwnershipDao().findElementReference(element)!!
        val originalAsset = db.assetDao().findById(asset.id)!!
        val originalBytes = File(root, asset.relativePath).readBytes()
        val trash = RoomLibraryTrashRepository(db, clock = { 3_000L })
        val batch = trash.trashNote(note)
        assertTrue(db.noteDao().findById(note)!!.deletedAtMillis != null)
        assertEquals(originalNote.revision, db.noteDao().findById(note)!!.revision)
        assertEquals(originalElement, db.elementDao().findById(element))
        assertEquals(originalEdge, db.assetOwnershipDao().findElementReference(element))
        assertEquals(originalAsset, db.assetDao().findById(asset.id))
        assertTrue(originalBytes.contentEquals(File(root, asset.relativePath).readBytes()))
        reopen()
        RoomLibraryTrashRepository(db, clock = { 3_000L }).restoreBatch(batch)
        val restored = db.noteDao().findById(note)!!
        assertEquals(null, restored.deletedAtMillis)
        assertEquals(originalNote.revision, restored.revision)
        assertEquals(originalElement, db.elementDao().findById(element))
        assertEquals(originalEdge, db.assetOwnershipDao().findElementReference(element))
        assertEquals(originalAsset, db.assetDao().findById(asset.id))
        assertTrue(originalBytes.contentEquals(File(root, asset.relativePath).readBytes()))
        assertEquals(1L, repo.load(note).revision)
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun attachedImageBlocksPermanentPurgePreviewAfterTrashAndReopen() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        repo.insertVerifiedImage(command(note, page, asset.id, element))
        val originalElement = db.elementDao().findById(element)!!
        val originalEdge = db.assetOwnershipDao().findElementReference(element)!!
        val originalAsset = db.assetDao().findById(asset.id)!!
        val originalBytes = File(root, asset.relativePath).readBytes()
        val batch = RoomLibraryTrashRepository(db, clock = { 3_000L }).trashNote(note)
        reopen()
        val failure = runCatching {
            RoomLibraryTrashRepository(db, clock = { 3_000L }).previewPurge(batch)
        }.exceptionOrNull()
        assertTrue(failure is LibraryOperationException && failure.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        val trashed = db.noteDao().findById(note)!!
        assertEquals(batch, trashed.deletionBatchId)
        assertTrue(trashed.deletedAtMillis != null)
        assertEquals(1L, trashed.revision)
        assertEquals(originalElement, db.elementDao().findById(element))
        assertEquals(originalEdge, db.assetOwnershipDao().findElementReference(element))
        assertEquals(originalAsset, db.assetDao().findById(asset.id))
        assertTrue(originalBytes.contentEquals(File(root, asset.relativePath).readBytes()))
        assertEquals("UNVERIFIED", db.assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun unconfiguredWriterRejectsAndGenericImageInsertionRemainsBlocked() = runBlocking {
        val (note, page) = noteAndPage()
        val asset = imageAsset()
        val element = id()
        val unconfigured = RoomDocumentRepository(db)
        val error = runCatching {
            unconfigured.insertVerifiedImage(command(note, page, asset.id, element))
        }.exceptionOrNull()
        assertTrue(error is LibraryOperationException && error.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        val json = """{"id":"$element","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${asset.id}","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""
        val generic = runCatching {
            repo.commit(note, DocumentEdit.InsertElements(page, listOf(json)))
        }.exceptionOrNull()
        assertTrue(generic is LibraryOperationException && generic.code == LibraryErrorCode.UNSUPPORTED_CONTENT)
        assertNull(db.elementDao().findById(element))
        assertNull(db.assetOwnershipDao().findElementReference(element))
        assertEquals(0L, db.noteDao().findById(note)!!.revision)
    }
}
