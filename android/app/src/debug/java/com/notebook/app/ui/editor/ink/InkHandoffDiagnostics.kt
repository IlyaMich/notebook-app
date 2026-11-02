package com.notebook.app.ui.editor.ink

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Compares the confirmed native input samples immediately to the page-local
 * canonical projection captured from those samples. This tests geometric
 * continuity, NOT perceived visual latency, pressure-brush texture, or pixels.
 */
internal object InkHandoffDiagnostics {
    private const val POSITION_BUDGET_PX = 0.5 // Provisional evaluation flag only.
    private const val WIDTH_BUDGET_PX = 0.5

    internal data class Result(
        val count: Int,
        val maxPositionErrorPx: Double,
        val meanPositionErrorPx: Double,
        val widthErrorPx: Double,
        val sampleTimesPreserved: Boolean,
        val optionalPresencePreserved: Boolean,
    ) {
        val withinBudget: Boolean get() =
            maxPositionErrorPx <= POSITION_BUDGET_PX &&
                widthErrorPx <= WIDTH_BUDGET_PX &&
                sampleTimesPreserved && optionalPresencePreserved
    }

    fun compareOrNull(
        nativeSamples: List<InkPixelSample>,
        projected: InkViewportPageAdapter.Projected,
        nativeBrushWidthPx: Double,
    ): Result? {
        if (nativeSamples.isEmpty() || nativeSamples.size != projected.samples.size ||
            !nativeBrushWidthPx.isFinite() || nativeBrushWidthPx <= 0.0 ||
            !projected.brushWidthPx.isFinite() || projected.brushWidthPx <= 0.0
        ) return null
        var largest = 0.0
        var sum = 0.0
        var timeMatches = true
        var presenceMatches = true
        val startTime = nativeSamples.first().elapsedTimeMs
        for ((index, sample) in nativeSamples.withIndex()) {
            val reconstructed = projected.samples[index]
            if (!sample.xPx.isFinite() || !sample.yPx.isFinite() ||
                !reconstructed.xPx.isFinite() || !reconstructed.yPx.isFinite()
            ) return null
            val distance = hypot(sample.xPx - reconstructed.xPx,
                sample.yPx - reconstructed.yPx)
            if (!distance.isFinite()) return null
            largest = max(largest, distance)
            sum += distance
            if (sample.elapsedTimeMs - startTime != reconstructed.elapsedTimeMs) {
                timeMatches = false
            }
            if ((sample.pressure == null) != (reconstructed.pressure == null) ||
                (sample.tiltRad == null) != (reconstructed.tiltRad == null) ||
                (sample.orientationRad == null) != (reconstructed.orientationRad == null)
            ) presenceMatches = false
        }
        val widthError = abs(nativeBrushWidthPx - projected.brushWidthPx)
        if (!sum.isFinite() || !widthError.isFinite()) return null
        return Result(nativeSamples.size, largest, sum / nativeSamples.size, widthError,
            timeMatches, presenceMatches)
    }
}
