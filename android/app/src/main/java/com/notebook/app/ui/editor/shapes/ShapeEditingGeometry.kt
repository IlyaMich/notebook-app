package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.viewport.DuPoint
import kotlin.math.*

/** Pure DU editing and complete painted-footprint bounds, independent of viewport zoom. */
internal object ShapeEditingGeometry {
    data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        init { require(listOf(left, top, right, bottom).all { it.isFinite() }) }
        fun inside(width: Double, height: Double): Boolean =
            left >= 0.0 && top >= 0.0 && right <= width && bottom <= height
    }

    fun handles(geometry: CanonicalShapeGeometry): List<DuPoint> = when (geometry) {
        is CanonicalShapeGeometry.Endpoints -> listOf(
            DuPoint(geometry.x1, geometry.y1), DuPoint(geometry.x2, geometry.y2),
        )
        is CanonicalShapeGeometry.Frame -> listOf(
            DuPoint(geometry.x, geometry.y), DuPoint(geometry.x + geometry.width, geometry.y),
            DuPoint(geometry.x + geometry.width, geometry.y + geometry.height),
            DuPoint(geometry.x, geometry.y + geometry.height),
        )
    }

    fun inverse(transform: CanonicalElementAffine, point: DuPoint): DuPoint {
        val t = transform.coefficients()
        val determinant = t[0] * t[3] - t[1] * t[2]
        val x = point.x - t[4]; val y = point.y - t[5]
        val result = DuPoint((t[3] * x - t[2] * y) / determinant,
            (-t[1] * x + t[0] * y) / determinant)
        require(result.x.isFinite() && result.y.isFinite()) { "Cannot invert shape coordinates" }
        return result
    }

    /** Null handle means body movement: compose a page-space translation on the left. */
    fun drag(
        original: StoredCanonicalShape, handle: Int?, startPage: DuPoint, currentPage: DuPoint,
    ): ShapeEditCandidate {
        require(!original.locked)
        if (startPage == currentPage) return ShapeEditCandidate(original)
        if (handle == null) {
            val t = original.elementTransform.coefficients().toMutableList()
            t[4] += currentPage.x - startPage.x
            t[5] += currentPage.y - startPage.y
            return ShapeEditCandidate(original, transform = requireNotNull(
                CanonicalElementAffine.fromCanonicalOrNull(t),
            ) { "Invalid moved shape transform" })
        }
        val positions = handles(original.geometry)
        require(handle in positions.indices)
        val start = inverse(original.elementTransform, startPage)
        val current = inverse(original.elementTransform, currentPage)
        // Preserve the pointer-to-handle offset; a tap or off-center grab cannot jump geometry.
        val moved = DuPoint(positions[handle].x + (current.x - start.x),
            positions[handle].y + (current.y - start.y))
        val geometry = when (val g = original.geometry) {
            is CanonicalShapeGeometry.Endpoints -> if (handle == 0)
                g.copy(x1 = moved.x, y1 = moved.y) else g.copy(x2 = moved.x, y2 = moved.y)
            is CanonicalShapeGeometry.Frame -> CanonicalShapeGeometry.fromDrag(
                original.kind, positions[(handle + 2) % 4], moved,
            )
        }
        return ShapeEditCandidate(original, geometry = geometry)
    }

    fun headPoints(g: CanonicalShapeGeometry.Endpoints, head: CanonicalShapeArrowHead): List<DuPoint> {
        val dx = g.x2 - g.x1; val dy = g.y2 - g.y1
        val length = hypot(dx, dy)
        if (length == 0.0) return emptyList()
        require(length.isFinite())
        val vx = -dx / length; val vy = -dy / length
        val angle = Math.toRadians(head.angleDeg % 360.0)
        val c = cos(angle); val s = sin(angle)
        return listOf(
            DuPoint(g.x2 + head.lengthDu * (vx * c - vy * s),
                g.y2 + head.lengthDu * (vx * s + vy * c)),
            DuPoint(g.x2 + head.lengthDu * (vx * c + vy * s),
                g.y2 + head.lengthDu * (-vx * s + vy * c)),
        ).also { require(it.all { p -> p.x.isFinite() && p.y.isFinite() }) }
    }

    private fun visible(opacity: Double): Boolean = (opacity * 255.0).roundToInt() > 0

    /** Exact affine support bounds of the round-capped/joined outline and closed fill. */
    fun paintedBounds(candidate: ShapeEditCandidate): Bounds? {
        val shape = candidate.original
        val strokeVisible = visible(candidate.stroke.opacity)
        val closed = candidate.geometry is CanonicalShapeGeometry.Frame
        if (!strokeVisible && !(closed && candidate.fill?.let { visible(it.opacity) } == true)) return null
        val t = candidate.transform.coefficients()
        val radius = if (strokeVisible) candidate.stroke.widthDu / 2.0 else 0.0
        val padX = radius * hypot(t[0], t[2]); val padY = radius * hypot(t[1], t[3])
        val base = if (shape.kind == CanonicalShapeKind.ELLIPSE) {
            val g = candidate.geometry as CanonicalShapeGeometry.Frame
            val center = requireNotNull(candidate.transform.mapOrNull(DuPoint(
                g.x + g.width / 2.0, g.y + g.height / 2.0,
            )))
            val rx = hypot(t[0] * g.width / 2.0, t[2] * g.height / 2.0)
            val ry = hypot(t[1] * g.width / 2.0, t[3] * g.height / 2.0)
            Bounds(center.x - rx, center.y - ry, center.x + rx, center.y + ry)
        } else {
            val points = handles(candidate.geometry).toMutableList()
            if (shape.kind == CanonicalShapeKind.ARROW) points += headPoints(
                candidate.geometry as CanonicalShapeGeometry.Endpoints, requireNotNull(shape.arrowHead),
            )
            val mapped = points.map { requireNotNull(candidate.transform.mapOrNull(it)) }
            Bounds(mapped.minOf { it.x }, mapped.minOf { it.y },
                mapped.maxOf { it.x }, mapped.maxOf { it.y })
        }
        return Bounds(base.left - padX, base.top - padY, base.right + padX, base.bottom + padY)
    }

    fun contained(candidate: ShapeEditCandidate, width: Double, height: Double): Boolean =
        paintedBounds(candidate)?.inside(width, height) ?: true
}
