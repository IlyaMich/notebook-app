package com.notebook.app.ui.editor.shapes

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeEditingViewportAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val pageId = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val revision = mutableStateOf<Long?>(7L)
    private val editing = mutableStateOf(true)
    private val creationTool = mutableStateOf<EditorShapeTool?>(EditorShapeTool.LINE)
    private val blocked = mutableStateOf(false)
    private val finger = mutableStateOf(true)
    private val shown = mutableStateOf(true)
    private val width = mutableStateOf(320)
    private val pages = mutableStateOf(listOf(page()))
    private val edits = mutableListOf<CompletedShapeEdit>()
    private var selected: EditorShapeSelection? = null
    private var creates = 0
    private var errors = 0
    private var pen = 0
    private var eraser = 0
    // MotionEvent injection/window conversion adds sub-pixel rounding; pure JVM math is exact.
    private val coordinateEpsilonDu = 0.01

    private fun page(x: Double = 100.0, fill: Boolean = false, ink: Boolean = false): StoredPage {
        val raw = CanonicalShapeAdapter.create(id, CanonicalShapeKind.RECTANGLE,
            CanonicalShapeGeometry.Frame(x, 100.0, 250.0, 200.0),
            CanonicalShapeStroke("#2255AA", 3.0, 1.0),
            CanonicalShapeFill("#2255AA", 1.0).takeIf { fill })
        val rows = mutableListOf(StoredElement(id, 0, "shape", raw, 0))
        if (ink) {
            val inkId = UUID.randomUUID().toString()
            rows += StoredElement(inkId, 1, "stroke",
                """{"id":"$inkId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":10,"color":"#111111","opacity":1},"points":[{"x":100,"y":180,"tMs":0},{"x":400,"y":180,"tMs":10}]}}""", 0)
        }
        return StoredPage(pageId, 0, 595.276, 841.890,
            """{"type":"blank","color":"#FFFFFF"}""", 0, rows)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private fun mount(owner: LifecycleOwner? = null) {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides (owner ?: LocalLifecycleOwner.current)) {
                if (shown.value) Box(Modifier.size(width.value.dp, 460.dp)) {
                    ContinuousPageViewport(
                        pages = pages.value.map { PageViewportItem(it.id, it.width, it.height, it.elements.size) },
                        storedPages = pages.value.associateBy { it.id },
                        documentKey = "editing",
                        fingerWritingPreview = finger.value,
                        shapeTool = creationTool.value,
                        shapeInputRevision = revision.value,
                        shapeEditing = editing.value,
                        shapeInputBlocked = blocked.value,
                        onShapeSelectionChanged = { selected = it },
                        onShapeEdited = { edits += it },
                        onShapeInputError = { errors++ },
                        onShapeCreated = { creates++ },
                        onPenStrokeCompleted = { _, _ -> pen++ },
                        onEraserSweepCaptured = { _, _, _ -> eraser++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun node() = rule.onNodeWithContentDescription("Shape editing viewport with ${pages.value.size} pages")
    private fun point(x: Double, y: Double, canvasWidth: Int, pageWidth: Double = 595.276): Offset {
        val gutter = with(rule.density) { 16.dp.toPx() }
        val scale = (canvasWidth - 2 * gutter) / pageWidth
        return Offset((gutter + x * scale).toFloat(), (gutter + y * scale).toFloat())
    }
    private fun begin() = node().performTouchInput {
        down(point(225.0, 100.0, width))
        moveTo(point(265.0, 120.0, width))
    }
    private fun freshMove() = node().performTouchInput {
        down(point(225.0, 100.0, width))
        moveTo(point(265.0, 120.0, width))
        up()
    }

    @Test fun selectionIsReadOnlyAndBodyAndCornerEditsUseSameIdWithoutCreatingInk() {
        mount()
        node().performTouchInput { click(point(225.0, 100.0, width)) }
        rule.waitForIdle()
        assertEquals(id, selected!!.elementId)
        assertTrue(edits.isEmpty())
        freshMove()
        rule.waitForIdle()
        assertEquals(1, edits.size)
        assertEquals(id, edits.single().elementId)
        assertEquals(40.0, edits.single().candidate.transform.coefficients()[4], coordinateEpsilonDu)
        assertEquals(20.0, edits.single().candidate.transform.coefficients()[5], coordinateEpsilonDu)
        node().performTouchInput {
            down(point(100.0, 100.0, width))
            moveTo(point(120.0, 120.0, width))
            up()
        }
        rule.waitForIdle()
        assertEquals(2, edits.size)
        val frame = edits.last().candidate.geometry as CanonicalShapeGeometry.Frame
        assertEquals(120.0, frame.x, coordinateEpsilonDu)
        assertEquals(120.0, frame.y, coordinateEpsilonDu)
        assertEquals(0, creates)
        assertEquals(0, pen)
    }

    @Test fun inactiveShapeModeAndModalSettingsCannotInterruptPenCapture() {
        creationTool.value = null
        mount()
        val ink = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        ink.performTouchInput {
            down(point(225.0, 100.0, width))
            moveTo(point(265.0, 120.0, width))
        }
        rule.runOnIdle { editing.value = false; blocked.value = true; revision.value = null }
        rule.waitForIdle()
        rule.runOnIdle { editing.value = true; blocked.value = false; revision.value = 8L }
        ink.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, pen)
        assertTrue(edits.isEmpty())
        assertEquals(0, creates)
    }

    @Test fun fingerNavigationTapSelectsButDragCannotSubmitAnEdit() {
        finger.value = false
        mount()
        node().performTouchInput { click(point(225.0, 100.0, width)) }
        rule.waitForIdle()
        assertEquals(id, selected!!.elementId)
        freshMove()
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        assertEquals(0, creates)
        assertEquals(0, pen)
    }

    @Test fun stylusEditsWithFingerWritingOffWhileHardwareEraserAndMouseCannotEditOrErase() {
        finger.value = false
        mount()
        val original = pages.value.single()
        val bounds = node().fetchSemanticsNode().boundsInWindow
        val start = point(225.0, 100.0, bounds.width.toInt())
        val end = point(265.0, 120.0, bounds.width.toInt())
        fun dispatch(toolType: Int, source: Int) {
            val downTime = SystemClock.uptimeMillis()
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
                .forEachIndexed { index, action ->
                    val p = if (index == 0) start else end
                    val coordinates = MotionEvent.PointerCoords().apply {
                        x = bounds.left + p.x
                        y = bounds.top + p.y
                        pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                    }
                    val event = MotionEvent.obtain(downTime, downTime + index * 10L, action, 1,
                        arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, source, 0)
                    try {
                        rule.runOnUiThread { rule.activity.dispatchTouchEvent(event) }
                    } finally { event.recycle() }
                }
            rule.waitForIdle()
        }

        dispatch(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS)
        dispatch(MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE)
        assertTrue(edits.isEmpty())
        assertNull(selected)
        dispatch(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS)
        assertEquals(1, edits.size)
        val edit = edits.single()
        assertEquals(id, edit.elementId)
        assertEquals(pageId, edit.pageId)
        assertEquals(7L, edit.expectedRevision)
        assertEquals(original.elements.single().envelopeJson, edit.candidate.original.source.envelopeJson)
        assertEquals(40.0, edit.candidate.transform.coefficients()[4], coordinateEpsilonDu)
        assertEquals(20.0, edit.candidate.transform.coefficients()[5], coordinateEpsilonDu)
        assertEquals(original, pages.value.single())
        assertEquals(0, creates)
        assertEquals(0, pen)
        assertEquals(0, eraser)
        assertEquals(0, errors)
    }

    @Test fun coalescedPageExitWithUnchangedFinalPositionCancelsEditing() {
        mount()
        val original = pages.value.single()
        val bounds = node().fetchSemanticsNode().boundsInWindow
        val origin = Offset(bounds.left, bounds.top)
        rule.runOnUiThread {
            batchedShapeStylusPageExit(
                origin + point(225.0, 100.0, bounds.width.toInt()),
                origin + point(265.0, 120.0, bounds.width.toInt()),
                Offset(bounds.left, bounds.top + bounds.height * 0.4f),
                origin + point(400.0, 200.0, bounds.width.toInt()),
            ) { rule.activity.dispatchTouchEvent(it) }
        }
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        assertEquals(original, pages.value.single())
        assertEquals(0, creates)
        assertEquals(0, pen)
        assertEquals(0, eraser)
        freshMove()
        rule.waitForIdle()
        assertEquals(1, edits.size)
    }

    @Test fun secondFingerPlatformCancelRevisionModeModalAndSizeDiscardUnfinishedEdits() {
        mount()
        begin()
        node().performTouchInput { cancel() }
        begin()
        node().performTouchInput {
            down(1, point(400.0, 400.0, width))
            up(1)
            up(0)
        }
        begin()
        rule.runOnIdle { revision.value = 8L }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { blocked.value = true }
        rule.waitForIdle()
        rule.runOnIdle { blocked.value = false }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { editing.value = false }
        rule.onNodeWithContentDescription("Shape creation viewport with 1 pages").performTouchInput { up() }
        rule.runOnIdle { editing.value = true }
        begin()
        rule.runOnIdle { width.value = 300 }
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        assertEquals(0, creates)
        freshMove()
        rule.waitForIdle()
        assertEquals(1, edits.size)
    }

    @Test fun pauseDisposalAndChangedRenderedPageCannotCompleteAfterResume() {
        val owner = Owner()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        mount(owner)
        begin()
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        rule.waitForIdle()
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { pages.value = listOf(pages.value.single().copy(revision = 99L)) }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { shown.value = false }
        rule.waitForIdle()
        rule.runOnIdle { shown.value = true }
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        freshMove()
        rule.waitForIdle()
        assertEquals(1, edits.size)
    }

    @Test fun clippedImportedShapeMustBeBroughtFullyInsideAndNeverNormalizesOnSelection() {
        pages.value = listOf(page(x = -20.0))
        mount()
        node().performTouchInput { click(point(105.0, 100.0, width)) }
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        assertEquals(0, errors)
        node().performTouchInput {
            down(point(105.0, 100.0, width))
            moveTo(point(125.0, 100.0, width))
            up()
        }
        rule.waitForIdle()
        assertTrue(edits.isEmpty())
        assertEquals(1, errors)
        assertEquals(-20.0, (selected!!.shape.geometry as CanonicalShapeGeometry.Frame).x, 0.0)
        node().performTouchInput {
            down(point(105.0, 100.0, width))
            moveTo(point(135.0, 100.0, width))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, edits.size)
        assertEquals(30.0, edits.single().candidate.transform.coefficients()[4], coordinateEpsilonDu)
        assertEquals(-20.0, (edits.single().candidate.geometry as CanonicalShapeGeometry.Frame).x, 0.0)
    }

    @Test fun mixedPainterOrderIsPreservedDuringLiveEditPreview() {
        pages.value = listOf(page(fill = true, ink = true))
        mount()
        begin()
        val image = node().captureToImage()
        val sample = point(200.0, 180.0, image.width)
        val c = image.toPixelMap()[sample.x.toInt(), sample.y.toInt()]
        assertTrue("Ink above the edited shape must remain above its preview", c.red < 0.15f && c.blue < 0.15f)
        node().performTouchInput { cancel() }
        assertTrue(edits.isEmpty())
    }

    @Test fun firstOfMoreThanSixVisiblePagesIsSelectableAfterDecodeCacheEviction() {
        pages.value = (0 until 8).map { index ->
            val p = UUID.randomUUID().toString()
            val e = UUID.randomUUID().toString()
            StoredPage(p, index.toLong(), 600.0, 20.0,
                """{"type":"blank","color":"#FFFFFF"}""", 0, listOf(
                    StoredElement(e, 0, "shape", CanonicalShapeAdapter.create(e, CanonicalShapeKind.RECTANGLE,
                        CanonicalShapeGeometry.Frame(200.0, 4.0, 100.0, 12.0),
                        CanonicalShapeStroke("#2255AA", 1.0, 1.0), CanonicalShapeFill("#2255AA", 1.0)), 0),
                ))
        }
        mount()
        node().performTouchInput { click(point(250.0, 10.0, width, 600.0)) }
        rule.waitForIdle()
        assertEquals(pages.value.first().id, selected!!.pageId)
        assertTrue(edits.isEmpty())
    }
}
