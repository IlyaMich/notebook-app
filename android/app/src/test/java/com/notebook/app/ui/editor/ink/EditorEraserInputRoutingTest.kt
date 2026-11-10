package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** EDT-16 1D-4: eraser ownership uses the existing pen/finger arbiter. */
class EditorEraserInputRoutingTest {
    @Test fun hardwareEraserOnlyDrawsWhenEraserIsSelected() {
        for (tool in listOf(EditorInkTool.PEN, EditorInkTool.PENCIL, EditorInkTool.HIGHLIGHTER)) {
            assertEquals(
                EditorPointerAction.IGNORE,
                EditorInputRouting.onPointerDown(EditorContact.ERASER, tool, EditorFingerMode.WRITE, 1),
            )
        }
        assertEquals(
            EditorPointerAction.DRAW,
            EditorInputRouting.onPointerDown(
                EditorContact.ERASER, EditorInkTool.ERASER, EditorFingerMode.NAVIGATE, 1,
            ),
        )
    }

    @Test fun stylusAndFingerRespectEstablishedWriteModeRules() {
        assertEquals(
            EditorPointerAction.DRAW,
            EditorInputRouting.onPointerDown(
                EditorContact.STYLUS, EditorInkTool.ERASER, EditorFingerMode.NAVIGATE, 1,
            ),
        )
        assertEquals(
            EditorPointerAction.NAVIGATE,
            EditorInputRouting.onPointerDown(
                EditorContact.FINGER, EditorInkTool.ERASER, EditorFingerMode.NAVIGATE, 1,
            ),
        )
        assertEquals(
            EditorPointerAction.DRAW,
            EditorInputRouting.onPointerDown(
                EditorContact.FINGER, EditorInkTool.ERASER, EditorFingerMode.WRITE, 1,
            ),
        )
        assertEquals(
            EditorPointerAction.NAVIGATE,
            EditorInputRouting.onPointerDown(
                EditorContact.FINGER, EditorInkTool.ERASER, EditorFingerMode.WRITE, 2,
            ),
        )
    }

    @Test fun eraserCannotBeEncodedAsInkBrush() {
        var rejected = false
        try {
            EditorInkBrushPresets.forTool(EditorInkTool.ERASER)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun fingerLandingDuringHardwareEraserCannotAcquireNavigation() {
        val owner = EditorGestureOwnership()
        val started = owner.onDown(
            1L, EditorContact.ERASER, EditorInkTool.ERASER,
            EditorFingerMode.NAVIGATE, 0, "page-1",
        )
        assertTrue(started.single() is EditorGestureOwnership.Effect.Started)

        assertTrue(owner.onDown(
            2L, EditorContact.FINGER, EditorInkTool.ERASER,
            EditorFingerMode.NAVIGATE, 1, "page-1",
        ).isEmpty())
        assertFalse(owner.canNavigate())
        assertTrue(owner.onUp(1L).single() is EditorGestureOwnership.Effect.Finished)
        assertFalse(owner.canNavigate())
        owner.onUp(2L)
    }

    @Test fun hardwareEraserPreemptsFingerWritingOnlyWhenSelected() {
        val owner = EditorGestureOwnership()
        assertTrue(owner.onDown(
            1L, EditorContact.FINGER, EditorInkTool.ERASER,
            EditorFingerMode.WRITE, 1, "page-1",
        ).single() is EditorGestureOwnership.Effect.Started)

        val effects = owner.onDown(
            2L, EditorContact.ERASER, EditorInkTool.ERASER,
            EditorFingerMode.WRITE, 0, "page-1",
        )
        assertEquals(2, effects.size)
        assertTrue(effects[0] is EditorGestureOwnership.Effect.Cancelled)
        assertTrue(effects[1] is EditorGestureOwnership.Effect.Started)
        assertTrue(owner.onUp(1L).isEmpty())
        assertTrue(owner.onUp(2L).single() is EditorGestureOwnership.Effect.Finished)
    }
}
