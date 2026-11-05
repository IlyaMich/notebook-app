
package com.notebook.app.ui.editor.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.roundToInt

/**
 * Renders one transient stroke in viewport-local physical pixels.
 *
 * Like the EDT-07 saved-stroke renderer, this draws one complete
 * path per stroke. It does not convert coordinates, modify the
 * canonical document, or perform persistence.
 *
 * The caller owns page clipping.
 */
internal object EditorInkPreviewCanvasRenderer {

    fun draw(
        canvas: Canvas,
        preview: EditorPenPreview,
        brush: EditorInkBrushPreset,
    ) {
        val samples = preview.samples
        if (samples.isEmpty()) return

        val scale = preview.startTransform.scalePxPerDu
        val widthPx = (brush.widthDu * scale).toFloat()

        if (!widthPx.isFinite() || widthPx <= 0f) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(brush.colorHex)

            alpha = (brush.opacity * 255.0)
                .roundToInt()
                .coerceIn(0, 255)

            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        if (paint.alpha == 0) return

        if (samples.size == 1) {
            val point = samples.single()

            paint.style = Paint.Style.FILL

            canvas.drawCircle(
                point.xPx.toFloat(),
                point.yPx.toFloat(),
                widthPx / 2f,
                paint,
            )

            return
        }

        val path = Path()

        samples.forEachIndexed { index, sample ->
            val x = sample.xPx.toFloat()
            val y = sample.yPx.toFloat()

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        paint.style = Paint.Style.STROKE

        // One draw operation for the entire stroke,
        // avoiding repeated alpha blending at segment joins.
        canvas.drawPath(path, paint)
    }
}
