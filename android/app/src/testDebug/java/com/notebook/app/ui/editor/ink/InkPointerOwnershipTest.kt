package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPointerOwnershipTest {
    private val touch = InkPointerOwnership.Kind.TOUCH
    private val pen = InkPointerOwnership.Kind.STYLUS
    private val mouse = InkPointerOwnership.Kind.OTHER
    private fun c(id: Long, type: InkPointerOwnership.Kind, down: Boolean = true) =
        InkPointerOwnership.Contact(id, type, down)

    @Test fun touchNavigatesByDefaultAndIsConsumedBeforeInk() {
        val d = InkPointerOwnership().route(listOf(c(1, touch)), false)
        assertTrue(d.navigateTouch)
        assertTrue(d.consumeTouch)
        assertFalse(d.stylusActive)
    }

    @Test fun twoFingersNavigateAndDoNotBecomeInk() {
        val d = InkPointerOwnership().route(listOf(c(1, touch), c(2, touch)), false)
        assertTrue(d.navigateTouch)
        assertTrue(d.consumeTouch)
    }

    @Test fun explicitFingerDrawingAllowsTouchThroughToInk() {
        val d = InkPointerOwnership().route(listOf(c(1, touch)), true)
        assertFalse(d.navigateTouch)
        assertFalse(d.consumeTouch)
    }

    @Test fun stylusDoesNotTriggerNavigation() {
        val d = InkPointerOwnership().route(listOf(c(3, pen)), false)
        assertFalse(d.navigateTouch)
        assertTrue(d.stylusActive)
    }

    @Test fun stylusAndFingerTogetherFreezeCameraAndConsumeFinger() {
        val router = InkPointerOwnership()
        router.route(listOf(c(3, pen)), false)
        val d = router.route(listOf(c(3, pen), c(4, touch)), false)
        assertFalse(d.navigateTouch)
        assertTrue(d.consumeTouch)
    }

    @Test fun navigationResumesOnlyAfterStylusUp() {
        val router = InkPointerOwnership()
        router.route(listOf(c(3, pen)), false)
        val d = router.route(listOf(c(3, pen, false), c(4, touch)), false)
        assertTrue(d.navigateTouch)
        assertFalse(d.stylusActive)
    }

    @Test fun eachStylusIsTrackedIndependently() {
        val router = InkPointerOwnership()
        router.route(listOf(c(1, pen), c(2, pen)), false)
        assertFalse(router.route(listOf(c(1, pen, false), c(2, pen), c(3, touch)), false).navigateTouch)
        assertTrue(router.route(listOf(c(2, pen, false), c(3, touch)), false).navigateTouch)
    }

    @Test fun mouseIsNotMisclassifiedAsTouchNavigation() {
        val d = InkPointerOwnership().route(listOf(c(10, mouse)), false)
        assertFalse(d.consumeTouch)
        assertFalse(d.navigateTouch)
    }

    @Test fun resetReleasesInterruptedStylusOwnership() {
        val router = InkPointerOwnership()
        router.route(listOf(c(3, pen)), false)
        router.reset()
        assertTrue(router.route(listOf(c(5, touch)), false).navigateTouch)
    }

    @Test fun drawingModeNeverNavigatesEvenWithStylusPresent() {
        val router = InkPointerOwnership()
        val d = router.route(listOf(c(3, pen), c(4, touch)), true)
        assertFalse(d.navigateTouch)
        assertFalse(d.consumeTouch)
    }
}
