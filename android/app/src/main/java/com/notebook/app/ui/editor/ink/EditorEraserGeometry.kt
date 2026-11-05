
package com.notebook.app.ui.editor.ink

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometric hit-testing for an eraser sweep against
 * a round-capped stroke.
 *
 * All coordinates and dimensions are expressed in the
 * same document-unit coordinate space.
 *
 * This component does not modify document contents.
 */
internal object EditorEraserGeometry {

    data class Point(
        val x: Double,
        val y: Double,
    )

    fun intersectsStroke(
        strokePoints: List<Point>,
        strokeWidthDu: Double,
        eraserPoints: List<Point>,
        eraserRadiusDu: Double,
    ): Boolean {
        require(
            strokeWidthDu.isFinite() && strokeWidthDu > 0.0
        ) {
            "Stroke width must be finite and positive."
        }

        require(
            eraserRadiusDu.isFinite() && eraserRadiusDu >= 0.0
        ) {
            "Eraser radius must be finite and nonnegative."
        }

        require(
            (strokePoints + eraserPoints).all {
                it.x.isFinite() && it.y.isFinite()
            }
        ) {
            "All geometry coordinates must be finite."
        }

        if (
            strokePoints.isEmpty() ||
            eraserPoints.isEmpty()
        ) {
            return false
        }

        val hitRadius =
            eraserRadiusDu + strokeWidthDu / 2.0

        val thresholdSquared = hitRadius * hitRadius

        // A single sample is treated as a zero-length
        // segment, preserving single-point stroke dots.
        val strokeSegmentCount =
            max(1, strokePoints.size - 1)

        val eraserSegmentCount =
            max(1, eraserPoints.size - 1)

        for (i in 0 until strokeSegmentCount) {
            val strokeStart = strokePoints[i]
            val strokeEnd = strokePoints[
                min(i + 1, strokePoints.lastIndex)
            ]

            for (j in 0 until eraserSegmentCount) {
                val eraserStart = eraserPoints[j]
                val eraserEnd = eraserPoints[
                    min(j + 1, eraserPoints.lastIndex)
                ]

                val distanceSquared = segmentDistanceSquared(
                    strokeStart,
                    strokeEnd,
                    eraserStart,
                    eraserEnd,
                )

                if (distanceSquared <= thresholdSquared) {
                    return true
                }
            }
        }

        return false
    }

    private fun segmentDistanceSquared(
        a: Point,
        b: Point,
        c: Point,
        d: Point,
    ): Double {
        if (segmentsIntersect(a, b, c, d)) {
            return 0.0
        }

        return minOf(
            pointSegmentDistanceSquared(a, c, d),
            pointSegmentDistanceSquared(b, c, d),
            pointSegmentDistanceSquared(c, a, b),
            pointSegmentDistanceSquared(d, a, b),
        )
    }

    private fun pointSegmentDistanceSquared(
        p: Point,
        a: Point,
        b: Point,
    ): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0.0) {
            return squaredDistance(p, a)
        }

        val projection =
            ((p.x - a.x) * dx + (p.y - a.y) * dy) /
                lengthSquared

        val t = projection.coerceIn(0.0, 1.0)

        val closest = Point(
            x = a.x + t * dx,
            y = a.y + t * dy,
        )

        return squaredDistance(p, closest)
    }

    private fun squaredDistance(
        a: Point,
        b: Point,
    ): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y

        return dx * dx + dy * dy
    }

    private fun orientation(
        a: Point,
        b: Point,
        c: Point,
    ): Double =
        (b.x - a.x) * (c.y - a.y) -
            (b.y - a.y) * (c.x - a.x)

    private fun onSegment(
        a: Point,
        b: Point,
        p: Point,
    ): Boolean =
        p.x >= min(a.x, b.x) &&
            p.x <= max(a.x, b.x) &&
            p.y >= min(a.y, b.y) &&
            p.y <= max(a.y, b.y)

    private fun segmentsIntersect(
        a: Point,
        b: Point,
        c: Point,
        d: Point,
    ): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)

        if (
            ((o1 > 0 && o2 < 0) ||
                (o1 < 0 && o2 > 0)) &&
            ((o3 > 0 && o4 < 0) ||
                (o3 < 0 && o4 > 0))
        ) {
            return true
        }

        return (o1 == 0.0 && onSegment(a, b, c)) ||
            (o2 == 0.0 && onSegment(a, b, d)) ||
            (o3 == 0.0 && onSegment(c, d, a)) ||
            (o4 == 0.0 && onSegment(c, d, b))
    }
}
