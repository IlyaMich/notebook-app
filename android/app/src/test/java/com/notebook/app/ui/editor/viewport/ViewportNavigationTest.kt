package com.notebook.app.ui.editor.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportNavigationTest {
    private val tolerance = 1e-8
    private val layout = requireNotNull(
        ViewportGeometry.layoutOrNull(listOf(PageSizeDu("long-page", 400.0, 1200.0))),
    )
    private val viewport = PxSize(400.0, 600.0)
    private val gutter = 16.0
    private val initial = requireNotNull(ViewportGeometry.fitToWidthOrNull(layout, viewport, gutter))

    private fun gesture(
        current: ViewportTransform = initial,
        focal: PxPoint = PxPoint(200.0, 300.0),
        pan: PxPoint = PxPoint(0.0, 0.0),
        zoom: Double = 1.0,
    ): ViewportTransform = requireNotNull(
        ViewportNavigation.updateOrNull(
            layout, viewport, gutter, initial.scalePxPerDu, current, focal, pan, zoom,
        ),
    )

    @Test fun pinchPreservesFocalWorldPointWhenNotAtEdges() {
        val focal = PxPoint(200.0, 300.0)
        val before = requireNotNull(ViewportGeometry.viewportToWorldOrNull(focal, initial))
        val next = gesture(zoom = 1.5)
        val after = requireNotNull(ViewportGeometry.viewportToWorldOrNull(focal, next))
        assertEquals(1.5 * initial.scalePxPerDu, next.scalePxPerDu, tolerance)
        assertEquals(before.x, after.x, tolerance)
        assertEquals(before.y, after.y, tolerance)
    }

    @Test fun simultaneousPanAndPinchMovesThePreviousCentroidByPan() {
        val previousFocal = PxPoint(200.0, 300.0)
        val pan = PxPoint(15.0, -20.0)
        val before = requireNotNull(ViewportGeometry.viewportToWorldOrNull(previousFocal, initial))
        val next = gesture(focal = previousFocal, pan = pan, zoom = 1.5)
        val after = requireNotNull(
            ViewportGeometry.viewportToWorldOrNull(PxPoint(215.0, 280.0), next),
        )
        assertEquals(before.x, after.x, tolerance)
        assertEquals(before.y, after.y, tolerance)
    }

    @Test fun oneFingerPanClampsAgainstDocumentEdges() {
        val moved = gesture(pan = PxPoint(60.0, -100.0))
        assertEquals(16.0, moved.translationXPx, tolerance) // fits the width
        assertEquals(initial.translationYPx - 100.0, moved.translationYPx, tolerance)
        val top = gesture(current = moved, pan = PxPoint(0.0, 10000.0))
        assertEquals(gutter, top.translationYPx, tolerance)
        val bottom = gesture(current = moved, pan = PxPoint(0.0, -10000.0))
        val expectedBottom = viewport.height - gutter - layout.totalHeightDu * initial.scalePxPerDu
        assertEquals(expectedBottom, bottom.translationYPx, tolerance)
    }

    @Test fun zoomBoundsAreRelativeToFitScale() {
        val maximum = gesture(zoom = 100.0)
        assertEquals(initial.scalePxPerDu * ViewportNavigation.MAX_ZOOM_FACTOR,
            maximum.scalePxPerDu, tolerance)
        val minimum = gesture(current = maximum, zoom = 0.0001)
        assertEquals(initial.scalePxPerDu * ViewportNavigation.MIN_ZOOM_FACTOR,
            minimum.scalePxPerDu, tolerance)
        assertTrue(minimum.translationXPx.isFinite())
        assertTrue(minimum.translationYPx.isFinite())
    }

    @Test fun zoomedPageCanPanHorizontallyButCannotBeLost() {
        val enlarged = gesture(zoom = 3.0)
        val rightEdge = gesture(current = enlarged, pan = PxPoint(10000.0, 0.0))
        assertEquals(gutter, rightEdge.translationXPx, tolerance)
        val leftEdge = gesture(current = enlarged, pan = PxPoint(-10000.0, 0.0))
        assertEquals(
            viewport.width - gutter - layout.columnWidthDu * enlarged.scalePxPerDu,
            leftEdge.translationXPx, tolerance,
        )
    }

    @Test fun invalidPointerOrZoomIsSafelyRejected() {
        assertNull(ViewportNavigation.updateOrNull(layout, viewport, gutter,
            initial.scalePxPerDu, initial, PxPoint(Double.NaN, 10.0), PxPoint(0.0, 0.0), 1.0))
        assertNull(ViewportNavigation.updateOrNull(layout, viewport, gutter,
            initial.scalePxPerDu, initial, PxPoint(10.0, 10.0), PxPoint(Double.NaN, 0.0), 1.0))
        assertNull(ViewportNavigation.updateOrNull(layout, viewport, gutter,
            initial.scalePxPerDu, initial, PxPoint(10.0, 10.0), PxPoint(0.0, 0.0), 0.0))
        assertNull(ViewportNavigation.updateOrNull(layout, viewport, gutter,
            initial.scalePxPerDu, initial, PxPoint(10.0, 10.0), PxPoint(0.0, 0.0), Double.POSITIVE_INFINITY))
        assertNull(ViewportNavigation.updateOrNull(layout, viewport, gutter,
            -1.0, initial, PxPoint(10.0, 10.0), PxPoint(0.0, 0.0), 1.0))
    }
}
