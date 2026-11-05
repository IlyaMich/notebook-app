package com.notebook.app.ui.editor.ink

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInkViewportDisposalTest {

    @get:Rule
    val rule = createComposeRule()

    private val description =
        "Read-only note viewport with 1 pages"

    private val page = PageViewportItem(
        id = "page-1",
        widthDu = 595.276,
        heightDu = 841.890,
        elementCount = 0,
    )

    private fun hasBlueInk(): Boolean {
        val image = rule
            .onNodeWithContentDescription(description)
            .captureToImage()

        val pixels = image.toPixelMap()

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = pixels[x, y]

                if (
                    color.red < 0.30f &&
                    color.green in 0.20f..0.52f &&
                    color.blue > 0.55f &&
                    color.alpha > 0.90f
                ) {
                    return true
                }
            }
        }

        return false
    }

    @Test
    fun disposingDuringStrokeDiscardsPreviewAndLateRelease() {
        val visible = mutableStateOf(true)
        val completions = AtomicInteger(0)

        rule.setContent {
            if (visible.value) {
                ContinuousPageViewport(
                    pages = listOf(page),
                    documentKey = "edt09-disposal",
                    fingerWritingPreview = true,
                    selectedTool = EditorInkTool.PEN,
                    onPenStrokeCompleted = { _, _ ->
                        completions.incrementAndGet()
                    },
                )
            }
        }

        rule.waitForIdle()

        // Start drawing without releasing the finger.
        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
        }

        rule.waitForIdle()

        assertEquals(0, completions.get())

        assertTrue(
            "Active stroke preview should be visible",
            hasBlueInk(),
        )

        // Simulate leaving the editor: remove the viewport
        // from composition while drawing is active.
        rule.runOnIdle {
            visible.value = false
        }

        rule.waitForIdle()

        assertEquals(
            "Disposal must not finish an active stroke",
            0,
            completions.get(),
        )

        // Re-enter with the same document identity.
        rule.runOnIdle {
            visible.value = true
        }

        rule.waitForIdle()

        assertFalse(
            "An unfinished preview must not survive disposal",
            hasBlueInk(),
        )

        // The original finger is finally released.
        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            up()
        }

        rule.waitForIdle()

        assertEquals(
            "Late release must not save discarded ink",
            0,
            completions.get(),
        )

        // A new gesture must still work normally.
        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
            up()
        }

        rule.waitForIdle()

        assertEquals(
            "Fresh gesture must complete exactly once",
            1,
            completions.get(),
        )
    }
}
