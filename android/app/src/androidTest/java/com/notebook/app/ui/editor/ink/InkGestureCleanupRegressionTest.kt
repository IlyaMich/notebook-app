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

/**
 * UI/state regression for CP3B. Native graphics leakage must additionally be
 * checked manually on an emulator (semantics do not expose Ink's drawing layer).
 */
class InkGestureCleanupRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    private fun session(): InkGestureDemoSession =
        ViewModelProvider(rule.activity)[InkGestureDemoSession::class.java]

    @Test fun errorStatusDoesNotResizeViewportAndNavigationControlsStayUsable() {
        rule.waitForIdle()
        val before = rule.onNodeWithTag("inkGestureCanvas").fetchSemanticsNode().boundsInRoot
        rule.runOnUiThread { session().reject("Outside page or crosses page boundary") }
        rule.waitForIdle()
        rule.onNodeWithText("Ink conversion: Outside page or crosses page boundary")
            .assertExists()
        val after = rule.onNodeWithTag("inkGestureCanvas").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.bottom, after.bottom, 1f)
        rule.onNodeWithText("Enable finger drawing").performClick()
        rule.onNodeWithText("Mode: Finger draw").assertExists()
    }

    @Test fun clearResetsCanonicalStateAndInvalidatesNativeViewGeneration() {
        val value = InkViewportPageAdapter.Captured(
            pageId = "a4-portrait",
            canonical = CanonicalInkStroke(
                brush = CanonicalInkBrush("pen", 2.0, "#2255AA", 1.0),
                points = listOf(CanonicalInkPoint(12.0, 20.0, 0)),
            ),
        )
        rule.runOnUiThread {
            session().accept(value)
            session().reject("Outside page or crosses page boundary")
        }
        rule.waitForIdle()
        rule.onNodeWithText("Stored strokes: 1; Rejected: 1").assertExists()
        val oldGeneration = session().inkLayerGeneration
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Stored strokes: 0; Rejected: 0").assertExists()
        rule.onNodeWithText("Ink conversion: Ready").assertExists()
        rule.runOnUiThread {
            assertTrue(session().strokes.isEmpty())
            assertEquals(oldGeneration + 1, session().inkLayerGeneration)
        }
    }

    @Test fun repeatedRejectionAndClearDoNotLeaveBrokenControls() {
        repeat(3) {
            rule.runOnUiThread { session().reject("Outside page or crosses page boundary") }
            rule.waitForIdle()
            rule.onNodeWithText("Clear").performClick()
            rule.onNodeWithText("Stored strokes: 0; Rejected: 0").assertExists()
        }
        rule.onNodeWithText("Enable finger drawing").performClick()
        rule.onNodeWithText("Use finger navigation").performClick()
        rule.onNodeWithTag("inkGestureCanvas").assertExists()
    }
}
