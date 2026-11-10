package com.notebook.app.ui.editor

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserPartialStrokeReplacementPlanner
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

/** EDT-16 1E-5: real Room commits via one serial, commit-confirmed eraser history. */
@RunWith(AndroidJUnit4::class)
class NoteAutosavePartialEraserRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-partial-session-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5000L })
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun plannerToCoordinatorToRoomUndoRedoReopensWithIdenticalFragments() = runBlocking {
        val (noteId, pageId) = fixture()
        val originalId = id()
        val lockedId = id()
        val originals = listOf(stroke(originalId), stroke(lockedId, locked = true))
        val initial = repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserPartialStrokeReplacementPlanner.plan(
            content = snapshot,
            pageId = pageId,
            sweepPointsPageDu = listOf(Point(50.0, 100.0)),
            sweepRadiusPageDu = 4.0,
            scanNoteRevision = initial.revision,
            scanPageRevision = snapshot.pages.single().revision,
        ))
        assertEquals(2, plan.replacements.single().fragmentIds.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, snapshot.revision)
            assertEquals(EraserSessionCommitResult.Committed(initial.revision + 1),
                withTimeout(10_000) { coordinator.submitPartialStrokeReplacement(plan).await() })
            val after = repo.load(noteId)
            assertEquals(plan.resultingElementIdsInOrder, after.pages.single().elements.map { it.id })
            assertEquals(originals[1], after.pages.single().elements.last().envelopeJson)
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(initial.revision + 2),
                withTimeout(10_000) { coordinator.undoEraserEdit().await() })
            assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
            assertEquals(EraserSessionCommitResult.Committed(initial.revision + 3),
                withTimeout(10_000) { coordinator.redoEraserEdit().await() })
            assertEquals(after.pages.single().elements.map { it.envelopeJson },
                repo.load(noteId).pages.single().elements.map { it.envelopeJson })
            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            assertEquals(after.pages.single().elements.map { it.envelopeJson },
                repo.load(noteId).pages.single().elements.map { it.envelopeJson })
            assertEquals(initial.revision + 3, repo.load(noteId).revision)
        } finally { scope.cancel() }
    }

    @Test fun lostAcknowledgementMustNotOfferUndoOrReplayPartialReplacement() = runBlocking {
        val (noteId, pageId) = fixture()
        val originalId = id()
        val untouchedId = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId,
            listOf(stroke(originalId), stroke(untouchedId, locked = true))))
        val snapshot = repo.load(noteId)
        val plan = requireNotNull(EditorEraserPartialStrokeReplacementPlanner.plan(
            content = snapshot,
            pageId = pageId,
            sweepPointsPageDu = listOf(Point(50.0, 100.0)),
            sweepRadiusPageDu = 4.0,
        ))
        val durableRepo = repo
        val lostAck = object : DocumentRepository {
            var guardedCalls = 0
            override suspend fun load(noteId: String): StoredNoteContent = durableRepo.load(noteId)
            override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
                durableRepo.commit(noteId, edit)
            override suspend fun commitIfRevision(
                noteId: String, expectedRevision: Long, edit: DocumentEdit,
            ): DocumentCommit {
                guardedCalls++
                durableRepo.commitIfRevision(noteId, expectedRevision, edit)
                throw IllegalStateException("Simulated lost partial-erase acknowledgement")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, lostAck, scope, snapshot.revision)
            assertTrue(withTimeout(10_000) {
                coordinator.submitPartialStrokeReplacement(plan).await()
            } is EraserSessionCommitResult.Unconfirmed)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, lostAck.guardedCalls)
            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            val durable = repo.load(noteId)
            assertEquals(snapshot.revision + 1, durable.revision)
            assertEquals(plan.resultingElementIdsInOrder,
                durable.pages.single().elements.map { it.id })
            val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val fresh = NoteAutosaveCoordinator(noteId, repo, freshScope, durable.revision)
                assertEquals(0, fresh.eraserHistory.value.undoDepth)
                assertTrue(withTimeout(10_000) {
                    fresh.undoEraserEdit().await()
                } is EraserSessionCommitResult.Rejected)
            } finally { freshScope.cancel() }
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

    private fun stroke(id: String, locked: Boolean = false) =
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#2255AA","opacity":1.0},"points":[{"x":10,"y":100,"tMs":0,"pressure":0.3},{"x":90,"y":100,"tMs":80,"pressure":0.9}]}}"""

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
