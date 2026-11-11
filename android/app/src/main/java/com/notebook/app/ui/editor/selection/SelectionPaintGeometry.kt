package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.ink.StoredCanonicalStroke
import com.notebook.app.ui.editor.ink.StoredCanonicalStrokeDecoder
import com.notebook.app.ui.editor.shapes.*
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.Collections
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Derived local paint + element-to-page affine. No viewport, tessellation, visibility or hit claim.
 * Curved boundaries remain analytic. Canvas Float suitability is a separate, later gate.
 */
internal object SelectionPaintGeometry {
    data class Bounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        init {
            require(listOf(left, top, right, bottom).all(Double::isFinite))
            require(left <= right && top <= bottom)
        }

        /** Broad-phase candidate only: overlap of boxes never establishes visible paint. */
        fun candidateWithinPage(width: Double, height: Double): Bounds? {
            require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
            val l = maxOf(left, 0.0); val t = maxOf(top, 0.0)
            val r = minOf(right, width); val b = minOf(bottom, height)
            return if (l <= r && t <= b) Bounds(l, t, r, b) else null
        }
    }

    sealed interface Primitive {
        /** Filled capsule, including a disk when start == end. Radius is local DU. */
        data class Capsule(val start: DuPoint, val end: DuPoint, val radius: Double) : Primitive
        data class FilledFrame(val frame: CanonicalFrame) : Primitive
        /** Round-joined tube around the exact frame boundary, without a filled interior. */
        data class FrameOutline(val frame: CanonicalFrame, val radius: Double) : Primitive
        /**
         * Exact ellipse centerline plus optional local round stroke tube and filled interior.
         * The outline is a Euclidean distance-to-ellipse region, NOT concentric ellipse subtraction.
         */
        data class Ellipse(val frame: CanonicalFrame, val outlineRadius: Double, val filled: Boolean) : Primitive
    }

    sealed class Result(val pageId: String, val source: StoredElement) {
        class Painted internal constructor(
            pageId: String, source: StoredElement, val locked: Boolean,
            val affine: CanonicalElementAffine, primitives: List<Primitive>, val fullBounds: Bounds,
            val frameElement: StoredFrameElement? = null,
        ) : Result(pageId, source) {
            val primitives: List<Primitive> = Collections.unmodifiableList(ArrayList(primitives))
            // Even non-image geometry is not a certificate that Canvas can render it.
            val requiresRenderabilityCheck: Boolean get() = true
        }
        class NoPaint(pageId: String, source: StoredElement) : Result(pageId, source)
        class Unavailable(pageId: String, source: StoredElement, val reason: String) : Result(pageId, source)
        class Invalid(pageId: String, source: StoredElement, val reason: String) : Result(pageId, source)
        class NumericalFailure(pageId: String, source: StoredElement, val reason: String) : Result(pageId, source)
        class WorkloadExceeded(pageId: String, source: StoredElement) : Result(pageId, source)
    }

    /**
     * Optional caller budget counts exact derived primitives, not parser work. No selection quota
     * is approved here. C1-B2 still needs envelope/sample/query budgets before untrusted use.
     */
    fun derive(pageId: String, source: StoredElement, maxPrimitives: Int = Int.MAX_VALUE): Result {
        require(maxPrimitives >= 0)
        if (source.kind !in setOf("stroke", "shape", "text", "image"))
            return Result.Unavailable(pageId, source, "Unsupported element kind")
        val decoded: Any = try {
            com.notebook.app.data.library.LibraryValidation.canonicalId(pageId)
            StoredFrameElementDecoder.checkedEnvelope(source)
            when (source.kind) {
                "stroke" -> StoredCanonicalStrokeDecoder.decode(pageId, source)
                "shape" -> CanonicalShapeAdapter.decode(pageId, source)
                else -> StoredFrameElementDecoder.decode(pageId, source)
            }
        } catch (_: RuntimeException) {
            return Result.Invalid(pageId, source, "Invalid canonical source")
        }
        return try {
            val primitives = ArrayList<Primitive>()
            fun add(p: Primitive) {
                if (primitives.size >= maxPrimitives) throw WorkLimit()
                primitives.add(p)
            }
            fun capsule(a: DuPoint, b: DuPoint, radius: Double) {
                check(radius.isFinite() && radius > 0) { "Local radius underflow" }
                add(Primitive.Capsule(a, b, radius))
            }
            val affine: CanonicalElementAffine
            val locked: Boolean
            val frameElement = decoded as? StoredFrameElement
            when (decoded) {
                is StoredCanonicalStroke -> {
                    affine = decoded.elementTransform; locked = decoded.locked
                    if (visible(decoded.brush.opacity)) {
                        val r = decoded.brush.widthDu / 2
                        if (decoded.samples.size == 1)
                            capsule(decoded.samples[0].localPoint, decoded.samples[0].localPoint, r)
                        else for (i in 1 until decoded.samples.size)
                            capsule(decoded.samples[i - 1].localPoint, decoded.samples[i].localPoint, r)
                    }
                }
                is StoredCanonicalShape -> {
                    affine = decoded.elementTransform; locked = decoded.locked
                    val outline = visible(decoded.stroke.opacity)
                    val fill = decoded.fill?.let { visible(it.opacity) } == true
                    val radius = if (outline) decoded.stroke.widthDu / 2 else 0.0
                    if (outline) check(radius > 0) { "Local radius underflow" }
                    when (val g = decoded.geometry) {
                        is CanonicalShapeGeometry.Endpoints -> if (outline) {
                            val a = DuPoint(g.x1, g.y1); val b = DuPoint(g.x2, g.y2)
                            capsule(a, b, radius)
                            if (decoded.kind == CanonicalShapeKind.ARROW && a != b) {
                                val head = ShapeEditingGeometry.headPoints(g, requireNotNull(decoded.arrowHead))
                                capsule(head[0], b, radius); capsule(b, head[1], radius)
                            }
                        }
                        is CanonicalShapeGeometry.Frame -> {
                            val f = CanonicalFrame(g.x, g.y, g.width, g.height)
                            if (decoded.kind == CanonicalShapeKind.ELLIPSE) {
                                if (outline || fill) add(Primitive.Ellipse(f, radius, fill))
                            } else {
                                if (fill) add(Primitive.FilledFrame(f))
                                if (outline) add(Primitive.FrameOutline(f, radius))
                            }
                        }
                    }
                }
                is StoredFrameElement -> {
                    affine = decoded.transform; locked = decoded.locked
                    // A visual-frame proxy; not glyph paint, bitmap pixels or image availability.
                    if (decoded !is StoredFrameElement.Image || visible(decoded.opacity))
                        add(Primitive.FilledFrame(decoded.frame))
                }
                else -> error("Unsupported decoder")
            }
            if (primitives.isEmpty()) Result.NoPaint(pageId, source)
            else {
                checkNumericalAffine(affine)
                val bounds = primitives.map { bounds(it, affine) }
                Result.Painted(pageId, source, locked, affine, primitives,
                    Bounds(bounds.minOf { it.left }, bounds.minOf { it.top },
                        bounds.maxOf { it.right }, bounds.maxOf { it.bottom }), frameElement)
            }
        } catch (_: WorkLimit) {
            Result.WorkloadExceeded(pageId, source)
        } catch (_: RuntimeException) {
            Result.NumericalFailure(pageId, source, "Unrepresentable derived geometry")
        }
    }

    private class WorkLimit : RuntimeException()
    private fun visible(opacity: Double): Boolean = (opacity * 255).roundToInt() > 0

    /** No arbitrary determinant epsilon: reject only unrepresentable inverse calculations. */
    private fun checkNumericalAffine(t: CanonicalElementAffine) {
        val c = t.coefficients()
        val det = c[0] * c[3] - c[1] * c[2]
        check(det.isFinite() && det != 0.0)
        check(listOf(c[3] / det, c[1] / det, c[2] / det, c[0] / det).all(Double::isFinite))
        val x = (c[2] * c[5] - c[3] * c[4]) / det
        val y = (c[1] * c[4] - c[0] * c[5]) / det
        check(x.isFinite() && y.isFinite())
    }

    /** Outward-rounded intervals for support bounds, including intermediate affine arithmetic. */
    private data class Range(val lo: Double, val hi: Double = lo) {
        init { check(lo.isFinite() && hi.isFinite() && lo <= hi) }
        operator fun plus(other: Range) =
            Range(Math.nextDown(lo + other.lo), Math.nextUp(hi + other.hi))
        operator fun times(value: Double): Range {
            if (value == 0.0) return Range(0.0)
            val a = lo * value; val b = hi * value
            return Range(Math.nextDown(minOf(a, b)), Math.nextUp(maxOf(a, b)))
        }
        fun expanded(radius: Double) = Range(Math.nextDown(lo - radius), Math.nextUp(hi + radius))
    }

    private fun bounds(p: Primitive, t: CanonicalElementAffine): Bounds {
        val c = t.coefficients()
        fun map(x: Range, y: Range): Pair<Range, Range> =
            (x * c[0] + y * c[2] + Range(c[4])) to
                (x * c[1] + y * c[3] + Range(c[5]))
        fun map(point: DuPoint) = map(Range(point.x), Range(point.y))
        // Math.hypot is within one ulp; pad by two steps before multiplication.
        fun support(x: Double, y: Double, radius: Double): Double {
            if (radius == 0.0) return 0.0
            val norm = hypot(x, y)
            val result = radius * Math.nextUp(Math.nextUp(norm))
            check(result > 0 && result.isFinite()) { "Paint support underflow/overflow" }
            return Math.nextUp(result).also { check(it.isFinite()) }
        }
        val radius: Double
        val points: List<Pair<Range, Range>>
        when (p) {
            is Primitive.Capsule -> {
                points = listOf(map(p.start), map(p.end))
                radius = p.radius
            }
            is Primitive.FilledFrame, is Primitive.FrameOutline -> {
                val f = if (p is Primitive.FilledFrame) p.frame else (p as Primitive.FrameOutline).frame
                val x = Range(f.x); val y = Range(f.y)
                val right = x + Range(f.width); val bottom = y + Range(f.height)
                points = listOf(map(x, y), map(right, y), map(right, bottom), map(x, bottom))
                radius = if (p is Primitive.FrameOutline) p.radius else 0.0
            }
            is Primitive.Ellipse -> {
                val f = p.frame
                val rx = f.width / 2; val ry = f.height / 2
                check(rx > 0 && ry > 0) { "Ellipse radii underflow" }
                val center = map(Range(f.x) + Range(Math.nextDown(rx), Math.nextUp(rx)),
                    Range(f.y) + Range(Math.nextDown(ry), Math.nextUp(ry)))
                // Independent scaled axes avoid unstable radius ratios.
                fun axis(a: Double, b: Double): Double {
                    val ax = Range(rx) * a
                    val by = Range(ry) * b
                    val value = hypot(maxOf(kotlin.math.abs(ax.lo), kotlin.math.abs(ax.hi)),
                        maxOf(kotlin.math.abs(by.lo), kotlin.math.abs(by.hi)))
                    return Math.nextUp(Math.nextUp(value)).also { check(it.isFinite()) }
                }
                points = listOf(center.first.expanded(axis(c[0], c[2])) to
                    center.second.expanded(axis(c[1], c[3])))
                radius = p.outlineRadius
            }
        }
        // Row norms account for the transformed circular brush, not a scalar-width proxy.
        val dx = support(c[0], c[2], radius); val dy = support(c[1], c[3], radius)
        val x = Range(points.minOf { it.first.lo }, points.maxOf { it.first.hi }).expanded(dx)
        val y = Range(points.minOf { it.second.lo }, points.maxOf { it.second.hi }).expanded(dy)
        return Bounds(x.lo, y.lo, x.hi, y.hi)
    }
}
