package com.notebook.app.ui.editor.shapes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.notebook.app.ui.editor.rendering.PageElementCommand
import com.notebook.app.ui.editor.ink.StrokeViewportAffine
import com.notebook.app.ui.editor.rendering.renderFloat
import com.notebook.app.ui.editor.rendering.renderMatrix
import kotlin.math.roundToInt

/**
 * Element-local DU painter, shared by retained output and creation previews.
 * The caller applies the owning-page clip. Never changes or normalizes source JSON.
 * Uses the accepted ADR-0002 open-head convention.
 */
internal object CanonicalShapeCanvasRenderer {
    class CommandRenderer {
        private val path = Path()
        private var preparedClosed = false
        private var preparedDot = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun draw(canvas: Canvas, command: PageElementCommand.Shape) = draw(
            canvas, command.shape.kind, command.geometry, command.shape.stroke,
            command.shape.fill, command.shape.arrowHead, command.localToViewport,
        )

        /** Actual visible local paint, reused for clipped shape-only targeting. */
        fun paintedPath(shape: StoredCanonicalShape): Path {
            draw(null, shape.kind, shape.geometry, shape.stroke, shape.fill, shape.arrowHead,
                StrokeViewportAffine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
            val result = Path()
            if (preparedClosed && shape.fill?.let { (it.opacity * 255).roundToInt() > 0 } == true)
                result.set(path)
            if ((shape.stroke.opacity * 255).roundToInt() > 0) {
                val outline = Path()
                paint.style = if (preparedDot) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = shape.stroke.widthDu.renderFloat()
                require(paint.getFillPath(path, outline)) { "Cannot prepare shape outline" }
                require(result.op(outline, Path.Op.UNION)) { "Cannot combine shape paint" }
            }
            return result
        }

        /** Validate a completed creation with exactly the same path builder, without painting. */
        fun validate(
            kind: CanonicalShapeKind,
            geometry: CanonicalShapeGeometry,
            stroke: CanonicalShapeStroke,
            arrowHead: CanonicalShapeArrowHead?,
            localToViewport: StrokeViewportAffine,
        ) = draw(null, kind, geometry, stroke, null, arrowHead, localToViewport)

        /** Shared transient/retained painter. A null canvas prepares and validates only. */
        fun draw(
            canvas: Canvas?,
            kind: CanonicalShapeKind,
            geometry: CanonicalShapeGeometry,
            stroke: CanonicalShapeStroke,
            fill: CanonicalShapeFill?,
            arrowHead: CanonicalShapeArrowHead?,
            localToViewport: StrokeViewportAffine,
        ) {
            val matrix = localToViewport.renderMatrix()
            val width = stroke.widthDu.renderFloat()
            require(width > 0f) { "Shape outline width cannot be represented" }
            path.rewind()
            var dot = false
            val closed = when (val g = geometry) {
                is CanonicalShapeGeometry.Endpoints -> {
                    val x1 = g.x1.renderFloat(); val y1 = g.y1.renderFloat()
                    val x2 = g.x2.renderFloat(); val y2 = g.y2.renderFloat()
                    dot = g.x1 == g.x2 && g.y1 == g.y2
                    if (dot) {
                        require(width / 2f > 0f) { "Shape dot radius cannot be represented" }
                        path.addCircle(x1, y1, width / 2f, Path.Direction.CW)
                    } else {
                        // A finite conversion can still erase a nonzero shaft. Fail before
                        // painting/targeting rather than silently turning saved geometry into a dot.
                        require(x1 != x2 || y1 != y2) { "Shape endpoints collapse in Canvas precision" }
                        path.moveTo(x1, y1)
                        path.lineTo(x2, y2)
                        if (kind == CanonicalShapeKind.ARROW) {
                            val head = requireNotNull(arrowHead)
                            val points = ShapeEditingGeometry.headPoints(g, head)
                            val ax = points[0].x.renderFloat(); val ay = points[0].y.renderFloat()
                            val bx = points[1].x.renderFloat(); val by = points[1].y.renderFloat()
                            path.moveTo(ax, ay)
                            path.lineTo(x2, y2)
                            path.lineTo(bx, by)
                        }
                    }
                    false
                }
                is CanonicalShapeGeometry.Frame -> {
                    val left = g.x.renderFloat(); val top = g.y.renderFloat()
                    val right = (g.x + g.width).renderFloat()
                    val bottom = (g.y + g.height).renderFloat()
                    require(right > left && bottom > top) { "Shape frame cannot be represented" }
                    when (kind) {
                        CanonicalShapeKind.RECTANGLE ->
                            path.addRect(left, top, right, bottom, Path.Direction.CW)
                        CanonicalShapeKind.ELLIPSE ->
                            path.addOval(left, top, right, bottom, Path.Direction.CW)
                        else -> throw IllegalArgumentException("Shape geometry differs from kind")
                    }
                    true
                }
            }
            preparedClosed = closed
            preparedDot = dot
            // Validate all geometry before painting, so a failed shape cannot be half-filled.
            if (canvas == null) return
            val saved = canvas.save()
            try {
                canvas.concat(matrix)
                if (closed) fill?.let { fill ->
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor(fill.colorHex)
                    paint.alpha = (fill.opacity * 255).roundToInt().coerceIn(0, 255)
                    if (paint.alpha > 0) canvas.drawPath(path, paint)
                }
                paint.style = if (dot) Paint.Style.FILL else Paint.Style.STROKE
                paint.color = Color.parseColor(stroke.colorHex)
                paint.alpha = (stroke.opacity * 255).roundToInt().coerceIn(0, 255)
                paint.strokeWidth = width
                if (paint.alpha > 0) canvas.drawPath(path, paint)
            } finally {
                canvas.restoreToCount(saved)
            }
        }
    }
}
