package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import java.math.BigInteger

/**
 * Exact predicates on finite binary Doubles, subject to operation budgets. No epsilon, division,
 * Float conversion, decimal reconstruction, affine certification or intersection-point construction.
 * All BigInteger shifts/products are bounded and charged before execution.
 */
internal object SelectionRobustPredicates {
    enum class Sign { NEGATIVE, ZERO, POSITIVE }
    enum class Collinearity { COLLINEAR, NONCOLLINEAR }
    enum class PointLocation { ON_SEGMENT, OUTSIDE }
    enum class SegmentRelation { DISJOINT, CROSSING, TOUCHING, OVERLAPPING }
    enum class Coordinates { FINITE }

    fun finite(point: DuPoint): SelectionPredicateResult<Coordinates> =
        if (point.x.isFinite() && point.y.isFinite()) SelectionPredicateResult.Known(Coordinates.FINITE)
        else SelectionPredicateResult.InvalidCoordinates

    /** Primitive comparisons are exact for finite values; signed zeros denote the same coordinate. */
    fun compare(a: Double, b: Double, budget: SelectionQueryBudget): SelectionPredicateResult<Sign> {
        budget.checkpoint()
        if (!a.isFinite() || !b.isFinite()) return SelectionPredicateResult.InvalidCoordinates
        budget.charge(SelectionQueryResource.WORK_UNITS)?.let { return SelectionPredicateResult.WorkloadExceeded(it) }
        return SelectionPredicateResult.Known(order(a, b))
    }

    fun orientation(a: DuPoint, b: DuPoint, c: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(a, b, c), budget) { Engine(budget).orientation(a, b, c) }

    fun collinearity(a: DuPoint, b: DuPoint, c: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(a, b, c), budget) {
            if (Engine(budget).orientation(a, b, c) == Sign.ZERO) Collinearity.COLLINEAR
            else Collinearity.NONCOLLINEAR
        }

    fun pointOnSegment(p: DuPoint, a: DuPoint, b: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(p, a, b), budget) {
            val e = Engine(budget)
            if (e.orientation(a, b, p) == Sign.ZERO && e.inBounds(p, a, b)) PointLocation.ON_SEGMENT
            else PointLocation.OUTSIDE
        }

    fun segmentIntersection(a: DuPoint, b: DuPoint, c: DuPoint, d: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(a, b, c, d), budget) {
            val e = Engine(budget)
            if (a.x == b.x && a.y == b.y)
                return@evaluate if (e.orientation(c, d, a) == Sign.ZERO && e.inBounds(a, c, d))
                    SegmentRelation.TOUCHING else SegmentRelation.DISJOINT
            if (c.x == d.x && c.y == d.y)
                return@evaluate if (e.orientation(a, b, c) == Sign.ZERO && e.inBounds(c, a, b))
                    SegmentRelation.TOUCHING else SegmentRelation.DISJOINT
            val abC = e.orientation(a, b, c); val abD = e.orientation(a, b, d)
            val cdA = e.orientation(c, d, a); val cdB = e.orientation(c, d, b)
            fun opposite(x: Sign, y: Sign) = x != Sign.ZERO && y != Sign.ZERO && x != y
            when {
                opposite(abC, abD) && opposite(cdA, cdB) -> SegmentRelation.CROSSING
                listOf(abC, abD, cdA, cdB).all { it == Sign.ZERO } -> {
                    // Project onto an axis with nonzero extent; also handles two point-segments.
                    take(budget.charge(SelectionQueryResource.WORK_UNITS, 16))
                    val useX = a.x != b.x || c.x != d.x
                    val av = if (useX) a.x else a.y; val bv = if (useX) b.x else b.y
                    val cv = if (useX) c.x else c.y; val dv = if (useX) d.x else d.y
                    val lo = maxOf(minOf(av, bv), minOf(cv, dv))
                    val hi = minOf(maxOf(av, bv), maxOf(cv, dv))
                    when (order(lo, hi)) {
                        Sign.NEGATIVE -> SegmentRelation.OVERLAPPING
                        Sign.ZERO -> SegmentRelation.TOUCHING
                        Sign.POSITIVE -> SegmentRelation.DISJOINT
                    }
                }
                (abC == Sign.ZERO && e.inBounds(c, a, b)) ||
                    (abD == Sign.ZERO && e.inBounds(d, a, b)) ||
                    (cdA == Sign.ZERO && e.inBounds(a, c, d)) ||
                    (cdB == Sign.ZERO && e.inBounds(b, c, d)) -> SegmentRelation.TOUCHING
                else -> SegmentRelation.DISJOINT
            }
        }

    private fun order(a: Double, b: Double) = when {
        a < b -> Sign.NEGATIVE
        a > b -> Sign.POSITIVE
        else -> Sign.ZERO
    }
    private class Stop(val failure: SelectionWorkloadFailure) : RuntimeException()
    private fun take(failure: SelectionWorkloadFailure?) { if (failure != null) throw Stop(failure) }

