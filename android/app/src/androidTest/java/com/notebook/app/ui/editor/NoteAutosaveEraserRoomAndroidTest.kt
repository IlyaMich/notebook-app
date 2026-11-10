package com.notebook.app.ui.editor

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.ui.editor.ink.EditorEraserWholeStrokeRemovalPlanner
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** EDT-16 1C-2C: real Room commits through the retained autosave coordinator. */
@RunWith(AndroidJUnit4::class)
class NoteAutosaveEraserRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-session-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5_000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun completeEraserGestureCanRemoveUndoRedoAndReopenExactly() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val locked = id(); val last = id()
        val original = listOf(stroke(first), stroke(locked, locked = true), stroke(last))
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, original))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(
            snapshot, pageId, snapshot.revision, snapshot.pages.single().revision,
            listOf(first, last),
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, snapshot.revision)
            val removed = withTimeout(10_000) { coordinator.submitWholeStrokeRemoval(plan).await() }
            assertEquals(EraserSessionCommitResult.Committed(2L), removed)
            assertEquals(listOf(locked), repo.load(noteId).pages.single().elements.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)

            val undone = withTimeout(10_000) { coordinator.undoWholeStroke().await() }
            assertEquals(EraserSessionCommitResult.Committed(3L), undone)
            val afterUndo = repo.load(noteId).pages.single().elements
            assertEquals(original, afterUndo.map { it.envelopeJson })
            assertEquals(listOf(first, locked, last), afterUndo.map { it.id })
            assertEquals(listOf(0L, 1L, 2L), afterUndo.map { it.orderIndex })

            val redone = withTimeout(10_000) { coordinator.redoWholeStroke().await() }
            assertEquals(EraserSessionCommitResult.Committed(4L), redone)
            assertEquals(listOf(locked), repo.load(noteId).pages.single().elements.map { it.id })
            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            assertEquals(4L, repo.load(noteId).revision)
            assertEquals(listOf(locked), repo.load(noteId).pages.single().elements.map { it.id })
            // The in-memory undo stack belongs to the editor session, not to Room.
            val reopenedSession = NoteAutosaveCoordinator(noteId, repo, scope, 4L)
            assertFalse(reopenedSession.eraserHistory.value.canUndo)
        } finally { scope.cancel() }
    }

    @Test fun competingRevisionAfterScanCannotDeleteOrExposeUndo() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val second = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(first), stroke(second))))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(
            snapshot, pageId, snapshot.revision, snapshot.pages.single().revision,
            listOf(first),
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, snapshot.revision)
            repo.commit(noteId, DocumentEdit.ChangeBackground(
                pageId, """{"type":"blank","color":"#FFF9E9"}"""
            ))
            val actual = repo.load(noteId)
            val outcome = withTimeout(10_000) { coordinator.submitWholeStrokeRemoval(plan).await() }
            assertTrue(outcome is EraserSessionCommitResult.Unconfirmed)
            assertEquals(actual, repo.load(noteId))
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
        } finally { scope.cancel() }
    }

    @Test fun alreadyClosedSessionDoesNotCommitRemoval() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val second = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(first), stroke(second))))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(
            snapshot, pageId, snapshot.revision, snapshot.pages.single().revision, listOf(first),
        ))
        val endedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        endedScope.cancel()
        val coordinator = NoteAutosaveCoordinator(noteId, repo, endedScope, snapshot.revision)
        val result = withTimeout(10_000) { coordinator.submitWholeStrokeRemoval(plan).await() }
        assertTrue(result is EraserSessionCommitResult.Rejected)
        assertEquals(SavePhase.SAVED, coordinator.state.value.phase)
        assertEquals(0, coordinator.state.value.pendingCommandCount)
        assertEquals(snapshot, repo.load(noteId))
        assertFalse(coordinator.eraserHistory.value.canUndo)
    }

    @Test fun lostRoomAcknowledgementRequiresReloadWithoutReplayingRemoval() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val second = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(first), stroke(second))))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(
            snapshot, pageId, snapshot.revision, snapshot.pages.single().revision, listOf(first),
        ))
        val productionRepository = repo
        val lostAckRepository = object : DocumentRepository {
            var guardedCalls = 0
            override suspend fun load(noteId: String): StoredNoteContent = productionRepository.load(noteId)
            override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
                productionRepository.commit(noteId, edit)
            override suspend fun commitIfRevision(
                noteId: String, expectedRevision: Long, edit: DocumentEdit,
            ): DocumentCommit {
                guardedCalls++
                productionRepository.commitIfRevision(noteId, expectedRevision, edit)
                throw IllegalStateException("Simulated lost Room acknowledgement")
            }
        }
        val previousScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val previous = NoteAutosaveCoordinator(noteId, lostAckRepository, previousScope, snapshot.revision)
            val outcome = withTimeout(10_000) { previous.submitWholeStrokeRemoval(plan).await() }
            assertTrue(outcome is EraserSessionCommitResult.Unconfirmed)
            assertEquals(SavePhase.FAILED, previous.state.value.phase)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, previous.retry())
            assertEquals(1, lostAckRepository.guardedCalls)
        } finally { previousScope.cancel() }

        // Simulate leaving the editor and opening a new session from durable Room state.
        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        repo = RoomDocumentRepository(db)
        val reloaded = repo.load(noteId)
        assertEquals(snapshot.revision + 1, reloaded.revision)
        assertEquals(listOf(second), reloaded.pages.single().elements.map { it.id })
        val replacementScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val replacement = NoteAutosaveCoordinator(noteId, repo, replacementScope, reloaded.revision)
            assertEquals(SavePhase.SAVED, replacement.state.value.phase)
            assertEquals(0, replacement.eraserHistory.value.undoDepth)
            assertTrue(withTimeout(10_000) { replacement.undoWholeStroke().await() } is EraserSessionCommitResult.Rejected)
            assertEquals(reloaded, repo.load(noteId))
        } finally { replacementScope.cancel() }
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

    private fun stroke(id: String, locked: Boolean = false) =
        """ {"id":"$id", "type":"stroke", "transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1},"points":[{"x":80,"y":110,"tMs":0,"pressure":0.45},{"x":99,"y":100,"tMs":18,"pressure":0.65}]}} """

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
