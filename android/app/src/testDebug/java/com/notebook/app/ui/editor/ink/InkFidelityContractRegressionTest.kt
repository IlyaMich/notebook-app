package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PageSizeDu
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * EDT-05 CP5A: deterministic, JVM-only geometry/data fidelity through the
 * existing viewport -> canonical -> viewport chain.
 *
 * These tests DO NOT exercise native Jetpack Ink, pixels, pressure rendering,
 * physical latency, or Android's graphics pipeline.
 */
class InkFidelityContractRegressionTest {
    private val layout = checkNotNull(ViewportGeometry.layoutOrNull(listOf(
        PageSizeDu("portrait", 595.276, 841.89),
        PageSizeDu("landscape", 792.0, 612.0),
    )))
    private val viewport = PxSize(1200.0, 2200.0)

    private fun camera(pageId: String, pxPerDu: Double): ViewportTransform {
        val page = layout.pages.first { it.id == pageId }
        // Keep the same point visible regardless of page geometry or zoom.
        return ViewportTransform(
            pxPerDu,
            480.0 - (page.originXDu + 80.0) * pxPerDu,
            380.0 - (page.originYDu + 100.0) * pxPerDu,
        )
    }

    private fun nativeSamples(
        pageId: String,
        transform: ViewportTransform,
        points: List<DuPoint>,
    ): List<InkPixelSample> {
        val page = layout.pages.first { it.id == pageId }
        return points.mapIndexed { index, du ->
            val px = ViewportGeometry.pageToViewport(du, page, transform)
            InkPixelSample(
                xPx = px.x,
                yPx = px.y,
                elapsedTimeMs = 2_000L + index * 13L,
                pressure = if (index % 2 == 0) 0.35 + index * 0.005 else null,
                tiltRad = if (index % 3 == 0) 0.25 else null,
                orientationRad = if (index % 4 == 0) -0.4 else null,
            )
        }
    }

    private fun capture(
        samples: List<InkPixelSample>,
        transform: ViewportTransform,
        widthDu: Double = 3.25,
    ): InkViewportPageAdapter.Captured = checkNotNull(
        InkViewportPageAdapter.captureOrNull(
            samples = samples,
            layout = layout,
            viewportTransform = transform,
            viewportSize = viewport,
            brushKind = "pen",
            brushWidthPx = widthDu * transform.scalePxPerDu,
            brushColorHex = "#2255AA",
            brushOpacity = 0.8,
        ),
    )

    @Test fun twoPageShapesRetainCanonicalDataAcrossCameraReprojection() {
        val du = listOf(
            DuPoint(80.0, 100.0),
            DuPoint(86.125, 103.75),
            DuPoint(92.5, 116.0),
            DuPoint(105.25, 122.75),
        )
        for (pageId in listOf("portrait", "landscape")) {
            val initialCamera = camera(pageId, 1.4)
            val originalSamples = nativeSamples(pageId, initialCamera, du)
            val captured = capture(originalSamples, initialCamera)
            val canonicalBefore = captured.canonical.copy(
                points = captured.canonical.points.toList(),
            )
            assertEquals(pageId, captured.pageId)
            assertEquals("sampledPointsV1", captured.canonical.encoding)
            assertEquals(3.25, captured.canonical.brush.widthDu, 1e-9)
            assertEquals("#2255AA", captured.canonical.brush.colorHex)
            assertEquals(0.8, captured.canonical.brush.opacity, 0.0)
            for ((index, expected) in du.withIndex()) {
                val actual = captured.canonical.points[index]
                assertEquals(expected.x, actual.x, 1e-9)
                assertEquals(expected.y, actual.y, 1e-9)
                assertEquals(index * 13L, actual.tMs)
                assertEquals(originalSamples[index].pressure, actual.pressure)
                assertEquals(originalSamples[index].tiltRad, actual.tiltRad)
                assertEquals(originalSamples[index].orientationRad, actual.orientationRad)
            }
            for (zoom in listOf(0.85, 1.4, 2.25)) {
                val atZoom = camera(pageId, zoom)
                val projected = checkNotNull(InkViewportPageAdapter.projectOrNull(
                    pageId, captured.canonical, layout, atZoom,
                ))
                assertEquals(pageId, projected.pageId)
                assertEquals(3.25 * zoom, projected.brushWidthPx, 1e-9)
                val page = layout.pages.first { it.id == pageId }
                for ((index, expectedDu) in du.withIndex()) {
                    val expectedPx = ViewportGeometry.pageToViewport(expectedDu, page, atZoom)
                    assertEquals(expectedPx.x, projected.samples[index].xPx, 1e-8)
                    assertEquals(expectedPx.y, projected.samples[index].yPx, 1e-8)
                    assertEquals(index * 13L, projected.samples[index].elapsedTimeMs)
                    assertEquals(originalSamples[index].pressure, projected.samples[index].pressure)
                    assertEquals(originalSamples[index].tiltRad, projected.samples[index].tiltRad)
                    assertEquals(originalSamples[index].orientationRad, projected.samples[index].orientationRad)
                }
                assertEquals(canonicalBefore, captured.canonical)
            }
        }
    }

