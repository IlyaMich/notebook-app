package com.notebook.app.ui.editor.viewport

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
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

    @Test fun tapTargetsPageAndGutterTapClearsTheReadOnlyTarget() {
        composeRule.setContent {
            MaterialTheme {
                ContinuousPageViewport(listOf(PageViewportItem("page", 612.0, 1600.0, 0)))
            }
        }
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithContentDescription("Read-only note viewport with 1 pages")
        viewport.performTouchInput { click() }
        viewport.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Zoom 100 percent; selected page 1",
        ))
        // Top-left is in the 16-dp canvas gutter, not on the page.
        viewport.performTouchInput { click(Offset(1f, 1f)) }
        viewport.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Zoom 100 percent; no page selected",
        ))
    }

}
