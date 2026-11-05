package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Affine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Conservative page-clipped collision test for the complete affine brush footprint.
 *
 * CONFIRMED_VISIBLE_HIT: inner approximations intersect within the page.
 * NO_VISIBLE_HIT: outer approximations do not intersect within the page.
 * UNRESOLVED_GEOMETRY: neither claim is proven (including tangencies).
 * UNSUPPORTED_TRANSFORM: singular or numerically unstable affine transform.
 *
 * No mutations and no persistence.
 */
internal object EditorEraserAffineClipping {
    enum class Verdict {
        CONFIRMED_VISIBLE_HIT,
        NO_VISIBLE_HIT,
        UNRESOLVED_GEOMETRY,
        UNSUPPORTED_TRANSFORM,
    }

    private const val SIDES = 64
    private const val MAX_ABS_COORDINATE_DU = 1.0e9
    private const val MIN_NORMALIZED_DETERMINANT = 1.0e-12

    fun classify(
        strokePointsLocalDu: List<Point>,
        strokeWidthLocalDu: Double,
        transform: Affine,
        eraserPointsPageDu: List<Point>,
        eraserRadiusPageDu: Double,
        pageWidthDu: Double,
        pageHeightDu: Double,
    ): Verdict {
        require(strokeWidthLocalDu.isFinite() && strokeWidthLocalDu > 0.0)
        require(eraserRadiusPageDu.isFinite() && eraserRadiusPageDu >= 0.0)
        require(pageWidthDu.isFinite() && pageWidthDu > 0.0)
        require(pageHeightDu.isFinite() && pageHeightDu > 0.0)
        require(
            listOf(transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty)
                .all(Double::isFinite)
        )
        require((strokePointsLocalDu + eraserPointsPageDu).all {
            it.x.isFinite() && it.y.isFinite()
        })

        if (strokePointsLocalDu.isEmpty() || eraserPointsPageDu.isEmpty()) {
            return Verdict.NO_VISIBLE_HIT
        }

        val firstScale = hypot(transform.a, transform.b)
        val secondScale = hypot(transform.c, transform.d)
        if (!firstScale.isFinite() || !secondScale.isFinite() ||
            firstScale == 0.0 || secondScale == 0.0
        ) return Verdict.UNSUPPORTED_TRANSFORM

        val normalizedDeterminant =
            (transform.a / firstScale) * (transform.d / secondScale) -
                (transform.b / firstScale) * (transform.c / secondScale)
        if (!normalizedDeterminant.isFinite() ||
            abs(normalizedDeterminant) < MIN_NORMALIZED_DETERMINANT
        ) return Verdict.UNSUPPORTED_TRANSFORM

        // Avoid unstable floating-point cross products; this is a conservative
        // runtime limit, not a restriction on the canonical format.
        val pageScale = max(1.0, max(pageWidthDu, pageHeightDu))
        val padding = 1.0e-8 * pageScale
        val strokeRadius = strokeWidthLocalDu / 2.0
        val pointsPage = strokePointsLocalDu.map(transform::map)
        val strokeFootprintExtent = strokeRadius * max(firstScale, secondScale)
        if (!(pointsPage + strokePointsLocalDu + eraserPointsPageDu).all(::withinSafeRange) ||
            pageWidthDu > MAX_ABS_COORDINATE_DU ||
            pageHeightDu > MAX_ABS_COORDINATE_DU ||
            !strokeFootprintExtent.isFinite() ||
            strokeFootprintExtent > MAX_ABS_COORDINATE_DU / 4.0 ||
            eraserRadiusPageDu > MAX_ABS_COORDINATE_DU / 4.0 ||
            !padding.isFinite()
        ) return Verdict.UNRESOLVED_GEOMETRY

        // Conservative whole-gesture AABB prefilter.
        //
        // For a transformed circular brush, the exact
        // maximum X/Y displacements are derived from
        // the corresponding affine matrix row norms.
        //
        // Padding is outward-only: ambiguous boundaries
        // must never become false misses.
        val strokePaddingX =
            strokeRadius * hypot(transform.a, transform.c) +
                padding

        val strokePaddingY =
            strokeRadius * hypot(transform.b, transform.d) +
                padding

        val eraserPadding = eraserRadiusPageDu + padding

        if (
            !listOf(
                strokePaddingX,
                strokePaddingY,
                eraserPadding,
            ).all(Double::isFinite)
        ) {
            return Verdict.UNRESOLVED_GEOMETRY
        }

        val strokeLeft =
            pointsPage.minOf { it.x } - strokePaddingX
        val strokeRight =
            pointsPage.maxOf { it.x } + strokePaddingX
        val strokeTop =
            pointsPage.minOf { it.y } - strokePaddingY
        val strokeBottom =
            pointsPage.maxOf { it.y } + strokePaddingY

        val eraserLeft =
            eraserPointsPageDu.minOf { it.x } - eraserPadding
        val eraserRight =
            eraserPointsPageDu.maxOf { it.x } + eraserPadding
        val eraserTop =
            eraserPointsPageDu.minOf { it.y } - eraserPadding
        val eraserBottom =
            eraserPointsPageDu.maxOf { it.y } + eraserPadding

        if (
            !listOf(
                strokeLeft, strokeRight,
                strokeTop, strokeBottom,
                eraserLeft, eraserRight,
                eraserTop, eraserBottom,
            ).all(Double::isFinite)
        ) {
            return Verdict.UNRESOLVED_GEOMETRY
        }

        if (
        // Entire stroke or eraser lies outside the page.
            strokeRight < 0.0 ||
            strokeLeft > pageWidthDu ||
            strokeBottom < 0.0 ||
            strokeTop > pageHeightDu ||
            eraserRight < 0.0 ||
            eraserLeft > pageWidthDu ||
            eraserBottom < 0.0 ||
            eraserTop > pageHeightDu ||

            // Their painted bounding boxes are disjoint.
            strokeRight < eraserLeft ||
            eraserRight < strokeLeft ||
            strokeBottom < eraserTop ||
            eraserBottom < strokeTop
        ) {
            return Verdict.NO_VISIBLE_HIT
        }

        val page = listOf(
            Point(0.0, 0.0),
            Point(pageWidthDu, 0.0),
            Point(pageWidthDu, pageHeightDu),
            Point(0.0, pageHeightDu),
        )
        var unresolved = false
        for ((s0, s1) in segments(strokePointsLocalDu)) {
            for ((e0, e1) in segments(eraserPointsPageDu)) {
                // Outer bounds are strictly larger than the true painted shapes.
                val strokeOuter = capsuleHull(s0, s1, strokeRadius / cos(PI / SIDES) + padding, transform)
                val eraserOuter = capsuleHull(
                    e0, e1, eraserRadiusPageDu / cos(PI / SIDES) + padding,
                    Affine.Identity,
                )
                if (!(strokeOuter + eraserOuter).all(::withinSafeRange)) {
                    return Verdict.UNRESOLVED_GEOMETRY
                }
                if (!polygonsOverlapWithinPage(strokeOuter, eraserOuter, page)) {
                    continue
                }

                // Inner bounds are contained in the true footprints.
                val innerStrokeRadius = max(0.0, strokeRadius - padding)
                val innerEraserRadius = max(0.0, eraserRadiusPageDu - padding)
                if (innerStrokeRadius > 0.0 && innerEraserRadius > 0.0) {
                    val strokeInner = capsuleHull(s0, s1, innerStrokeRadius, transform)
                    val eraserInner = capsuleHull(e0, e1, innerEraserRadius, Affine.Identity)
                    val common = clipConvex(clipConvex(strokeInner, eraserInner), page)
                    if (polygonArea(common) > 1.0e-10 * pageScale * pageScale) {
                        return Verdict.CONFIRMED_VISIBLE_HIT
                    }
                }
                unresolved = true
            }
        }

        return if (unresolved) Verdict.UNRESOLVED_GEOMETRY else Verdict.NO_VISIBLE_HIT
    }

