package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * EDT-16 1E-1: read-only, bounded partial-erase geometry for an IDENTITY-transform
 * canonical stroke whose entire painted footprint is inside its owning page.
 *
 * The renderer paints a constant-width polyline with round caps/joins. In this
 * restricted case, removing points whose distance to the swept eraser path is
 * <= eraserRadius + strokeWidth/2 leaves each retained fragment's round cap
 * outside the eraser's painted disk (apart from boundary tangency).
 *
 * This is deliberately NOT a persistence command or general-affine splitter.
 * Unsupported transforms, page-clipped strokes and ambiguous geometry fail
 * closed; later checkpoints must add those cases before enabling partial erase.
 */
internal object EditorEraserPartialStrokeGeometry {
    sealed interface Result {
        data object Unchanged : Result
        data object Removed : Result
        data class Fragments(val pieces: List<List<StoredStrokeSample>>) : Result
        data class Unresolved(val reason: String) : Result
    }

    private data class Range(val start: Double, val end: Double)
    private const val MAX_SAMPLES = 8192
    private const val MAX_PAIR_CHECKS = 250_000L
    private const val MAX_ABS_COORDINATE = 1.0e7
    private const val MAX_FRAGMENTS = 8192
    private const val PARAM_EPS = 1.0e-10
    private const val MIN_FRAGMENT_LENGTH_DU = 1.0e-7
    private val IDENTITY = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /** Caller must pass the actual owning page dimensions in DU. */
    fun split(
        stroke: StoredCanonicalStroke,
        pageWidthDu: Double,
        pageHeightDu: Double,
        eraserPointsPageDu: List<Point>,
        eraserRadiusPageDu: Double,
    ): Result {
        require(pageWidthDu.isFinite() && pageWidthDu > 0.0)
        require(pageHeightDu.isFinite() && pageHeightDu > 0.0)
        require(eraserRadiusPageDu.isFinite() && eraserRadiusPageDu >= 0.0)
        require(stroke.brush.widthDu.isFinite() && stroke.brush.widthDu > 0.0)
        require(stroke.brush.opacity.isFinite() && stroke.brush.opacity in 0.0..1.0)
        require(stroke.samples.isNotEmpty())
        require(stroke.samples.all {
            it.xDu.isFinite() && it.yDu.isFinite() && it.tMs >= 0L
        })
        require(stroke.samples.zipWithNext().all { (a, b) -> a.tMs <= b.tMs })
        require(eraserPointsPageDu.all { it.x.isFinite() && it.y.isFinite() })

        if (stroke.locked || eraserPointsPageDu.isEmpty() ||
            (stroke.brush.opacity * 255.0).roundToInt() == 0
        ) return Result.Unchanged
        if (stroke.elementTransform.coefficients() != IDENTITY) {
            return Result.Unresolved("Non-identity element transform requires affine splitting")
        }
        val radius = eraserRadiusPageDu + stroke.brush.widthDu / 2.0
        if (!radius.isFinite() || radius <= 0.0 ||
            pageWidthDu > MAX_ABS_COORDINATE || pageHeightDu > MAX_ABS_COORDINATE ||
            radius > MAX_ABS_COORDINATE ||
            stroke.samples.size > MAX_SAMPLES || eraserPointsPageDu.size > MAX_SAMPLES
        ) return Result.Unresolved("Geometry exceeds bounded splitter limits")
        val halfWidth = stroke.brush.widthDu / 2.0
        // For a straight polyline inside a convex rectangle, checking all
        // vertices suffices to bound the entire round-capped painted stroke.
        if (stroke.samples.any { s ->
                abs(s.xDu) > MAX_ABS_COORDINATE || abs(s.yDu) > MAX_ABS_COORDINATE ||
                    s.xDu - halfWidth <= 0.0 || s.yDu - halfWidth <= 0.0 ||
                    s.xDu + halfWidth >= pageWidthDu ||
                    s.yDu + halfWidth >= pageHeightDu
            }
        ) return Result.Unresolved("Stroke footprint intersects the page clip")
        if (eraserPointsPageDu.any {
                abs(it.x) > MAX_ABS_COORDINATE || abs(it.y) > MAX_ABS_COORDINATE ||
                    it.x < 0.0 || it.y < 0.0 ||
                    it.x > pageWidthDu || it.y > pageHeightDu
            }
        ) return Result.Unresolved("Eraser path leaves its owning page")

        val strokeSegmentCount = max(1, stroke.samples.size - 1)
        val eraserSegmentCount = max(1, eraserPointsPageDu.size - 1)
        if (strokeSegmentCount.toLong() * eraserSegmentCount > MAX_PAIR_CHECKS) {
            return Result.Unresolved("Too many segment pairs for bounded splitting")
        }
        val tolerance = boundaryTol(radius)
        if (radius <= tolerance) {
            return Result.Unresolved("Eraser radius is below numeric tolerance")
        }
        val radiusSq = radius * radius
        val removed = ArrayList<Range>()
        for (i in 0 until strokeSegmentCount) {
            val first = stroke.samples[i]
            val second = stroke.samples[min(i + 1, stroke.samples.lastIndex)]
            val a = Point(first.xDu, first.yDu)
            val b = Point(second.xDu, second.yDu)
            for (j in 0 until eraserSegmentCount) {
                val c = eraserPointsPageDu[j]
                val d = eraserPointsPageDu[min(j + 1, eraserPointsPageDu.lastIndex)]
                if (disjointBounds(a, b, c, d, radius + tolerance)) continue
                // Compare a slightly expanded and contracted capsule. A barely
                // touching pair cannot silently become a confirmed miss/hit
                // because of floating-point rounding.
                val expanded = capsuleIntervals(
                    a, b, c, d, radius + tolerance, (radius + tolerance) * (radius + tolerance)
                ) ?: return Result.Unresolved("Numerically ambiguous expanded boundary")
                if (expanded.isEmpty()) continue
                val contracted = capsuleIntervals(
                    a, b, c, d, radius - tolerance, (radius - tolerance) * (radius - tolerance)
                ) ?: return Result.Unresolved("Numerically ambiguous contracted boundary")
                if (contracted.isEmpty()) {
                    return Result.Unresolved("Near-tangential eraser contact")
                }
                val ranges = capsuleIntervals(a, b, c, d, radius, radiusSq)
                    ?: return Result.Unresolved("Numerically ambiguous capsule boundary")
                if (ranges.isEmpty()) return Result.Unresolved("Inconsistent capsule classification")
                for (range in ranges) {
                    // Reject near-tangent or tiny intersections rather than
                    // guessing that they authorize a destructive replacement.
                    if (range.end - range.start <= PARAM_EPS) {
                        return Result.Unresolved("Near-tangential eraser contact")
                    }
                    removed += Range(i + range.start, i + range.end)
                }
            }
        }
        if (removed.isEmpty()) return Result.Unchanged
        removed.sortWith(compareBy<Range> { it.start }.thenBy { it.end })
        val merged = ArrayList<Range>()
        removed.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.end + PARAM_EPS) {
                merged[merged.lastIndex] = Range(last.start, max(last.end, range.end))
            } else {
                merged += range
            }
        }
        val total = (stroke.samples.size - 1).toDouble()
        if (total == 0.0) return Result.Removed
        if (merged.first().start <= PARAM_EPS && merged.last().end >= total - PARAM_EPS &&
            merged.zipWithNext().all { (a, b) -> b.start <= a.end + PARAM_EPS }
        ) return Result.Removed

        val kept = ArrayList<Range>()
        var cursor = 0.0
        for (cut in merged) {
            if (cut.start > cursor + PARAM_EPS) kept += Range(cursor, cut.start)
            cursor = max(cursor, cut.end)
        }
        if (cursor < total - PARAM_EPS) kept += Range(cursor, total)
        if (kept.isEmpty()) return Result.Removed
        if (kept.size > MAX_FRAGMENTS) return Result.Unresolved("Too many retained fragments")

        val pieces = ArrayList<List<StoredStrokeSample>>(kept.size)
        for (range in kept) {
            if (range.end <= range.start + PARAM_EPS) {
                return Result.Unresolved("Degenerate fragment")
            }
            val length = pathLength(stroke.samples, range)
            if (!length.isFinite() || length < MIN_FRAGMENT_LENGTH_DU) {
                return Result.Unresolved("Fragment is too small to render safely")
            }
            val points = fragmentSamples(stroke.samples, range)
            if (points.size < 2) return Result.Unresolved("Unrenderable fragment")
            pieces += points
        }
        return Result.Fragments(pieces)
    }

    // A capsule is the union of endpoint disks and a finite perpendicular
    // band. Clip a stroke segment's t in [0,1] against those three convex sets.
    // null means non-finite/ambiguous arithmetic, not 'no intersection'.
    private fun capsuleIntervals(
        a: Point, b: Point, c: Point, d: Point, radius: Double, radiusSq: Double,
    ): List<Range>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val segSq = dx * dx + dy * dy
        if (!segSq.isFinite()) return null
        val result = ArrayList<Range>(3)
        for (center in listOf(c, d)) {
            val px = center.x - a.x
            val py = center.y - a.y
            if (segSq == 0.0) {
                val distSq = px * px + py * py
                if (!distSq.isFinite()) return null
                if (abs(distSq - radiusSq) <= boundaryTol(radius) * max(1.0, radius)) return null
                if (distSq < radiusSq) result += Range(0.0, 1.0)
            } else {
                val t0 = (px * dx + py * dy) / segSq
                val cross = px * dy - py * dx
                val perpendicularSq = (cross * cross) / segSq
                if (!t0.isFinite() || !perpendicularSq.isFinite()) return null
                val inside = radiusSq - perpendicularSq
                if (inside < 0.0) continue
                val dt = sqrt(inside / segSq)
                val interval = intersect(Range(t0 - dt, t0 + dt), Range(0.0, 1.0))
                if (interval != null) result += interval
            }
        }
        val ex = d.x - c.x
        val ey = d.y - c.y
        val elen = hypot(ex, ey)
        if (!elen.isFinite()) return null
        if (elen != 0.0) {
            val ux = ex / elen
            val uy = ey / elen
            val ox = a.x - c.x
            val oy = a.y - c.y
            val along = linearBand(ox * ux + oy * uy, dx * ux + dy * uy, 0.0, elen)
            val across0 = -ox * uy + oy * ux
            val acrossSlope = -dx * uy + dy * ux
            val across = linearBand(across0, acrossSlope, -radius, radius)
            if (along != null && across != null) {
                val rect = intersect(intersect(along, across), Range(0.0, 1.0))
                if (rect != null) {
                    if (abs(acrossSlope) <= 1.0e-13 &&
                        abs(abs(across0) - radius) <= boundaryTol(radius)
                    ) return null
                    result += rect
                }
            }
        }
        return result.takeIf { ranges -> ranges.all {
            it.start.isFinite() && it.end.isFinite()
        } }
    }

    private fun linearBand(value: Double, slope: Double, low: Double, high: Double): Range? {
        if (!value.isFinite() || !slope.isFinite()) return null
        if (slope == 0.0) return if (value >= low && value <= high) {
            Range(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        } else null
        val a = (low - value) / slope
        val b = (high - value) / slope
        return Range(min(a, b), max(a, b))
    }

    private fun intersect(first: Range?, second: Range): Range? =
        first?.let {
            val lo = max(it.start, second.start)
            val hi = min(it.end, second.end)
            if (lo <= hi) Range(lo, hi) else null
        }

    private fun boundaryTol(radius: Double): Double = 1.0e-9 * max(1.0, radius)

    private fun disjointBounds(a: Point, b: Point, c: Point, d: Point, margin: Double): Boolean =
        max(a.x, b.x) < min(c.x, d.x) - margin ||
            min(a.x, b.x) > max(c.x, d.x) + margin ||
            max(a.y, b.y) < min(c.y, d.y) - margin ||
            min(a.y, b.y) > max(c.y, d.y) + margin

    private fun fragmentSamples(samples: List<StoredStrokeSample>, range: Range): List<StoredStrokeSample> {
        val result = ArrayList<StoredStrokeSample>()
        result += pointAt(samples, range.start)
        var k = floor(range.start).toInt() + 1
        while (k < range.end - PARAM_EPS) {
            result += samples[k]
            k++
        }
        result += pointAt(samples, range.end)
        return result
    }

    private fun pointAt(samples: List<StoredStrokeSample>, position: Double): StoredStrokeSample {
        val index = floor(position).toInt().coerceIn(0, samples.lastIndex)
        val part = position - index
        if (part <= PARAM_EPS || index == samples.lastIndex) return samples[index]
        if (part >= 1.0 - PARAM_EPS) return samples[index + 1]
        val a = samples[index]
        val b = samples[index + 1]
        fun lerp(start: Double, end: Double): Double = start + (end - start) * part
        fun nullable(start: Double?, end: Double?): Double? =
            if (start != null && end != null) lerp(start, end) else null
        return StoredStrokeSample(
            xDu = lerp(a.xDu, b.xDu),
            yDu = lerp(a.yDu, b.yDu),
            tMs = a.tMs + ((b.tMs - a.tMs).toDouble() * part).roundToLong(),
            pressure = nullable(a.pressure, b.pressure),
            tiltRad = nullable(a.tiltRad, b.tiltRad),
            orientationRad = nullable(a.orientationRad, b.orientationRad),
        )
    }

    private fun pathLength(samples: List<StoredStrokeSample>, range: Range): Double {
        var length = 0.0
        val startIndex = floor(range.start).toInt().coerceIn(0, samples.lastIndex)
        val endIndex = floor(range.end).toInt().coerceIn(0, samples.lastIndex)
        val a = pointAt(samples, range.start)
        val b = pointAt(samples, range.end)
        if (startIndex == endIndex) return hypot(b.xDu - a.xDu, b.yDu - a.yDu)
        length += hypot(samples[startIndex + 1].xDu - a.xDu, samples[startIndex + 1].yDu - a.yDu)
        for (i in startIndex + 1 until endIndex) {
            length += hypot(samples[i + 1].xDu - samples[i].xDu, samples[i + 1].yDu - samples[i].yDu)
        }
        length += hypot(b.xDu - samples[endIndex].xDu, b.yDu - samples[endIndex].yDu)
        return length
    }
}
