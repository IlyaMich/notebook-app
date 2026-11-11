package com.notebook.app.ui.editor.shapes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.floor
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeCreationViewportAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val tool = mutableStateOf<EditorShapeTool?>(EditorShapeTool.LINE)
    private val revision = mutableStateOf<Long?>(7L)
    private val finger = mutableStateOf(true)
    private val visible = mutableStateOf(true)
    private val width = mutableStateOf(320)
    private val pageSize = mutableStateOf(595.276 to 841.890)
    private var shapeErrors = 0
    private val completed = mutableListOf<CompletedShapeCreation>()
    private var inkCallbacks = 0
    private var eraserCallbacks = 0
    private val description = "Shape creation viewport with 1 pages"

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private fun mount(owner: LifecycleOwner? = null) {
        rule.setContent {
            val actualOwner = owner ?: LocalLifecycleOwner.current
            CompositionLocalProvider(LocalLifecycleOwner provides actualOwner) {
                if (visible.value) Box(Modifier.size(width.value.dp, 460.dp)) {
                    ContinuousPageViewport(
                        pages = listOf(PageViewportItem("page", pageSize.value.first, pageSize.value.second, 0)),
                        fingerWritingPreview = finger.value,
                        shapeTool = tool.value,
                        shapeInputRevision = revision.value,
                        onShapeCreated = { completed += it },
                        onShapeInputError = { shapeErrors++ },
                        onPenStrokeCompleted = { _, _ -> inkCallbacks++ },
                        onEraserSweepCaptured = { _, _, _ -> eraserCallbacks++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun node() = rule.onNodeWithContentDescription(description)
    private fun begin() = node().performTouchInput {
        down(percentOffset(0.30f, 0.25f))
        moveTo(percentOffset(0.55f, 0.45f))
    }
    private fun freshDrag() = node().performTouchInput {
        down(percentOffset(0.30f, 0.25f))
        moveTo(percentOffset(0.55f, 0.45f))
        up()
    }

    private fun bluePixels(): Int {
        val image = node().captureToImage()
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = pixels[x, y]
            if (c.red < 0.3f && c.green in 0.2f..0.52f && c.blue > 0.55f) count++
        }
        return count
    }

    @Test fun allFourToolsPreviewAndCompleteWithoutInkOrEraserCallbacks() {
        mount()
        EditorShapeTool.entries.forEachIndexed { index, choice ->
            rule.runOnIdle { tool.value = choice }
            begin()
            assertTrue("Live semantic geometry must be visible", bluePixels() > 10)
            assertEquals(index, completed.size)
            node().performTouchInput { up() }
            rule.waitForIdle()
            assertEquals(index + 1, completed.size)
            assertEquals(choice, completed.last().tool)
            assertEquals(7L, completed.last().expectedRevision)
            assertEquals("page", completed.last().pageId)
            assertEquals(0, bluePixels())
        }
        assertEquals(0, inkCallbacks)
        assertEquals(0, eraserCallbacks)
    }

    @Test fun stylusCreatesWithFingerWritingOffWhileHardwareEraserAndMouseRemainIgnored() {
        finger.value = false
        mount()
        val bounds = node().fetchSemanticsNode().boundsInWindow
        fun dispatch(toolType: Int, source: Int) {
            val time = SystemClock.uptimeMillis()
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                this.toolType = toolType
            }
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
                .forEachIndexed { index, action ->
                    val coordinates = MotionEvent.PointerCoords().apply {
                        x = bounds.left + bounds.width * if (index == 0) 0.30f else 0.55f
                        y = bounds.top + bounds.height * if (index == 0) 0.25f else 0.45f
                        pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                    }
                    val event = MotionEvent.obtain(time, time + index * 10L, action, 1,
                        arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, source, 0)
                    try {
                        rule.runOnUiThread { rule.activity.dispatchTouchEvent(event) }
                    } finally { event.recycle() }
                }
            rule.waitForIdle()
        }
        dispatch(MotionEvent.TOOL_TYPE_ERASER, InputDevice.SOURCE_STYLUS)
        dispatch(MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE)
        assertTrue(completed.isEmpty())
        dispatch(MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS)
        assertEquals(1, completed.size)
        assertEquals(0, inkCallbacks)
        assertEquals(0, eraserCallbacks)
    }

    @Test fun liveArrowHeadAndOutlineAreClippedAtOwningPageEdge() {
        tool.value = EditorShapeTool.ARROW
        mount()
        node().performTouchInput {
            val x = width * (16.5f / 320f)
            down(Offset(x, height * 0.25f))
            moveTo(Offset(x, height * 0.45f))
        }
        val image = node().captureToImage()
        val pixels = image.toPixelMap()
        val left = floor(image.width * (16.0 / 320.0)).toInt()
        var blue = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = pixels[x, y]
            if (c.red < 0.3f && c.green in 0.2f..0.52f && c.blue > 0.55f) {
                blue++
                assertTrue("No preview paint may escape the page", x >= left)
            }
        }
        assertTrue(blue > 10)
        node().performTouchInput { cancel() }
    }

    @Test fun pageExitCannotResumeAfterReentryAndFreshDownStillWorks() {
        mount()
        begin()
        node().performTouchInput {
            moveTo(Offset(0f, height * 0.4f))
            moveTo(percentOffset(0.50f, 0.40f))
            up()
        }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, bluePixels())
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun coalescedPageExitWithUnchangedFinalPositionCancelsCreation() {
        mount()
        val bounds = node().fetchSemanticsNode().boundsInWindow
        rule.runOnUiThread {
            batchedShapeStylusPageExit(
                Offset(bounds.left + bounds.width * 0.3f, bounds.top + bounds.height * 0.25f),
                Offset(bounds.left + bounds.width * 0.55f, bounds.top + bounds.height * 0.45f),
                Offset(bounds.left, bounds.top + bounds.height * 0.4f),
                Offset(bounds.left + bounds.width * 0.7f, bounds.top + bounds.height * 0.5f),
            ) { rule.activity.dispatchTouchEvent(it) }
        }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, bluePixels())
        assertEquals(0, inkCallbacks)
        assertEquals(0, eraserCallbacks)
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun secondFingerTakesOverNavigationWithoutCompletingShape() {
        mount()
        begin()
        node().performTouchInput {
            down(1, percentOffset(0.70f, 0.50f))
            moveBy(1, Offset(0f, -20f))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, bluePixels())
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun unrepresentablePreviewCancelsAndReportsWithoutSubmitting() {
        pageSize.value = 1.0e42 to 1.0e42
        mount()
        begin()
        rule.waitForIdle()
        assertEquals(1, shapeErrors)
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, inkCallbacks)
        rule.runOnIdle { pageSize.value = 595.276 to 841.890 }
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun fastReleaseBeforePreviewFrameStillRejectsUnrepresentableGeometry() {
        pageSize.value = 1.0e42 to 1.0e42
        mount()
        freshDrag()
        rule.waitForIdle()
        assertTrue(shapeErrors >= 1)
        assertTrue(completed.isEmpty())
        assertEquals(0, inkCallbacks)
    }

    @Test fun shapeAdmissionChangesCannotInterruptAnActivePenGesture() {
        tool.value = null
        mount()
        val ink = rule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        ink.performTouchInput {
            down(percentOffset(0.30f, 0.25f))
            moveTo(percentOffset(0.55f, 0.45f))
        }
        rule.runOnIdle { revision.value = null }
        rule.waitForIdle()
        rule.runOnIdle { revision.value = 8L }
        ink.performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(1, inkCallbacks)
        assertTrue(completed.isEmpty())
    }

    @Test fun platformCancelAndSubthresholdDragCreateNothing() {
        mount()
        begin()
        node().performTouchInput { cancel() }
        node().performTouchInput {
            val start = percentOffset(0.3f, 0.25f)
            down(start)
            moveTo(start + Offset(1f, 1f))
            up()
        }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, bluePixels())
    }

    @Test fun toolRevisionBusyStateAndResizeEachCancelHeldGesture() {
        mount()
        begin()
        rule.runOnIdle { tool.value = EditorShapeTool.RECTANGLE }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { revision.value = 8L }
        node().performTouchInput { up() }
        begin()
        rule.runOnIdle { revision.value = null }
        node().performTouchInput { up() }
        freshDrag() // Disabled state must not fall through to ink.
        rule.runOnIdle { revision.value = 8L }
        begin()
        rule.runOnIdle { width.value = 300 }
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        assertEquals(0, inkCallbacks)
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun pauseAndLateReleaseDiscardPreviewAndRequireFreshDown() {
        val owner = Owner()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        mount(owner)
        begin()
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        assertEquals(0, bluePixels())
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }

    @Test fun disposalAndFingerModeChangeCancelWithoutLateCompletion() {
        mount()
        begin()
        rule.runOnIdle { finger.value = false }
        node().performTouchInput { up() }
        freshDrag() // Finger navigation remains the default when finger writing is off.
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        rule.runOnIdle { finger.value = true }
        begin()
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle { visible.value = true }
        node().performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(completed.isEmpty())
        freshDrag()
        rule.waitForIdle()
        assertEquals(1, completed.size)
    }
}
