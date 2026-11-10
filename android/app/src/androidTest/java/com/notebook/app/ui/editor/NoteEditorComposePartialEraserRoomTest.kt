package com.notebook.app.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.ui.editor.ink.EditorInkTool
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose pointer release -> real canonical split -> guarded Room -> exact undo/redo. */
@RunWith(AndroidJUnit4::class)
class NoteEditorComposePartialEraserRoomTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-1e6-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5_000L })
    }

    @After fun after() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun composeTapSplitsCanonicalStrokeAndUndoRedoSurviveRoomReopen() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        val first = id(); val lockedId = id()
        val originals = listOf(stroke(first), stroke(lockedId, y = 220, locked = true))
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val visible = repo.load(noteId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completion = CompletableDeferred<PartialStrokeEraserGestureResult>()
        val callbacks = AtomicInteger(0)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, visible.revision)
            val handler = NoteEditorPartialStrokeEraser(noteId, repo, coordinator)
            rule.setContent {
                Box(Modifier.size(320.dp, 460.dp)) {
                    ContinuousPageViewport(
                        pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 2)),
                        storedPages = mapOf(pageId to visible.pages.single()),
                        documentKey = noteId,
                        selectedTool = EditorInkTool.ERASER,
                        fingerWritingPreview = true,
                        onEraserSweepCaptured = { hitPage, points, radius ->
                            callbacks.incrementAndGet()
                            scope.launch {
                                try {
                                    completion.complete(handler.submitViewportSweep(
                                        visible, hitPage, points.toList(), radius))
                                } catch (failure: Throwable) {
                                    completion.completeExceptionally(failure)
                                }
                            }
                        },
                    )
                }
            }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
                .performTouchInput {
                    // First ink is a horizontal 40..200 DU stroke at y=100 DU.
                    // Tap near its interior, away from the second locked stroke.
                    down(percentOffset(0.24f, 0.13f))
                    up()
                }
            val result = withTimeout(15_000) { completion.await() }
            assertEquals(PartialStrokeEraserGestureResult.Committed(visible.revision + 1L), result)
            assertEquals(1, callbacks.get())
            val updated = repo.load(noteId)
            val rows = updated.pages.single().elements
            assertEquals(3, rows.size)
            assertTrue(rows.take(2).all { it.id != first })
            assertEquals(originals[1], rows.last().envelopeJson)
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(updated.revision + 1),
                withTimeout(15_000) { coordinator.undoEraserEdit().await() })
            assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
            assertEquals(EraserSessionCommitResult.Committed(updated.revision + 2),
                withTimeout(15_000) { coordinator.redoEraserEdit().await() })
            val redone = repo.load(noteId).pages.single().elements.map { it.envelopeJson }
            assertEquals(rows.map { it.envelopeJson }, redone)
            db.close()
            db = AppDatabaseProvider.openIsolated(context, databaseName)
            repo = RoomDocumentRepository(db)
            assertEquals(redone, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        } finally { scope.cancel() }
    }

    @Test fun cancelledComposeSweepHasNoCallbackAndCannotModifyRoom() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        val visible = repo.load(noteId)
        val callbacks = AtomicInteger(0)
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 1)),
                    storedPages = mapOf(pageId to visible.pages.single()),
                    documentKey = noteId,
                    selectedTool = EditorInkTool.ERASER,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { _, _, _ -> callbacks.incrementAndGet() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
            .performTouchInput {
                down(percentOffset(0.24f, 0.13f))
                moveTo(percentOffset(0.30f, 0.14f))
                cancel()
            }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(visible, repo.load(noteId))
    }

    @Test fun changedRenderedPageMidGestureDiscardsCapture() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        val visible = repo.load(noteId)
        val pages = mutableStateOf(mapOf(pageId to visible.pages.single()))
        val callbacks = AtomicInteger(0)
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 0)),
                    storedPages = pages.value,
                    documentKey = noteId,
                    selectedTool = EditorInkTool.ERASER,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { _, _, _ -> callbacks.incrementAndGet() },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        node.performTouchInput {
            down(percentOffset(0.24f, 0.13f))
            moveTo(percentOffset(0.30f, 0.14f))
        }
        rule.runOnIdle {
            pages.value = mapOf(pageId to visible.pages.single().copy(revision = 99L))
        }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(visible, repo.load(noteId))
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
    private fun id() = UUID.randomUUID().toString()
    private fun stroke(id: String, y: Int = 100, locked: Boolean = false) =
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":$y,"tMs":0},{"x":200,"y":$y,"tMs":160}]}}"""
    companion object { private const val BLANK = """{"type":"blank","color":"#FFFFFF"}""" }
}
