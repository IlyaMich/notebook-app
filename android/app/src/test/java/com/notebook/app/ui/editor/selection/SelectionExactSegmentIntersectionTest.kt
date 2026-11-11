package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import java.math.BigInteger
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionExactSegmentIntersectionTest {
    private fun p(x: Double, y: Double) = DuPoint(x, y)
    private fun <T> known(r: SelectionPredicateResult<T>) = (r as SelectionPredicateResult.Known<T>).value
    private fun fraction(r: SelectionExactArithmetic.Rational, n: Long, d: Long = 1) {
        assertEquals(BigInteger.valueOf(n), r.numerator); assertEquals(BigInteger.valueOf(d), r.denominator)
    }
    private fun withResult(a: DuPoint, b: DuPoint, c: DuPoint, d: DuPoint, check: (Intersection) -> Unit) {
        SelectionExactArithmetic(SelectionQueryBudget()).use { ctx ->
            check(known(SelectionExactSegmentIntersection.intersect(a, b, c, d, ctx)))
        }
    }
    @Test fun halfAndThirdCrossingsPreserveExactParameters() {
        withResult(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,0.0)) {
            val r = it as Intersection.AtPoint
            fraction(r.point.x,1,2); fraction(r.point.y,1,2)
            fraction(r.firstParameter,1,2); fraction(r.secondParameter,1,2)
        }
        withResult(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,-1.0)) {
            val r = it as Intersection.AtPoint
            fraction(r.point.x,1,3); fraction(r.point.y,1,3)
            fraction(r.firstParameter,1,3); fraction(r.secondParameter,1,3)
        }
    }
    @Test fun endpointValuesAreReusedAndInteriorTouchWorks() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val points = known(a.attempt { listOf(point(p(0.0,0.0)),point(p(1.0,0.0)),
                point(p(1.0,0.0)),point(p(1.0,1.0))) })
            val r = known(SelectionExactSegmentIntersection.intersect(points[0],points[1],points[2],points[3],a)) as Intersection.AtPoint
            assertSame(points[1], r.point)
            fraction(r.firstParameter,1); fraction(r.secondParameter,0)
        }
        withResult(p(0.0,0.0),p(2.0,0.0),p(1.0,0.0),p(1.0,1.0)) {
            val r = it as Intersection.AtPoint; fraction(r.firstParameter,1,2); fraction(r.secondParameter,0)
        }
    }
    @Test fun overlapParametersRetainDirectedCorrespondence() {
        withResult(p(3.0,0.0),p(0.0,0.0),p(1.0,0.0),p(4.0,0.0)) {
            val r = it as Intersection.Overlap
            fraction(r.first.x,1); fraction(r.last.x,3)
            fraction(r.firstSegmentAtFirst,2,3); fraction(r.firstSegmentAtLast,0)
            fraction(r.secondSegmentAtFirst,0); fraction(r.secondSegmentAtLast,2,3)
        }
    }
    @Test fun completeReversedAndVerticalOverlaps() {
        withResult(p(0.0,3.0),p(0.0,0.0),p(0.0,0.0),p(0.0,3.0)) {
            val r = it as Intersection.Overlap
            fraction(r.first.y,0); fraction(r.last.y,3)
            fraction(r.firstSegmentAtFirst,1); fraction(r.firstSegmentAtLast,0)
            fraction(r.secondSegmentAtFirst,0); fraction(r.secondSegmentAtLast,1)
        }
    }
    @Test fun disjointParallelAndCollinear() {
        withResult(p(0.0,0.0),p(1.0,0.0),p(2.0,0.0),p(3.0,0.0)) { assertSame(Intersection.Disjoint,it) }
        withResult(p(0.0,0.0),p(1.0,0.0),p(0.0,1.0),p(1.0,1.0)) { assertSame(Intersection.Disjoint,it) }
        withResult(p(0.0,0.0),p(1.0,0.0),p(2.0,-1.0),p(2.0,1.0)) { assertSame(Intersection.Disjoint,it) }
    }
    @Test fun allPointSegmentCases() {
        withResult(p(1.0,0.0),p(1.0,0.0),p(0.0,0.0),p(2.0,0.0)) {
            val r=it as Intersection.AtPoint; fraction(r.firstParameter,0); fraction(r.secondParameter,1,2)
        }
        withResult(p(0.0,0.0),p(2.0,0.0),p(1.0,0.0),p(1.0,0.0)) {
            val r=it as Intersection.AtPoint; fraction(r.firstParameter,1,2); fraction(r.secondParameter,0)
        }
        withResult(p(1.0,0.0),p(1.0,0.0),p(1.0,0.0),p(1.0,0.0)) { assertTrue(it is Intersection.AtPoint) }
        withResult(p(1.0,0.0),p(1.0,0.0),p(2.0,0.0),p(2.0,0.0)) { assertSame(Intersection.Disjoint,it) }
    }
    @Test fun exactRationalInputRetainsNearbyDistinctCrossings() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val points = known(a.attempt {
                val zero=rational(0); val one=rational(1)
                val near=add(one,rational(1,1L shl 60))
                listOf(point(zero,zero),point(rational(2),zero),point(one,rational(-1)),
                    point(one,one),point(near,rational(-1)),point(near,one))
            })
            val first=known(SelectionExactSegmentIntersection.intersect(points[0],points[1],points[2],points[3],a)) as Intersection.AtPoint
            val second=known(SelectionExactSegmentIntersection.intersect(points[0],points[1],points[4],points[5],a)) as Intersection.AtPoint
            assertNotEquals(first.point.x,second.point.x)
            known(a.attempt { assertEquals(SelectionRobustPredicates.Sign.NEGATIVE,compare(first.point,second.point)) })
            // Separation is 2^-60, below half an ulp at 1; no conversion was used for construction.
        }
    }
    @Test fun tinyAndLargeFiniteEndpoints() {
        for (scale in listOf(1e-30, 1e30, Math.scalb(1.0, -400), Math.scalb(1.0, 400))) {
            withResult(p(0.0,0.0),p(scale,scale),p(0.0,scale),p(scale,0.0)) { assertTrue(it is Intersection.AtPoint) }
        }
    }
    @Test fun invalidAndExhaustedNeverBecomeDisjoint() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            assertEquals(SelectionPredicateResult.InvalidCoordinates,
                SelectionExactSegmentIntersection.intersect(p(Double.NaN,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,0.0),a))
        }
        for (bits in listOf(16,52)) {
            val b=SelectionQueryBudget(SelectionQueryLimits(exactBits=bits))
            SelectionExactArithmetic(b).use { a ->
                assertTrue(SelectionExactSegmentIntersection.intersect(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,0.0),a)
                    is SelectionPredicateResult.WorkloadExceeded)
            }
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun workExhaustionAndCancellationCleanArena() {
        val limits=SelectionQueryResource.entries.associateWith { if(it==SelectionQueryResource.WORK_UNITS) 100L else it.queryCeiling }
        val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
        SelectionExactArithmetic(b).use { a ->
            assertTrue(SelectionExactSegmentIntersection.intersect(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,0.0),a)
                is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        var checks=0
        val cancelled=SelectionQueryBudget(cancellationCheckpoint={ if(++checks==100) throw CancellationException() })
        SelectionExactArithmetic(cancelled).use { a ->
            assertThrows(CancellationException::class.java) {
                SelectionExactSegmentIntersection.intersect(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,0.0),a)
            }
        }
        assertEquals(0L,cancelled.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun preservesInputsAndDeterministicNormalizedResult() {
        val input=listOf(p(0.0,0.0),p(1.0,1.0),p(0.0,1.0),p(1.0,-1.0))
        val before=input.toList()
        repeat(3) {
            withResult(input[0],input[1],input[2],input[3]) { r ->
                fraction((r as Intersection.AtPoint).point.x,1,3)
            }
        }
        assertEquals(before,input)
    }
}
