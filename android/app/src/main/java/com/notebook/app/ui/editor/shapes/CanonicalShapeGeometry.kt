package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.DuPoint
import kotlin.math.abs
import kotlin.math.min

/** Element-local DU only. These are derived payload values, never viewport geometry. */
internal sealed interface CanonicalShapeGeometry {
    data class Endpoints(val x1: Double, val y1: Double, val x2: Double, val y2: Double) :
        CanonicalShapeGeometry {
        init {
            require(listOf(x1, y1, x2, y2).all { it.isFinite() }) { "Non-finite endpoints" }
        }
    }

    data class Frame(val x: Double, val y: Double, val width: Double, val height: Double) :
        CanonicalShapeGeometry {
        init {
            require(listOf(x, y, width, height).all { it.isFinite() }) { "Non-finite frame" }
            require(width > 0.0 && height > 0.0) { "Frame dimensions must be positive" }
        }
    }

    companion object {
        /**
         * Normalize a completed local-DU drag without quantization or UI thresholds.
         * Zero-length endpoints remain schema-valid; zero-area frames do not.
         * Overflow in subtraction is rejected by the geometry constructors.
         */
        fun fromDrag(kind: CanonicalShapeKind, start: DuPoint, end: DuPoint): CanonicalShapeGeometry =
            when (kind) {
                CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW ->
                    Endpoints(start.x, start.y, end.x, end.y)
                CanonicalShapeKind.RECTANGLE, CanonicalShapeKind.ELLIPSE ->
                    Frame(min(start.x, end.x), min(start.y, end.y),
                        abs(end.x - start.x), abs(end.y - start.y))
            }
    }
}

internal enum class CanonicalShapeKind(val wireName: String) {
    LINE("line"), RECTANGLE("rectangle"), ELLIPSE("ellipse"), ARROW("arrow");

    fun accepts(geometry: CanonicalShapeGeometry): Boolean = when (this) {
        LINE, ARROW -> geometry is CanonicalShapeGeometry.Endpoints
        RECTANGLE, ELLIPSE -> geometry is CanonicalShapeGeometry.Frame
    }
}
