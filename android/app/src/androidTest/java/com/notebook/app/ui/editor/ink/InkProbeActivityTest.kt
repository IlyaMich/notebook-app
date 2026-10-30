package com.notebook.app.ui.editor.ink

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke check for the debug-only probe. Actual wet ink requires device input. */
@RunWith(AndroidJUnit4::class)
class InkProbeActivityTest {
    @get:Rule val composeRule = createAndroidComposeRule<InkProbeActivity>()

    @Test
    fun opensWithEmptyInMemoryStrokeList() {
        composeRule.onNodeWithText("Ink probe (debug only)").assertExists()
        composeRule.onNodeWithText("0 finished strokes").assertExists()
        composeRule.onNodeWithText("In memory only - nothing is saved").assertExists()
    }
}
