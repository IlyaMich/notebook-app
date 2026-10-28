package com.notebook.app.data.content

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Physical SQLite acceptance tests for STO-02 Checkpoint 3A (no lifecycle adapter yet). */
@RunWith(AndroidJUnit4::class)
class RoomDocumentRepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun start() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = "sto02-document-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, file)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 1_000L })
    }

    @After fun stop() {
        db.close()
        context.deleteDatabase(file)
    }

    @Test fun pagesInsertReorderBackgroundRemoveAndReopen() = runBlocking {
        val note = fixture()
        val a = db.pageDao().listByNote(note.id).single().id
        val b = id()
        val c = id()
        assertEquals(1L, repo.commit(note.id, DocumentEdit.InsertPage(b, InitialPageSpec(612.0, 792.0, BLANK))).revision)
        assertEquals(2L, repo.commit(note.id, DocumentEdit.InsertPage(c, InitialPageSpec(841.89, 595.276, GRID), at = 1)).revision)
        assertPageIds(note.id, listOf(a, c, b))
        assertEquals(listOf(0L, 1L, 2L), repo.load(note.id).pages.map { it.orderIndex })
        assertEquals(3L, repo.commit(note.id, DocumentEdit.ReorderPages(listOf(b, a, c))).revision)
        assertEquals(3L, repo.commit(note.id, DocumentEdit.ReorderPages(listOf(b, a, c))).revision) // no-op
        assertEquals(4L, repo.commit(note.id, DocumentEdit.ChangeBackground(a, GRID)).revision)
        assertEquals(5L, repo.commit(note.id, DocumentEdit.RemovePage(c)).revision)
        assertPageIds(note.id, listOf(b, a))
        assertEquals(listOf(0L, 1L), repo.load(note.id).pages.map { it.orderIndex })
        assertEquals(612.0, repo.load(note.id).pages.first().width, 0.0)
        assertEquals(GRID, repo.load(note.id).pages.last().backgroundJson)
        assertEquals(5L, db.noteDao().findById(note.id)!!.revision)
        assertEquals(1_000L, db.noteDao().findById(note.id)!!.updatedAtMillis)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, file)
        repo = RoomDocumentRepository(db)
        assertPageIds(note.id, listOf(b, a))
        assertEquals(5L, repo.load(note.id).revision)
        repo.commit(note.id, DocumentEdit.RemovePage(b))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commit(note.id, DocumentEdit.RemovePage(a)) }
        assertPageIds(note.id, listOf(a))
        assertEquals(6L, repo.load(note.id).revision)
    }

    @Test fun elementsAreIndividualValidatedRowsAndCommandsAdvanceOnce() = runBlocking {
        val note = fixture()
        val page = repo.load(note.id).pages.single().id
        val strokeId = id()
        val textId = id()
        val shapeId = id()
        val stroke = stroke(strokeId)
        val text = text(textId, "First")
        val shape = shape(shapeId)
        assertEquals(1L, repo.commit(note.id, DocumentEdit.InsertElements(page, listOf(stroke, text))).revision)
        assertEquals(2L, repo.commit(note.id, DocumentEdit.InsertElements(page, listOf(shape), at = 1)).revision)
        assertElements(page, listOf(strokeId, shapeId, textId))
        assertEquals(3L, repo.commit(note.id, DocumentEdit.ReorderElements(page, listOf(textId, strokeId, shapeId))).revision)
        assertEquals(4L, repo.commit(note.id, DocumentEdit.ReplaceElements(page, listOf(text(textId, "Revised"), stroke))).revision)
        val stored = db.elementDao().listByPage(page)
        assertEquals("Revised", org.json.JSONObject(stored.first().elementJson).getJSONObject("payload").getString("text"))
        assertEquals(4L, stored.first().revision)
        assertEquals(3L, stored.last().revision) // unchanged row is not rewritten
        assertEquals(5L, repo.commit(note.id, DocumentEdit.RemoveElements(page, listOf(shapeId, strokeId))).revision)
        assertElements(page, listOf(textId))
        assertEquals(0L, db.pageDao().findById(page)!!.revision) // page payload never rewritten
        db.close()
        db = AppDatabaseProvider.openIsolated(context, file)
        repo = RoomDocumentRepository(db)
        val snapshot = repo.load(note.id)
        assertEquals(5L, snapshot.revision)
        assertEquals(listOf(textId), snapshot.pages.single().elements.map { it.id })
        assertEquals(0L, snapshot.pages.single().elements.single().orderIndex)
        assertEquals(text(textId, "Revised"), snapshot.pages.single().elements.single().envelopeJson)
    }

    @Test fun canonicalStrokeAndShapeRoundTripWithoutFlattening() = runBlocking {
        val note = fixture()
        val page = repo.load(note.id).pages.single().id
        val a = id(); val b = id()
        val strokeJson = stroke(a)
        val shapeJson = shape(b)
        val committed = repo.commit(note.id, DocumentEdit.InsertElements(page, listOf(strokeJson, shapeJson)))
        assertEquals(1L, committed.revision)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, file)
        repo = RoomDocumentRepository(db)
        val elements = repo.load(note.id).pages.single().elements
        assertEquals(listOf(a, b), elements.map { it.id })
        assertEquals(listOf("stroke", "shape"), elements.map { it.kind })
        assertEquals(strokeJson, elements[0].envelopeJson)
        assertEquals(shapeJson, elements[1].envelopeJson)
        assertEquals(listOf(0L, 1L), elements.map { it.orderIndex })
        assertEquals(1L, repo.load(note.id).revision)
    }

    @Test fun transferBetweenPagesAndMoveNoteKeepEditableContent() = runBlocking {
        val note = fixture()
        val first = repo.load(note.id).pages.single().id
        val second = id()
        repo.commit(note.id, DocumentEdit.InsertPage(second, InitialPageSpec(612.0, 792.0, BLANK)))
        val a = id(); val b = id(); val c = id()
        repo.commit(note.id, DocumentEdit.InsertElements(first, listOf(text(a, "A"), text(b, "B"))))
        repo.commit(note.id, DocumentEdit.InsertElements(second, listOf(text(c, "C"))))
        val rev = repo.commit(note.id, DocumentEdit.TransferElements(first, second, listOf(a, b), at = 0)).revision
        assertEquals(4L, rev)
        assertElements(first, emptyList())
        assertElements(second, listOf(a, b, c))
        assertEquals(listOf(0L, 1L, 2L), repo.load(note.id).pages.last().elements.map { it.orderIndex })
        // LIB-03 note move edits only library metadata and must not affect page/element IDs.
        val otherOwner = id()
        db.notebookDao().insert(NotebookEntity(otherOwner, null, "Other", sortOrder = 1,
            createdAtMillis = 100, updatedAtMillis = 100))
        val original = db.noteDao().findById(note.id)!!
        db.noteDao().update(original.copy(notebookId = otherOwner))
        assertEquals(rev, repo.load(note.id).revision)
        assertElements(second, listOf(a, b, c))
        assertEquals(otherOwner, db.noteDao().findById(note.id)!!.notebookId)
    }

    @Test fun invalidWritesAndMidTransactionConstraintFailureRollback() = runBlocking {
        val note = fixture()
        val first = repo.load(note.id).pages.single().id
        val second = id()
        repo.commit(note.id, DocumentEdit.InsertPage(second, InitialPageSpec(612.0, 792.0, BLANK)))
        val exists = id()
        repo.commit(note.id, DocumentEdit.InsertElements(second, listOf(text(exists, "existing"))))
        val original = repo.load(note.id)
        val valid = id()
        // First insert succeeds, second hits global PK collision. The entire edit must roll back.
        expectAnyFailure { repo.commit(note.id,
            DocumentEdit.InsertElements(first, listOf(text(valid, "new"), text(exists, "collision")))) }
        assertEquals(original, repo.load(note.id))
        assertNull(db.elementDao().findById(valid))
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(note.id, DocumentEdit.InsertElements(first, listOf(text(id(), "bad").replace("\"transform\":[1,0,0,1,0,0]", "\"transform\":[1,0,0,0,0,0]"))))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(note.id, DocumentEdit.InsertElements(first, listOf(text(id(), "bad").replace("\"fontSize\":12", "\"fontSize\":-1"))))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(note.id, DocumentEdit.InsertElements(first, listOf(text(id(), "bad").replace("\"type\":\"text\"", "\"type\":\"alien\""))))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(note.id, DocumentEdit.ChangeBackground(first, """{"type":"grid","color":"#FFFFFF","patternColor":"#ABCDEF","spacing":0}"""))
        }
        expectError(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repo.commit(note.id, DocumentEdit.ChangeBackground(first, """{"type":"pdfPage","assetId":"${id()}","pageIndex":0,"displayBox":"crop"}"""))
        }
        expectError(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repo.commit(note.id, DocumentEdit.InsertElements(first, listOf(image(id()))))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(note.id, DocumentEdit.ReorderPages(listOf(first, first)))
        }
        assertEquals(original, repo.load(note.id))
        db.close()
        db = AppDatabaseProvider.openIsolated(context, file)
        repo = RoomDocumentRepository(db)
        assertEquals(original, repo.load(note.id))
    }

    @Test fun invalidOwnershipLockedContentAndTrashRejectWithoutRevision() = runBlocking {
        val note = fixture()
        val page = repo.load(note.id).pages.single().id
        val lockedId = id()
        val locked = text(lockedId, "Fixed").replace("\"locked\":false", "\"locked\":true")
        repo.commit(note.id, DocumentEdit.InsertElements(page, listOf(locked)))
        val original = repo.load(note.id)
        expectError(LibraryErrorCode.PROTECTED_ENTITY) {
            repo.commit(note.id, DocumentEdit.RemoveElements(page, listOf(lockedId)))
        }
        expectError(LibraryErrorCode.NOT_FOUND) {
            repo.commit(note.id, DocumentEdit.InsertElements(id(), listOf(text(id(), "No parent"))))
        }
        assertEquals(original, repo.load(note.id))
        val row = db.noteDao().findById(note.id)!!
        db.noteDao().update(row.copy(deletedAtMillis = 3_000L))
        expectError(LibraryErrorCode.INACTIVE_OWNER) {
            repo.commit(note.id, DocumentEdit.ChangeBackground(page, GRID))
        }
        // Snapshot still available for eventual Trash restore; it does not edit content.
        assertEquals(original, repo.load(note.id))
    }

    private suspend fun fixture(): NoteEntity {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(notebookId, null, "Notebook", sortOrder = 0,
            createdAtMillis = 100, updatedAtMillis = 100))
        val note = NoteEntity(id(), notebookId, "Note", createdAtMillis = 100, updatedAtMillis = 100)
        db.noteDao().insert(note)
        db.pageDao().insert(PageEntity(id(), note.id, 0, 595.276, 841.890, BLANK, revision = 0))
        return note
    }

    private suspend fun assertPageIds(noteId: String, expected: List<String>) {
        assertEquals(expected, repo.load(noteId).pages.map { it.id })
    }

    private suspend fun assertElements(pageId: String, expected: List<String>) {
        assertEquals(expected, db.elementDao().listByPage(pageId).map { it.id })
    }

    private suspend fun expectError(code: LibraryErrorCode, block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (e: LibraryOperationException) {
            assertEquals(code, e.code)
        }
    }

    private suspend fun expectAnyFailure(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (e: Exception) { failed = true }
        assertTrue("Expected transaction failure", failed)
    }

    private fun id(): String = UUID.randomUUID().toString()

    private fun text(elementId: String, value: String) =
        """{"id":"$elementId","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"$value","frame":{"x":0,"y":0,"width":100,"height":30},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    private fun stroke(elementId: String) =
        """{"id":"$elementId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1},"points":[{"x":80,"y":110,"tMs":0,"pressure":0.45},{"x":99,"y":100,"tMs":18,"pressure":0.65}]}}"""

    private fun shape(elementId: String) =
        """{"id":"$elementId","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"rectangle","geometry":{"x":10,"y":10,"width":50,"height":20},"stroke":{"color":"#008F70","width":3,"opacity":1},"fill":null}}"""

    private fun image(elementId: String) =
        """{"id":"$elementId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${id()}","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}"""

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
        private const val GRID = """{"type":"grid","color":"#FFFFFF","patternColor":"#D8E7F7","spacing":24}"""
    }
}
