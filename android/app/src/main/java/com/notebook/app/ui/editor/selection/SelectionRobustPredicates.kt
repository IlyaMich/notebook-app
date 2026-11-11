package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint

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
        evaluate(listOf(a, b, c), budget) { arithmetic -> arithmetic.orientation(a, b, c) }

    fun collinearity(a: DuPoint, b: DuPoint, c: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(a, b, c), budget) { arithmetic ->
            if (arithmetic.orientation(a, b, c) == Sign.ZERO) Collinearity.COLLINEAR
            else Collinearity.NONCOLLINEAR
        }

    fun pointOnSegment(p: DuPoint, a: DuPoint, b: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(p, a, b), budget) { arithmetic ->
            val e = arithmetic
            if (e.orientation(a, b, p) == Sign.ZERO && e.inBounds(p, a, b)) PointLocation.ON_SEGMENT
            else PointLocation.OUTSIDE
        }

    fun segmentIntersection(a: DuPoint, b: DuPoint, c: DuPoint, d: DuPoint, budget: SelectionQueryBudget) =
        evaluate(listOf(a, b, c, d), budget) { arithmetic ->
            val e = arithmetic
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
                    e.consume(budget.charge(SelectionQueryResource.WORK_UNITS, 16))
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
    private fun <T> evaluate(points: List<DuPoint>, budget: SelectionQueryBudget,
        action: (SelectionExactArithmetic) -> T): SelectionPredicateResult<T> {
        budget.checkpoint()
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return SelectionPredicateResult.InvalidCoordinates
        return SelectionExactArithmetic(budget).use { arithmetic ->
            arithmetic.attempt { action(this) }
        }
    }
}
