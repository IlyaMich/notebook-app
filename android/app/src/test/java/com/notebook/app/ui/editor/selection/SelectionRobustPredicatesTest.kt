package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Collinearity
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.PointLocation
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.SegmentRelation
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionRobustPredicatesTest {
    private fun p(x: Double, y: Double) = DuPoint(x, y)
    private fun <T> known(result: SelectionPredicateResult<T>): T =
        (result as SelectionPredicateResult.Known<T>).value
    private fun orientation(a: DuPoint, b: DuPoint, c: DuPoint) =
        known(SelectionRobustPredicates.orientation(a, b, c, SelectionQueryBudget()))
    private fun segment(a: DuPoint, b: DuPoint, c: DuPoint, d: DuPoint) =
        known(SelectionRobustPredicates.segmentIntersection(a, b, c, d, SelectionQueryBudget()))

    @Test fun positiveNegativeZeroAndReversedWinding() {
        val a = p(0.0, 0.0); val b = p(2.0, 0.0); val c = p(1.0, 1.0)
        assertEquals(Sign.POSITIVE, orientation(a, b, c))
        assertEquals(Sign.NEGATIVE, orientation(a, c, b))
        assertEquals(Sign.ZERO, orientation(a, b, p(1.0, 0.0)))
    }
    @Test fun exactCollinearityAndPointBounds() {
        assertEquals(Collinearity.COLLINEAR, known(SelectionRobustPredicates.collinearity(
            p(0.0, 0.0), p(1.0, 1.0), p(2.0, 2.0), SelectionQueryBudget())))
        assertEquals(PointLocation.ON_SEGMENT, known(SelectionRobustPredicates.pointOnSegment(
            p(1.0, 1.0), p(0.0, 0.0), p(2.0, 2.0), SelectionQueryBudget())))
        assertEquals(PointLocation.OUTSIDE, known(SelectionRobustPredicates.pointOnSegment(
            p(3.0, 3.0), p(0.0, 0.0), p(2.0, 2.0), SelectionQueryBudget())))
    }
    @Test fun catastrophicCancellationStillFindsUnitDeterminant() {
        val n = Math.scalb(1.0, 52)
        assertEquals(Sign.POSITIVE, orientation(p(0.0, 0.0), p(n, n - 1), p(n + 1, n)))
    }
    @Test fun nearlyCollinearIsNotZero() {
        assertEquals(Sign.NEGATIVE, orientation(p(0.0, 0.0), p(1.0, 1.0), p(Math.nextUp(1.0), 1.0)))
        assertEquals(Collinearity.NONCOLLINEAR, known(SelectionRobustPredicates.collinearity(
            p(0.0, 0.0), p(1.0, 1.0), p(Math.nextUp(1.0), 1.0), SelectionQueryBudget())))
    }
    @Test fun naiveOverflowAndUnderflowCannotDetermineSign() {
        assertEquals(Sign.POSITIVE, orientation(p(0.0, 0.0), p(Double.MAX_VALUE, 0.0), p(0.0, Double.MAX_VALUE)))
        assertEquals(Sign.POSITIVE, orientation(p(0.0, 0.0), p(Double.MIN_VALUE, 0.0), p(0.0, Double.MIN_VALUE)))
        val huge = 1e200
        assertEquals(Sign.POSITIVE, orientation(p(-huge, -huge), p(huge, -huge), p(-huge, huge)))
    }
    @Test fun coordinateOrderIncludesSignedZeroAndFiniteValidation() {
        assertEquals(Sign.ZERO, known(SelectionRobustPredicates.compare(-0.0, 0.0, SelectionQueryBudget())))
        assertEquals(Sign.NEGATIVE, known(SelectionRobustPredicates.compare(-Double.MIN_VALUE, 0.0, SelectionQueryBudget())))
        assertEquals(Sign.POSITIVE, known(SelectionRobustPredicates.compare(Double.MAX_VALUE, 0.0, SelectionQueryBudget())))
        for (v in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertEquals(SelectionPredicateResult.InvalidCoordinates, SelectionRobustPredicates.finite(p(v, 0.0)))
            assertEquals(SelectionPredicateResult.InvalidCoordinates,
                SelectionRobustPredicates.orientation(p(v, 0.0), p(0.0, 0.0), p(1.0, 1.0), SelectionQueryBudget()))
            assertEquals(SelectionPredicateResult.InvalidCoordinates,
                SelectionRobustPredicates.compare(v, 0.0, SelectionQueryBudget()))
        }
    }
    @Test fun crossingAndSharedEndpoint() {
        assertEquals(SegmentRelation.CROSSING, segment(p(0.0, 0.0), p(2.0, 2.0), p(0.0, 2.0), p(2.0, 0.0)))
        assertEquals(SegmentRelation.TOUCHING, segment(p(0.0, 0.0), p(1.0, 0.0), p(1.0, 0.0), p(1.0, 1.0)))
        assertEquals(SegmentRelation.TOUCHING, segment(p(0.0, 0.0), p(2.0, 0.0), p(1.0, 0.0), p(1.0, 1.0)))
    }
    @Test fun partialCompleteAndVerticalOverlaps() {
        assertEquals(SegmentRelation.OVERLAPPING, segment(p(0.0, 0.0), p(3.0, 0.0), p(1.0, 0.0), p(4.0, 0.0)))
        assertEquals(SegmentRelation.OVERLAPPING, segment(p(3.0, 0.0), p(0.0, 0.0), p(0.0, 0.0), p(3.0, 0.0)))
        assertEquals(SegmentRelation.OVERLAPPING, segment(p(0.0, 0.0), p(0.0, 3.0), p(0.0, 1.0), p(0.0, 4.0)))
    }
    @Test fun parallelAndCollinearDisjoint() {
        assertEquals(SegmentRelation.DISJOINT, segment(p(0.0, 0.0), p(2.0, 0.0), p(0.0, 1.0), p(2.0, 1.0)))
        assertEquals(SegmentRelation.DISJOINT, segment(p(0.0, 0.0), p(1.0, 0.0), p(2.0, 0.0), p(3.0, 0.0)))
    }
    @Test fun zeroLengthSegmentsAndPointContacts() {
        assertEquals(SegmentRelation.TOUCHING, segment(p(1.0, 0.0), p(1.0, 0.0), p(0.0, 0.0), p(2.0, 0.0)))
        assertEquals(SegmentRelation.DISJOINT, segment(p(3.0, 0.0), p(3.0, 0.0), p(0.0, 0.0), p(2.0, 0.0)))
        assertEquals(SegmentRelation.DISJOINT, segment(p(1.0, 0.0), p(1.0, 0.0), p(2.0, 0.0), p(2.0, 0.0)))
        assertEquals(SegmentRelation.TOUCHING, segment(p(1.0, 0.0), p(1.0, 0.0), p(1.0, 0.0), p(1.0, 0.0)))
    }
    @Test fun exactLimitIsOperationalNotFalseCollinearityOrMiss() {
        val budget = SelectionQueryBudget(SelectionQueryLimits(exactBits = 16))
        val result = SelectionRobustPredicates.orientation(p(0.0, 0.0), p(1.0, 1.0), p(2.0, 2.0), budget)
        assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(SelectionBudgetConstraint.EXACT_BITS,
            (result as SelectionPredicateResult.WorkloadExceeded).failure.constraint)
        assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun extremeFiniteExponentSpreadMayExceedDefaultOperandBudget() {
        val result = SelectionRobustPredicates.orientation(p(Double.MIN_VALUE, Double.MAX_VALUE),
            p(Double.MAX_VALUE, Double.MIN_VALUE), p(-Double.MAX_VALUE, -Double.MAX_VALUE), SelectionQueryBudget())
        assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
    }
    @Test fun weightedWorkGrowsWithOperandSizes() {
        val small = SelectionQueryBudget(); val large = SelectionQueryBudget()
        SelectionRobustPredicates.orientation(p(0.0, 0.0), p(2.0, 0.0), p(0.0, 2.0), small)
        SelectionRobustPredicates.orientation(p(0.0, 0.0), p(1e200, 0.0), p(0.0, 1e200), large)
        assertTrue(large.used(SelectionQueryResource.WORK_UNITS) > small.used(SelectionQueryResource.WORK_UNITS))
        val limits = SelectionQueryResource.entries.associateWith { if (it == SelectionQueryResource.WORK_UNITS) 1L else it.queryCeiling }
        val exhausted = SelectionQueryBudget(SelectionQueryLimits(query = limits))
        assertTrue(SelectionRobustPredicates.segmentIntersection(p(0.0, 0.0), p(2.0, 2.0),
            p(0.0, 2.0), p(2.0, 0.0), exhausted) is SelectionPredicateResult.WorkloadExceeded)
    }
    @Test fun midPredicateCancellationPropagatesAndReleasesReservation() {
        var calls = 0
        val budget = SelectionQueryBudget(cancellationCheckpoint = { if (++calls == 12) throw CancellationException() })
        assertThrows(CancellationException::class.java) {
            SelectionRobustPredicates.orientation(p(0.0, 0.0), p(1.0, 0.0), p(0.0, 1.0), budget)
        }
        assertNull(budget.failure); assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun repeatedResultsAreDeterministicAndInputsUnchanged() {
        val points = listOf(p(0.0, 0.0), p(2.0, 2.0), p(0.0, 2.0), p(2.0, 0.0))
        val before = points.toList()
        val results = (1..5).map { SelectionRobustPredicates.segmentIntersection(
            points[0], points[1], points[2], points[3], SelectionQueryBudget()) }
        assertTrue(results.all { it == results[0] }); assertEquals(before, points)
    }
}
