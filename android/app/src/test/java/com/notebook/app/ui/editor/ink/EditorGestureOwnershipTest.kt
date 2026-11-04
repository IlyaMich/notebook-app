
package com.notebook.app.ui.editor.ink

import org.junit.Assert.*
import org.junit.Test

class EditorGestureOwnershipTest {

    private val owner = EditorGestureOwnership()

    private fun down(
        id: Long,
        contact: EditorContact = EditorContact.STYLUS,
        mode: EditorFingerMode = EditorFingerMode.NAVIGATE,
        count: Int = if (contact == EditorContact.FINGER) 1 else 0,
        page: String? = "page-1",
        tool: EditorInkTool? = EditorInkTool.PEN,
    ) = owner.onDown(id, contact, tool, mode, count, page)

    @Test
    fun stylusFinishesExactlyOnce() {
        val started = down(1)
        assertEquals(1, started.size)
        assertTrue(started.single() is EditorGestureOwnership.Effect.Started)

        val finished = owner.onUp(1)
        assertEquals(1, finished.size)
        assertTrue(finished.single() is EditorGestureOwnership.Effect.Finished)
        assertTrue(owner.onUp(1).isEmpty())
    }

    @Test
    fun drawingOutsidePageNeverStarts() {
        assertTrue(down(1, page = null).isEmpty())
        assertTrue(owner.onUp(1).isEmpty())
    }

    @Test
    fun fingerNavigatesByDefault() {
        assertTrue(
            down(1, EditorContact.FINGER).isEmpty()
        )
        assertTrue(owner.canNavigate())
        owner.onUp(1)
        assertFalse(owner.canNavigate())
    }

    @Test
    fun secondFingerCancelsFingerStroke() {
        val first = down(
            1, EditorContact.FINGER,
            mode = EditorFingerMode.WRITE
        )
        assertTrue(
            first.single() is EditorGestureOwnership.Effect.Started
        )

        val second = down(
            2, EditorContact.FINGER,
            mode = EditorFingerMode.WRITE,
            count = 2,
        )
        assertEquals(1, second.size)
        assertTrue(
            second.single() is EditorGestureOwnership.Effect.Cancelled
        )
        assertTrue(owner.canNavigate())

        assertTrue(owner.onUp(1).isEmpty())
        assertTrue(owner.onUp(2).isEmpty())
    }

    @Test
    fun stylusSuspendsTouchNavigation() {
        down(1, EditorContact.FINGER)
        assertTrue(owner.canNavigate())

        down(2, EditorContact.STYLUS)
        assertFalse(owner.canNavigate())

        owner.onUp(2)
        assertTrue(owner.canNavigate())

        owner.onUp(1)
        assertFalse(owner.canNavigate())
    }

    @Test
    fun stylusPreemptsFingerWriting() {
        down(
            1, EditorContact.FINGER,
            mode = EditorFingerMode.WRITE
        )
        val effects = down(2, EditorContact.STYLUS)

        assertEquals(2, effects.size)
        assertTrue(
            effects[0] is EditorGestureOwnership.Effect.Cancelled
        )
        assertTrue(
            effects[1] is EditorGestureOwnership.Effect.Started
        )

        assertTrue(owner.onUp(1).isEmpty())
        assertTrue(
            owner.onUp(2).single() is
                EditorGestureOwnership.Effect.Finished
        )
    }

    @Test
    fun cancelledGestureCannotFinish() {
        down(1)
        val effects = owner.cancelAll()

        assertTrue(
            effects.single() is EditorGestureOwnership.Effect.Cancelled
        )
        assertTrue(owner.onUp(1).isEmpty())
        assertFalse(owner.canNavigate())
    }

    @Test
    fun toolChangeCancelsActiveStroke() {
        down(1)
        val effects = owner.onConfigurationChanged()

        assertTrue(
            effects.single() is EditorGestureOwnership.Effect.Cancelled
        )
        assertTrue(owner.onUp(1).isEmpty())
    }

    @Test
    fun onlyOneStrokeCanBeActive() {
        down(1)
        assertTrue(down(2).isEmpty())

        assertTrue(owner.onUp(2).isEmpty())
        assertTrue(
            owner.onUp(1).single() is
                EditorGestureOwnership.Effect.Finished
        )
    }

    @Test
    fun unsupportedContactsNeverDraw() {
        for (contact in listOf(
            EditorContact.ERASER,
            EditorContact.MOUSE,
            EditorContact.UNKNOWN,
        )) {
            assertTrue(down(10, contact).isEmpty())
            assertTrue(owner.onUp(10).isEmpty())
        }
    }

    @Test
    fun fingerNavigationContinuesAfterPinchContactLifts() {
        down(
            1,
            EditorContact.FINGER,
            mode = EditorFingerMode.WRITE,
        )

        val effects = down(
            2,
            EditorContact.FINGER,
            mode = EditorFingerMode.WRITE,
            count = 2,
        )

        assertTrue(
            effects.single() is
                EditorGestureOwnership.Effect.Cancelled
        )
        assertTrue(owner.canNavigate())

        owner.onUp(2)

        // The original finger is still pressed, but the
        // cancelled stroke must never resume.
        assertTrue(owner.canNavigate())

        assertTrue(owner.onUp(1).isEmpty())
        assertFalse(owner.canNavigate())
    }
}
