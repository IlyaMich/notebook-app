package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign

/** Construction only, not visible paint intersection. Results live in the caller's exact arena. */
internal object SelectionExactSegmentIntersection {
    sealed interface Intersection {
        data object Disjoint : Intersection
        data class AtPoint(val point: Point, val firstParameter: Rational, val secondParameter: Rational) : Intersection
        /** Endpoints are lexicographic; corresponding directed parameters may decrease. */
        data class Overlap(
            val first: Point, val last: Point,
            val firstSegmentAtFirst: Rational, val firstSegmentAtLast: Rational,
            val secondSegmentAtFirst: Rational, val secondSegmentAtLast: Rational,
        ) : Intersection
    }

    fun intersect(a: DuPoint, b: DuPoint, c: DuPoint, d: DuPoint, arithmetic: SelectionExactArithmetic):
        SelectionPredicateResult<Intersection> = arithmetic.attempt {
            construct(point(a), point(b), point(c), point(d))
        }

    fun intersect(a: Point, b: Point, c: Point, d: Point, arithmetic: SelectionExactArithmetic):
        SelectionPredicateResult<Intersection> = arithmetic.attempt { construct(a, b, c, d) }

    private fun SelectionExactArithmetic.construct(a: Point, b: Point, c: Point, d: Point): Intersection {
        retainRecord(96)
        val zero = rational(0); val one = rational(1)
        fun unit(t: Rational) = compare(t, zero) != Sign.NEGATIVE && compare(t, one) != Sign.POSITIVE
        fun parameter(p: Point, start: Point, end: Point): Rational {
            if (compare(start, end) == Sign.ZERO) return zero
            return if (compare(start.x, end.x) != Sign.ZERO)
                divide(subtract(p.x, start.x), subtract(end.x, start.x))
            else divide(subtract(p.y, start.y), subtract(end.y, start.y))
        }
        fun on(p: Point, start: Point, end: Point): Boolean {
            if (compare(start, end) == Sign.ZERO) return compare(p, start) == Sign.ZERO
            return sign(cross(vector(end, start), vector(p, start))) == Sign.ZERO && unit(parameter(p, start, end))
        }
        if (compare(a, b) == Sign.ZERO)
            return if (on(a, c, d)) Intersection.AtPoint(a, zero, parameter(a, c, d)) else Intersection.Disjoint
        if (compare(c, d) == Sign.ZERO)
            return if (on(c, a, b)) Intersection.AtPoint(c, parameter(c, a, b), zero) else Intersection.Disjoint
        val r = vector(b, a); val s = vector(d, c); val q = vector(c, a)
        val determinant = cross(r, s)
        if (sign(determinant) != Sign.ZERO) {
            val t = divide(cross(q, s), determinant); val u = divide(cross(q, r), determinant)
            if (!unit(t) || !unit(u)) return Intersection.Disjoint
            val point = when {
                compare(t, zero) == Sign.ZERO -> a
                compare(t, one) == Sign.ZERO -> b
                compare(u, zero) == Sign.ZERO -> c
                compare(u, one) == Sign.ZERO -> d
                else -> point(add(a.x, multiply(t, r.x)), add(a.y, multiply(t, r.y)))
            }
            return Intersection.AtPoint(point, t, u)
        }
        if (sign(cross(q, r)) != Sign.ZERO) return Intersection.Disjoint
        val aFirst = if (compare(a, b) == Sign.POSITIVE) b else a
        val aLast = if (aFirst === a) b else a
        val cFirst = if (compare(c, d) == Sign.POSITIVE) d else c
        val cLast = if (cFirst === c) d else c
        val first = if (compare(aFirst, cFirst) == Sign.NEGATIVE) cFirst else aFirst
        val last = if (compare(aLast, cLast) == Sign.POSITIVE) cLast else aLast
        return when (compare(first, last)) {
            Sign.POSITIVE -> Intersection.Disjoint
            Sign.ZERO -> Intersection.AtPoint(first, parameter(first, a, b), parameter(first, c, d))
            Sign.NEGATIVE -> Intersection.Overlap(first, last,
                parameter(first, a, b), parameter(last, a, b),
                parameter(first, c, d), parameter(last, c, d))
        }
    }
}
