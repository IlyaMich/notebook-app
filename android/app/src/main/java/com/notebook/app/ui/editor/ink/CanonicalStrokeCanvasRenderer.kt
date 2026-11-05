
package com.notebook.app.ui.editor.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.roundToInt
import android.util.Log

/**
 * EDT-07: Simple reference renderer for persisted canonical ink.
 *
 * Draws into an Android Canvas whose coordinates are viewport
 * physical pixels, matching PageStrokeRenderPlan.
 *
 * Does not capture input, update Room, or mutate source strokes.
 *
 * Current fidelity limitations:
 * - Constant brush width per stroke.
 * - Pressure, tilt and orientation remain preserved in the
 *   canonical data but do not alter the rendered footprint.
 * - No native Jetpack Ink brush texture or wet-ink path.
 */
internal object CanonicalStrokeCanvasRenderer {

    fun draw(
        canvas: Canvas,
        plan: PageStrokeRenderPlan,
    ) {
        val clip = plan.clipPx

        val left = clip.left.canvasFloat()
        val top = clip.top.canvasFloat()
        val right = clip.right.canvasFloat()
        val bottom = clip.bottom.canvasFloat()

        val pageSaveCount = canvas.save()

        try {
            if (!canvas.clipRect(left, top, right, bottom)) {
                return
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            val path = Path()

            for (command in plan.strokes) {
                try {
                    drawStroke(canvas, paint, path, command)
                } catch (exception: IllegalArgumentException) {
                    Log.w(
                        "CanonicalInkRenderer",
                        "Unable to render stroke ${command.stroke.elementId}",
                        exception,
                    )
                }
            }
        } finally {
            canvas.restoreToCount(pageSaveCount)
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        paint: Paint,
        path: Path,
        command: StrokeRenderCommand,
    ) {
        val stroke = command.stroke
        require(stroke.samples.isNotEmpty()) {
            "Cannot render a stroke without samples"
        }

        val width = stroke.brush.widthDu.canvasFloat()
        require(width > 0f) {
            "Brush width cannot be represented"
        }

        val alpha = (stroke.brush.opacity * 255.0)
            .roundToInt()
            .coerceIn(0, 255)

        if (alpha == 0) return

        paint.color = Color.parseColor(stroke.brush.colorHex)
        paint.alpha = alpha
        paint.strokeWidth = width

        // Android Matrix uses row-major:
        // [ a c tx ]
        // [ b d ty ]
        // [ 0 0  1 ]
        val transform = command.localToViewport

        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    transform.a.canvasFloat(),
                    transform.c.canvasFloat(),
                    transform.tx.canvasFloat(),
                    transform.b.canvasFloat(),
                    transform.d.canvasFloat(),
                    transform.ty.canvasFloat(),
                    0f, 0f, 1f,
                )
            )
        }

        val strokeSaveCount = canvas.save()

        try {
            // Applying the matrix to Canvas preserves the
            // complete transformed footprint, including
            // nonuniform scaling of the brush.
            canvas.concat(matrix)

            if (stroke.samples.size == 1) {
                val point = stroke.samples.single()

                paint.style = Paint.Style.FILL

                canvas.drawCircle(
                    point.xDu.canvasFloat(),
                    point.yDu.canvasFloat(),
                    width / 2f,
                    paint,
                )
            } else {
                path.rewind()

                stroke.samples.forEachIndexed { index, point ->
                    val x = point.xDu.canvasFloat()
                    val y = point.yDu.canvasFloat()

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                paint.style = Paint.Style.STROKE
                canvas.drawPath(path, paint)
            }
        } finally {
            canvas.restoreToCount(strokeSaveCount)
        }
    }

    private fun Double.canvasFloat(): Float {
        val result = toFloat()
        require(result.isFinite()) {
            "Canonical geometry exceeds Canvas float range"
        }
        return result
    }
}
