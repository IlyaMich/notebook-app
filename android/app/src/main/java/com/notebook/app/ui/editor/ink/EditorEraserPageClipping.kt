
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only visibility classification for eraser hits.
 *
 * Inputs are page-DU geometry after applying a supported
 * similarity transform to the canonical stroke.
 *
 * Stroke radius includes the transformed brush width.
 * Unresolved edge cases are never treated as safe hits.
 */
internal object EditorEraserPageClipping {

    enum class Verdict {
        CONFIRMED_VISIBLE_HIT,
        NO_VISIBLE_HIT,
        UNRESOLVED_BOUNDARY,
    }

    private data class Bounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        fun outsidePage(width: Double, height: Double) =
            right < 0.0 ||
                bottom < 0.0 ||
                left > width ||
                top > height

        fun completelyInside(width: Double, height: Double) =
            left >= 0.0 &&
                top >= 0.0 &&
                right <= width &&
                bottom <= height
    }

    fun classify(
        strokePointsPageDu: List<Point>,
        strokeRadiusPageDu: Double,
        eraserPointsPageDu: List<Point>,
        eraserRadiusPageDu: Double,
        pageWidthDu: Double,
        pageHeightDu: Double,
    ): Verdict {
        require(
            pageWidthDu.isFinite() &&
                pageHeightDu.isFinite() &&
                pageWidthDu > 0.0 &&
                pageHeightDu > 0.0
        )

        require(
            strokeRadiusPageDu.isFinite() &&
                strokeRadiusPageDu > 0.0 &&
                eraserRadiusPageDu.isFinite() &&
                eraserRadiusPageDu >= 0.0
        )

        require(
            (strokePointsPageDu + eraserPointsPageDu).all {
                it.x.isFinite() && it.y.isFinite()
            }
        )

        if (
            strokePointsPageDu.isEmpty() ||
            eraserPointsPageDu.isEmpty()
        ) {
            return Verdict.NO_VISIBLE_HIT
        }

        val strokeWidth = strokeRadiusPageDu * 2.0
        require(strokeWidth.isFinite())

        // First check whether the un-clipped painted
        // stroke and eraser actually intersect.
        if (
            !EditorEraserGeometry.intersectsStroke(
                strokePoints = strokePointsPageDu,
                strokeWidthDu = strokeWidth,
                eraserPoints = eraserPointsPageDu,
                eraserRadiusDu = eraserRadiusPageDu,
            )
        ) {
            return Verdict.NO_VISIBLE_HIT
        }

        val strokeBounds = bounds(
            strokePointsPageDu,
            strokeRadiusPageDu,
        )
        val eraserBounds = bounds(
            eraserPointsPageDu,
            eraserRadiusPageDu,
        )

        // If either entire painted footprint is outside
        // the page, no visible collision is possible.
        if (
            strokeBounds.outsidePage(
                pageWidthDu, pageHeightDu
            ) ||
            eraserBounds.outsidePage(
                pageWidthDu, pageHeightDu
            )
        ) {
            return Verdict.NO_VISIBLE_HIT
        }

        // Since a collision was already established,
        // containment of either complete footprint
        // proves the collision lies inside the page.
        if (
            strokeBounds.completelyInside(
                pageWidthDu, pageHeightDu
            ) ||
            eraserBounds.completelyInside(
                pageWidthDu, pageHeightDu
            )
        ) {
            return Verdict.CONFIRMED_VISIBLE_HIT
        }

        // Clip both centerlines to the page rectangle.
        // If their thickened segments intersect, the
        // convexity of the rectangle guarantees a
        // visible intersection within the page.
        val strokeSegments = clippedSegments(
            strokePointsPageDu,
            pageWidthDu,
            pageHeightDu,
        )

        val eraserSegments = clippedSegments(
            eraserPointsPageDu,
            pageWidthDu,
            pageHeightDu,
        )

        for (stroke in strokeSegments) {
            for (eraser in eraserSegments) {
                if (
                    EditorEraserGeometry.intersectsStroke(
                        strokePoints = listOf(
                            stroke.first,
                            stroke.second,
                        ),
                        strokeWidthDu = strokeWidth,
                        eraserPoints = listOf(
                            eraser.first,
                            eraser.second,
                        ),
                        eraserRadiusDu =
                            eraserRadiusPageDu,
                    )
                ) {
                    return Verdict.CONFIRMED_VISIBLE_HIT
                }
            }
        }

        // Thick footprints may still overlap the page
        // even if their centerlines do not provide a
        // direct witness. Do not guess HIT or MISS.
        return Verdict.UNRESOLVED_BOUNDARY
    }

    private fun bounds(
        points: List<Point>,
        radius: Double,
    ): Bounds {
        val result = Bounds(
            left = points.minOf { it.x } - radius,
            top = points.minOf { it.y } - radius,
            right = points.maxOf { it.x } + radius,
            bottom = points.maxOf { it.y } + radius,
        )

        require(
            listOf(
                result.left,
                result.top,
                result.right,
                result.bottom,
            ).all(Double::isFinite)
        )

        return result
    }

    private fun clippedSegments(
        points: List<Point>,
        width: Double,
        height: Double,
    ): List<Pair<Point, Point>> {
        val segments = if (points.size == 1) {
            listOf(points.single() to points.single())
        } else {
            points.zipWithNext()
        }

        return segments.mapNotNull { (start, end) ->
            clipSegment(start, end, width, height)
        }
    }

    /**
     * Liang-Barsky segment clipping.
     * Returns a segment wholly inside the page, or null.
     */
    private fun clipSegment(
        start: Point,
        end: Point,
        width: Double,
        height: Double,
    ): Pair<Point, Point>? {
        val dx = end.x - start.x
        val dy = end.y - start.y

        require(dx.isFinite() && dy.isFinite())

        var lower = 0.0
        var upper = 1.0

        fun constrain(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0

            val t = q / p
            require(t.isFinite())

            if (p < 0.0) {
                if (t > upper) return false
                lower = max(lower, t)
            } else {
                if (t < lower) return false
                upper = min(upper, t)
            }

            return lower <= upper
        }

        if (
            !constrain(-dx, start.x) ||
            !constrain(dx, width - start.x) ||
            !constrain(-dy, start.y) ||
            !constrain(dy, height - start.y)
        ) {
            return null
        }

        fun pointAt(t: Double) = Point(
            x = (start.x + t * dx).coerceIn(0.0, width),
            y = (start.y + t * dy).coerceIn(0.0, height),
        )

        return pointAt(lower) to pointAt(upper)
    }
}
