package com.notebook.app.ui.editor.viewport

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class ContinuousPageViewportTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun mixedSizePreviewIsAccessibleAsTwoPages() {
        composeRule.setContent {
            MaterialTheme {
                ContinuousPageViewport(
                    listOf(
                        PageViewportItem("portrait", 612.0, 792.0, 0),
                        PageViewportItem("landscape", 792.0, 612.0, 2),
                    ),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Read-only note viewport with 2 pages")
            .assertExists()
    }

    @Test fun invalidPageDimensionsShowSafeFailure() {
        composeRule.setContent {
            MaterialTheme {
                ContinuousPageViewport(listOf(PageViewportItem("bad", -10.0, 100.0, 0)))
            }
        }
        composeRule.onNodeWithText("Cannot display invalid page dimensions").assertExists()
    }
}
