package com.notebook.app.ui.editor.ink

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * EDT-05 CP1B. The ONLY bridge here between Jetpack Ink and canonical sampledPointsV1.
 * Debug-only; no document writes. The canonical object is never reconstructed from a
 * re-rendered native stroke merely because the view or scale changed.
 */
internal object JetpackInkProbeAdapter {
    private const val PROBE_COLOR = "#2255AA"
    private const val PROBE_BRUSH_SIZE_PX = 6.0

    fun capture(stroke: Stroke, scale: InkPixelScale): CanonicalInkStroke {
        val inputs = stroke.inputs
        val samples = (0 until inputs.size).map { index ->
            val input = inputs.get(index)
            InkPixelSample(
                xPx = input.x.toDouble(),
                yPx = input.y.toDouble(),
                elapsedTimeMs = input.elapsedTimeMillis,
                pressure = if (input.hasPressure) input.pressure.toDouble() else null,
                tiltRad = if (input.hasTilt) input.tiltRadians.toDouble() else null,
                orientationRad = if (input.hasOrientation) input.orientationRadians.toDouble() else null,
            )
        }
        return CanonicalInkConverter.capture(
            samples = samples,
            scale = scale,
            brushKind = "pen",
            brushWidthPx = PROBE_BRUSH_SIZE_PX,
            brushColorHex = PROBE_COLOR,
            brushOpacity = 1.0,
        )
    }

    fun rebuild(stroke: CanonicalInkStroke, scale: InkPixelScale): Stroke {
        require(stroke.brush.kind == "pen") {
            "CP1B native rebuild supports only the tested pressure-pen mapping"
        }
        val pixels = CanonicalInkConverter.projectForRendering(stroke, scale)
        val allHavePressure = pixels.all { it.pressure != null }
        val allHaveTilt = pixels.all { it.tiltRad != null }
        val allHaveOrientation = pixels.all { it.orientationRad != null }
        val inputs = MutableStrokeInputBatch()
        pixels.forEach { point ->
            // Ink batches require uniform optional fields and reject duplicate x/y/time
            // triplets. Never modify canonical points to make an unsupported batch fit.
            // For a mixed-optional canonical stroke, omit that field from the *rendering*
            // batch; the original canonical value remains untouched.
            inputs.add(
                InputToolType.STYLUS,
                point.xPx.checkedFloat(),
                point.yPx.checkedFloat(),
                point.elapsedTimeMs,
                StrokeInput.NO_STROKE_UNIT_LENGTH,
                if (allHavePressure) point.pressure!!.checkedFloat() else StrokeInput.NO_PRESSURE,
                if (allHaveTilt) point.tiltRad!!.checkedFloat() else StrokeInput.NO_TILT,
                if (allHaveOrientation) point.orientationRad!!.toNativeOrientation() else StrokeInput.NO_ORIENTATION,
            )
        }
        val sizePx = (stroke.brush.widthDu * scale.pxPerDu).checkedFloat()
        require(sizePx > 0f)
        val nativeBrush = Brush.createWithComposeColor(
            family = StockBrushes.pressurePen(),
            color = Color(AndroidColor.parseColor(stroke.brush.colorHex))
                .copy(alpha = stroke.brush.opacity.toFloat()),
            size = sizePx,
            epsilon = 0.1f,
        )
        return Stroke(nativeBrush, inputs.toImmutable())
    }

    /** Canonical angles are finite radians; Ink accepts only [0, 2*pi). */
    private fun Double.toNativeOrientation(): Float {
        val turn = 2.0 * Math.PI
        val wrapped = ((this % turn) + turn) % turn
        val converted = wrapped.checkedFloat()
        return if (converted >= turn.toFloat()) 0f else converted
    }

    private fun Double.checkedFloat(): Float {
        require(isFinite()) { "Non-finite native rendering value" }
        val converted = toFloat()
        require(converted.isFinite()) { "Native Float overflow" }
        return converted
    }
}