    @Test fun denseCurvePreservesConfirmedSampleHandoffWithinNumericalTolerance() {
        for (pageId in listOf("portrait", "landscape")) {
            val transform = camera(pageId, 1.9)
            val path = (0 until 64).map { index ->
                DuPoint(80.0 + index * 0.65, 100.0 + 8.0 * sin(index * 0.16))
            }
            val source = nativeSamples(pageId, transform, path)
            val captured = capture(source, transform)
            val projected = checkNotNull(InkViewportPageAdapter.projectOrNull(
                captured.pageId, captured.canonical, layout, transform,
            ))
            val result = checkNotNull(InkHandoffDiagnostics.compareOrNull(
                source, projected, 3.25 * transform.scalePxPerDu,
            ))
            assertEquals(path.size, result.count)
            assertTrue("Max geometry error is ${result.maxPositionErrorPx} px",
                result.maxPositionErrorPx < 1e-6)
            assertTrue(result.meanPositionErrorPx < 1e-6)
            assertEquals(0.0, result.widthErrorPx, 1e-9)
            assertTrue(result.sampleTimesPreserved)
            assertTrue(result.optionalPresencePreserved)
            assertTrue(result.withinBudget)
        }
    }

    @Test fun canonicalStrokeCanBeReCapturedAtDifferentZoomWithoutGeometryDrift() {
        val pageId = "portrait"
        val points = listOf(DuPoint(80.0, 100.0), DuPoint(92.25, 105.5), DuPoint(110.0, 95.0))
        val firstCamera = camera(pageId, 1.0)
        val initial = capture(nativeSamples(pageId, firstCamera, points), firstCamera)
        val canonicalSnapshot = initial.canonical.copy(points = initial.canonical.points.toList())
        val secondCamera = camera(pageId, 2.4)
        val reprojected = checkNotNull(InkViewportPageAdapter.projectOrNull(
            pageId, initial.canonical, layout, secondCamera,
        ))
        val recaptured = capture(reprojected.samples, secondCamera,
            widthDu = initial.canonical.brush.widthDu)
        assertEquals(pageId, recaptured.pageId)
        assertEquals(initial.canonical.brush, recaptured.canonical.brush)
        for (index in points.indices) {
            val before = initial.canonical.points[index]
            val after = recaptured.canonical.points[index]
            assertEquals(before.x, after.x, 1e-9)
            assertEquals(before.y, after.y, 1e-9)
            assertEquals(before.tMs, after.tMs)
            assertEquals(before.pressure, after.pressure)
            assertEquals(before.tiltRad, after.tiltRad)
            assertEquals(before.orientationRad, after.orientationRad)
        }
        assertEquals(canonicalSnapshot, initial.canonical)
    }
}
