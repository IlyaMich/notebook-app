package com.notebook.app.ui.editor.shapes

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.editor.Nav3EditorAutosaveTestActivity
import com.notebook.app.ui.editor.Nav3EditorTestBridge
import com.notebook.app.ui.editor.Nav3EditorTestConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C6: real Activity/Navigation3 recreation, canonical Room rows and the retained save/history owner. */
@RunWith(AndroidJUnit4::class)
class EditorShapeActivityRecreationTest {
    @get:Rule val rule = createAndroidComposeRule<Nav3EditorAutosaveTestActivity>()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var databaseName: String
    private lateinit var noteId: String
    private lateinit var repository: HeldRepository
    private val creation = "Shape creation viewport with 1 pages"
    private val editing = "Shape editing viewport with 1 pages"
    private var lastPointerInWindow = Offset.Zero

    private class HeldRepository(private val delegate: DocumentRepository) : DocumentRepository by delegate {
        val holdNext = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val guardedCalls = AtomicInteger()

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit {
            guardedCalls.incrementAndGet()
            if (holdNext.compareAndSet(true, false)) {
                entered.complete(Unit)
                release.await()
            }
            return delegate.commitIfRevision(noteId, expectedRevision, edit)
        }
    }

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt12-c6-recreation-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        noteId = runBlocking {
            LibraryRepositoryFactory(db).noteLifecycle().createNote(
                null, "Synthetic shape recreation", InitialPageSpec.a4PortraitBlank(),
            ).id
        }
        repository = HeldRepository(RoomDocumentRepository(db))
        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                "edt12-c6-host", noteId, RoomNoteRepository(db), repository,
            )
        }
        awaitSaved(0)
    }

    @After fun after() {
        if (::repository.isInitialized) repository.release.complete(Unit)
        if (::db.isInitialized) {
            try {
                rule.runOnIdle { Nav3EditorTestBridge.current = null }
                rule.waitForIdle()
            } finally {
                db.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    private fun snapshot(): StoredNoteContent = runBlocking { repository.load(noteId) }

    private fun awaitSaved(revision: Long, description: String? = null) {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Saved locally - revision $revision").fetchSemanticsNodes().isNotEmpty() &&
                (description == null || rule.onAllNodesWithContentDescription(description)
                    .fetchSemanticsNodes().isNotEmpty())
        }
        rule.waitForIdle()
    }

    private fun chooseRectangle() {
        rule.onNodeWithText("Shapes").performScrollTo().performClick()
        rule.onNodeWithText("Rectangle").performScrollTo().performClick()
        rule.onNodeWithText("Enable finger shape drawing").performClick()
        rule.waitForIdle()
    }

    private fun beginCreation() {
        val node = rule.onNodeWithContentDescription(creation)
        val bounds = node.fetchSemanticsNode().boundsInWindow
        lastPointerInWindow = Offset(bounds.left + bounds.width * 0.60f, bounds.top + bounds.height * 0.42f)
        node.performTouchInput {
            down(percentOffset(0.32f, 0.24f))
            moveTo(percentOffset(0.60f, 0.42f))
        }
    }

    private fun createRectangle() {
        chooseRectangle()
        beginCreation()
        rule.onNodeWithContentDescription(creation).performTouchInput { up() }
        awaitSaved(1, creation)
    }

    private fun chooseEditing() {
        rule.onNodeWithText("Edit shapes").performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun blueBounds(description: String): Pair<Offset, Offset>? {
        val image = rule.onNodeWithContentDescription(description).captureToImage()
        val pixels = image.toPixelMap()
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = pixels[x, y]
            if (color.red < 0.30f && color.green in 0.20f..0.52f && color.blue > 0.55f) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        return if (right < left) null else Offset(left.toFloat(), top.toFloat()) to
            Offset(right.toFloat(), bottom.toFloat())
    }

    private fun beginEdit() {
        // The toolbar can change viewport height/camera. Target actual retained paint, not an assumed origin.
        val (first, last) = requireNotNull(blueBounds(editing))
        val body = Offset((first.x + last.x) / 2f, first.y)
        val delta = with(rule.density) { 24.dp.toPx() }
        val node = rule.onNodeWithContentDescription(editing)
        val bounds = node.fetchSemanticsNode().boundsInWindow
        lastPointerInWindow = Offset(bounds.left, bounds.top) + body + Offset(delta, delta)
        node.performTouchInput {
            down(body)
            moveTo(body + Offset(delta, delta))
        }
    }

    private fun lateRelease() {
        // Activity recreation resets Compose's injector. Dispatch the stale platform UP directly.
        val time = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = lastPointerInWindow.x
            y = lastPointerInWindow.y
            pressure = 0f
        }
        val event = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, 1,
            arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            rule.runOnUiThread { rule.activity.dispatchTouchEvent(event) }
        } finally { event.recycle() }
        rule.waitForIdle()
    }

    private fun assertSameRows(before: StoredNoteContent, after: StoredNoteContent) {
        assertEquals(before.noteId, after.noteId)
        // Element commands advance note/element revisions, leaving page metadata revision unchanged.
        fun canonical(content: StoredNoteContent) = content.pages.map { page ->
            page.copy(elements = page.elements.map { it.copy(revision = 0) })
        }
        assertEquals(canonical(before), canonical(after))
        after.pages.single().elements.forEach { assertEquals(after.revision, it.revision) }
    }

    @Test fun committedReplacementAndExactHistorySurviveActualActivityRecreation() {
        createRectangle()
        val inserted = snapshot()
        chooseEditing()
        beginEdit()
        rule.onNodeWithContentDescription(editing).performTouchInput { up() }
        awaitSaved(2, editing)
        val edited = snapshot()
        val original = inserted.pages.single().elements.single()
        val changed = edited.pages.single().elements.single()
        assertEquals(original.id, changed.id)
        assertEquals(original.orderIndex, changed.orderIndex)
        assertNotEquals(original.envelopeJson, changed.envelopeJson)

        rule.activityRule.scenario.recreate()
        awaitSaved(2, editing)
        assertEquals(edited, snapshot())
        assertNotNull("Committed shape must render after recreation", blueBounds(editing))
        rule.onNodeWithText("Undo shape").assertIsEnabled().performClick()
        awaitSaved(3, editing)
        assertSameRows(inserted, snapshot())
        rule.onNodeWithText("Redo shape").assertIsEnabled().performClick()
        awaitSaved(4, editing)
        assertSameRows(edited, snapshot())
    }

    @Test fun unfinishedCreationCannotCompleteAfterActivityRecreationAndLateRelease() {
        chooseRectangle()
        beginCreation()
        assertNotNull("Unfinished creation should be visible", blueBounds(creation))
        assertEquals(0L, snapshot().revision)
        assertEquals(0, repository.guardedCalls.get())

        rule.activityRule.scenario.recreate()
        awaitSaved(0, creation)
        assertNull("Unfinished preview must be discarded", blueBounds(creation))
        lateRelease()
        assertEquals(0L, snapshot().revision)
        assertTrue(snapshot().pages.single().elements.isEmpty())
        rule.onNodeWithText("Undo shape").assertIsNotEnabled()

        beginCreation()
        rule.onNodeWithContentDescription(creation).performTouchInput { up() }
        awaitSaved(1, creation)
        assertEquals(1, repository.guardedCalls.get())
        assertEquals(1, snapshot().pages.single().elements.size)
    }

    @Test fun unfinishedReplacementCannotAddHistoryOrRewriteSourceAfterActivityRecreation() {
        createRectangle()
        val original = snapshot()
        chooseEditing()
        beginEdit()
        assertEquals(original, snapshot())

        rule.activityRule.scenario.recreate()
        awaitSaved(1, editing)
        lateRelease()
        assertEquals(original, snapshot())
        assertEquals(1, repository.guardedCalls.get())
        assertNotNull(blueBounds(editing))
        // Only the completed insertion is on history; the abandoned replacement cannot hide it.
        rule.onNodeWithText("Undo shape").performClick()
        awaitSaved(2, editing)
        assertTrue(snapshot().pages.single().elements.isEmpty())
        rule.onNodeWithText("Redo shape").performClick()
        awaitSaved(3, editing)
        assertSameRows(original, snapshot())
        beginEdit()
        rule.onNodeWithContentDescription(editing).performTouchInput { up() }
        awaitSaved(4, editing)
        val actual = snapshot().pages.single().elements.single()
        assertEquals(original.pages.single().elements.single().id, actual.id)
        assertNotEquals(original.pages.single().elements.single().envelopeJson, actual.envelopeJson)
    }

    @Test fun admittedBlockedReplacementFinishesOnceAfterActivityRecreation() {
        createRectangle()
        val original = snapshot()
        chooseEditing()
        repository.holdNext.set(true)
        beginEdit()
        rule.onNodeWithContentDescription(editing).performTouchInput { up() }
        runBlocking { withTimeout(10_000) { repository.entered.await() } }
        rule.onNodeWithText("Saved locally - revision 2").assertDoesNotExist()
        assertEquals(original, snapshot())

        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals(original, snapshot())
        assertEquals(2, repository.guardedCalls.get())
        repository.release.complete(Unit)
        awaitSaved(2, editing)
        val edited = snapshot()
        assertEquals(1, edited.pages.single().elements.size)
        assertEquals(original.pages.single().elements.single().id, edited.pages.single().elements.single().id)
        assertNotEquals(original.pages.single().elements.single().envelopeJson,
            edited.pages.single().elements.single().envelopeJson)
        assertEquals("Recreation must not resubmit the guarded replacement", 2, repository.guardedCalls.get())
        rule.onNodeWithText("Undo shape").performClick()
        awaitSaved(3, editing)
        assertSameRows(original, snapshot())
        rule.onNodeWithText("Redo shape").performClick()
        awaitSaved(4, editing)
        assertSameRows(edited, snapshot())
    }
}
