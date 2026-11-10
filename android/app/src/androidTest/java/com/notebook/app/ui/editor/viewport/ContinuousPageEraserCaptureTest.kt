package com.notebook.app.ui.editor.viewport

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.ink.EditorInkTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** EDT-16 1D-4: captures page-local sweeps without any Room or document writes. */
@RunWith(AndroidJUnit4::class)
class ContinuousPageEraserCaptureTest {
    @get:Rule val rule = createComposeRule()

    private val description = "Read-only note viewport with 1 pages"
    private val page = PageViewportItem("page-1", 595.276, 841.890, 0)
    private data class Sweep(val pageId: String, val points: List<DuPoint>, val radius: Double)
    private val captures = mutableListOf<Sweep>()
    private var penWrites = 0

    private fun mount(tool: androidx.compose.runtime.State<EditorInkTool> = mutableStateOf(EditorInkTool.ERASER)) {
        rule.setContent {
            ContinuousPageViewport(
                pages = listOf(page),
                documentKey = "edt16-1d4-tests",
                fingerWritingPreview = true,
                selectedTool = tool.value,
                onEraserSweepCaptured = { pageId, points, radius ->
                    captures += Sweep(pageId, points, radius)
                },
                onPenStrokeCompleted = { _, _ -> penWrites++ },
            )
        }
        rule.waitForIdle()
    }

    private fun gesture() {
        rule.onNodeWithContentDescription(description).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.47f, 0.28f))
            up()
        }
        rule.waitForIdle()
    }

    @Test fun completedGestureCapturesOnePageLocalSweepWithoutPenWrites() {
        mount()
        gesture()
        assertEquals(0, penWrites)
        assertEquals(1, captures.size)
        val sweep = captures.single()
        assertEquals("page-1", sweep.pageId)
        assertTrue(sweep.radius.isFinite() && sweep.radius > 0.0)
        assertTrue(sweep.points.size >= 2)
        assertTrue(sweep.points.all {
            it.x >= 0.0 && it.x <= page.widthDu &&
                it.y >= 0.0 && it.y <= page.heightDu
        })
        assertTrue(sweep.points.first() != sweep.points.last())
    }

    @Test fun normalPenCaptureRemainsUnchanged() {
        mount(mutableStateOf(EditorInkTool.PEN))
        gesture()
        assertEquals(0, captures.size)
        assertEquals(1, penWrites)
    }

    @Test fun cancelledPointerNeverReportsCompletion() {
        mount()
        rule.onNodeWithContentDescription(description).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.47f, 0.28f))
            cancel()
        }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        assertEquals(0, penWrites)
    }

    @Test fun secondFingerTakeoverCancelsEraserCapture() {
        mount()
        rule.onNodeWithContentDescription(description).performTouchInput {
            down(0, percentOffset(0.35f, 0.20f))
            moveTo(0, percentOffset(0.45f, 0.27f))
            down(1, percentOffset(0.65f, 0.30f))
            moveTo(0, percentOffset(0.48f, 0.28f))
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
    }

    @Test fun leavingOwningPageCancelsEntireGesture() {
        mount()
        rule.onNodeWithContentDescription(description).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(Offset(1f, 80f)) // left canvas gutter, outside the page
            up()
        }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
    }

    @Test fun toolSwitchDuringGestureCancelsEraserAndFreshPenStillWorks() {
        val tool = mutableStateOf(EditorInkTool.ERASER)
        mount(tool)
        val node = rule.onNodeWithContentDescription(description)
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.47f, 0.28f))
        }
        rule.runOnIdle { tool.value = EditorInkTool.PEN }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        gesture()
        assertEquals(1, penWrites)
    }

    @Test fun disposalDiscardsUnfinishedEraserAndFreshGestureWorks() {
        val visible = mutableStateOf(true)
        rule.setContent {
            if (visible.value) {
                ContinuousPageViewport(
                    pages = listOf(page),
                    documentKey = "edt16-disposal",
                    selectedTool = EditorInkTool.ERASER,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { id, points, radius ->
                        captures += Sweep(id, points, radius)
                    },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription(description)
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.47f, 0.28f))
        }
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(description).performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        gesture()
        assertEquals(1, captures.size)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test fun lifecyclePauseRejectsLateReleaseAndPermitsNewGesture() {
        val owner = Owner()
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ContinuousPageViewport(
                    pages = listOf(page),
                    documentKey = "edt16-lifecycle",
                    selectedTool = EditorInkTool.ERASER,
                    fingerWritingPreview = true,
                    onEraserSweepCaptured = { id, points, radius ->
                        captures += Sweep(id, points, radius)
                    },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithContentDescription(description)
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.47f, 0.28f))
        }
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        rule.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        rule.waitForIdle()
        node.performTouchInput { up() }
        rule.waitForIdle()
        assertTrue(captures.isEmpty())
        gesture()
        assertEquals(1, captures.size)
    }
}
