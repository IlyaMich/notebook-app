package com.notebook.app.ui.editor

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserGestureBuffer
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end completed-sweep -> canonical scan -> one guarded Room edit. No UI wiring. */
@RunWith(AndroidJUnit4::class)
class NoteEditorWholeStrokeEraserRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-sweep-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5_000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun completedSweepAtomicallyRemovesMultipleHitsAndUndoRestoresExactJson() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val locked = id(); val second = id(); val distant = id()
        val originals = listOf(
            stroke(first, y = 100),
            stroke(locked, y = 150, locked = true),
            stroke(second, y = 200),
            stroke(distant, y = 350),
        )
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val originalSnapshot = repo.load(noteId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, originalSnapshot.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val buffer = EditorEraserGestureBuffer()
            buffer.begin(pageId, Point(100.0, 85.0), 7.0)
            buffer.append(pageId, Point(100.0, 215.0))
            val result = withTimeout(10_000) { handler.finishAndSubmit(buffer, pageId) }
            assertEquals(
                WholeStrokeEraserGestureResult.Committed(originalSnapshot.revision + 1L), result
            )
            val after = repo.load(noteId)
            assertEquals(originalSnapshot.revision + 1L, after.revision)
            assertEquals(listOf(locked, distant), after.pages.single().elements.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)

            assertEquals(
                EraserSessionCommitResult.Committed(after.revision + 1L),
                withTimeout(10_000) { coordinator.undoWholeStroke().await() },
            )
            val restored = repo.load(noteId).pages.single().elements
            assertEquals(listOf(first, locked, second, distant), restored.map { it.id })
            assertEquals(originals, restored.map { it.envelopeJson })
            assertEquals(listOf(0L, 1L, 2L, 3L), restored.map { it.orderIndex })

            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        } finally { scope.cancel() }
    }

    @Test fun cancelledSweepDoesNotReadOrChangeRoomRevision() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(first, 100))))
        val before = repo.load(noteId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, before.revision)
            )
            val buffer = EditorEraserGestureBuffer()
            buffer.begin(pageId, Point(100.0, 100.0), 7.0)
            buffer.cancel()
            assertEquals(WholeStrokeEraserGestureResult.Cancelled,
                handler.finishAndSubmit(buffer, pageId))
            assertEquals(before, repo.load(noteId))
        } finally { scope.cancel() }
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

    private fun id(): String = UUID.randomUUID().toString()

    private fun stroke(id: String, y: Int, locked: Boolean = false) =
        """ {"id":"$id", "type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":4,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":$y,"tMs":0},{"x":160,"y":$y,"tMs":20}]}} """

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
