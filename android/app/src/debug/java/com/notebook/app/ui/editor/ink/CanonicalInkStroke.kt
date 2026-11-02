package com.notebook.app.ui.editor.ink

/**
 * EDT-05 CP1B: in-memory canonical DTOs used only by the replaceable Android ink probe.
 * Names and units mirror FMT-04's sampledPointsV1 payload; this is NOT a second wire schema.
 * No Jetpack Ink, Compose, Room, viewport, or native SDK types appear in this file.
 */
internal data class CanonicalInkPoint(
    val x: Double,
    val y: Double,
    val tMs: Long,
    val pressure: Double? = null,
    val tiltRad: Double? = null,
    val orientationRad: Double? = null,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Coordinates must be finite" }
        require(tMs >= 0) { "Relative time must be nonnegative" }
        require(pressure == null || (pressure.isFinite() && pressure in 0.0..1.0))
        require(tiltRad == null || (tiltRad.isFinite() && tiltRad in 0.0..Math.PI / 2))
        require(orientationRad == null || orientationRad.isFinite())
    }
}

internal data class CanonicalInkBrush(
    val kind: String,
    val widthDu: Double,
    val colorHex: String,
    val opacity: Double,
) {
    init {
        require(kind in setOf("pen", "pencil", "highlighter")) { "Unsupported canonical brush" }
        require(widthDu.isFinite() && widthDu > 0) { "Brush width must be positive DU" }
        require(Regex("#[0-9a-fA-F]{6}").matches(colorHex)) { "Expected #RRGGBB" }
        require(opacity.isFinite() && opacity in 0.0..1.0)
    }
}

internal data class CanonicalInkStroke(
    val brush: CanonicalInkBrush,
    val points: List<CanonicalInkPoint>,
) {
    val encoding: String = "sampledPointsV1"

    init {
        require(points.isNotEmpty()) { "A stroke must contain at least one point" }
        require(points.zipWithNext().all { (a, b) -> b.tMs >= a.tMs }) {
            "Stroke samples must retain nondecreasing timestamps"
        }
    }
}

/** One native-space point before/after a screen-PX <-> document-DU conversion. */
internal data class InkPixelSample(
    val xPx: Double,
    val yPx: Double,
    val elapsedTimeMs: Long,
    val pressure: Double? = null,
    val tiltRad: Double? = null,
    val orientationRad: Double? = null,
)

internal data class InkPixelScale(val pxPerDu: Double) {
    init {
        require(pxPerDu.isFinite() && pxPerDu > 0) { "pxPerDu must be finite and positive" }
    }
}

/** Pure conversion. Native Float quantization belongs only to the renderer adapter. */
internal object CanonicalInkConverter {
    fun capture(
        samples: List<InkPixelSample>,
        scale: InkPixelScale,
        brushKind: String,
        brushWidthPx: Double,
        brushColorHex: String,
        brushOpacity: Double,
    ): CanonicalInkStroke {
        require(samples.isNotEmpty()) { "Cannot capture empty native stroke" }
        require(brushWidthPx.isFinite() && brushWidthPx > 0)
        val firstTime = samples.first().elapsedTimeMs
        require(firstTime >= 0) { "Native times must be nonnegative" }
        val points = samples.map { sample ->
            require(sample.elapsedTimeMs >= firstTime) { "Native times out of order" }
            CanonicalInkPoint(
                x = sample.xPx / scale.pxPerDu,
                y = sample.yPx / scale.pxPerDu,
                tMs = sample.elapsedTimeMs - firstTime,
                pressure = sample.pressure,
                tiltRad = sample.tiltRad,
                orientationRad = sample.orientationRad,
            )
        }
        return CanonicalInkStroke(
            brush = CanonicalInkBrush(
                kind = brushKind,
                widthDu = brushWidthPx / scale.pxPerDu,
                colorHex = brushColorHex,
                opacity = brushOpacity,
            ),
            points = points,
        )
    }

    fun projectForRendering(stroke: CanonicalInkStroke, scale: InkPixelScale): List<InkPixelSample> =
        stroke.points.map { point ->
            InkPixelSample(
                xPx = point.x * scale.pxPerDu,
                yPx = point.y * scale.pxPerDu,
                elapsedTimeMs = point.tMs,
                pressure = point.pressure,
                tiltRad = point.tiltRad,
                orientationRad = point.orientationRad,
            )
        }
}
