package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Read-only conservative interval solver for a general-affine, constant-width
 * round-capped stroke against a circular eraser swept along one page-space segment.
 *
 * The painted footprint is (A * local round brush) + translated centerline.
 * The eraser's swept region is a page-space capsule. Their Minkowski sum is
 * convex. Its support function for a unit normal u is
 *    max(u.c, u.d) + eraserRadius + localBrushRadius * length(A^T u).
 *
 * Finite support planes give an OUTER enclosure of the true overlap interval;
 * convex hulls of genuine support points give an INNER enclosure. Only when
 * both agree within a bounded page-space error do we return the slightly
 * larger outer cut, preventing a false un-erased brush cap. Otherwise unresolved.
 *
 * This is deliberately not a raster approximation or a document mutation.
 */
internal class EditorEraserAffinePartialIntervals(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val brushRadius: Double,
    private val eraserRadius: Double,
) {
    data class Interval(val start: Double, val end: Double)

    sealed interface Verdict {
        data object NoHit : Verdict
        data class Hit(val interval: Interval) : Verdict
        data object Unresolved : Verdict
    }


    private data class Support(val ux: Double, val uy: Double, val x: Double, val y: Double, val radius: Double)
    private val normals: List<Support>
    private val valid: Boolean
    private val permittedErrorDu: Double

    init {
        val scaleX = hypot(a, b)
        val scaleY = hypot(c, d)
        val maxScale = max(scaleX, scaleY)
        val detNormalized = if (scaleX > 0 && scaleY > 0) {
            (a / scaleX) * (d / scaleY) - (b / scaleX) * (c / scaleY)
        } else 0.0
        valid = listOf(a, b, c, d, brushRadius, eraserRadius, maxScale).all(Double::isFinite) &&
            brushRadius > 0.0 && eraserRadius >= 0.0 &&
            maxScale > 0.0 && abs(detNormalized) >= 1.0e-12 &&
            maxScale * brushRadius <= 1.0e7 &&
            eraserRadius + maxScale * brushRadius <= 1.0e7
        val support = ArrayList<Support>(NORMALS)
        if (valid) {
            for (index in 0 until NORMALS) {
                val theta = 2.0 * PI * index / NORMALS
                val ux = cos(theta)
                val uy = sin(theta)
                val ax = a * ux + b * uy
                val ay = c * ux + d * uy
                val norm = hypot(ax, ay)
                if (!norm.isFinite() || norm <= 0.0) break
                // Exact support point of A*(brush disk) + (eraser disk).
                val x = brushRadius * (a * ax + c * ay) / norm + eraserRadius * ux
                val y = brushRadius * (b * ax + d * ay) / norm + eraserRadius * uy
                val h = brushRadius * norm + eraserRadius
                if (!x.isFinite() || !y.isFinite() || !h.isFinite()) break
                support += Support(ux, uy, x, y, h)
            }
        }
        normals = support
        permittedErrorDu = min(0.02, max(0.003, 0.006 * (eraserRadius + brushRadius * maxScale)))
    }

    fun classify(strokeStart: Point, strokeEnd: Point, eraserStart: Point, eraserEnd: Point): Verdict {
        if (!valid || normals.size != NORMALS) return Verdict.Unresolved
        if (!(listOf(strokeStart, strokeEnd, eraserStart, eraserEnd).all {
                it.x.isFinite() && it.y.isFinite() &&
                    abs(it.x) <= 1.0e7 && abs(it.y) <= 1.0e7
            })) return Verdict.Unresolved
        val outside = intersectOuter(strokeStart, strokeEnd, eraserStart, eraserEnd)
            ?: return Verdict.NoHit
        val vertices = ArrayList<Point>(NORMALS * 2)
        for (support in normals) {
            // Move very slightly into the convex body to offset any rounding
            // error that could otherwise turn an inner point into an outer one.
            val x = support.x * (1.0 - 1.0e-12)
            val y = support.y * (1.0 - 1.0e-12)
            vertices += Point(eraserStart.x + x, eraserStart.y + y)
            vertices += Point(eraserEnd.x + x, eraserEnd.y + y)
        }
        val hull = convexHull(vertices)
        if (hull.size < 3) return Verdict.Unresolved
        val inside = intersectPolygon(strokeStart, strokeEnd, hull) ?: return Verdict.Unresolved
        val length = hypot(strokeEnd.x - strokeStart.x, strokeEnd.y - strokeStart.y)
        if (!length.isFinite()) return Verdict.Unresolved
        // The inner polygon certifies a real collision; the outer planes bound
        // how much extra ink can be trimmed. Refuse poorly approximated cuts.
        if ((inside.start - outside.start) * length > permittedErrorDu ||
            (outside.end - inside.end) * length > permittedErrorDu ||
            (outside.end - outside.start) * length <= 1.0e-8 ||
            (inside.end - inside.start) * length <= 1.0e-9 && length > 1.0e-8
        ) return Verdict.Unresolved
        return Verdict.Hit(outside)
    }

    private fun intersectOuter(aPoint: Point, bPoint: Point, cPoint: Point, dPoint: Point): Interval? {
        var lower = 0.0
        var upper = 1.0
        val dx = bPoint.x - aPoint.x
        val dy = bPoint.y - aPoint.y
        for (normal in normals) {
            val h = max(normal.ux * cPoint.x + normal.uy * cPoint.y,
                normal.ux * dPoint.x + normal.uy * dPoint.y) + normal.radius + OUTER_PAD
            val value = normal.ux * aPoint.x + normal.uy * aPoint.y
            val slope = normal.ux * dx + normal.uy * dy
            if (!h.isFinite() || !value.isFinite() || !slope.isFinite()) return Interval(0.0, 1.0)
            if (slope > 0.0) upper = min(upper, (h - value) / slope)
            else if (slope < 0.0) lower = max(lower, (h - value) / slope)
            else if (value > h) return null
            if (lower > upper) return null
        }
        return Interval(lower, upper)
    }

    private fun intersectPolygon(aPoint: Point, bPoint: Point, hull: List<Point>): Interval? {
        var lower = 0.0
        var upper = 1.0
        val dx = bPoint.x - aPoint.x
        val dy = bPoint.y - aPoint.y
        for (i in hull.indices) {
            val p = hull[i]
            val q = hull[(i + 1) % hull.size]
            val ex = q.x - p.x
            val ey = q.y - p.y
            val value = ex * (aPoint.y - p.y) - ey * (aPoint.x - p.x)
            val slope = ex * dy - ey * dx
            if (!value.isFinite() || !slope.isFinite()) return null
            if (slope > 0) lower = max(lower, -value / slope)
            else if (slope < 0) upper = min(upper, -value / slope)
            else if (value < 0.0) return null
            if (lower > upper) return null
        }
        return Interval(lower, upper)
    }

    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy<Point> { it.x }.thenBy { it.y }).distinct()
        if (sorted.size <= 2) return sorted
        fun cross(o: Point, a: Point, b: Point) =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Point>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0.0) {
                lower.removeAt(lower.lastIndex)
            }
            lower += p
        }
        val upper = ArrayList<Point>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0.0) {
                upper.removeAt(upper.lastIndex)
            }
            upper += p
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private companion object {
        const val NORMALS = 128
        const val OUTER_PAD = 1.0e-8
    }
}
