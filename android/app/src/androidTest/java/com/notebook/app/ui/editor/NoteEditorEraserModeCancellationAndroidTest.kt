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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** EDT-16 1E-7C-A: a mode/tool change must invalidate unfinished eraser capture. */
@RunWith(AndroidJUnit4::class)
class NoteEditorEraserModeCancellationAndroidTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var repository: RoomDocumentRepository

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-1e7c-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        repository = RoomDocumentRepository(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun partialToWholeMidGestureDoesNotCompleteOrWrite() = runBlocking<Unit> {
        assertModeChangeCancels(
            initialMode = NoteEditorEraserMode.PARTIAL,
            nextMode = NoteEditorEraserMode.WHOLE_STROKE,
        )
    }

    @Test fun wholeToPartialMidGestureDoesNotCompleteOrWrite() = runBlocking<Unit> {
        assertModeChangeCancels(
            initialMode = NoteEditorEraserMode.WHOLE_STROKE,
            nextMode = NoteEditorEraserMode.PARTIAL,
        )
    }

    private suspend fun assertModeChangeCancels(
        initialMode: NoteEditorEraserMode,
        nextMode: NoteEditorEraserMode,
    ) {
        val (noteId, pageId) = createNoteWithStroke()
        val before = repository.load(noteId)
        val mode = mutableStateOf(initialMode)
        val callbacks = AtomicInteger(0)
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 1)),
                    storedPages = mapOf(pageId to before.pages.single()),
                    documentKey = noteId,
                    selectedTool = EditorInkTool.ERASER,
                    eraserModeKey = mode.value.name,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { _, _, _ -> callbacks.incrementAndGet() },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        node.performTouchInput {
            down(percentOffset(0.20f, 0.13f))
            moveTo(percentOffset(0.30f, 0.13f))
        }
        rule.runOnIdle { mode.value = nextMode }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("Switching ${initialMode.name} to ${nextMode.name} must cancel capture", 0, callbacks.get())
        assertEquals("Interrupted erase must not change Room", before, repository.load(noteId))
    }

    @Test fun eraserToPenMidGestureDoesNotCompleteOrWrite() = runBlocking<Unit> {
        val (noteId, pageId) = createNoteWithStroke()
        val before = repository.load(noteId)
        val tool = mutableStateOf(EditorInkTool.ERASER)
        val callbacks = AtomicInteger(0)
        rule.setContent {
            Box(Modifier.size(320.dp, 460.dp)) {
                ContinuousPageViewport(
                    pages = listOf(PageViewportItem(pageId, 595.276, 841.890, 1)),
                    storedPages = mapOf(pageId to before.pages.single()),
                    documentKey = noteId,
                    selectedTool = tool.value,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { _, _, _ -> callbacks.incrementAndGet() },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        node.performTouchInput {
            down(percentOffset(0.20f, 0.13f))
            moveTo(percentOffset(0.30f, 0.13f))
        }
        rule.runOnIdle { tool.value = EditorInkTool.PEN }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(before, repository.load(noteId))
    }

    private suspend fun createNoteWithStroke(): Pair<String, String> {
        val notebookId = UUID.randomUUID().toString()
        db.notebookDao().insert(NotebookEntity(
            notebookId, null, "Test notebook", sortOrder = 0,
            createdAtMillis = 100L, updatedAtMillis = 100L,
        ))
        val noteId = UUID.randomUUID().toString()
        db.noteDao().insert(NoteEntity(
            noteId, notebookId, "Test note", createdAtMillis = 100L, updatedAtMillis = 100L,
        ))
        val pageId = UUID.randomUUID().toString()
        db.pageDao().insert(PageEntity(
            pageId, noteId, 0L, 595.276, 841.890,
            """{"type":"blank","color":"#FFFFFF"}""", 0L,
        ))
        val strokeId = UUID.randomUUID().toString()
        val envelope = """{"id":"$strokeId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":4,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":100,"tMs":0},{"x":160,"y":100,"tMs":120}]}}"""
        repository.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(envelope)))
        return noteId to pageId
    }
}