    private fun <T> evaluate(points: List<DuPoint>, budget: SelectionQueryBudget, action: () -> T):
        SelectionPredicateResult<T> {
        budget.checkpoint()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return SelectionPredicateResult.InvalidCoordinates
        // Explicit scratch accounting model: up to 32 operand-sized buffers + 4096 bytes.
        // This is not measured heap, BigInteger allocator instrumentation, or a query memory proof.
        val scratch = 32L * ((budget.limits.exactBits.toLong() + 7) / 8) + 4096
        try {
            take(budget.charge(SelectionQueryResource.TEMPORARY_BYTES, scratch))
        } catch (stop: Stop) { return SelectionPredicateResult.WorkloadExceeded(stop.failure) }
        return try {
            SelectionPredicateResult.Known(action())
        } catch (stop: Stop) {
            SelectionPredicateResult.WorkloadExceeded(stop.failure)
        } finally {
            budget.releaseTemporary(scratch)
        }
    }

    /** n * 2^exponent; normalization keeps ordinary inputs small without losing binary precision. */
    private data class Dyadic(val n: BigInteger, val exponent: Int)
    /**
     * Cost model uses 32-bit limbs: subtraction charges both aligned operands plus output;
     * multiplication charges the limb-count product plus output. Conversion charges four units.
     * Bounds include intermediate shifts/carry before normalization; they may reject work whose
     * final simplified answer would fit. These conservative operation estimates are not timings.
     */
    private class Engine(private val budget: SelectionQueryBudget) {
        private fun bits(n: BigInteger) = n.abs().bitLength().toLong()
        private fun limbs(bits: Long) = maxOf(1, (bits + 31) / 32)

        private fun charge(bound: Long, cost: Long) {
            take(budget.checkExactBits(bound))
            take(budget.charge(SelectionQueryResource.WORK_UNITS, cost))
        }
        private fun normalized(n: BigInteger, exponent: Int): Dyadic {
            if (n.signum() == 0) return Dyadic(BigInteger.ZERO, 0)
            val trailing = n.lowestSetBit
            return Dyadic(n.shiftRight(trailing), exponent + trailing)
        }
        private fun dyadic(value: Double): Dyadic {
            // Inputs have already passed finite validation; mantissa fits a signed Long.
            val raw = value.toRawBits()
            val encodedExponent = ((raw ushr 52) and 0x7ff).toInt()
            var mantissa = raw and 0x000fffffffffffffL
            if (encodedExponent != 0) mantissa = mantissa or (1L shl 52)
            if (raw < 0) mantissa = -mantissa
            val bound = if (mantissa == 0L) 0L else 64L - java.lang.Long.numberOfLeadingZeros(kotlin.math.abs(mantissa))
            charge(bound, 4)
            return normalized(BigInteger.valueOf(mantissa),
                if (encodedExponent == 0) -1074 else encodedExponent - 1023 - 52)
        }
        private fun subtract(a: Dyadic, b: Dyadic): Dyadic {
            val exponent = minOf(a.exponent, b.exponent)
            val leftBits = bits(a.n) + (a.exponent - exponent)
            val rightBits = bits(b.n) + (b.exponent - exponent)
            val bound = maxOf(leftBits, rightBits) + 1
            charge(bound, limbs(leftBits) + limbs(rightBits) + limbs(bound))
            return normalized(a.n.shiftLeft(a.exponent - exponent)
                .subtract(b.n.shiftLeft(b.exponent - exponent)), exponent)
        }
        private fun multiply(a: Dyadic, b: Dyadic): Dyadic {
            val leftBits = bits(a.n); val rightBits = bits(b.n)
            charge(leftBits + rightBits, limbs(leftBits) * limbs(rightBits) + limbs(leftBits + rightBits))
            return normalized(a.n.multiply(b.n), a.exponent + b.exponent)
        }
        fun orientation(a: DuPoint, b: DuPoint, c: DuPoint): Sign {
            val ax = dyadic(a.x); val ay = dyadic(a.y)
            val bx = dyadic(b.x); val by = dyadic(b.y)
            val cx = dyadic(c.x); val cy = dyadic(c.y)
            val abx = subtract(bx, ax); val aby = subtract(by, ay)
            val acx = subtract(cx, ax); val acy = subtract(cy, ay)
            val determinant = subtract(multiply(abx, acy), multiply(aby, acx))
            return when (determinant.n.signum()) {
                -1 -> Sign.NEGATIVE
                0 -> Sign.ZERO
                else -> Sign.POSITIVE
            }
        }
        fun inBounds(p: DuPoint, a: DuPoint, b: DuPoint): Boolean {
            take(budget.charge(SelectionQueryResource.WORK_UNITS, 12))
            return p.x >= minOf(a.x, b.x) && p.x <= maxOf(a.x, b.x) &&
                p.y >= minOf(a.y, b.y) && p.y <= maxOf(a.y, b.y)
        }
    }
}
