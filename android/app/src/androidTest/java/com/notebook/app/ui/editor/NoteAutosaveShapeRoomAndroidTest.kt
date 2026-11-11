package com.notebook.app.ui.editor

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.*
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.*
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserPartialStrokeReplacementPlanner
import com.notebook.app.ui.editor.ink.EditorEraserWholeStrokeRemovalPlanner
import com.notebook.app.ui.editor.shapes.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteAutosaveShapeRoomAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun start() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt12-shape-session-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db)
    }
    @After fun stop() { db.close(); context.deleteDatabase(databaseName) }
    private fun id() = UUID.randomUUID().toString()
    private suspend fun fixture(): Pair<String, String> {
        val notebook = id(); val note = id(); val page = id()
        db.notebookDao().insert(NotebookEntity(notebook, null, "Synthetic", sortOrder = 0,
            createdAtMillis = 100, updatedAtMillis = 100))
        db.noteDao().insert(NoteEntity(note, notebook, "Shapes", createdAtMillis = 100, updatedAtMillis = 100))
        db.pageDao().insert(PageEntity(page, note, 0, 100.0, 100.0, BLANK, 0))
        return note to page
    }
    private fun shape(kind: CanonicalShapeKind = CanonicalShapeKind.RECTANGLE) =
        CanonicalShapeAdapter.create(id(), kind,
            if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
                CanonicalShapeGeometry.Endpoints(10.0, 20.0, 60.0, 50.0)
            else CanonicalShapeGeometry.Frame(10.0, 20.0, 50.0, 30.0),
            CanonicalShapeStroke("#aBcDeF", 3.0, 0.75), CanonicalShapeFill("#FEDCBA", 0.5),
            if (kind == CanonicalShapeKind.ARROW) CanonicalShapeArrowHead(12.0, -390.0) else null)
    private fun stroke(elementId: String) =
        """{"id":"$elementId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","color":"#2255AA","width":2,"opacity":1},"points":[{"x":10,"y":50,"tMs":0},{"x":90,"y":50,"tMs":20}]}}"""
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private suspend fun result(value: Deferred<EraserSessionCommitResult>) = withTimeout(10_000) { value.await() }
    private suspend fun saved(coordinator: NoteAutosaveCoordinator, revision: Long) {
        withTimeout(10_000) { coordinator.state.first { it.phase == SavePhase.SAVED && it.committedRevision == revision } }
    }

    @Test fun fourKindsInsertUndoRedoAndReopenWithSameIdsAndExactEnvelopes() = runBlocking {
        val (note, page) = fixture()
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 0)
            val commands = NoteEditorShapeCommands(note, repo, coordinator)
            for (kind in CanonicalShapeKind.entries) {
                val before = repo.load(note)
                val raw = shape(kind)
                val expected = before.revision
                assertEquals(ShapeCommandResult.Committed(expected + 1), commands.insert(before, page, raw))
                assertTrue(coordinator.shapeHistory.value.canUndo)
                assertEquals(EraserSessionCommitResult.Committed(expected + 2), result(coordinator.undoShapeEdit()))
                assertEquals(before.pages.single().elements.map { it.envelopeJson },
                    repo.load(note).pages.single().elements.map { it.envelopeJson })
                assertEquals(EraserSessionCommitResult.Committed(expected + 3), result(coordinator.redoShapeEdit()))
                assertEquals(raw, repo.load(note).pages.single().elements.last().envelopeJson)
            }
            val expected = repo.load(note)
            assertEquals(12L, expected.revision)
            assertEquals(listOf(0L, 1L, 2L, 3L), expected.pages.single().elements.map { it.orderIndex })
            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            assertEquals(expected, repo.load(note))
            val fresh = NoteAutosaveCoordinator(note, repo, scope, expected.revision)
            assertFalse(fresh.shapeHistory.value.canUndo)
            assertFalse(fresh.eraserHistory.value.canUndo)
        } finally { scope.cancel() }
    }

    @Test fun replacementRestoresSourceBytesAffineAndOrderWithoutRewritingInk() = runBlocking {
        val (note, page) = fixture()
        val original = " \n" + shape().replace("[1,0,0,1,0,0]", "[1,0.25,0.5,2,5,-10]") + "\n"
        repo.commit(note, DocumentEdit.InsertElements(page, listOf(stroke(id()), original)))
        val before = repo.load(note)
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, before.revision)
            val commands = NoteEditorShapeCommands(note, repo, coordinator)
            val decoded = CanonicalShapeAdapter.decode(page, before.pages.single().elements.last())
            val edited = CanonicalShapeAdapter.replace(decoded, stroke = decoded.stroke.copy(widthDu = 4.125))
            assertEquals(ShapeCommandResult.Committed(2), commands.replace(before, page, edited))
            assertEquals(before.pages.single().elements.first(), repo.load(note).pages.single().elements.first())
            assertEquals(EraserSessionCommitResult.Committed(3), result(coordinator.undoShapeEdit()))
            assertEquals(original, repo.load(note).pages.single().elements.last().envelopeJson)
            assertEquals(EraserSessionCommitResult.Committed(4), result(coordinator.redoShapeEdit()))
            val after = repo.load(note)
            assertEquals(edited, after.pages.single().elements.last().envelopeJson)
            assertEquals(before.pages.single().elements.map { it.id }, after.pages.single().elements.map { it.id })
            assertEquals(ShapeCommandResult.NoChange, commands.replace(after, page, edited))
            assertEquals(after, repo.load(note))
        } finally { scope.cancel() }
    }

    @Test fun lockedInvalidAndWrongPageActionsAreRejectedWithoutAnyRevisionChange() = runBlocking {
        val (note, page) = fixture()
        val raw = shape().replace("\"locked\":false", "\"locked\":true")
        repo.commit(note, DocumentEdit.InsertElements(page, listOf(raw)))
        val before = repo.load(note)
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, before.revision)
            val commands = NoteEditorShapeCommands(note, repo, coordinator)
            assertTrue(commands.replace(before, page, raw.replace("\"locked\":true", "\"locked\":false"))
                is ShapeCommandResult.Rejected)
            assertTrue(commands.insert(before, page, "{}") is ShapeCommandResult.Rejected)
            assertTrue(commands.insert(before, id(), shape()) is ShapeCommandResult.Rejected)
            assertEquals(before, repo.load(note))
            assertFalse(coordinator.shapeHistory.value.canUndo)
        } finally { scope.cancel() }
    }

    @Test fun concurrentWriteInsideGuardIsRejectedWithoutShapeMutationOrFalseSaved() = runBlocking {
        val (note, page) = fixture()
        val interfering = object : DocumentRepository {
            override suspend fun load(noteId: String) = repo.load(noteId)
            override suspend fun commit(noteId: String, edit: DocumentEdit) = repo.commit(noteId, edit)
            override suspend fun commitIfRevision(noteId: String, expectedRevision: Long, edit: DocumentEdit): DocumentCommit {
                repo.commit(noteId, DocumentEdit.ChangeBackground(page, WARM))
                return repo.commitIfRevision(noteId, expectedRevision, edit)
            }
        }
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, interfering, scope, 0)
            val commands = NoteEditorShapeCommands(note, interfering, coordinator)
            assertTrue(commands.insert(repo.load(note), page, shape()) is ShapeCommandResult.Unconfirmed)
            val actual = repo.load(note)
            assertEquals(1L, actual.revision) // only the competing background command
            assertTrue(actual.pages.single().elements.isEmpty())
            assertEquals(WARM, actual.pages.single().backgroundJson)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertFalse(coordinator.shapeHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
        } finally { scope.cancel() }
    }

    @Test fun failuresBeforeAndAfterCommitKeepHistoryAndRetryDurabilityHonest() = runBlocking {
        for (afterWrite in listOf(false, true)) {
            val (note, page) = fixture()
            var failOnce = true
            var calls = 0
            val failing = object : DocumentRepository {
                override suspend fun load(noteId: String) = repo.load(noteId)
                override suspend fun commit(noteId: String, edit: DocumentEdit) = repo.commit(noteId, edit)
                override suspend fun commitIfRevision(noteId: String, expectedRevision: Long, edit: DocumentEdit): DocumentCommit {
                    calls++
                    if (failOnce && !afterWrite) { failOnce = false; error("Synthetic write failure") }
                    val committed = repo.commitIfRevision(noteId, expectedRevision, edit)
                    if (failOnce) { failOnce = false; error("Synthetic lost acknowledgement") }
                    return committed
                }
            }
            val scope = scope()
            try {
                val coordinator = NoteAutosaveCoordinator(note, failing, scope, 0)
                val commands = NoteEditorShapeCommands(note, failing, coordinator)
                val raw = shape()
                assertTrue(commands.insert(repo.load(note), page, raw) is ShapeCommandResult.Unconfirmed)
                assertFalse(coordinator.shapeHistory.value.canUndo)
                assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
                if (afterWrite) {
                    assertEquals(1L, repo.load(note).revision)
                    assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
                    assertEquals(1, calls)
                } else {
                    assertEquals(0L, repo.load(note).revision)
                    assertEquals(SaveRetryResult.STARTED, coordinator.retry())
                    saved(coordinator, 1)
                    // Retry returns STARTED; save and history are separate StateFlow publications.
                    withTimeout(10_000) { coordinator.shapeHistory.first { it.canUndo } }
                    assertTrue(coordinator.shapeHistory.value.canUndo)
                    assertEquals(2, calls)
                }
                assertEquals(listOf(raw), repo.load(note).pages.single().elements.map { it.envelopeJson })
            } finally { scope.cancel() }
        }
    }

    @Test fun shapePartialAndWholeEraseHistoryRemainChronologicalAndExactlyReversible() = runBlocking {
        val (note, page) = fixture()
        val inkId = id()
        val original = listOf(stroke(inkId), shape())
        repo.commit(note, DocumentEdit.InsertElements(page, original))
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 1)
            val commands = NoteEditorShapeCommands(note, repo, coordinator)
            val before = repo.load(note)
            val shape = CanonicalShapeAdapter.decode(page, before.pages.single().elements.last())
            assertEquals(ShapeCommandResult.Committed(2), commands.replace(before, page,
                CanonicalShapeAdapter.replace(shape, stroke = shape.stroke.copy(widthDu = 4.0))))
            val current = repo.load(note)
            val partial = requireNotNull(EditorEraserPartialStrokeReplacementPlanner.plan(
                current, page, listOf(Point(50.0, 50.0)), 6.0))
            assertEquals(EraserSessionCommitResult.Committed(3), result(coordinator.submitPartialStrokeReplacement(partial)))
            assertTrue(result(coordinator.undoShapeEdit()) is EraserSessionCommitResult.Rejected)
            result(coordinator.undoEraserEdit()) // 4: exact stroke restored
            result(coordinator.undoShapeEdit()) // 5: original shape
            assertEquals(original, repo.load(note).pages.single().elements.map { it.envelopeJson })
            assertTrue(result(coordinator.redoEraserEdit()) is EraserSessionCommitResult.Rejected)
            result(coordinator.redoShapeEdit()) // 6
            result(coordinator.redoEraserEdit()) // 7: same fragment IDs
            val fragments = repo.load(note)
            assertEquals(partial.resultingElementIdsInOrder, fragments.pages.single().elements.map { it.id })
            val whole = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(fragments, page,
                fragments.revision, fragments.pages.single().revision,
                listOf(fragments.pages.single().elements.first { it.kind == "stroke" }.id)))
            assertEquals(EraserSessionCommitResult.Committed(8), result(coordinator.submitWholeStrokeRemoval(whole)))
            result(coordinator.undoEraserEdit()) // 9: restore fragment
            result(coordinator.undoEraserEdit()) // 10: restore pre-partial stroke
            result(coordinator.undoShapeEdit()) // 11: restore original shape
            val restored = repo.load(note)
            assertEquals(11L, restored.revision)
            assertEquals(original, restored.pages.single().elements.map { it.envelopeJson })
            assertEquals(listOf(0L, 1L), restored.pages.single().elements.map { it.orderIndex })
        } finally { scope.cancel() }
    }

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
        private const val WARM = """{"type":"blank","color":"#FFF9E9"}"""
    }
}
