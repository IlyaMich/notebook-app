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
import com.notebook.app.data.content.model.StoredPage
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

/** 1D-5: actual Compose pointer release -> visible revision gate -> Room -> undo. */
@RunWith(AndroidJUnit4::class)
class NoteEditorComposeEraserRoomTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repo: RoomDocumentRepository

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "edt16-1d5-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repo = RoomDocumentRepository(db, clock = { 5_000L })
    }

    @After fun after() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun completedComposeSweepRemovesHitStrokesAndUndoRestoresExactJson() = runBlocking {
        val (noteId, pageId) = fixture()
        val first = id(); val locked = id(); val second = id(); val distant = id()
        val originals = listOf(
            stroke(first, 100), stroke(locked, 160, true),
            stroke(second, 215), stroke(distant, 360),
        )
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, originals))
        val visible = repo.load(noteId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val result = CompletableDeferred<WholeStrokeEraserGestureResult>()
        val completions = AtomicInteger(0)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, visible.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            rule.setContent {
                Box(Modifier.size(320.dp, 460.dp)) {
                    ContinuousPageViewport(
                        pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 4)),
                        storedPages = mapOf(pageId to visible.pages.single()),
                        documentKey = noteId,
                        selectedTool = EditorInkTool.ERASER,
                        fingerWritingPreview = true,
                        onEraserSweepCaptured = { hitPage, points, radius ->
                            completions.incrementAndGet()
                            scope.launch {
                                try {
                                    result.complete(handler.submitViewportSweep(
                                        visible, hitPage, points, radius,
                                    ))
                                } catch (failure: Throwable) {
                                    result.completeExceptionally(failure)
                                }
                            }
                        },
                    )
                }
            }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
                .performTouchInput {
                    // On 320dp width, page fit scale = 288/595.276 dp per DU.
                    // These two positions cross the y=100 and y=215 strokes.
                    down(percentOffset(0.20f, 0.13f))
                    moveTo(percentOffset(0.20f, 0.28f))
                    up()
                }
            val committed = withTimeout(15_000) { result.await() }
            assertEquals(WholeStrokeEraserGestureResult.Committed(visible.revision + 1L), committed)
            assertEquals(1, completions.get())
            val after = repo.load(noteId)
            assertEquals(visible.revision + 1L, after.revision)
            assertEquals(listOf(locked, distant), after.pages.single().elements.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)

            assertEquals(EraserSessionCommitResult.Committed(after.revision + 1L),
                withTimeout(15_000) { coordinator.undoWholeStroke().await() })
            val restored = repo.load(noteId).pages.single().elements
            assertEquals(listOf(first, locked, second, distant), restored.map { it.id })
            assertEquals(originals, restored.map { it.envelopeJson })
            assertEquals(listOf(0L, 1L, 2L, 3L), restored.map { it.orderIndex })
            // Room reopen must retain the acknowledged undo operation.
            db.close()
            db = AppDatabaseProvider.openIsolated(context, dbName)
            repo = RoomDocumentRepository(db)
            assertEquals(originals, repo.load(noteId).pages.single().elements.map { it.envelopeJson })
        } finally { scope.cancel() }
    }

    @Test fun platformCancelledComposeGestureCannotTouchRoom() = runBlocking {
        val (noteId, pageId) = fixture()
        val strokeId = id()
        repo.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(strokeId, 100))))
        val before = repo.load(noteId)
        val callbacks = AtomicInteger(0)
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 1)),
                    storedPages = mapOf(pageId to before.pages.single()),
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
                down(percentOffset(0.20f, 0.13f))
                moveTo(percentOffset(0.20f, 0.27f))
                cancel()
            }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(before, repo.load(noteId))
    }

    @Test fun renderedPageChangesMidGestureDiscardCaptureWithoutCallback() = runBlocking {
        val (noteId, pageId) = fixture()
        val before = repo.load(noteId)
        val callbacks = AtomicInteger(0)
        val renderedPages = mutableStateOf(mapOf(pageId to before.pages.single()))
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 0)),
                    storedPages = renderedPages.value,
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
            down(percentOffset(0.20f, 0.13f))
            moveTo(percentOffset(0.20f, 0.27f))
        }
        rule.runOnIdle {
            // Simulates a newly rendered Room page while the pointer remains down.
            val updated: StoredPage = before.pages.single().copy(revision = 99L)
            renderedPages.value = mapOf(pageId to updated)
        }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(before, repo.load(noteId))
    }

    private suspend fun fixture(): Pair<String, String> {
        val notebookId = id()
        db.notebookDao().insert(NotebookEntity(
            notebookId, null, "Notebook", sortOrder = 0,
            createdAtMillis = 100L, updatedAtMillis = 100L,
        ))
        val noteId = id()
        db.noteDao().insert(NoteEntity(
            noteId, notebookId, "Note", createdAtMillis = 100L, updatedAtMillis = 100L,
        ))
        val pageId = id()
        db.pageDao().insert(PageEntity(
            pageId, noteId, 0L, 595.276, 841.890, BLANK, 0L,
        ))
        return noteId to pageId
    }

    private fun id() = UUID.randomUUID().toString()

    private fun stroke(id: String, y: Int, locked: Boolean = false): String =
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":4,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":$y,"tMs":0},{"x":160,"y":$y,"tMs":20}]}}"""

    companion object {
        private const val BLANK = """{"type":"blank","color":"#FFFFFF"}"""
    }
}
