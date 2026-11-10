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
 * EDT-16 1E-2: read-only, bounded partial-stroke splitting in element-local DU.
 *
 * Similarity transforms are handled exactly as round page-space capsules.
 * General affine brush footprints are handled using a conservative inner/outer
 * Minkowski approximation; ambiguous, excessively approximate, or costly cases
 * fail closed. Page-clipped source strokes may be split only when the eraser's
 * entire swept disk stays within the owning page, proving every contact is
 * actually visible. No edit command, JSON rewrite, or persistence is performed.
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
    private const val MAX_AFFINE_OVERLAPS = 4096L

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
        val coefficients = stroke.elementTransform.coefficients()
        if (coefficients.size != 6 || coefficients.any { !it.isFinite() }) {
            return Result.Unresolved("Invalid affine element transform")
        }
        val a = coefficients[0]
        val b = coefficients[1]
        val c = coefficients[2]
        val d = coefficients[3]
        val tx = coefficients[4]
        val ty = coefficients[5]
        val columnX = hypot(a, b)
        val columnY = hypot(c, d)
        if (!columnX.isFinite() || !columnY.isFinite() ||
            columnX <= 0.0 || columnY <= 0.0
        ) return Result.Unresolved("Degenerate affine transform")
        val normalizedDet = (a / columnX) * (d / columnY) -
            (b / columnX) * (c / columnY)
        if (!normalizedDet.isFinite() || abs(normalizedDet) < 1.0e-12) {
            return Result.Unresolved("Numerically unstable affine transform")
        }
        val maxScale = max(columnX, columnY)
        val similar = abs(columnX - columnY) <= 1.0e-12 * maxScale &&
            abs(a * c + b * d) <= 1.0e-12 * maxScale * maxScale
        val brushRadius = stroke.brush.widthDu / 2.0
        val broadRadius = eraserRadiusPageDu + brushRadius * maxScale
        val exactRadius = eraserRadiusPageDu + brushRadius * columnX
        val radius = if (similar) exactRadius else broadRadius
        if (!radius.isFinite() || radius <= 0.0 ||
            pageWidthDu > MAX_ABS_COORDINATE || pageHeightDu > MAX_ABS_COORDINATE ||
            radius > MAX_ABS_COORDINATE ||
            stroke.samples.size > MAX_SAMPLES || eraserPointsPageDu.size > MAX_SAMPLES
        ) return Result.Unresolved("Geometry exceeds bounded splitter limits")
        val transformed = stroke.samples.map { point ->
            Point(a * point.xDu + c * point.yDu + tx,
                b * point.xDu + d * point.yDu + ty)
        }
        if (transformed.any { !it.x.isFinite() || !it.y.isFinite() ||
                abs(it.x) > MAX_ABS_COORDINATE || abs(it.y) > MAX_ABS_COORDINATE
            } || stroke.samples.any {
                abs(it.xDu) > MAX_ABS_COORDINATE || abs(it.yDu) > MAX_ABS_COORDINATE
            }
        ) return Result.Unresolved("Transformed stroke exceeds safe coordinate range")

        val extentX = brushRadius * hypot(a, c)
        val extentY = brushRadius * hypot(b, d)
        if (!extentX.isFinite() || !extentY.isFinite()) {
            return Result.Unresolved("Invalid painted brush extent")
        }
        val intersectsPageClip = transformed.any { point ->
            point.x - extentX <= 0.0 || point.x + extentX >= pageWidthDu ||
                point.y - extentY <= 0.0 || point.y + extentY >= pageHeightDu
        }
        // For page-clipped strokes, a contact is certified visible only when
        // the entire swept eraser disk lies inside the page. If the disk crosses
        // the clip, unbounded brush/capsule overlap is not enough to authorize
        // cutting visible ink. No-hit remains safe to return unchanged.
        val sweepDiskInsidePage = eraserPointsPageDu.all { point ->
            point.x - eraserRadiusPageDu >= 0.0 &&
                point.y - eraserRadiusPageDu >= 0.0 &&
                point.x + eraserRadiusPageDu <= pageWidthDu &&
                point.y + eraserRadiusPageDu <= pageHeightDu
        }
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
        val affineSolver = if (similar) null else EditorEraserAffinePartialIntervals(
            a = a, b = b, c = c, d = d,
            brushRadius = brushRadius, eraserRadius = eraserRadiusPageDu,
        )
        val removed = ArrayList<Range>()
        var affineOverlapCount = 0L
        for (i in 0 until strokeSegmentCount) {
            val first = transformed[i]
            val second = transformed[min(i + 1, transformed.lastIndex)]
            for (j in 0 until eraserSegmentCount) {
                val eraserFirst = eraserPointsPageDu[j]
                val eraserSecond = eraserPointsPageDu[min(j + 1, eraserPointsPageDu.lastIndex)]
                if (disjointBounds(first, second, eraserFirst, eraserSecond,
                        radius + tolerance)) continue
                val ranges = if (similar) {
                    val expanded = capsuleIntervals(
                        first, second, eraserFirst, eraserSecond,
                        radius + tolerance, (radius + tolerance) * (radius + tolerance),
                    ) ?: return Result.Unresolved("Numerically ambiguous expanded boundary")
                    if (expanded.isEmpty()) continue
                    val contracted = capsuleIntervals(
                        first, second, eraserFirst, eraserSecond,
                        radius - tolerance, (radius - tolerance) * (radius - tolerance),
                    ) ?: return Result.Unresolved("Numerically ambiguous contracted boundary")
                    if (contracted.isEmpty()) {
                        return Result.Unresolved("Near-tangential eraser contact")
                    }
                    capsuleIntervals(first, second, eraserFirst, eraserSecond,
                        radius, radius * radius)
                        ?: return Result.Unresolved("Numerically ambiguous capsule boundary")
                } else {
                    affineOverlapCount++
                    if (affineOverlapCount > MAX_AFFINE_OVERLAPS) {
                        return Result.Unresolved("Too many affine overlap calculations")
                    }
                    when (val verdict = affineSolver!!.classify(
                        first, second, eraserFirst, eraserSecond,
                    )) {
                        EditorEraserAffinePartialIntervals.Verdict.NoHit -> continue
                        EditorEraserAffinePartialIntervals.Verdict.Unresolved ->
                            return Result.Unresolved("Ambiguous general-affine brush intersection")
                        is EditorEraserAffinePartialIntervals.Verdict.Hit ->
                            listOf(Range(verdict.interval.start, verdict.interval.end))
                    }
                }
                if (ranges.isEmpty()) {
                    return Result.Unresolved("Inconsistent eraser intersection classification")
                }
                if (intersectsPageClip && !sweepDiskInsidePage) {
                    return Result.Unresolved("Uncertain clipped-page eraser intersection")
                }
                for (range in ranges) {
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
