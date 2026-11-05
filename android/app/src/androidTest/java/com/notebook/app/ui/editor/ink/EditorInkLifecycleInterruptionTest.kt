package com.notebook.app.ui.editor.ink

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInkLifecycleInterruptionTest {

    @get:Rule
    val rule = createComposeRule()

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle = registry
    }

    @Test
    fun pauseCancelsStrokeAndLateReleaseCannotSave() {
        val owner = TestLifecycleOwner()
        val completions = AtomicInteger(0)

        rule.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }

        rule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner
            ) {
                ContinuousPageViewport(
                    pages = listOf(
                        PageViewportItem(
                            id = "page-1",
                            widthDu = 595.276,
                            heightDu = 841.890,
                            elementCount = 0,
                        )
                    ),
                    documentKey = "edt09-lifecycle",
                    fingerWritingPreview = true,
                    selectedTool = EditorInkTool.PEN,
                    onPenStrokeCompleted = { _, _ ->
                        completions.incrementAndGet()
                    },
                )
            }
        }

        rule.waitForIdle()

        val node = rule.onNodeWithContentDescription(
            "Read-only note viewport with 1 pages"
        )

        // Begin a stroke, but do not release it.
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.42f, 0.25f))
        }

        // The Activity-like lifecycle pauses while
        // the finger is still pressed.
        rule.runOnIdle {
            owner.registry.handleLifecycleEvent(
                Lifecycle.Event.ON_PAUSE
            )
        }

        rule.waitForIdle()

        assertEquals(
            "Pausing must not complete unfinished ink",
            0,
            completions.get(),
        )

        // Resume the same composition.
        rule.runOnIdle {
            owner.registry.handleLifecycleEvent(
                Lifecycle.Event.ON_RESUME
            )
        }

        rule.waitForIdle()

        // The original finger finally lifts.
        node.performTouchInput {
            up()
        }

        rule.waitForIdle()

        assertEquals(
            "Late release must not save cancelled ink",
            0,
            completions.get(),
        )

        // A completely new gesture must still work.
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
            up()
        }

        rule.waitForIdle()

        assertEquals(
            "A fresh gesture should complete once",
            1,
            completions.get(),
        )
    }
}
