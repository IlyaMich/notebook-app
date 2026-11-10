package com.notebook.app.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.notebook.app.ui.editor.ink.EditorInkTool
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** EDT-16 1E-7C-C: cancellation boundaries and a committed-but-unacknowledged Room write. */
@RunWith(AndroidJUnit4::class)
class NoteEditorEraserLifecycleRecoveryAndroidTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private lateinit var room: RoomDocumentRepository

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt16-1e7c-c-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        db.openHelper.writableDatabase
        room = RoomDocumentRepository(db, clock = { 5_000L })
    }

    @After fun after() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun pauseAndResumeDiscardActiveEraserButAllowNextGesture() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        room.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        val before = room.load(noteId)
        val owner = TestOwner()
        val callbacks = AtomicInteger()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
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
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        node.performTouchInput {
            down(percentOffset(0.24f, 0.13f))
            moveTo(percentOffset(0.28f, 0.13f))
        }
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        rule.waitForIdle()
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(before, room.load(noteId))
        // A new completed gesture is eligible after resume, but this capture-only
        // callback intentionally does not write a document.
        node.performTouchInput {
            down(percentOffset(0.24f, 0.13f))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, callbacks.get())
        assertEquals(before, room.load(noteId))
    }

    @Test fun removingViewportMidGestureCannotCompleteOrModifyRoom() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        room.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        val before = room.load(noteId)
        val mounted = mutableStateOf(true)
        val callbacks = AtomicInteger()
        rule.setContent {
            if (mounted.value) {
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
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        node.performTouchInput {
            down(percentOffset(0.24f, 0.13f))
            moveTo(percentOffset(0.28f, 0.13f))
        }
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        assertEquals(0, callbacks.get())
        assertEquals(before, room.load(noteId))
    }

    @Test fun roomWriteWithLostAcknowledgementIsNotRetriedOrUndoable() = runBlocking<Unit> {
        val (noteId, pageId) = fixture()
        room.commit(noteId, DocumentEdit.InsertElements(pageId, listOf(stroke(id()))))
        val visible = room.load(noteId)
        val intercepted = object : DocumentRepository {
            var guardedCalls = 0
            override suspend fun load(noteId: String): StoredNoteContent = room.load(noteId)
            override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
                room.commit(noteId, edit)
            override suspend fun commitIfRevision(
                noteId: String, expectedRevision: Long, edit: DocumentEdit,
            ): DocumentCommit {
                guardedCalls++
                room.commitIfRevision(noteId, expectedRevision, edit)
                throw IllegalStateException("Synthetic acknowledgement lost after durable Room commit")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, intercepted, scope, visible.revision)
            val result = withTimeout(15_000) {
                NoteEditorPartialStrokeEraser(noteId, intercepted, coordinator)
                    .submitViewportSweep(visible, pageId, listOf(DuPoint(100.0, 100.0)), 4.0)
            }
            assertTrue("Must surface uncertain outcome", result is PartialStrokeEraserGestureResult.Unconfirmed)
            val persisted = room.load(noteId)
            assertEquals(visible.revision + 1L, persisted.revision)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertFalse(coordinator.eraserHistory.value.canRedo)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, intercepted.guardedCalls)
            assertEquals(persisted, room.load(noteId))
        } finally { scope.cancel() }
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
    private fun stroke(id: String): String =
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":100,"tMs":0},{"x":200,"y":100,"tMs":160}]}}"""
    companion object { private const val BLANK = """{"type":"blank","color":"#FFFFFF"}""" }
}
