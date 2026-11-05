
package com.notebook.app.ui.editor.ink

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInkToolViewportTest {

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

    private fun mount(
        tool: MutableState<EditorInkTool>,
        saved: MutableList<Pair<String, String>>,
        errors: MutableList<String>,
    ) {
        rule.setContent {
            ContinuousPageViewport(
                pages = listOf(page),
                documentKey = "edt09-tools",
                fingerWritingPreview = true,
                selectedTool = tool.value,
                onPenStrokeCompleted = { pageId, json ->
                    saved.add(pageId to json)
                },
                onPenStrokeError = { message ->
                    errors.add(message)
                },
            )
        }

        rule.waitForIdle()
    }

    private fun drawStroke() {
        rule.onNodeWithContentDescription(
            description
        ).performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
            up()
        }

        rule.waitForIdle()
    }

    private fun brush(json: String): JSONObject =
        JSONObject(json)
            .getJSONObject("payload")
            .getJSONObject("brush")

    @Test
    fun eachToolProducesItsOwnCanonicalBrush() {
        val selectedTool = mutableStateOf(EditorInkTool.PEN)
        val saved = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()

        mount(selectedTool, saved, errors)

        drawStroke()

        rule.runOnIdle {
            selectedTool.value = EditorInkTool.PENCIL
        }
        rule.waitForIdle()

        drawStroke()

        rule.runOnIdle {
            selectedTool.value = EditorInkTool.HIGHLIGHTER
        }
        rule.waitForIdle()

        drawStroke()

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(3, saved.size)

        saved.forEach { (pageId, _) ->
            assertEquals("page-1", pageId)
        }

        val pen = brush(saved[0].second)
        assertEquals("pen", pen.getString("kind"))
        assertEquals(3.0, pen.getDouble("width"), 0.000001)
        assertEquals("#2255AA", pen.getString("color"))
        assertEquals(1.0, pen.getDouble("opacity"), 0.000001)

        val pencil = brush(saved[1].second)
        assertEquals("pencil", pencil.getString("kind"))
        assertEquals(2.0, pencil.getDouble("width"), 0.000001)
        assertEquals("#444444", pencil.getString("color"))
        assertEquals(0.85, pencil.getDouble("opacity"), 0.000001)

        val highlighter = brush(saved[2].second)
        assertEquals(
            "highlighter",
            highlighter.getString("kind"),
        )
        assertEquals(
            14.0,
            highlighter.getDouble("width"),
            0.000001,
        )
        assertEquals(
            "#FFE45C",
            highlighter.getString("color"),
        )
        assertEquals(
            0.35,
            highlighter.getDouble("opacity"),
            0.000001,
        )
    }

    @Test
    fun changingToolDuringStrokeDiscardsOldCapture() {
        val selectedTool = mutableStateOf(EditorInkTool.PEN)
        val saved = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()

        mount(selectedTool, saved, errors)

        val node = rule.onNodeWithContentDescription(
            description
        )

        // Start a Pen gesture without releasing the finger.
        node.performTouchInput {
            down(percentOffset(0.35f, 0.20f))
            moveTo(percentOffset(0.42f, 0.25f))
        }

        rule.waitForIdle()

        // Tool change must cancel the old pointer handler.
        rule.runOnIdle {
            selectedTool.value = EditorInkTool.PENCIL
        }

        rule.waitForIdle()

        // Release the original finger after the handler changed.
        node.performTouchInput {
            up()
        }

        rule.waitForIdle()

        assertTrue(
            "Interrupted Pen stroke must not be saved",
            saved.isEmpty(),
        )

        // A fresh gesture must use the newly selected tool.
        drawStroke()

        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, saved.size)
        assertEquals(
            "pencil",
            brush(saved.single().second).getString("kind"),
        )
    }
}
