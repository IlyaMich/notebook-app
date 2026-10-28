package com.notebook.app.data.content

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomNoteContentTransactionAdapter
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteLifecycleRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.ElementEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** STO-02 3B: actual LIB-03 coordinator + real Room page/element adapter. */
@RunWith(AndroidJUnit4::class)
class RoomNoteContentLifecycleIntegrationTest {
    private lateinit var context: Context
    private lateinit var file: String
    private lateinit var db: AppDatabase
    private lateinit var repos: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        file = "sto02-lifecycle-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, file)
        db.openHelper.writableDatabase
        repos = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(file)
    }

    @Test fun productionCreationAddsFirstPageInQuickNotesAndSurvivesReopen() = runBlocking {
        val created = repos.noteLifecycle().createNote(title = "Untitled work")
        val quick = db.notebookDao().findQuickNotes()!!
        assertEquals(quick.id, created.notebookId)
        assertEquals(0L, created.revision)
        assertEquals(1, db.noteDao().countAll())
        val content = repos.documents.load(created.id)
        assertEquals(0L, content.revision)
        assertEquals(1, content.pages.size)
        assertEquals(0L, content.pages.single().orderIndex)
        assertEquals(595.276, content.pages.single().width, 0.0)
        assertEquals(841.890, content.pages.single().height, 0.0)
        assertEquals(BLANK, content.pages.single().backgroundJson)
        assertEquals(emptyList<String>(), content.pages.single().elements.map { it.id })
        assertEquals(0L, content.pages.single().revision)
        UUID.fromString(content.pages.single().id)

        reopen()
        assertEquals(created, repos.notes.findById(created.id))
        assertEquals(content, repos.documents.load(created.id))
        assertEquals(1, db.noteDao().countAll())
    }

    @Test fun creationSupportsExplicitNotebookAndCustomPaperAndRejectsBadBackground() = runBlocking {
        val notebook = repos.notebooks.create("Mathematics")
        val spec = InitialPageSpec(612.0, 792.0, RULED)
        val note = repos.noteLifecycle().createNote(notebook.id, "Exercise", spec)
        val page = repos.documents.load(note.id).pages.single()
        assertEquals(notebook.id, note.notebookId)
        assertEquals(612.0, page.width, 0.0)
        assertEquals(792.0, page.height, 0.0)
        assertEquals(RULED, page.backgroundJson)
        assertEquals(0L, note.revision)

        expectCode(LibraryErrorCode.INVALID_INPUT) {
            repos.noteLifecycle().createNote(notebook.id, "No page", InitialPageSpec(612.0, 792.0, "{}"))
        }
        expectCode(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repos.noteLifecycle().createNote(notebook.id, "PDF", InitialPageSpec(
                612.0, 792.0,
                """{"type":"pdfPage","assetId":"${id()}","pageIndex":0,"displayBox":"crop"}""",
            ))
        }
        assertEquals(1, db.noteDao().countAll())
        assertEquals(1, db.pageDao().countByNote(note.id))
        assertEquals(1, countRows("pages"))
    }

    @Test fun invalidQuickNotesCreationRollsBackProvisioningAndMetadata() = runBlocking {
        assertNull(db.notebookDao().findQuickNotes())
        expectCode(LibraryErrorCode.INVALID_INPUT) {
            repos.noteLifecycle().createNote(firstPage = InitialPageSpec(12.0, 14.0, "{}"))
        }
        assertNull(db.notebookDao().findQuickNotes())
        assertEquals(0, db.noteDao().countAll())
        assertEquals(0, countRows("pages"))
    }

    @Test fun duplicatePreservesAllEditableContentAndRemapsIdentityAndOrder() = runBlocking {
        val sourceNotebook = repos.notebooks.create("Source")
        val destNotebook = repos.notebooks.create("Target")
        val source = repos.noteLifecycle().createNote(sourceNotebook.id, "Lecture")
        val first = repos.documents.load(source.id).pages.single().id
        val second = id()
        val third = id()
        repos.documents.commit(source.id, DocumentEdit.InsertPage(second, InitialPageSpec(612.0, 792.0, RULED)))
        repos.documents.commit(source.id, DocumentEdit.InsertPage(third, InitialPageSpec(841.89, 595.276, GRID), at = 1))
        val strokeId = id(); val textId = id(); val shapeId = id()
        repos.documents.commit(source.id, DocumentEdit.InsertElements(first, listOf(
            stroke(strokeId), text(textId, "Equation"),
        )))
        repos.documents.commit(source.id, DocumentEdit.ReorderElements(first, listOf(textId, strokeId)))
        repos.documents.commit(source.id, DocumentEdit.InsertElements(third, listOf(shape(shapeId))))
        val before = repos.documents.load(source.id)
        assertTrue(before.revision > 0L)

        val duplicate = repos.noteLifecycle().duplicateNote(source.id, destNotebook.id)
        val after = repos.documents.load(duplicate.id)
        assertNotEquals(source.id, duplicate.id)
        assertEquals(destNotebook.id, duplicate.notebookId)
        assertEquals(source.title, duplicate.title)
        assertEquals(0L, duplicate.revision)
        assertEquals(0L, after.revision)
        assertEquals(before.pages.size, after.pages.size)
        assertEquals(before.pages.map { it.orderIndex }, after.pages.map { it.orderIndex })
        assertEquals(before.pages.map { it.width }, after.pages.map { it.width })
        assertEquals(before.pages.map { it.height }, after.pages.map { it.height })
        assertEquals(before.pages.map { it.backgroundJson }, after.pages.map { it.backgroundJson })
        assertTrue(before.pages.map { it.id }.toSet().intersect(after.pages.map { it.id }.toSet()).isEmpty())
        before.pages.zip(after.pages).forEach { (oldPage, newPage) ->
            assertEquals(0L, newPage.revision)
            assertEquals(oldPage.elements.size, newPage.elements.size)
            assertEquals(oldPage.elements.map { it.orderIndex }, newPage.elements.map { it.orderIndex })
            oldPage.elements.zip(newPage.elements).forEach { (oldElement, newElement) ->
                assertNotEquals(oldElement.id, newElement.id)
                assertEquals(oldElement.kind, newElement.kind)
                assertEquals(0L, newElement.revision)
                val oldEnvelope = JSONObject(oldElement.envelopeJson)
                val newEnvelope = JSONObject(newElement.envelopeJson)
                assertEquals(newElement.id, newEnvelope.getString("id"))
                oldEnvelope.remove("id"); newEnvelope.remove("id")
                assertEquals(oldEnvelope.toString(), newEnvelope.toString())
            }
        }
        assertEquals(before, repos.documents.load(source.id)) // source untouched
        assertEquals(2, db.noteDao().countAll())

        reopen()
        assertEquals(before, repos.documents.load(source.id))
        assertEquals(after, repos.documents.load(duplicate.id))
        assertEquals(duplicate, repos.notes.findById(duplicate.id))
        // The default destination remains the source notebook.
        val another = repos.noteLifecycle().duplicateNote(source.id)
        assertEquals(sourceNotebook.id, another.notebookId)
        assertEquals(3, db.noteDao().countAll())
    }

    @Test fun forcedFailureAfterRealFirstPageWriteRollsBackQuickNotesAndPage() = runBlocking {
        val delegate = RoomNoteContentTransactionAdapter(db)
        val fault = FailAfterContentPort(delegate, failOnInitial = true)
        expectCode(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repos.noteLifecycle(fault).createNote(title = "Must disappear")
        }
        assertTrue(fault.initialWritten)
        assertNull(db.notebookDao().findQuickNotes())
        assertEquals(0, db.noteDao().countAll())
        assertEquals(0, countRows("pages"))
        assertEquals(0, countRows("elements"))
        reopen()
        assertEquals(0, db.noteDao().countAll())
        assertEquals(0, countRows("pages"))
    }

    @Test fun forcedFailureAfterRealMultiPageCloneRollsBackAllNewRows() = runBlocking {
        val source = repos.noteLifecycle().createNote(title = "Source")
        val first = repos.documents.load(source.id).pages.single().id
        repos.documents.commit(source.id, DocumentEdit.InsertElements(first, listOf(text(id(), "Data"))))
        repos.documents.commit(source.id, DocumentEdit.InsertPage(id(), InitialPageSpec(612.0, 792.0, RULED)))
        val before = repos.documents.load(source.id)
        val pagesBefore = countRows("pages")
        val elementsBefore = countRows("elements")
        val fault = FailAfterContentPort(RoomNoteContentTransactionAdapter(db), failOnClone = true)
        expectCode(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repos.noteLifecycle(fault).duplicateNote(source.id)
        }
        assertTrue(fault.cloneWritten)
        assertEquals(1, db.noteDao().countAll())
        assertEquals(pagesBefore, countRows("pages"))
        assertEquals(elementsBefore, countRows("elements"))
        assertEquals(before, repos.documents.load(source.id))
        reopen()
        assertEquals(1, db.noteDao().countAll())
        assertEquals(before, repos.documents.load(source.id))
    }

    @Test fun assetBackedAndCorruptSourcesCannotBePartiallyDuplicated() = runBlocking {
        val source = repos.noteLifecycle().createNote(title = "Corrupt fixture")
        val pageId = repos.documents.load(source.id).pages.single().id
        // Raw DAO is test-only: real DocumentRepository rejects image content.
        val imageId = id()
        db.elementDao().insert(ElementEntity(
            id = imageId, pageId = pageId, orderIndex = 0L, kind = "image",
            elementJson = """{"id":"$imageId","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${id()}","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}""",
            revision = 0L,
        ))
        val before = repos.documents.load(source.id)
        expectCode(LibraryErrorCode.UNSUPPORTED_CONTENT) {
            repos.noteLifecycle().duplicateNote(source.id)
        }
        assertEquals(1, db.noteDao().countAll())
        assertEquals(1, countRows("pages"))
        assertEquals(1, countRows("elements"))
        assertEquals(before, repos.documents.load(source.id))
        db.elementDao().update(db.elementDao().findById(imageId)!!.copy(
            kind = "text", elementJson = text(imageId, "Valid envelope"),
        ))
        // Indexed kind must match envelope, even when source data was modified by raw DAO.
        db.elementDao().update(db.elementDao().findById(imageId)!!.copy(kind = "shape"))
        expectCode(LibraryErrorCode.INVALID_INPUT) {
            repos.noteLifecycle().duplicateNote(source.id)
        }
        assertEquals(1, db.noteDao().countAll())
    }

    private suspend fun reopen() {
        db.close()
        db = AppDatabaseProvider.openIsolated(context, file)
        repos = LibraryRepositoryFactory(db)
    }

    private fun countRows(table: String): Int {
        check(table == "pages" || table == "elements")
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private suspend fun expectCode(code: LibraryErrorCode, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: LibraryOperationException) {
            assertEquals(code, error.code)
            return
        }
        throw AssertionError("Expected LibraryOperationException($code)")
    }

    private class FailAfterContentPort(
        private val delegate: NoteContentTransactionPort,
        private val failOnInitial: Boolean = false,
        private val failOnClone: Boolean = false,
    ) : NoteContentTransactionPort {
        var initialWritten = false
            private set
        var cloneWritten = false
            private set

        override suspend fun insertInitialPage(noteId: String, spec: InitialPageSpec) {
            delegate.insertInitialPage(noteId, spec)
            initialWritten = true
            if (failOnInitial) fail()
        }

        override suspend fun cloneContent(sourceNoteId: String, targetNoteId: String) {
            delegate.cloneContent(sourceNoteId, targetNoteId)
            cloneWritten = true
            if (failOnClone) fail()
        }

        private fun fail(): Nothing = throw LibraryOperationException(
            LibraryErrorCode.UNSUPPORTED_CONTENT, "Injected failure after real Room content writes",
        )
    }

    private fun id() = UUID.randomUUID().toString()

    private fun stroke(id: String) = """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1},"points":[{"x":80,"y":110,"tMs":0,"pressure":0.45},{"x":99,"y":100,"tMs":18,"pressure":0.65}]}}"""
    private fun shape(id: String) = """{"id":"$id","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"rectangle","geometry":{"x":10,"y":12,"width":40,"height":30},"stroke":{"color":"#008F70","width":3,"opacity":1},"fill":null}}"""
    private fun text(id: String, content: String) = """{"id":"$id","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"$content","frame":{"x":1,"y":2,"width":80,"height":30},"style":{"fontFamily":"sans","fontSize":12,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    private companion object {
        const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
        const val RULED = """{"type":"ruled","color":"#FFFFFF","patternColor":"#D8E7F7","spacing":24}"""
        const val GRID = """{"type":"grid","color":"#FFFFFF","patternColor":"#D8E7F7","spacing":20}"""
    }
}
