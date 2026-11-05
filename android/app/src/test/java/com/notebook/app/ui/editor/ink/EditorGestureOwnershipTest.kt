
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

    @Test
    fun fingerJoiningStylusCannotNavigateAfterStylusRelease() {
        val started = down(1, EditorContact.STYLUS)

        assertTrue(
            started.single() is
                EditorGestureOwnership.Effect.Started
        )

        // Finger lands while the stylus owns the gesture.
        assertTrue(
            down(
                2,
                EditorContact.FINGER,
                mode = EditorFingerMode.NAVIGATE,
            ).isEmpty()
        )

        assertFalse(owner.canNavigate())

        // Releasing the stylus completes its stroke.
        assertTrue(
            owner.onUp(1).single() is
                EditorGestureOwnership.Effect.Finished
        )

        // The remaining finger must not become navigation.
        assertFalse(owner.canNavigate())

        assertTrue(owner.onUp(2).isEmpty())
        assertFalse(owner.canNavigate())

        // Navigation works again with a fresh finger-down.
        assertTrue(
            down(3, EditorContact.FINGER).isEmpty()
        )
        assertTrue(owner.canNavigate())

        owner.onUp(3)
        assertFalse(owner.canNavigate())
    }

    @Test
    fun earlierNavigationResumesButNewPalmDoesNot() {
        // A finger starts navigation before the stylus.
        down(1, EditorContact.FINGER)
        assertTrue(owner.canNavigate())

        // Stylus temporarily suspends navigation.
        down(2, EditorContact.STYLUS)
        assertFalse(owner.canNavigate())

        // A second finger lands while the stylus writes.
        down(
            3,
            EditorContact.FINGER,
            count = 2,
        )

        assertFalse(owner.canNavigate())

        // Stylus completes; original navigation resumes.
        assertTrue(
            owner.onUp(2).single() is
                EditorGestureOwnership.Effect.Finished
        )
        assertTrue(owner.canNavigate())

        // The original navigating finger leaves.
        owner.onUp(1)

        // Only the palm contact remains. It must not navigate.
        assertFalse(owner.canNavigate())

        owner.onUp(3)
        assertFalse(owner.canNavigate())
    }

    @Test
    fun palmReleaseDoesNotCancelOrFinishStylusStroke() {
        down(1, EditorContact.STYLUS)

        down(
            2,
            EditorContact.FINGER,
            mode = EditorFingerMode.NAVIGATE,
        )

        // Palm lifts before the stylus.
        assertTrue(owner.onUp(2).isEmpty())

        // Stylus still owns the original drawing.
        assertFalse(owner.canNavigate())

        val finished = owner.onUp(1)

        assertEquals(1, finished.size)
        assertTrue(
            finished.single() is
                EditorGestureOwnership.Effect.Finished
        )

        // Duplicate release is ignored.
        assertTrue(owner.onUp(1).isEmpty())
    }

    @Test
    fun palmCannotParticipateInNavigationAfterStylusRelease() {
        // Finger 1 begins navigation.
        down(1, EditorContact.FINGER)

        assertTrue(owner.isNavigationPointer(1))

        // Stylus starts a drawing gesture.
        down(2, EditorContact.STYLUS)

        assertFalse(owner.canNavigate())

        // Finger 3 lands while the stylus is drawing.
        down(3, EditorContact.FINGER, count = 2)

        assertFalse(owner.isNavigationPointer(3))

        // Stylus releases.
        owner.onUp(2)

        // Navigation can resume through finger 1 only.
        assertTrue(owner.canNavigate())
        assertTrue(owner.isNavigationPointer(1))
        assertFalse(owner.isNavigationPointer(3))

        // Original navigation finger releases.
        owner.onUp(1)

        // Palm contact cannot take over.
        assertFalse(owner.canNavigate())
        assertFalse(owner.isNavigationPointer(3))

        owner.onUp(3)
    }

    @Test
    fun fingerWritingTakeoverRegistersBothNavigationPointers() {
        // Finger 1 begins writing.
        val started = down(
            1,
            EditorContact.FINGER,
            mode = EditorFingerMode.WRITE,
        )

        assertTrue(
            started.single() is
                EditorGestureOwnership.Effect.Started
        )

        assertFalse(owner.isNavigationPointer(1))

        // Second finger cancels writing and transfers
        // both fingers to navigation.
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
        assertTrue(owner.isNavigationPointer(1))
        assertTrue(owner.isNavigationPointer(2))

        // Releasing one finger preserves navigation.
        owner.onUp(2)

        assertTrue(owner.canNavigate())
        assertTrue(owner.isNavigationPointer(1))
        assertFalse(owner.isNavigationPointer(2))

        // Releasing the final finger ends navigation.
        owner.onUp(1)

        assertFalse(owner.canNavigate())
    }

    @Test
    fun cancelledNavigationCannotResumeWithoutFreshDown() {
        down(1, EditorContact.FINGER)
        down(
            2,
            EditorContact.FINGER,
            count = 2,
        )

        assertTrue(owner.isNavigationPointer(1))
        assertTrue(owner.isNavigationPointer(2))

        // Platform cancellation clears every contact.
        assertTrue(owner.cancelAll().isEmpty())

        assertFalse(owner.canNavigate())
        assertFalse(owner.isNavigationPointer(1))
        assertFalse(owner.isNavigationPointer(2))

        // Late releases cannot restore navigation.
        assertTrue(owner.onUp(1).isEmpty())
        assertTrue(owner.onUp(2).isEmpty())

        assertFalse(owner.canNavigate())

        // Only a fresh pointer-down can acquire ownership.
        down(3, EditorContact.FINGER)

        assertTrue(owner.canNavigate())
        assertTrue(owner.isNavigationPointer(3))

        owner.onUp(3)
        assertFalse(owner.canNavigate())
    }
}