    private fun withinSafeRange(p: Point): Boolean =
        p.x.isFinite() && p.y.isFinite() &&
            abs(p.x) <= MAX_ABS_COORDINATE_DU &&
            abs(p.y) <= MAX_ABS_COORDINATE_DU

    private fun segments(points: List<Point>): List<Pair<Point, Point>> =
        if (points.size == 1) listOf(points[0] to points[0])
        else points.zipWithNext()

    /** Convex hull of two copies of an affine-transformed regular disk polygon. */
    private fun capsuleHull(
        a: Point,
        b: Point,
        radius: Double,
        transform: Affine,
    ): List<Point> {
        val vertices = ArrayList<Point>(SIDES * 2)
        for (center in listOf(a, b)) {
            for (i in 0 until SIDES) {
                val angle = 2.0 * PI * i / SIDES
                vertices += transform.map(
                    Point(center.x + radius * cos(angle), center.y + radius * sin(angle))
                )
            }
        }
        return hull(vertices)
    }

    private fun hull(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy<Point> { it.x }.thenBy { it.y }).distinct()
        if (sorted.size <= 2) return sorted
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
        return lower.dropLast(1) + upper.dropLast(1)
    }

    private fun cross(a: Point, b: Point, p: Point): Double =
        (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    /** Sutherland-Hodgman clipping; both polygon inputs are convex and CCW. */
    private fun clipConvex(subject: List<Point>, boundary: List<Point>): List<Point> {
        if (subject.size < 3 || boundary.size < 3) return emptyList()
        var result = subject
        for (i in boundary.indices) {
            if (result.isEmpty()) break
            val edgeStart = boundary[i]
            val edgeEnd = boundary[(i + 1) % boundary.size]
            val next = ArrayList<Point>()
            var previous = result.last()
            for (current in result) {
                val previousSide = cross(edgeStart, edgeEnd, previous)
                val currentSide = cross(edgeStart, edgeEnd, current)
                val previousInside = previousSide >= 0.0
                val currentInside = currentSide >= 0.0
                if (previousInside != currentInside) {
                    val t = previousSide / (previousSide - currentSide)
                    next += Point(
                        previous.x + t * (current.x - previous.x),
                        previous.y + t * (current.y - previous.y),
                    )
                }
                if (currentInside) next += current
                previous = current
            }
            result = next
        }
        return result
    }

    private fun polygonsOverlapWithinPage(
        stroke: List<Point>, eraser: List<Point>, page: List<Point>,
    ): Boolean = clipConvex(clipConvex(stroke, eraser), page).isNotEmpty()

    private fun polygonArea(vertices: List<Point>): Double {
        if (vertices.size < 3) return 0.0
        var twiceArea = 0.0
        for (i in vertices.indices) {
            val p = vertices[i]
            val q = vertices[(i + 1) % vertices.size]
            twiceArea += p.x * q.y - p.y * q.x
        }
        return abs(twiceArea) / 2.0
    }
}
