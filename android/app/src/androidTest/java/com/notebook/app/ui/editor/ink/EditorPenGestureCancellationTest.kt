
package com.notebook.app.ui.editor.ink

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPenGestureCancellationTest {

    @get:Rule
    val rule = createComposeRule()

    private val description =
        "Read-only note viewport with 1 pages"

    private fun mountViewport(
        completions: AtomicInteger,
    ) {
        rule.setContent {
            ContinuousPageViewport(
                pages = listOf(
                    PageViewportItem(
                        id = "page-1",
                        widthDu = 595.276,
                        heightDu = 841.890,
                        elementCount = 0,
                    )
                ),
                documentKey = "edt09-cancellation-test",
                fingerWritingPreview = true,
                onPenStrokeCompleted = { _, _ ->
                    completions.incrementAndGet()
                },
            )
        }

        rule.waitForIdle()
    }

    @Test
    fun normalReleaseCompletesExactlyOnce() {
        val completions = AtomicInteger(0)
        mountViewport(completions)

        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            val start = percentOffset(0.35f, 0.20f)
            val end = percentOffset(0.50f, 0.30f)

            down(start)
            moveTo(end)
            up()
        }

        rule.waitForIdle()

        assertEquals(1, completions.get())
    }

    @Test
    fun platformCancellationNeverCompletesStroke() {
        val completions = AtomicInteger(0)
        mountViewport(completions)

        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            val start = percentOffset(0.35f, 0.20f)
            val end = percentOffset(0.50f, 0.30f)

            down(start)
            moveTo(end)

            // Simulate platform cancellation, not a
            // normal pointer release.
            cancel()
        }

        rule.waitForIdle()

        assertEquals(
            "A cancelled stroke must never complete",
            0,
            completions.get(),
        )
    }

    @Test
    fun secondFingerCancelsWritingWithoutCompletion() {
        val completions = AtomicInteger(0)
        mountViewport(completions)

        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            val start = percentOffset(0.35f, 0.20f)
            val moved = percentOffset(0.45f, 0.27f)

            down(0, start)
            moveTo(0, moved)

            // This second contact transfers ownership
            // from finger writing to navigation.
            down(
                1,
                percentOffset(0.65f, 0.30f),
            )

            moveTo(
                0,
                percentOffset(0.48f, 0.28f),
            )

            up(1)
            up(0)
        }

        rule.waitForIdle()

        assertEquals(
            "Multi-touch navigation must not save ink",
            0,
            completions.get(),
        )
    }
}
