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

/** EDT-16 1C-2A: physical SQLite transaction and revision-gate regressions. */
@RunWith(AndroidJUnit4::class)
class RoomDocumentRevisionGuardAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-revision-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 2_000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun guardedRemovalCommitsOnceAndPreservesOtherContentAfterReopen() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id(); val d = id()
        val originalA = stroke(a)
        val originalB = stroke(b)
        val originalC = stroke(c)
        val originalD = stroke(d)
        repo.commit(noteId, DocumentEdit.InsertElements(pageId,
            listOf(originalA, originalB, originalC, originalD)))
        val before = repo.load(noteId)
        assertEquals(1L, before.revision)

        val result = repo.commitIfRevision(noteId, before.revision,
            DocumentEdit.RemoveElements(pageId, listOf(b, d)))
        assertEquals(2L, result.revision)
        val after = repo.load(noteId)
        assertEquals(listOf(a, c), after.pages.single().elements.map { it.id })
        assertEquals(listOf(0L, 1L), after.pages.single().elements.map { it.orderIndex })
        assertEquals(listOf(originalA, originalC),
            after.pages.single().elements.map { it.envelopeJson })

        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        repo = RoomDocumentRepository(db)
        assertEquals(after, repo.load(noteId))
    }

    @Test fun staleRevisionRejectsRemovalWithoutTouchingRowsOrRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val b = id(); val c = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a), stroke(b))))
        val scannedRevision = repo.load(noteId).revision
        // Another edit commits after the hit-test snapshot.
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(c))))
        val actual = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, scannedRevision,
                DocumentEdit.RemoveElements(pageId, listOf(a, b)))
        }
        assertEquals(actual, repo.load(noteId))
        assertEquals(listOf(a, b, c), repo.load(noteId).pages.single().elements.map { it.id })
    }

    @Test fun lockedHitRejectsEntireGuardedBatchWithoutRevisionAdvance() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id(); val lockedId = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(
            stroke(a), stroke(lockedId).replace("\"locked\":false", "\"locked\":true")
        )))
        val before = repo.load(noteId)
        expectError(LibraryErrorCode.PROTECTED_ENTITY) {
            repo.commitIfRevision(noteId, before.revision,
                DocumentEdit.RemoveElements(pageId, listOf(a, lockedId)))
        }
        assertEquals(before, repo.load(noteId))
    }

    @Test fun missingElementRejectsEntireGuardedBatch() = runBlocking {
        val (noteId, pageId) = fixture()
        val a = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(a))))
        val before = repo.load(noteId)
        expectError(LibraryErrorCode.NOT_FOUND) {
            repo.commitIfRevision(noteId, before.revision,
                DocumentEdit.RemoveElements(pageId, listOf(a, id())))
        }
        assertEquals(before, repo.load(noteId))
    }

    @Test fun guardedNoOpKeepsRevisionButStillRejectsStaleSnapshot() = runBlocking {
        val (noteId, pageId) = fixture()
        assertEquals(0L, repo.commitIfRevision(noteId, 0L,
            DocumentEdit.RemoveElements(pageId, emptyList())).revision)
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        val before = repo.load(noteId)
        expectError(LibraryErrorCode.INVALID_INPUT) {
            repo.commitIfRevision(noteId, 0L,
                DocumentEdit.RemoveElements(pageId, emptyList()))
        }
        assertEquals(before, repo.load(noteId))
    }

    @Test fun negativeExpectedRevisionIsRejectedBeforeMutation() = runBlocking {
        val (noteId, pageId) = fixture()
        val before = repo.load(noteId)
        var rejected = false
        try {
            repo.commitIfRevision(noteId, -1L,
                DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
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
        db.pageDao().insert(PageEntity(pageId, noteId, 0L, 595.276, 841.890,
            """{"type":"blank","color":"#FFFFFF"}""", revision = 0L))
        return noteId to pageId
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

    private fun stroke(elementId: String): String =
        """{"id":"$elementId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1},"points":[{"x":80,"y":110,"tMs":0,"pressure":0.45},{"x":99,"y":100,"tMs":18,"pressure":0.65}]}}"""
}
