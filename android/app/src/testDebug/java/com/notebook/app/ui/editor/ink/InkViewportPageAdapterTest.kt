package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PageSizeDu
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only CP3A contract tests; no native Ink SDK, Android device or Room required. */
class InkViewportPageAdapterTest {
    private val layout = checkNotNull(
        ViewportGeometry.layoutOrNull(
            listOf(
                PageSizeDu("portrait", 595.276, 841.89),
                PageSizeDu("landscape", 792.0, 612.0),
            ),
        ),
    )
    private val viewport = PxSize(1100.0, 2400.0)
    private val fitted = checkNotNull(ViewportGeometry.fitToWidthOrNull(layout, viewport, 30.0))

    private fun point(pageId: String, xDu: Double, yDu: Double, transform: ViewportTransform = fitted): PxPoint =
        ViewportGeometry.pageToViewport(
            DuPoint(xDu, yDu), layout.pages.first { it.id == pageId }, transform,
        )

    private fun sample(px: PxPoint, time: Long, pressure: Double? = null) =
        InkPixelSample(px.x, px.y, time, pressure = pressure)

    private fun capture(
        samples: List<InkPixelSample>,
        transform: ViewportTransform = fitted,
        widthPx: Double = 4.0 * transform.scalePxPerDu,
    ) = InkViewportPageAdapter.captureOrNull(
        samples, layout, transform, viewport, "pen", widthPx, "#2255AA", 1.0,
    )

    @Test fun portraitStrokeUsesPageLocalDocumentUnits() {
        val samples = listOf(sample(point("portrait", 32.0, 45.0), 120),
            sample(point("portrait", 90.0, 60.0), 139))
        val result = checkNotNull(capture(samples))
        assertEquals("portrait", result.pageId)
        assertEquals(32.0, result.canonical.points[0].x, 1e-8)
        assertEquals(45.0, result.canonical.points[0].y, 1e-8)
        assertEquals(90.0, result.canonical.points[1].x, 1e-8)
        assertEquals(0L, result.canonical.points[0].tMs)
        assertEquals(19L, result.canonical.points[1].tMs)
        assertEquals(4.0, result.canonical.brush.widthDu, 1e-8)
    }

    @Test fun landscapeStrokeTargetsStablePageIdAndDoesNotUsePreviousPageOrigin() {
        val result = checkNotNull(capture(listOf(
            sample(point("landscape", 150.0, 25.0), 0),
            sample(point("landscape", 300.0, 70.0), 12),
        )))
        assertEquals("landscape", result.pageId)
        assertEquals(150.0, result.canonical.points.first().x, 1e-8)
        assertEquals(25.0, result.canonical.points.first().y, 1e-8)
    }

    @Test fun gutterInterPageGapAndOutsideViewportCannotStartStroke() {
        val gap = ViewportGeometry.worldToViewport(
            DuPoint(layout.pages.last().originXDu + 100.0, 841.89 + 12.0), fitted,
        )
        assertNull(capture(listOf(sample(gap, 0))))
        assertNull(capture(listOf(sample(PxPoint(2.0, 90.0), 0))))
        assertNull(capture(listOf(sample(PxPoint(-10.0, 10.0), 0))))
        assertNull(capture(listOf(sample(PxPoint(1100.0, 30.0), 0))))
    }

    @Test fun gestureMayNotSwitchOwnershipAcrossPagesOrIntoGap() {
        val start = sample(point("portrait", 25.0, 25.0), 0)
        assertNull(capture(listOf(start, sample(point("landscape", 40.0, 30.0), 10))))
        val gap = ViewportGeometry.worldToViewport(
            DuPoint(layout.pages.last().originXDu + 80.0, 841.89 + 8.0), fitted,
        )
        assertNull(capture(listOf(start, sample(gap, 10))))
    }

    @Test fun sameDocumentPointsAtDifferentZoomPanRemainCanonical() {
        val x = 130.0
        val y = 180.0
        val atFit = checkNotNull(capture(listOf(sample(point("portrait", x, y), 10))))
        val scale = fitted.scalePxPerDu * 1.7
        val page = layout.pages.first()
        val panZoom = ViewportTransform(
            scale,
            500.0 - (page.originXDu + x) * scale,
            450.0 - (page.originYDu + y) * scale,
        )
        val zoomed = checkNotNull(capture(listOf(
            sample(point("portrait", x, y, panZoom), 10),
        ), panZoom, widthPx = 4.0 * scale))
        assertEquals(atFit.canonical.points[0].x, zoomed.canonical.points[0].x, 1e-8)
        assertEquals(atFit.canonical.points[0].y, zoomed.canonical.points[0].y, 1e-8)
        assertEquals(atFit.canonical.brush.widthDu, zoomed.canonical.brush.widthDu, 1e-8)
    }

    @Test fun missingPressureIsNotSynthesizedAndMeasuredPressureIsPreserved() {
        val result = checkNotNull(capture(listOf(
            sample(point("portrait", 25.0, 35.0), 100, 0.65),
            sample(point("portrait", 45.0, 55.0), 115),
        )))
        assertEquals(0.65, result.canonical.points[0].pressure!!, 0.0)
        assertNull(result.canonical.points[1].pressure)
    }

    @Test fun projectionFollowsCurrentCameraWithoutChangingCanonicalGeometry() {
        val captured = checkNotNull(capture(listOf(
            sample(point("landscape", 220.0, 100.0), 7),
            sample(point("landscape", 280.0, 140.0), 28),
        )))
        val original = captured.canonical.points.toList()
        val zoom = ViewportTransform(fitted.scalePxPerDu * 2.0, -200.0, -700.0)
        val projected = checkNotNull(InkViewportPageAdapter.projectOrNull(
            captured.pageId, captured.canonical, layout, zoom,
        ))
        val expected = point("landscape", 220.0, 100.0, zoom)
        assertEquals(expected.x, projected.samples[0].xPx, 1e-6)
        assertEquals(expected.y, projected.samples[0].yPx, 1e-6)
        assertEquals(8.0 * fitted.scalePxPerDu, projected.brushWidthPx, 1e-6)
        assertEquals(original, captured.canonical.points)
        assertEquals(21L, projected.samples[1].elapsedTimeMs)
        assertNull(InkViewportPageAdapter.projectOrNull("missing-page", captured.canonical, layout, zoom))
    }

    @Test fun exactPageBoundaryIsAcceptedButNoPixelsOutsidePageAreOwned() {
        val leftTop = point("portrait", 0.0, 0.0)
        val rightBottom = point("portrait", 595.276, 841.89)
        val result = capture(listOf(sample(leftTop, 0), sample(rightBottom, 10)))
        assertNotNull(result)
        val outside = point("portrait", 595.276 + 2.0, 10.0)
        assertNull(capture(listOf(sample(leftTop, 0), sample(outside, 10))))
    }

    @Test fun nonFiniteOrInvalidViewportTransformCannotCapture() {
        assertNull(capture(listOf(sample(PxPoint(Double.NaN, 20.0), 0))))
        assertNull(capture(listOf(sample(PxPoint(60.0, Double.POSITIVE_INFINITY), 0))))
        assertNull(capture(listOf(sample(point("portrait", 30.0, 30.0), 0)),
            transform = ViewportTransform(0.0, 0.0, 0.0)))
    }
}
