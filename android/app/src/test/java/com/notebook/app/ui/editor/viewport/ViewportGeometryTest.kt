package com.notebook.app.ui.editor.viewport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportGeometryTest {
    private val tolerance = 1e-8

    private fun mixedPages(): ContinuousPageLayout = requireNotNull(
        ViewportGeometry.layoutOrNull(
            listOf(PageSizeDu("portrait", 612.0, 792.0), PageSizeDu("landscape", 792.0, 612.0)),
        ),
    )

    @Test fun mixedSizePagesCenterWithinContinuousColumn() {
        val layout = mixedPages()
        assertEquals(792.0, layout.columnWidthDu, tolerance)
        assertEquals(1428.0, layout.totalHeightDu, tolerance)
        assertEquals(90.0, layout.pages[0].originXDu, tolerance)
        assertEquals(0.0, layout.pages[0].originYDu, tolerance)
        assertEquals(0.0, layout.pages[1].originXDu, tolerance)
        assertEquals(816.0, layout.pages[1].originYDu, tolerance)
        assertEquals(listOf("portrait", "landscape"), layout.pages.map { it.id })
    }

    @Test fun pageCoordinateRoundTripMatchesContract() {
        val page = mixedPages().pages[1]
        val transform = ViewportTransform(2.0, 30.0, -100.0)
        val viewport = ViewportGeometry.pageToViewport(DuPoint(10.0, 20.0), page, transform)
        assertEquals(50.0, viewport.x, tolerance)
        assertEquals(1572.0, viewport.y, tolerance)
        val local = requireNotNull(ViewportGeometry.viewportToPageOrNull(viewport, page, transform))
        assertEquals(10.0, local.x, tolerance)
        assertEquals(20.0, local.y, tolerance)
    }

    @Test fun pinchKeepsWorldPointUnderFingersUntilClamped() {
        val previous = ViewportTransform(2.0, 30.0, -100.0)
        val focal = PxPoint(200.0, 400.0)
        val before = requireNotNull(ViewportGeometry.viewportToWorldOrNull(focal, previous))
        val next = requireNotNull(ViewportGeometry.zoomAroundOrNull(previous, focal, 3.0))
        val after = requireNotNull(ViewportGeometry.viewportToWorldOrNull(focal, next))
        assertEquals(-55.0, next.translationXPx, tolerance)
        assertEquals(-350.0, next.translationYPx, tolerance)
        assertEquals(85.0, before.x, tolerance)
        assertEquals(250.0, before.y, tolerance)
        assertEquals(before.x, after.x, tolerance)
        assertEquals(before.y, after.y, tolerance)
    }

    @Test fun panOnlyChangesTransientTranslation() {
        val start = ViewportTransform(2.0, 30.0, -100.0)
        val end = ViewportGeometry.pan(start, PxPoint(40.0, -25.0))
        assertEquals(start.scalePxPerDu, end.scalePxPerDu, tolerance)
        assertEquals(70.0, end.translationXPx, tolerance)
        assertEquals(-125.0, end.translationYPx, tolerance)
    }

    @Test fun hitTestingRejectsGapAndShorterPageMargins() {
        val layout = mixedPages()
        val identity = ViewportTransform(1.0, 0.0, 0.0)
        assertEquals("portrait", ViewportGeometry.hitTestPage(PxPoint(100.0, 100.0), layout, identity)?.pageId)
        assertEquals("landscape", ViewportGeometry.hitTestPage(PxPoint(10.0, 836.0), layout, identity)?.pageId)
        assertNull(ViewportGeometry.hitTestPage(PxPoint(120.0, 800.0), layout, identity))
        assertNull(ViewportGeometry.hitTestPage(PxPoint(20.0, 100.0), layout, identity))
        assertNull(ViewportGeometry.hitTestPage(PxPoint(Double.NaN, 100.0), layout, identity))
    }

    @Test fun fitUsesGutterAndCentersPage() {
        val layout = mixedPages()
        val fitted = requireNotNull(ViewportGeometry.fitToWidthOrNull(layout, PxSize(824.0, 1000.0), 16.0))
        assertEquals(1.0, fitted.scalePxPerDu, tolerance)
        assertEquals(16.0, fitted.translationXPx, tolerance)
        assertEquals(16.0, fitted.translationYPx, tolerance)
    }

    @Test fun clampAllowsHorizontalPanningAtZoomAndTopAlignsShortPages() {
        val layout = requireNotNull(ViewportGeometry.layoutOrNull(listOf(PageSizeDu("a", 600.0, 200.0))))
        val viewport = PxSize(400.0, 700.0)
        val start = ViewportTransform(2.0, -800.0, -200.0)
        val clamped = requireNotNull(ViewportGeometry.clampOrNull(layout, viewport, 16.0, start))
        assertEquals(-800.0, clamped.translationXPx, tolerance)
        assertEquals(16.0, clamped.translationYPx, tolerance)
        val right = requireNotNull(ViewportGeometry.clampOrNull(layout, viewport, 16.0, ViewportTransform(2.0, 500.0, 100.0)))
        assertEquals(16.0, right.translationXPx, tolerance)
        assertEquals(16.0, right.translationYPx, tolerance)
    }

    @Test fun emptyAndInvalidGeometryFailsSafely() {
        val empty = requireNotNull(ViewportGeometry.layoutOrNull(emptyList()))
        assertTrue(empty.pages.isEmpty())
        assertNull(ViewportGeometry.fitToWidthOrNull(empty, PxSize(800.0, 600.0), 16.0))
        assertNull(ViewportGeometry.layoutOrNull(listOf(PageSizeDu("a", 0.0, 20.0))))
        assertNull(ViewportGeometry.layoutOrNull(listOf(PageSizeDu("a", Double.NaN, 20.0))))
        assertNull(ViewportGeometry.layoutOrNull(listOf(PageSizeDu("a", 20.0, 20.0), PageSizeDu("a", 30.0, 30.0))))
        assertNull(ViewportGeometry.layoutOrNull(listOf(PageSizeDu("a", 20.0, 20.0)), -1.0))
        assertNull(ViewportGeometry.fitToWidthOrNull(mixedPages(), PxSize(20.0, 600.0), 16.0))
        assertNull(ViewportGeometry.viewportToWorldOrNull(PxPoint(1.0, 2.0), ViewportTransform(0.0, 0.0, 0.0)))
        assertNull(ViewportGeometry.zoomAroundOrNull(ViewportTransform(1.0, 0.0, 0.0), PxPoint(100.0, 100.0), Double.POSITIVE_INFINITY))
    }

    @Test fun layoutDoesNotMutateSourcePageDimensions() {
        val sizes = listOf(PageSizeDu("a", 612.0, 792.0), PageSizeDu("b", 792.0, 612.0))
        ViewportGeometry.layoutOrNull(sizes)
        assertEquals(612.0, sizes[0].width, tolerance)
        assertEquals(792.0, sizes[0].height, tolerance)
        assertEquals(792.0, sizes[1].width, tolerance)
        assertEquals(612.0, sizes[1].height, tolerance)
    }
}
