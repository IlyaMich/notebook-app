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
 * UI/state checks, not native pixel or latency measurements. Complete the manual
 * wet/dry/cancel checklist on the emulator to test the actual native ink layer.
 */
class InkGestureHandoffRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    private fun session(): InkGestureDemoSession =
        ViewModelProvider(rule.activity)[InkGestureDemoSession::class.java]

    private fun syntheticCapturedStroke() = InkViewportPageAdapter.Captured(
        pageId = "a4-portrait",
        canonical = CanonicalInkStroke(
            brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
            points = listOf(CanonicalInkPoint(30.0, 40.0, 0L)),
        ),
    )

    @Test fun cancelNativeLayerPreservesCanonicalStrokeAndInvalidatesCallbacks() {
        rule.runOnUiThread { session().accept(syntheticCapturedStroke()) }
        rule.waitForIdle()
        val generation = session().inkLayerGeneration
        rule.onNodeWithText("Cancel wet ink (0)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel wet ink (1)").assertExists()
        rule.onNodeWithText("Stored strokes: 1; Rejected: 0").assertExists()
        rule.runOnUiThread {
            assertEquals(generation + 1, session().inkLayerGeneration)
            assertEquals(1, session().strokes.size)
        }
    }

    @Test fun diagnosticsSwitchWithoutChangingViewportBounds() {
        rule.waitForIdle()
        val before = rule.onNodeWithTag("inkGestureCanvas")
            .fetchSemanticsNode().boundsInRoot
        val handoff = InkHandoffDiagnostics.Result(
            count = 2, maxPositionErrorPx = 0.125,
            meanPositionErrorPx = 0.0625, widthErrorPx = 0.0,
            sampleTimesPreserved = true, optionalPresencePreserved = true,
        )
        rule.runOnUiThread { session().accept(syntheticCapturedStroke(), handoff) }
        rule.waitForIdle()
        rule.onNodeWithText("Handoff geometry: 0.125 px max (PASS)").assertExists()
        val after = rule.onNodeWithTag("inkGestureCanvas")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.bottom, after.bottom, 1f)
    }

    @Test fun clearResetsDiagnosticAndCancellationStateWithoutLeakingStrokes() {
        rule.runOnUiThread { session().accept(syntheticCapturedStroke()) }
        rule.onNodeWithText("Cancel wet ink (0)").performClick()
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Stored strokes: 0; Rejected: 0").assertExists()
        rule.onNodeWithText("Handoff geometry: not measured").assertExists()
        rule.onNodeWithText("Cancel wet ink (0)").assertExists()
        rule.runOnUiThread { assertTrue(session().strokes.isEmpty()) }
    }
}
