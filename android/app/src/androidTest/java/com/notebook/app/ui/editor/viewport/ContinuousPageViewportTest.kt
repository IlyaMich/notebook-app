package com.notebook.app.ui.editor.viewport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    /**
     * Both pages must be hit-testable at a compact (phone-like) viewport width.
     * The synthetic first page is deliberately short so the inter-page gap and
     * second, wider page are both visible without requiring a scroll gesture.
     */
    @Test fun compactMixedSizeViewportTargetsEachPageAndRejectsGap() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 320.dp, height = 460.dp)) {
                    ContinuousPageViewport(
                        pages = listOf(
                            PageViewportItem("short-portrait", 612.0, 240.0, 0),
                            PageViewportItem("wide-landscape", 792.0, 612.0, 0),
                        ),
                        documentKey = "compact-mixed-size-test",
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithContentDescription(
            "Read-only note viewport with 2 pages",
        )
        // At 320dp width and 16dp gutters, fit-scale is 288/792 dp/DU.
        // Page 1 occupies approximately y=16..103dp, gap y=103..112dp,
        // and page 2 starts at y=112dp. Touch offsets are node-relative.
        viewport.performTouchInput {
            click(percentOffset(0.5f, 60f / 460f))
        }
        viewport.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Zoom 100 percent; selected page 1",
        ))
        viewport.performTouchInput {
            click(percentOffset(0.5f, 108f / 460f))
        }
        viewport.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Zoom 100 percent; no page selected",
        ))
        viewport.performTouchInput {
            click(percentOffset(0.5f, 160f / 460f))
        }
        viewport.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Zoom 100 percent; selected page 2",
        ))
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
