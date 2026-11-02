package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.PageSizeDu
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkClipGeometryTest {
    private val pages = checkNotNull(ViewportGeometry.layoutOrNull(
        listOf(PageSizeDu("a", 100.0, 100.0), PageSizeDu("b", 80.0, 60.0)),
        gapDu = 20.0,
    ))
    private val viewport = PxSize(200.0, 180.0)

    @Test fun firstPageBoundsAreSharedByWetAndDryRenderers() {
        val rects = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(1.0, 20.0, 10.0), viewport)
        val a = requireNotNull(rects["a"])
        assertEquals(20.0, a.left, 0.0)
        assertEquals(10.0, a.top, 0.0)
        assertEquals(120.0, a.right, 0.0)
        assertEquals(110.0, a.bottom, 0.0)
        assertTrue(a.contains(30.0, 40.0))
        assertFalse(a.contains(30.0, 120.0))
    }

    @Test fun secondMixedWidthPageIsCenteredAndClippedToViewport() {
        val rects = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(1.0, 20.0, 10.0), viewport)
        val b = requireNotNull(rects["b"])
        assertEquals(30.0, b.left, 0.0)
        assertEquals(130.0, b.top, 0.0)
        assertEquals(110.0, b.right, 0.0)
        assertEquals(180.0, b.bottom, 0.0)
    }

    @Test fun gapIsNotIncludedInAnyVisiblePageRectangle() {
        val rects = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(1.0, 20.0, 10.0), viewport)
        assertTrue(rects.values.none { it.contains(60.0, 120.0) })
    }

    @Test fun offscreenPagesAreExcluded() {
        val rects = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(1.0, 20.0, -500.0), viewport)
        assertTrue(rects.isEmpty())
    }

    @Test fun panAndZoomChangeClippingWithoutChangingLayout() {
        val first = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(1.0, 20.0, 10.0), viewport)
        val second = InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(2.0, -20.0, -40.0), viewport)
        assertTrue(first["a"] != second["a"])
        assertEquals(100.0, pages.pages.first().widthDu, 0.0)
    }

    @Test fun invalidViewportOrCameraReturnsNoClips() {
        val validCamera = ViewportTransform(1.0, 20.0, 10.0)
        assertTrue(InkClipGeometry.visiblePageRects(pages, null, viewport).isEmpty())
        assertTrue(InkClipGeometry.visiblePageRects(pages,
            ViewportTransform(Double.NaN, 0.0, 0.0), viewport).isEmpty())
        assertTrue(InkClipGeometry.visiblePageRects(pages, validCamera,
            PxSize(0.0, 180.0)).isEmpty())
    }
}
