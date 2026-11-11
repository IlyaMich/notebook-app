package com.notebook.app.ui.editor.shapes

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.screens.NoteEditorScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual screen controls/pointers -> guarded replacement/history -> Room close/reopen. */
@RunWith(AndroidJUnit4::class)
class EditorShapeEditingProductionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private lateinit var noteId: String
    private val shown = mutableStateOf(true)
    private val opening = mutableIntStateOf(0)
    private val editDescription = "Shape editing viewport with 1 pages"
    private val createDescription = "Shape creation viewport with 1 pages"
    private var revision = 0L
    private var cameraYPx: Double? = null

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "edt12-c5-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, name)
        noteId = runBlocking {
            LibraryRepositoryFactory(db).noteLifecycle().createNote(
                null, "Synthetic reopened shapes", InitialPageSpec.a4PortraitBlank(),
            ).id
        }
    }
    @After fun after() { db.close(); context.deleteDatabase(name) }

    private fun mount() {
        rule.setContent {
            if (shown.value) {
                val session: NoteEditorSessionViewModel = viewModel(key = "shape-edit-${opening.intValue}")
                Box(Modifier.size(360.dp, 800.dp)) {
                    NoteEditorScreen(noteId, RoomNoteRepository(db), RoomDocumentRepository(db),
                        session, 0, onBack = { shown.value = false })
                }
            }
        }
        awaitSaved()
    }

    private fun awaitSaved() {
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Saved locally - revision $revision").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }
    private fun committed() { revision++; awaitSaved() }
    private fun page() = runBlocking { RoomDocumentRepository(db).load(noteId) }.pages.single()
    private fun shape(index: Int) = page().let { CanonicalShapeAdapter.decode(it.id, it.elements[index]) }
    private fun node() = rule.onNodeWithContentDescription(editDescription)
    private fun point(page: DuPoint, canvasWidth: Int): Offset {
        val gutter = with(rule.density) { 16.dp.toPx() }
        val scale = (canvasWidth - 2 * gutter) / 595.276
        return Offset((gutter + page.x * scale).toFloat(),
            ((cameraYPx ?: gutter.toDouble()) + page.y * scale).toFloat())
    }
    private fun body(shape: StoredCanonicalShape): DuPoint {
        val p = when (val g = shape.geometry) {
            is CanonicalShapeGeometry.Endpoints -> DuPoint((g.x1 + g.x2) / 2, (g.y1 + g.y2) / 2)
            is CanonicalShapeGeometry.Frame -> DuPoint(g.x + g.width / 2, g.y)
        }
        return shape.elementTransform.mapOrNull(p)!!
    }

    /**
     * Toolbar remeasurement preserves the camera anchor rather than resetting its top gutter.
     * Locate actual rendered outline paint; do not duplicate the camera-restoration algorithm.
     * Input remains a black-box interaction with the real retained renderer and hit tester.
     */
    private fun locateRenderedShape(shape: StoredCanonicalShape) {
        val image = node().captureToImage()
        val pixels = image.toPixelMap()
        val gutter = with(rule.density) { 16.dp.toPx() }
        val scale = (image.width - 2 * gutter) / 595.276
        val anchor = body(shape)
        val targetX = gutter + anchor.x * scale
        val color = android.graphics.Color.parseColor(shape.stroke.colorHex)
        val expected = doubleArrayOf(android.graphics.Color.red(color).toDouble(),
            android.graphics.Color.green(color).toDouble(), android.graphics.Color.blue(color).toDouble())
        val low = expected.min()
        val span = expected.max() - low
        require(span > 0.0) { "This fixture requires a colored outline" }
        val normalized = expected.map { (it - low) / span }
        val radius = with(rule.density) { 3.dp.toPx() }.toInt().coerceAtLeast(1)
        var bestDistance = Double.POSITIVE_INFINITY
        var bestY: Int? = null
        var bestChroma = -1.0
        val closed = shape.geometry is CanonicalShapeGeometry.Frame
        for (x in (targetX.toInt() - radius).coerceAtLeast(0)..(targetX.toInt() + radius).coerceAtMost(image.width - 1)) {
            for (y in 0 until image.height) {
                val c = pixels[x, y]
                val channels = doubleArrayOf(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())
                val minimum = channels.min()
                val chroma = channels.max() - minimum
                if (chroma < 0.08) continue // paper, handles' white centers and diagnostic gray text
                val mismatch = channels.indices.sumOf { abs((channels[it] - minimum) / chroma - normalized[it]) }
                if (mismatch > 0.35) continue
                val distance = abs(x - targetX)
                if (distance < bestDistance || (distance == bestDistance &&
                        (if (closed) bestY == null || y < bestY!! else chroma > bestChroma))) {
                    bestDistance = distance
                    bestY = y
                    bestChroma = chroma
                }
            }
        }
        assertNotNull("Fixture outline must be visibly rendered before targeting it", bestY)
        cameraYPx = requireNotNull(bestY) - anchor.y * scale
    }

    private fun createFour() {
        rule.onNodeWithText("Shapes").performScrollTo().performClick()
        rule.onNodeWithText("Enable finger shape drawing").performClick()
        EditorShapeTool.entries.forEachIndexed { index, tool ->
            rule.onNodeWithText(tool.label).performScrollTo().performClick()
            rule.onNodeWithContentDescription(createDescription).performTouchInput {
                down(percentOffset(0.13f + index * 0.21f, 0.25f))
                moveTo(percentOffset(0.26f + index * 0.21f, 0.45f))
                up()
            }
            committed()
        }
    }
    private fun enterEdit() {
        rule.onNodeWithText("Shapes").performScrollTo().performClick()
        rule.onNodeWithText("Edit shapes").performScrollTo().performClick()
        if (rule.onAllNodesWithText("Enable finger shape editing").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Enable finger shape editing").performClick()
        rule.waitForIdle()
    }
    private fun reopen() {
        cameraYPx = null
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        rule.runOnIdle { opening.intValue++; shown.value = true }
        awaitSaved()
    }

    @Test fun allFourReopenedShapesMoveResizeAndStyleWithExactUndoRedoAndStableIds() {
        mount()
        createFour()
        val originals = page().elements.map { it.envelopeJson }
        val ids = page().elements.map { it.id }
        reopen()
        assertEquals(originals, page().elements.map { it.envelopeJson })
        enterEdit()
        var beforeLastWidth = ""
        var afterLastWidth = ""

        EditorShapeTool.entries.forEachIndexed { index, tool ->
            val before = shape(index)
            locateRenderedShape(before)
            val start = body(before)
            node().performTouchInput {
                down(point(start, width))
                moveTo(point(start, width) + Offset(0f, with(rule.density) { 12.dp.toPx() }))
                up()
            }
            committed()
            val moved = shape(index)
            assertEquals(before.geometry, moved.geometry)
            assertTrue(moved.elementTransform.coefficients()[5] > before.elementTransform.coefficients()[5])
            assertEquals(before.source.id, moved.source.id)

            val handle = moved.elementTransform.mapOrNull(ShapeEditingGeometry.handles(moved.geometry).first())!!
            node().performTouchInput {
                down(point(handle, width))
                moveTo(point(handle, width) + Offset(
                    with(rule.density) { 12.dp.toPx() }, with(rule.density) { 12.dp.toPx() }))
                up()
            }
            committed()
            val resized = shape(index)
            assertNotEquals(moved.geometry, resized.geometry)
            assertEquals(moved.elementTransform.coefficients(), resized.elementTransform.coefficients())
            assertEquals(ids, page().elements.map { it.id })

            rule.onNodeWithText("Shape style").performScrollTo().assertIsEnabled().performClick()
            rule.onNodeWithText("Outline red").performScrollTo().performClick()
            committed()
            beforeLastWidth = shape(index).source.envelopeJson
            rule.onNodeWithText("Thick").performScrollTo().performClick()
            committed()
            afterLastWidth = shape(index).source.envelopeJson
            if (tool == EditorShapeTool.RECTANGLE || tool == EditorShapeTool.ELLIPSE) {
                rule.onNodeWithText("Fill yellow").performScrollTo().performClick()
                committed()
            } else {
                rule.onAllNodesWithText("Fill yellow").assertCountEquals(0)
            }
            rule.onNodeWithText("Done").performClick()
            val styled = shape(index)
            assertEquals("#CC3333", styled.stroke.colorHex)
            assertEquals(6.0, styled.stroke.widthDu, 0.0)
            assertEquals(before.stroke.opacity, styled.stroke.opacity, 0.0)
            assertEquals(before.arrowHead, styled.arrowHead)
            assertEquals(before.source.id, styled.source.id)
        }
        assertEquals(22L, revision)
        rule.onNodeWithText("Undo shape").performScrollTo().performClick()
        committed()
        assertEquals(beforeLastWidth, shape(3).source.envelopeJson)
        rule.onNodeWithText("Redo shape").performScrollTo().performClick()
        committed()
        assertEquals(afterLastWidth, shape(3).source.envelopeJson)
        val finalRows = page().elements.map { it.envelopeJson }
        assertEquals(listOf(0L, 1L, 2L, 3L), page().elements.map { it.orderIndex })
        reopen()
        assertEquals(finalRows, page().elements.map { it.envelopeJson })
        enterEdit()
        val reopened = shape(0)
        locateRenderedShape(reopened)
        node().performTouchInput { click(point(body(reopened), width)) }
        rule.waitForIdle()
        rule.onNodeWithText("Shape style").assertIsEnabled().performClick()
        rule.onNodeWithText("Outline black").performScrollTo().performClick()
        committed()
        rule.onNodeWithText("Done").performClick()
        assertEquals("#222222", shape(0).stroke.colorHex)
        assertEquals(ids, page().elements.map { it.id })
        assertEquals(revision, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
    }

    @Test fun transformedImportedArrowUsesInverseHandlesAndExactUndoThenEditsAfterReopen() {
        val owner = page()
        val id = UUID.randomUUID().toString()
        val raw = CanonicalShapeAdapter.create(id, CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(40.0, 20.0, 180.0, 20.0),
            CanonicalShapeStroke("#2255AA", 3.0, 0.65),
            CanonicalShapeFill("#ABCDEF", 0.35), CanonicalShapeArrowHead(12.0, 420.0))
        val decoded = CanonicalShapeAdapter.decode(owner.id, StoredElement(id, 0, "shape", raw, 0))
        val affine = listOf(1.5, 0.25, -0.5, 1.0, 80.0, 20.0)
        val imported = "  " + CanonicalShapeAdapter.replace(decoded,
            elementTransform = requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(affine))) + "  "
        runBlocking { RoomDocumentRepository(db).commit(noteId, DocumentEdit.InsertElements(owner.id, listOf(imported))) }
        revision = 1
        mount()
        enterEdit()
        val before = shape(0)
        locateRenderedShape(before)
        node().performTouchInput {
            down(point(body(before), width))
            moveBy(Offset(0f, with(rule.density) { 12.dp.toPx() }))
            up()
        }
        committed()
        val moved = shape(0)
        val movedJson = moved.source.envelopeJson
        assertEquals(affine.take(4), moved.elementTransform.coefficients().take(4))
        assertEquals(before.geometry, moved.geometry)
        assertEquals(before.arrowHead, moved.arrowHead)
        val handle = moved.elementTransform.mapOrNull(ShapeEditingGeometry.handles(moved.geometry).first())!!
        val gutter = with(rule.density) { 16.dp.toPx() }
        val scale = (node().fetchSemanticsNode().boundsInRoot.width - 2 * gutter) / 595.276
        val deltaDu = with(rule.density) { 12.dp.toPx() } / scale
        node().performTouchInput {
            down(point(handle, width))
            moveBy(Offset(with(rule.density) { 12.dp.toPx() }, with(rule.density) { 12.dp.toPx() }))
            up()
        }
        committed()
        val resized = shape(0)
        val geometry = resized.geometry as CanonicalShapeGeometry.Endpoints
        assertEquals(40.0 + deltaDu * 1.5 / 1.625, geometry.x1, 0.01)
        assertEquals(20.0 + deltaDu * 1.25 / 1.625, geometry.y1, 0.01)
        assertEquals(180.0, geometry.x2, 0.0)
        assertEquals(20.0, geometry.y2, 0.0)
        assertEquals(moved.elementTransform.coefficients(), resized.elementTransform.coefficients())
        rule.onNodeWithText("Undo shape").performScrollTo().performClick()
        committed()
        assertEquals(movedJson, shape(0).source.envelopeJson)
        rule.onNodeWithText("Undo shape").performScrollTo().performClick()
        committed()
        assertEquals(imported, shape(0).source.envelopeJson)
        reopen()
        enterEdit()
        val reopened = shape(0)
        locateRenderedShape(reopened)
        node().performTouchInput { click(point(body(reopened), width)) }
        rule.onNodeWithText("Shape style").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Outline red").performScrollTo().performClick()
        committed()
        rule.onNodeWithText("Done").performClick()
        val styled = shape(0)
        assertEquals(affine, styled.elementTransform.coefficients())
        assertEquals(0.65, styled.stroke.opacity, 0.0)
        assertEquals(CanonicalShapeArrowHead(12.0, 420.0), styled.arrowHead)
        assertEquals(CanonicalShapeFill("#ABCDEF", 0.35), styled.fill)
        assertEquals(id, styled.source.id)
        assertEquals(owner.id, styled.pageId)
    }

    @Test fun selectionAndNoOpStyleDoNotIncrementRevisionOrHistoryAndCancelLeavesSourceExact() {
        mount()
        createFour()
        val original = page().elements.map { it.envelopeJson }
        reopen()
        enterEdit()
        val selected = shape(1)
        locateRenderedShape(selected)
        node().performTouchInput { click(point(body(selected), width)) }
        rule.waitForIdle()
        assertEquals(4L, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
        rule.onNodeWithText("Undo shape").assertIsNotEnabled()
        rule.onNodeWithText("Shape style").performScrollTo().performClick()
        rule.onNodeWithText("Medium").performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertEquals(original, page().elements.map { it.envelopeJson })
        assertEquals(4L, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
        rule.onNodeWithText("Undo shape").assertIsNotEnabled()
        node().performTouchInput {
            down(point(body(selected), width))
            moveBy(Offset(0f, with(rule.density) { 20.dp.toPx() }))
            cancel()
        }
        rule.waitForIdle()
        assertEquals(original, page().elements.map { it.envelopeJson })
        assertEquals(4L, runBlocking { RoomDocumentRepository(db).load(noteId) }.revision)
    }
}
