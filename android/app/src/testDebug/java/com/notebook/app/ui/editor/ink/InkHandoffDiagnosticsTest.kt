package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkHandoffDiagnosticsTest {
    private val native = listOf(
        InkPixelSample(100.0, 200.0, 850L, pressure = 0.4),
        InkPixelSample(105.0, 205.0, 866L, pressure = null),
    )
    private fun project(samples: List<InkPixelSample>, width: Double = 6.0) =
        InkViewportPageAdapter.Projected("page-a", samples, width)

    @Test fun zeroGeometryErrorAndRelativeTimestampsPass() {
        val result = requireNotNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0].copy(elapsedTimeMs = 0L),
                native[1].copy(elapsedTimeMs = 16L))), 6.0))
        assertEquals(2, result.count)
        assertEquals(0.0, result.maxPositionErrorPx, 0.0)
        assertTrue(result.sampleTimesPreserved)
        assertTrue(result.optionalPresencePreserved)
        assertTrue(result.withinBudget)
    }

    @Test fun smallQuantizationErrorIsReportedNotFlattened() {
        val result = requireNotNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0].copy(xPx = 100.1, elapsedTimeMs = 0L),
                native[1].copy(elapsedTimeMs = 16L))), 6.0))
        assertTrue(result.maxPositionErrorPx > 0.09)
        assertTrue(result.withinBudget)
    }

    @Test fun largeGeometryMismatchIsFlagged() {
        val result = requireNotNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0].copy(xPx = 102.0, elapsedTimeMs = 0L),
                native[1].copy(elapsedTimeMs = 16L))), 6.0))
        assertFalse(result.withinBudget)
    }

    @Test fun wrongRelativeTimeAndMissingOptionalPresenceAreFlagged() {
        val result = requireNotNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0].copy(elapsedTimeMs = 0L, pressure = null),
                native[1].copy(elapsedTimeMs = 17L))), 6.0))
        assertFalse(result.sampleTimesPreserved)
        assertFalse(result.optionalPresencePreserved)
        assertFalse(result.withinBudget)
    }

    @Test fun invalidSampleCountAndNonfiniteSampleAreRejected() {
        assertNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0])), 6.0))
        assertNull(InkHandoffDiagnostics.compareOrNull(native,
            project(native.mapIndexed { index, point ->
                if (index == 0) point.copy(xPx = Double.NaN, elapsedTimeMs = 0L)
                else point.copy(elapsedTimeMs = 16L)
            }), 6.0))
    }

    @Test fun brushWidthDifferenceIsFlagged() {
        val result = requireNotNull(InkHandoffDiagnostics.compareOrNull(native,
            project(listOf(native[0].copy(elapsedTimeMs = 0L),
                native[1].copy(elapsedTimeMs = 16L)), 7.0), 6.0))
        assertEquals(1.0, result.widthErrorPx, 0.0)
        assertFalse(result.withinBudget)
    }
}
