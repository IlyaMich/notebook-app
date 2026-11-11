package com.notebook.app.ui.editor.shapes

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import com.notebook.app.ui.screens.NoteEditorScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C4: real toolbar + pointer release -> C3 guarded queue -> Room -> retained rendering/reopen. */
@RunWith(AndroidJUnit4::class)
class EditorShapeProductionIntegrationTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase
    private val shown = mutableStateOf(true)
    private val opening = mutableIntStateOf(0)
    private val shapeDescription = "Shape creation viewport with 1 pages"
    private val inkDescription = "Read-only note viewport with 1 pages"

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt12-c4-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun after() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private fun fixture(): Pair<String, String> = runBlocking {
        val note = LibraryRepositoryFactory(db).noteLifecycle().createNote(
            null, "Synthetic shape test", InitialPageSpec.a4PortraitBlank(),
        )
        note.id to RoomDocumentRepository(db).load(note.id).pages.single().id
    }

    private fun mount(noteId: String, compact: Boolean = false) {
        rule.setContent {
            if (shown.value) {
                val session: NoteEditorSessionViewModel = viewModel(key = "shape-${opening.intValue}")
                Box(if (compact) Modifier.size(360.dp, 740.dp) else Modifier) {
                    NoteEditorScreen(noteId, RoomNoteRepository(db), RoomDocumentRepository(db),
                        session, 0, onBack = { shown.value = false })
                }
            }
        }
        awaitSaved(0)
    }

    private fun awaitSaved(revision: Long) {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("Saved locally - revision $revision")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun chooseShapes() {
        rule.onNodeWithText("Shapes").performScrollTo().performClick()
        rule.onNodeWithText("Enable finger shape drawing").performClick()
        rule.waitForIdle()
    }

    private fun draw() = rule.onNodeWithContentDescription(shapeDescription).performTouchInput {
        down(percentOffset(0.35f, 0.22f))
        moveTo(percentOffset(0.55f, 0.42f))
        up()
    }

    private fun blueMask(description: String): Set<Int> {
        val image = rule.onNodeWithContentDescription(description).captureToImage()
        val pixels = image.toPixelMap()
        val blue = mutableSetOf<Int>()
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = pixels[x, y]
            if (c.red < 0.30f && c.green in 0.20f..0.52f && c.blue > 0.55f) blue += y * image.width + x
        }
        return blue
    }

    @Test fun fourKindsAutosaveUndoRedoAndRenderAfterActualRoomReopen() {
        val (noteId, pageId) = fixture()
        mount(noteId)
        chooseShapes()
        val ids = mutableListOf<String>()
        EditorShapeTool.entries.forEachIndexed { index, tool ->
            rule.onNodeWithText(tool.label).performScrollTo().performClick()
            val node = rule.onNodeWithContentDescription(shapeDescription)
            node.performTouchInput {
                down(percentOffset(0.35f, 0.22f))
                moveTo(percentOffset(0.55f, 0.42f))
            }
            val live = blueMask(shapeDescription)
            assertTrue(live.isNotEmpty())
            node.performTouchInput { up() }
            awaitSaved(index + 1L)
            assertEquals("Preview and retained paint must agree for $tool", live, blueMask(shapeDescription))
            val saved = runBlocking { RoomDocumentRepository(db).load(noteId) }
            assertEquals(index + 1L, saved.revision)
            val page = saved.pages.single()
            assertEquals(pageId, page.id)
            assertEquals(index + 1, page.elements.size)
            val row = page.elements.last()
            assertEquals("shape", row.kind)
            assertEquals(index.toLong(), row.orderIndex)
            val shape = CanonicalShapeAdapter.decode(pageId, row)
            assertEquals(tool.kind, shape.kind)
            assertFalse(shape.locked)
            assertNull(shape.fill)
            assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), shape.elementTransform.coefficients())
            if (tool == EditorShapeTool.ARROW) assertEquals(ShapeCreationDefaults.arrowHead, shape.arrowHead)
            ids += row.id
            assertEquals(ids.distinct(), ids)
            assertEquals(ids, page.elements.map { it.id })
        }
        val original = runBlocking { RoomDocumentRepository(db).load(noteId) }
            .pages.single().elements.map { it.envelopeJson }
        rule.onNodeWithText("Undo shape").performClick()
        awaitSaved(5)
        assertEquals(ids.dropLast(1),
            runBlocking { RoomDocumentRepository(db).load(noteId) }.pages.single().elements.map { it.id })
        rule.onNodeWithText("Redo shape").performClick()
        awaitSaved(6)
        assertEquals(original,
            runBlocking { RoomDocumentRepository(db).load(noteId) }.pages.single().elements.map { it.envelopeJson })
        assertTrue(blueMask(shapeDescription).isNotEmpty())

        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        db.close()
        db = AppDatabaseProvider.openIsolated(context, databaseName)
        rule.runOnIdle {
            opening.intValue++
            shown.value = true
        }
        awaitSaved(6)
        assertTrue("Reopened shapes use retained rendering", blueMask(inkDescription).isNotEmpty())
        val reopened = runBlocking { RoomDocumentRepository(db).load(noteId) }
        assertEquals(6L, reopened.revision)
        assertEquals(original, reopened.pages.single().elements.map { it.envelopeJson })
        rule.onNodeWithText("Shapes").performScrollTo().performClick()
        rule.onNodeWithText("Undo shape").assertIsNotEnabled()
    }

    @Test fun compactWindowCanReachEveryShapeChoiceAndSave() {
        val (noteId, _) = fixture()
        mount(noteId, compact = true)
        chooseShapes()
        EditorShapeTool.entries.forEachIndexed { index, tool ->
            rule.onNodeWithText(tool.label).performScrollTo().performClick()
            rule.onNodeWithContentDescription(shapeDescription).assertHeightIsAtLeast(48.dp)
            draw()
            awaitSaved(index + 1L)
        }
        assertEquals(4, runBlocking { RoomDocumentRepository(db).load(noteId) }
            .pages.single().elements.size)
    }

    @Test fun cancelledTinyAndDisposedGesturesNeverWriteAndFreshGestureStillSaves() {
        val (noteId, _) = fixture()
        mount(noteId)
        chooseShapes()
        val node = rule.onNodeWithContentDescription(shapeDescription)
        node.performTouchInput {
            down(percentOffset(0.35f, 0.22f))
            moveTo(percentOffset(0.55f, 0.42f))
            cancel()
        }
        node.performTouchInput {
            val start = percentOffset(0.35f, 0.22f)
            down(start)
            moveTo(start + Offset(1f, 1f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0L, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
        node.performTouchInput {
            down(percentOffset(0.35f, 0.22f))
            moveTo(percentOffset(0.55f, 0.42f))
        }
        rule.onNodeWithText("Back").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(runBlocking { RoomDocumentRepository(db).load(noteId) }.pages.single().elements.isEmpty())
        rule.runOnIdle {
            opening.intValue++
            shown.value = true
        }
        awaitSaved(0)
        // The old release cannot complete anything in the reopened editor.
        rule.onNodeWithContentDescription(inkDescription).performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(0L, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
        chooseShapes()
        draw()
        awaitSaved(1)
        assertEquals(1, runBlocking { RoomDocumentRepository(db).load(noteId) }.pages.single().elements.size)
    }
}
