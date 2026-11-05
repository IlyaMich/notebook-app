
package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorInputRoutingTest {

    private fun route(
        contact: EditorContact,
        tool: EditorInkTool? = EditorInkTool.PEN,
        mode: EditorFingerMode = EditorFingerMode.NAVIGATE,
        count: Int = 1,
    ): EditorPointerAction =
        EditorInputRouting.onPointerDown(
            contact = contact,
            selectedTool = tool,
            fingerMode = mode,
            pointerCount = count,
        )

    @Test
    fun stylusWithPenDraws() {
        assertEquals(
            EditorPointerAction.DRAW,
            route(EditorContact.STYLUS),
        )
    }

    @Test
    fun stylusWithPencilDraws() {
        assertEquals(
            EditorPointerAction.DRAW,
            route(
                EditorContact.STYLUS,
                tool = EditorInkTool.PENCIL,
            ),
        )
    }

    @Test
    fun stylusWithHighlighterDraws() {
        assertEquals(
            EditorPointerAction.DRAW,
            route(
                EditorContact.STYLUS,
                tool = EditorInkTool.HIGHLIGHTER,
                mode = EditorFingerMode.WRITE,
            ),
        )
    }

    @Test
    fun fingerNavigatesByDefault() {
        for (count in listOf(1, 2)) {
            assertEquals(
                EditorPointerAction.NAVIGATE,
                route(
                    EditorContact.FINGER,
                    count = count,
                ),
            )
        }
    }

    @Test
    fun singleFingerDrawsInWriteMode() {
        assertEquals(
            EditorPointerAction.DRAW,
            route(
                EditorContact.FINGER,
                mode = EditorFingerMode.WRITE,
            ),
        )
    }

    @Test
    fun multipleFingersNavigateInWriteMode() {
        for (count in listOf(2, 3)) {
            assertEquals(
                EditorPointerAction.NAVIGATE,
                route(
                    EditorContact.FINGER,
                    mode = EditorFingerMode.WRITE,
                    count = count,
                ),
            )
        }
    }

    @Test
    fun missingDrawingToolDoesNotStartStroke() {
        assertEquals(
            EditorPointerAction.IGNORE,
            route(
                EditorContact.STYLUS,
                tool = null,
            ),
        )

        assertEquals(
            EditorPointerAction.IGNORE,
            route(
                EditorContact.FINGER,
                tool = null,
                mode = EditorFingerMode.WRITE,
            ),
        )
    }

    @Test
    fun unsupportedContactsAreIgnored() {
        for (contact in listOf(
            EditorContact.ERASER,
            EditorContact.MOUSE,
            EditorContact.UNKNOWN,
        )) {
            assertEquals(
                EditorPointerAction.IGNORE,
                route(
                    contact,
                    mode = EditorFingerMode.WRITE,
                ),
            )
        }
    }

    @Test
    fun invalidPointerCountsAreIgnored() {
        for (count in listOf(0, -1)) {
            for (contact in EditorContact.entries) {
                assertEquals(
                    EditorPointerAction.IGNORE,
                    route(
                        contact,
                        mode = EditorFingerMode.WRITE,
                        count = count,
                    ),
                )
            }
        }
    }
}
