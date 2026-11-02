package com.notebook.app.ui.editor.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * REAL injected finger-input reproduction of the rejection -> valid -> rejection
 * sequence. Unlike state-only tests this exercises Jetpack Ink's native callback.
 * Run on an emulator and inspect manual visuals as well.
 */
class InkGestureRejectedInputSequenceTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    private fun session(): InkGestureDemoSession =
        ViewModelProvider(rule.activity)[InkGestureDemoSession::class.java]

    @Test fun rejectedBoundaryThenValidDrawingCanRepeatWithoutRemount() {
        rule.onNodeWithText("Enable finger drawing").performClick()
        rule.waitUntil(5_000L) { session().visiblePageRects.containsKey("a4-portrait") }
        val rect = rule.runOnIdle { checkNotNull(session().visiblePageRects["a4-portrait"]) }
        assertTrue("Need left margin to make an outside-boundary stroke", rect.left >= 8.0)
        assertTrue("Need a visible page area", rect.right - rect.left > 90.0 && rect.bottom - rect.top > 70.0)

        val y = (rect.top + (rect.bottom - rect.top) * 0.20).toFloat()
        val xInside = (rect.left + (rect.right - rect.left) * 0.40).toFloat()
        val xOtherInside = (rect.left + (rect.right - rect.left) * 0.58).toFloat()
        val xOutside = (rect.left / 2.0).toFloat()
        val firstGeneration = rule.runOnIdle { session().inkLayerGeneration }

        repeat(2) { index ->
            rule.onNodeWithTag("inkGestureCanvas").performTouchInput {
                swipe(Offset(xInside, y), Offset(xOutside, y), durationMillis = 280L)
            }
            rule.waitUntil(8_000L) { session().rejected >= index + 1 }
            assertEquals(firstGeneration, rule.runOnIdle { session().inkLayerGeneration })

            rule.onNodeWithTag("inkGestureCanvas").performTouchInput {
                swipe(Offset(xInside, y + 8f), Offset(xOtherInside, y + 8f), durationMillis = 280L)
            }
            rule.waitUntil(8_000L) { session().strokes.size >= index + 1 }
            assertEquals(index + 1, rule.runOnIdle { session().strokes.size })
            assertEquals(firstGeneration, rule.runOnIdle { session().inkLayerGeneration })
        }
    }
}
