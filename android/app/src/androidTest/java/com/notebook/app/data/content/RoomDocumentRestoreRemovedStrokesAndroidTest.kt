package com.notebook.app.data.content

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
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

/** EDT-16 1C-2B: atomic stroke restoration against physical SQLite/Room. */
@RunWith(AndroidJUnit4::class)
class RoomDocumentRestoreRemovedStrokesAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-restore-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 3_000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun nonadjacentWholeStrokeUndoRestoresExactBytesAroundLockedNeighborsAndReopens() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val locked = id(); val c = id(); val textId = id(); val e = id()
        val originals = listOf(
            stroke(a, brushWidth = "2.50"),
            stroke(locked, locked = true),
            stroke(c, brushWidth = "4.125"),
            text(textId),
            stroke(e, brushWidth = "1.75"),
        )
        assertEquals(1L, repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals)).revision)
        val before = repo.load(noteId).pages.single().elements
        val removedIds = listOf(a, c, e)
        val removedEnvelopes = before.filter { it.id in removedIds }.map { it.envelopeJson }
        val originalOrder = before.map { it.id }
        val removed = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, removedIds))
        assertEquals(2L, removed.revision)
        assertEquals(listOf(locked, textId), repo.load(noteId).pages.single().elements.map { it.id })

        val restored = repo.commitIfRevision(noteId, removed.revision,
            DocumentEdit.RestoreRemovedStrokes(pageId, removedEnvelopes, originalOrder))
        assertEquals(3L, restored.revision)
        val after = repo.load(noteId).pages.single().elements
        assertEquals(originalOrder, after.map { it.id })
        assertEquals(originals, after.map { it.envelopeJson })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), after.map { it.orderIndex })
        assertEquals(before.first { it.id == locked }.envelopeJson,
            after.first { it.id == locked }.envelopeJson)
        assertEquals(before.first { it.id == textId }.envelopeJson,
            after.first { it.id == textId }.envelopeJson)

        reopen()
        val persisted = repo.load(noteId)
        assertEquals(3L, persisted.revision)
        assertEquals(after, persisted.pages.single().elements)
    }

    @Test fun restoringAnEntirePageOfStrokesRecreatesTheOriginalOrder() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        val originals = listOf(stroke(a), stroke(b))
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val revision = repo.load(noteId).revision
        repo.commitIfRevision(noteId, revision, DocumentEdit.RemoveElements(pageId, listOf(a, b)))
        assertTrue(repo.load(noteId).pages.single().elements.isEmpty())
        val committed = repo.commitIfRevision(noteId, revision + 1L,
            DocumentEdit.RestoreRemovedStrokes(pageId, originals, listOf(a, b)))
        assertEquals(revision + 2L, committed.revision)
        assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
    }

    @Test fun changedNoteRevisionRejectsUndoWithoutRestoringAnything() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val originalOrder = listOf(a, b)
        val deleted = repo.commitIfRevision(noteId, 1L, DocumentEdit.RemoveElements(pageId, listOf(a)))
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(c))))
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, deleted.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId, listOf(stroke(a)), originalOrder))
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun incorrectSurvivorOrderingIsRejectedEvenAtCurrentRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id(); val d = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId,
            listOf(stroke(a), stroke(b), stroke(c), stroke(d))))
        val deleted = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, listOf(b)))
        val unchanged = repo.load(noteId)
        // Would change the order of untouched elements a/c/d.
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, deleted.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId, listOf(stroke(b)), listOf(c, b, a, d)))
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun foreignPageIdCollisionRejectsEntireRestore() = runBlocking {
        val (noteId, pageId) = fixture()
        val otherPage = id()
        db.pageDao().insert(PageEntity(otherPage, noteId, 1L, 612.0, 792.0, BLANK, 0L))
        val a = id(); val b = id(); val occupied = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val deleted = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, listOf(a)))
        repo.commit(noteId, DocumentEdit.InsertElements(otherPage, listOf(stroke(occupied))))
        val unchanged = repo.load(noteId)
        // The other page already owns the ID; a restore cannot steal it.
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, unchanged.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId, listOf(stroke(occupied)),
                    listOf(occupied, b)))
        }
        assertEquals(unchanged, repo.load(noteId))
        assertEquals(deleted.revision + 1L, unchanged.revision)
    }

    @Test fun attemptingToRestoreOnWrongPageFailsWithoutMutation() = runBlocking {
        val (noteId, pageId) = fixture()
        val wrongPage = id()
        db.pageDao().insert(PageEntity(wrongPage, noteId, 1L, 612.0, 792.0, BLANK, 0L))
        val a = id(); val b = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val removed = repo.commitIfRevision(noteId, 1L, DocumentEdit.RemoveElements(pageId, listOf(a)))
        val before = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(wrongPage, listOf(stroke(a)), listOf(a, b)))
        }
        assertEquals(before, repo.load(noteId))
    }

    @Test fun lockedOrNonStrokeRestorationIsRejected() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val removed = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, listOf(a)))
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.PROTECTED_ENTITY) {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId,
                    listOf(stroke(a, locked = true)), listOf(a, b)))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId,
                    listOf(text(a)), listOf(a, b)))
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun duplicateOriginalIdAndDuplicateRequestedPositionsFailClosed() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val removed = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, listOf(a)))
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId,
                    listOf(stroke(a), stroke(a)), listOf(a, a, b)))
        }
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId,
                    listOf(stroke(a)), listOf(a, a)))
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun doubleRestoreIsRejectedWithoutAdvancingRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val removed = repo.commitIfRevision(noteId, 1L, DocumentEdit.RemoveElements(pageId, listOf(a)))
        val undo = DocumentEdit.RestoreRemovedStrokes(pageId, listOf(stroke(a)), listOf(a, b))
        val first = repo.commitIfRevision(noteId, removed.revision, undo)
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, first.revision, undo)
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun sqlFailureDuringSecondRestoreInsertRollsBackFirstInsertAndRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id()
        val originals = listOf(stroke(a), stroke(b), stroke(c))
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val removed = repo.commitIfRevision(noteId, 1L,
            DocumentEdit.RemoveElements(pageId, listOf(a, b)))
        val unchanged = repo.load(noteId)
        // Inject a real SQLite failure AFTER restoring 'a' but BEFORE 'b'.
        db.openHelper.writableDatabase.execSQL(
            """CREATE TRIGGER reject_second_eraser_restore BEFORE INSERT ON elements
               WHEN NEW.id = '$b' BEGIN SELECT RAISE(ABORT, 'synthetic restore failure'); END"""
        )
        val outcome = runCatching {
            repo.commitIfRevision(noteId, removed.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId,
                    originals.take(2), listOf(a, b, c)))
        }
        assertTrue("Expected injected SQL failure", outcome.isFailure)
        assertEquals(unchanged, repo.load(noteId))
        assertEquals(null, db.elementDao().findById(a))
        assertEquals(null, db.elementDao().findById(b))
    }

    @Test fun undoThenRedoAreEachOneCommitAndRestoreOriginalStrokes() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id()
        val originals = listOf(stroke(a), stroke(b), stroke(c))
        val inserted = repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val forward = DocumentEdit.RemoveElements(pageId, listOf(a, c))
        val removed = repo.commitIfRevision(noteId, inserted.revision, forward)
        val afterRemoval = repo.load(noteId).pages.single().elements
        val restored = repo.commitIfRevision(noteId, removed.revision,
            DocumentEdit.RestoreRemovedStrokes(pageId, listOf(stroke(a), stroke(c)),
                listOf(a, b, c)))
        assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        val redone = repo.commitIfRevision(noteId, restored.revision, forward)
        assertEquals(restored.revision + 1L, redone.revision)
        val afterRedo = repo.load(noteId)
        assertEquals(listOf(b), afterRedo.pages.single().elements.map { it.id })
        assertEquals(afterRemoval.map { it.envelopeJson },
            afterRedo.pages.single().elements.map { it.envelopeJson })
    }

    @Test fun unguardedRestorationIsRejectedEvenIfOrderingMatches() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        repo.commitIfRevision(noteId, 1L, DocumentEdit.RemoveElements(pageId, listOf(a)))
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commit(noteId, DocumentEdit.RestoreRemovedStrokes(pageId,
                listOf(stroke(a)), listOf(a, b)))
        }
        assertEquals(unchanged, repo.load(noteId))
    }

    @Test fun emptyRestorationIsRejectedEvenWithCurrentRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val unchanged = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, unchanged.revision,
                DocumentEdit.RestoreRemovedStrokes(pageId, emptyList(), emptyList()))
        }
        assertEquals(unchanged, repo.load(noteId))
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
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        repo = RoomDocumentRepository(db)
    }

    private suspend fun expectError(
        code: LibraryErrorCode,
        action: suspend () -> Unit,
    ) {
        try {
            action()
            throw AssertionError("Expected $code")
        } catch (e: LibraryOperationException) {
            assertEquals(code, e.code)
        }
    }

    private fun id(): String = UUID.randomUUID().toString()

    private fun stroke(elementId: String, locked: Boolean = false, brushWidth: String = "2.5"): String =
        """ {"id":"$elementId", "type":"stroke", "transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":$brushWidth,"color":"#2255AA","opacity":1},"points":[{"x":80,"y":110,"tMs":0,"pressure":0.45},{"x":99,"y":100,"tMs":18,"pressure":0.65}]}} """

    private fun text(elementId: String): String =
        """{"id":"$elementId","type":"text","transform":[1,0,0,1,0,0],"locked":false,"payload":{"text":"untouched","frame":{"x":10,"y":20,"width":100,"height":40},"style":{"fontFamily":"sans","fontSize":20,"color":"#202020","bold":false,"italic":false,"underline":false,"alignment":"left"}}}"""

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
