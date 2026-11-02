package com.notebook.app.ui.editor.ink

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Rejected completed strokes must not remount Ink. */
class InkGestureRejectionLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    private fun session(): InkGestureDemoSession =
        ViewModelProvider(rule.activity)[InkGestureDemoSession::class.java]

    private fun validStroke() = InkViewportPageAdapter.Captured(
        pageId = "a4-portrait",
        canonical = CanonicalInkStroke(
            brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
            points = listOf(CanonicalInkPoint(50.0, 70.0, 0L)),
        ),
    )

    @Test fun rejectionNeverRemountsNativeInkLayer() {
        rule.waitForIdle()
        val original = session().inkLayerGeneration
        rule.runOnUiThread { session().reject("Crosses page boundary") }
        rule.waitForIdle()
        assertEquals(original, session().inkLayerGeneration)
        assertEquals(1, session().rejected)
        rule.onNodeWithText("Ink conversion: Crosses page boundary").assertExists()
    }

    @Test fun repeatedRejectionsKeepOneNativeLayerAndAllowAcceptance() {
        rule.waitForIdle()
        val original = session().inkLayerGeneration
        rule.runOnUiThread {
            repeat(10) { session().reject("Crosses page boundary") }
            session().accept(validStroke())
        }
        rule.waitForIdle()
        assertEquals(original, session().inkLayerGeneration)
        assertEquals(10, session().rejected)
        assertEquals(1, session().strokes.size)
        rule.onNodeWithTag("inkGestureCanvas").assertExists()
    }

    @Test fun clearAfterRejectionPreservesNativeLayer() {
        rule.waitForIdle()
        val original = session().inkLayerGeneration
        rule.runOnUiThread { session().reject("Crosses page boundary") }
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        assertEquals(original, session().inkLayerGeneration)
        assertEquals(0, session().rejected)
        assertTrue(session().strokes.isEmpty())
    }

    @Test fun cancelAfterRejectionRemountsExactlyOnce() {
        rule.waitForIdle()
        val original = session().inkLayerGeneration
        rule.runOnUiThread { session().reject("Crosses page boundary") }
        rule.onNodeWithText("Cancel wet ink (0)").performClick()
        rule.waitForIdle()
        assertEquals(original + 1, session().inkLayerGeneration)
        assertEquals(1, session().cancellations)
    }

    @Test fun togglingDrawingModeStillRemountsOutsideNativeCallback() {
        rule.waitForIdle()
        val original = session().inkLayerGeneration
        rule.onNodeWithText("Enable finger drawing").performClick()
        rule.waitForIdle()
        assertEquals(original + 1, session().inkLayerGeneration)
        rule.onNodeWithText("Use finger navigation").performClick()
        rule.waitForIdle()
        assertEquals(original + 2, session().inkLayerGeneration)
    }
}
