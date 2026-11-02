package com.notebook.app.ui.editor.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real pointer-input + HWUI stress test for EDT-05's zoomed multi-stroke Clear
 * crash. Native SIGSEGV cannot be caught by JUnit; the process must survive.
 */
class InkGestureClearZoomRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    private fun session(): InkGestureDemoSession =
        ViewModelProvider(rule.activity)[InkGestureDemoSession::class.java]

    private fun visibleA4(): InkClipGeometry.RectPx {
        rule.waitUntil(8_000L) { session().visiblePageRects.containsKey("a4-portrait") }
        return rule.runOnIdle { checkNotNull(session().visiblePageRects["a4-portrait"]) }
    }

    private fun drawValid(count: Int) {
        repeat(count) { index ->
            val rect = visibleA4()
            assertTrue("Visible page too narrow for synthetic writing", rect.right - rect.left > 70.0)
            assertTrue("Visible page too short for synthetic writing", rect.bottom - rect.top > 70.0)
            val width = (rect.right - rect.left).toFloat()
            val height = (rect.bottom - rect.top).toFloat()
            val y = rect.top.toFloat() + height * (0.20f + 0.09f * (index % 5))
            val x0 = rect.left.toFloat() + width * 0.35f
            val x1 = rect.left.toFloat() + width * 0.58f
            val before = rule.runOnIdle { session().strokes.size }
            rule.onNodeWithTag("inkGestureCanvas").performTouchInput {
                swipe(Offset(x0, y), Offset(x1, y), durationMillis = 240L)
            }
            rule.waitUntil(8_000L) { session().strokes.size >= before + 1 }
        }
    }

    @Test fun zoomedMultipleCompletedStrokesCanClearRepeatedlyWithoutRemount() {
        val original = rule.runOnIdle { session().inkLayerGeneration }
        // Zoom in using two fingers while in default navigation mode.
        val rect = visibleA4()
        val cx = ((rect.left + rect.right) / 2.0).toFloat()
        val cy = ((rect.top + rect.bottom) / 2.0).toFloat()
        rule.onNodeWithTag("inkGestureCanvas").performTouchInput {
            pinch(
                Offset(cx - 25f, cy), Offset(cx - 55f, cy),
                Offset(cx + 25f, cy), Offset(cx + 55f, cy),
                durationMillis = 400L,
            )
        }
        rule.waitUntil(8_000L) { session().navigationUpdates > 0 }
        rule.onNodeWithText("Enable finger drawing").performClick()
        val drawingGeneration = rule.runOnIdle { session().inkLayerGeneration }
        assertEquals(original + 1, drawingGeneration)

        repeat(2) {
            drawValid(4)
            rule.onNodeWithText("Clear").performClick()
            rule.waitForIdle()
            assertEquals(0, rule.runOnIdle { session().strokes.size })
            assertEquals(drawingGeneration, rule.runOnIdle { session().inkLayerGeneration })
        }
        // Drawing must still work after Clear, with no native layer remount.
        drawValid(1)
        assertEquals(1, rule.runOnIdle { session().strokes.size })
    }

    @Test fun emptyClearDoesNotRemountInk() {
        rule.waitForIdle()
        val generation = rule.runOnIdle { session().inkLayerGeneration }
        repeat(3) { rule.onNodeWithText("Clear").performClick() }
        assertEquals(generation, rule.runOnIdle { session().inkLayerGeneration })
        assertEquals(0, rule.runOnIdle { session().strokes.size })
    }
}
