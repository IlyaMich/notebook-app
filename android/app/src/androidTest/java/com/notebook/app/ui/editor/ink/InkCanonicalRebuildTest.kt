package com.notebook.app.ui.editor.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual native Ink batch/mesh creation on an Android emulator/device. */
@RunWith(AndroidJUnit4::class)
class InkCanonicalRebuildTest {
    @Test fun canonicalPenRebuildAndRecapturePreservesSampleGeometry() {
        val source = CanonicalInkStroke(
            brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
            points = listOf(
                CanonicalInkPoint(10.0, 20.0, 0, pressure = 0.3),
                CanonicalInkPoint(30.0, 28.0, 17, pressure = 0.6),
                CanonicalInkPoint(50.0, 35.0, 36, pressure = 0.8),
            ),
        )
        val scale = InkPixelScale(2.0)
        val renderedNative = JetpackInkProbeAdapter.rebuild(source, scale)
        val recaptured = JetpackInkProbeAdapter.capture(renderedNative, scale)
        assertEquals(source.points.size, renderedNative.inputs.size)
        assertEquals(source.brush.widthDu, recaptured.brush.widthDu, 0.0001)
        source.points.zip(recaptured.points).forEach { (original, actual) ->
            assertEquals(original.x, actual.x, 0.01)
            assertEquals(original.y, actual.y, 0.01)
            assertEquals(original.tMs, actual.tMs)
            assertEquals(original.pressure!!, actual.pressure!!, 0.005)
        }
    }

    @Test fun absenceOfOptionalPressureSurvivesNativeRebuild() {
        val canonical = CanonicalInkStroke(
            brush = CanonicalInkBrush("pen", 2.5, "#2255AA", 1.0),
            points = listOf(
                CanonicalInkPoint(10.0, 10.0, 0),
                CanonicalInkPoint(20.0, 20.0, 12),
            ),
        )
        val native = JetpackInkProbeAdapter.rebuild(canonical, InkPixelScale(2.4))
        val captured = JetpackInkProbeAdapter.capture(native, InkPixelScale(2.4))
        assertNull(captured.points.first().pressure)
        assertNull(captured.points.last().pressure)
    }
}
