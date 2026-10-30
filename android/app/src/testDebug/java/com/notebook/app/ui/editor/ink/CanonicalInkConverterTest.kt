package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** No Android, Jetpack Ink, or native-library runtime needed for these JVM tests. */
class CanonicalInkConverterTest {
    private fun capture(
        points: List<InkPixelSample>,
        pxPerDu: Double = 2.0,
    ): CanonicalInkStroke = CanonicalInkConverter.capture(
        samples = points,
        scale = InkPixelScale(pxPerDu),
        brushKind = "pen",
        brushWidthPx = 6.0,
        brushColorHex = "#2255AA",
        brushOpacity = 1.0,
    )

    @Test fun coordinatesAndBrushWidthAreConvertedToDu() {
        val result = capture(listOf(
            InkPixelSample(120.0, 220.0, 100),
            InkPixelSample(160.0, 240.0, 121),
        ))
        assertEquals("sampledPointsV1", result.encoding)
        assertEquals(60.0, result.points[0].x, 0.0)
        assertEquals(110.0, result.points[0].y, 0.0)
        assertEquals(80.0, result.points[1].x, 0.0)
        assertEquals(3.0, result.brush.widthDu, 0.0)
        assertEquals("#2255AA", result.brush.colorHex)
    }

    @Test fun timesStartAtZeroAndEqualTimestampsArePreserved() {
        val result = capture(listOf(
            InkPixelSample(1.0, 2.0, 900),
            InkPixelSample(3.0, 4.0, 900),
            InkPixelSample(4.0, 5.0, 932),
        ))
        assertEquals(listOf(0L, 0L, 32L), result.points.map { it.tMs })
    }

    @Test fun missingOptionalValuesRemainAbsent() {
        val result = capture(listOf(InkPixelSample(1.0, 2.0, 0)))
        assertNull(result.points.single().pressure)
        assertNull(result.points.single().tiltRad)
        assertNull(result.points.single().orientationRad)
    }

    @Test fun optionalValuesRoundTripWithoutLossInPureMapping() {
        val result = capture(listOf(
            InkPixelSample(40.0, 50.0, 200, pressure = 0.4, tiltRad = 0.3, orientationRad = 1.2),
            InkPixelSample(60.0, 70.0, 212, pressure = 0.7, tiltRad = 0.2, orientationRad = 1.8),
        ))
        val projected = CanonicalInkConverter.projectForRendering(result, InkPixelScale(2.0))
        assertEquals(40.0, projected[0].xPx, 0.0)
        assertEquals(50.0, projected[0].yPx, 0.0)
        assertEquals(0L, projected[0].elapsedTimeMs)
        assertEquals(0.4, projected[0].pressure!!, 0.0)
        assertEquals(0.3, projected[0].tiltRad!!, 0.0)
        assertEquals(1.2, projected[0].orientationRad!!, 0.0)
        assertEquals(12L, projected[1].elapsedTimeMs)
    }

    @Test fun viewportResizingChangesPixelsNotCanonicalPoints() {
        val stroke = capture(listOf(InkPixelSample(200.0, 400.0, 100)), 2.0)
        val twiceZoomed = CanonicalInkConverter.projectForRendering(stroke, InkPixelScale(4.0))
        assertEquals(100.0, stroke.points.single().x, 0.0)
        assertEquals(200.0, stroke.points.single().y, 0.0)
        assertEquals(400.0, twiceZoomed.single().xPx, 0.0)
        assertEquals(800.0, twiceZoomed.single().yPx, 0.0)
    }

    @Test fun rejectedBadScaleAndNonFiniteValues() {
        expectInvalid { InkPixelScale(0.0) }
        expectInvalid { InkPixelScale(Double.NaN) }
        expectInvalid { capture(listOf(InkPixelSample(Double.POSITIVE_INFINITY, 3.0, 0))) }
        expectInvalid { CanonicalInkBrush("pen", Double.NaN, "#2255AA", 1.0) }
    }

    @Test fun rejectsDescendingTimestampsAndInvalidPressure() {
        expectInvalid { capture(listOf(InkPixelSample(0.0, 0.0, 100), InkPixelSample(1.0, 1.0, 99))) }
        expectInvalid { capture(listOf(InkPixelSample(0.0, 0.0, 0, pressure = 1.2))) }
        expectInvalid { capture(emptyList()) }
    }

    @Test fun unknownBrushAndInvalidOrientationAreRejectedOrKeptExplicit() {
        expectInvalid { CanonicalInkBrush("native-special", 2.0, "#2255AA", 1.0) }
        expectInvalid { CanonicalInkPoint(0.0, 0.0, 0, orientationRad = Double.NaN) }
        // FMT-04 permits finite canonical orientation beyond the native Ink [0,2pi) range.
        val point = CanonicalInkPoint(0.0, 0.0, 0, orientationRad = -0.4)
        assertTrue(point.orientationRad!! < 0)
    }

    private inline fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
