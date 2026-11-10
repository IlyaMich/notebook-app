package com.notebook.app.data.content

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserPartialStrokeReplacementPlanner
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** EDT-16 1E-4: real SQLite atomic partial-erase replacement, inverse and redo. */
@RunWith(AndroidJUnit4::class)
class RoomDocumentPartialStrokeReplacementAndroidTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "edt16-partial-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun actualPlannerFragmentsPersistUndoAndReopenWithCanonicalMetadata() = runBlocking {
        val (noteId, pageId) = fixture()
        val sourceId = id()
        val untouchedId = id()
        val originals = listOf(stroke(sourceId), stroke(untouchedId, locked = true))
        val inserted = repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserPartialStrokeReplacementPlanner.plan(
            content = snapshot,
            pageId = pageId,
            sweepPointsPageDu = listOf(Point(50.0, 100.0)),
            sweepRadiusPageDu = 4.0,
            scanNoteRevision = inserted.revision,
            scanPageRevision = snapshot.pages.single().revision,
        ))
        assertEquals(2, plan.replacements.single().fragmentIds.size)
        val committed = repo.commitIfRevision(noteId, inserted.revision, plan.forwardEdit)
        val current = repo.load(noteId)
        assertEquals(committed.revision, current.revision)
        assertEquals(plan.resultingElementIdsInOrder,
            current.pages.single().elements.map { it.id })
        assertEquals(originals[1], current.pages.single().elements.last().envelopeJson)
        val undone = repo.commitIfRevision(noteId, committed.revision, plan.inverseEdit)
        assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        reopen()
        assertEquals(undone.revision, repo.load(noteId).revision)
        assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
    }

    @Test fun forwardUndoRedoPreservesExactBytesAndLockedNeighborsAfterReopen() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val locked = id(); val b = id(); val textId = id()
        val f1 = id(); val f2 = id(); val f3 = id()
        val initial = listOf(stroke(a), stroke(locked, locked = true), stroke(b), text(textId))
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, initial))
        val plan = commands(pageId, listOf(a, locked, b, textId),
            listOf(f1, f2, locked, f3, textId),
            listOf(
                DocumentEdit.PartialStrokeReplacement(initial[0], listOf(stroke(f1, end = 45), stroke(f2, start = 55))),
                DocumentEdit.PartialStrokeReplacement(initial[2], listOf(stroke(f3, start = 55))),
            ))
        val forward = repo.commitIfRevision(noteId, first.revision, plan.first)
        assertEquals(first.revision + 1, forward.revision)
        val after = repo.load(noteId).pages.single().elements
        assertEquals(listOf(f1, f2, locked, f3, textId), after.map { it.id })
        assertEquals(initial[1], after[2].envelopeJson)
        assertEquals(initial[3], after[4].envelopeJson)
        val undo = repo.commitIfRevision(noteId, forward.revision, plan.second)
        assertEquals(forward.revision + 1, undo.revision)
        assertEquals(initial, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        val redo = repo.commitIfRevision(noteId, undo.revision, plan.first)
        assertEquals(undo.revision + 1, redo.revision)
        assertEquals(after.map { it.envelopeJson }, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        reopen()
        assertEquals(redo.revision, repo.load(noteId).revision)
        assertEquals(after.map { it.envelopeJson }, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
    }

    @Test fun fullStrokeRemovalAsZeroFragmentsIsReversible() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        val originals = listOf(stroke(a), stroke(b))
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val (forward, inverse) = commands(pageId, listOf(a, b), listOf(b),
            listOf(DocumentEdit.PartialStrokeReplacement(originals[0], emptyList())))
        val removed = repo.commitIfRevision(noteId, first.revision, forward)
        assertEquals(listOf(b), repo.load(noteId).pages.single().elements.map { it.id })
        repo.commitIfRevision(noteId, removed.revision, inverse)
        assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
    }

    @Test fun staleRevisionAndUnguardedCommitsNeverMutate() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val f = id()
        val original = stroke(a)
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(original)))
        val (forward, undo) = commands(pageId, listOf(a), listOf(f),
            listOf(DocumentEdit.PartialStrokeReplacement(original, listOf(stroke(f)))))
        val before = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commit(noteId, forward) }
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commit(noteId, undo) }
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, first.revision - 1, forward) }
        assertEquals(before, repo.load(noteId))
        val applied = repo.commitIfRevision(noteId, first.revision, forward)
        val now = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, first.revision, undo) }
        assertEquals(applied.revision, now.revision)
        assertEquals(now, repo.load(noteId))
    }

    @Test fun incorrectOrderingOrBytesFailWithoutMutation() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val f = id()
        val original = stroke(a); val second = stroke(b)
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(original, second)))
        val before = repo.load(noteId)
        val malformed = DocumentEdit.ApplyPartialStrokeReplacements(pageId,
            listOf(b, a), listOf(f, b),
            listOf(DocumentEdit.PartialStrokeReplacement(original, listOf(stroke(f)))))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, first.revision, malformed) }
        val altered = DocumentEdit.ApplyPartialStrokeReplacements(pageId,
            listOf(a, b), listOf(f, b),
            listOf(DocumentEdit.PartialStrokeReplacement(stroke(a, end = 60), listOf(stroke(f)))))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, first.revision, altered) }
        assertEquals(before, repo.load(noteId))
    }

    @Test fun lockedSourceAndForgedBrushOrTransformFailClosed() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val f = id()
        val locked = stroke(a, locked = true)
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(locked)))
        val before = repo.load(noteId)
        val (lockedEdit, _) = commands(pageId, listOf(a), listOf(f),
            listOf(DocumentEdit.PartialStrokeReplacement(locked, listOf(stroke(f)))))
        expectError(LibraryErrorCode.PROTECTED_ENTITY) { repo.commitIfRevision(noteId, first.revision, lockedEdit) }
        assertEquals(before, repo.load(noteId))
        // Build another valid fixture in another note for the forged-fragment check.
        val (note2, page2) = fixture()
        val c = id()
        val source = stroke(c)
        val second = repo.commit(note2, DocumentEdit.InsertElements(page2, listOf(source)))
        val malicious = DocumentEdit.ApplyPartialStrokeReplacements(page2,
            listOf(c), listOf(f),
            listOf(DocumentEdit.PartialStrokeReplacement(source, listOf(
                stroke(f).replace("\"width\":2.5", "\"width\":13.0")
            ))))
        val untouched = repo.load(note2)
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(note2, second.revision, malicious) }
        assertEquals(untouched, repo.load(note2))
    }

    @Test fun foreignPageCollisionAndWrongPageRejected() = runBlocking {
        val (noteId, pageId) = fixture()
        val otherPage = id()
        db.pageDao().insert(PageEntity(otherPage, noteId, 1L, 612.0, 792.0, BLANK, 0L))
        val a = id(); val occupied = id()
        val first = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a))))
        val inserted = repo.commit(noteId, DocumentEdit.InsertElements(otherPage, listOf(stroke(occupied))))
        val before = repo.load(noteId)
        val bad = DocumentEdit.ApplyPartialStrokeReplacements(pageId,
            listOf(a), listOf(occupied),
            listOf(DocumentEdit.PartialStrokeReplacement(stroke(a), listOf(stroke(occupied)))))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, inserted.revision, bad) }
        val wrong = DocumentEdit.ApplyPartialStrokeReplacements(otherPage,
            listOf(a), listOf(id()),
            listOf(DocumentEdit.PartialStrokeReplacement(stroke(a), listOf(stroke(id())))))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, inserted.revision, wrong) }
        assertEquals(first.revision + 1, inserted.revision)
        assertEquals(before, repo.load(noteId))
    }

    @Test fun sqlFailureDuringSecondInsertRollsBackDeletionAndFirstFragment() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val f1 = id(); val f2 = id()
        val source = stroke(a)
        val initial = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(source)))
        val (forward, _) = commands(pageId, listOf(a), listOf(f1, f2),
            listOf(DocumentEdit.PartialStrokeReplacement(source, listOf(stroke(f1), stroke(f2)))))
        val before = repo.load(noteId)
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER abort_second_partial BEFORE INSERT ON elements
               WHEN NEW.id = '$f2' BEGIN SELECT RAISE(ABORT, 'synthetic partial failure'); END"""
        )
        assertTrue(runCatching { repo.commitIfRevision(noteId, initial.revision, forward) }.isFailure)
        assertEquals(before, repo.load(noteId))
        assertEquals(null, db.elementDao().findById(f1))
        assertEquals(null, db.elementDao().findById(f2))
    }

    @Test fun undoRefusesTamperedFragmentEvenAtCurrentNoteRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val f = id()
        val source = stroke(a); val originalFragment = stroke(f)
        val initial = repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(source)))
        val (forward, inverse) = commands(pageId, listOf(a), listOf(f),
            listOf(DocumentEdit.PartialStrokeReplacement(source, listOf(originalFragment))))
        val applied = repo.commitIfRevision(noteId, initial.revision, forward)
        val before = repo.load(noteId)
        // Claimed inverse carries different bytes than the committed fragment.
        val badUndo = inverse.copy(replacements = listOf(
            DocumentEdit.PartialStrokeReplacement(source, listOf(stroke(f, end = 60)))))
        expectError(LibraryErrorCode.INVALID_INPUT) { repo.commitIfRevision(noteId, applied.revision, badUndo) }
        assertEquals(before, repo.load(noteId))
    }

    private suspend fun fixture(): Pair<String, String> {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(notebookId, null, "Notebook", sortOrder = 0,
            createdAtMillis = 100L, updatedAtMillis = 100L))
        val noteId = id()
        db.noteDao().insert(NoteEntity(noteId, notebookId, "Note",
            createdAtMillis = 100L, updatedAtMillis = 100L))
        val pageId = id()
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 595.276, 841.890, BLANK, 0L))
        return noteId to pageId
    }

    private fun reopen() {
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        repo = RoomDocumentRepository(db)
    }

    private suspend fun expectError(code: LibraryErrorCode, block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected $code") }
        catch (ex: LibraryOperationException) { assertEquals(code, ex.code) }
    }

    private fun commands(
        pageId: String, original: List<String>, resulting: List<String>,
        replacements: List<DocumentEdit.PartialStrokeReplacement>,
    ) = DocumentEdit.ApplyPartialStrokeReplacements(pageId, original, resulting, replacements) to
        DocumentEdit.RestorePartialStrokeReplacements(pageId, original, resulting, replacements)

    private fun id() = UUID.randomUUID().toString()

    private fun stroke(
        id: String, locked: Boolean = false, start: Int = 10, end: Int = 90,
    ): String = """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1.0},"points":[{"x":$start,"y":100,"tMs":0,"pressure":0.3},{"x":$end,"y":100,"tMs":80,"pressure":0.9}]}}"""

    private fun text(id: String): String =
        """{"id":"$id","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"untouched","frame":{"x":10,"y":20,"width":100,"height":40},"style":{"fontFamily":"sans","fontSize":20,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
