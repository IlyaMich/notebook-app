package com.notebook.app.ui.editor.ink

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Rule
import org.junit.Test

/** UI smoke/regression; physical stylus and palm tests remain manual. */
class InkGestureArbitrationActivityTest {
    @get:Rule val rule = createAndroidComposeRule<InkGestureArbitrationActivity>()

    @Test fun probeStartsInFingerNavigationModeAndToggleIsVisible() {
        rule.onNodeWithText("Mode: Finger navigate").assertExists()
        rule.onNodeWithTag("inkGestureCanvas").assertExists()
        rule.onNodeWithText("Enable finger drawing").performClick()
        rule.onNodeWithText("Mode: Finger draw").assertExists()
        rule.onNodeWithText("Use finger navigation").performClick()
        rule.onNodeWithText("Mode: Finger navigate").assertExists()
    }

    @Test fun touchSwipeInNavigationModeDoesNotCreateDryInk() {
        rule.onNodeWithTag("inkGestureCanvas").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithText("Stored strokes: 0; Rejected: 0").assertExists()
    }
}
