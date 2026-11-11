package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.math.BigInteger
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoSplitEventsTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private val triangle = listOf(p(0,0), p(2,0), p(0,2))
    private val square = listOf(p(0,0), p(2,0), p(2,2), p(0,2))
    private fun <T> known(result: SelectionPredicateResult<T>): T {
        assertTrue("Expected known, got $result", result is SelectionPredicateResult.Known)
        return (result as SelectionPredicateResult.Known<T>).value
    }
    private fun prepare(input: List<DuPoint>, budget: SelectionQueryBudget = SelectionQueryBudget()) =
        known(SelectionLassoSplitEvents.prepare(input, budget))
    private fun limits(resource: SelectionQueryResource, limit: Long) = SelectionQueryLimits(
        query = SelectionQueryResource.entries.associateWith { if (it == resource) limit else it.queryCeiling })
    private fun fraction(r: SelectionExactArithmetic.Rational) = "${r.numerator}/${r.denominator}"
    private fun point(p: SelectionExactArithmetic.Point) = "${fraction(p.x)},${fraction(p.y)}"
    private fun signature(prepared: Prepared): List<String> = prepared.segments.map { s ->
        "${s.sourceIndex}:${point(s.start.point)}>${point(s.end.point)}:" +
            s.events.joinToString { "${fraction(it.parameter)}@${point(it.point)}" }
    }
    private fun topology(prepared: Prepared): List<String> = prepared.segments.flatMap { s ->
        s.events.zipWithNext { a,b ->
            listOf(point(a.point), point(b.point)).sorted().joinToString("|")
        }
    }.sorted()
    private fun assertOrdered(prepared: Prepared) {
        known(prepared.exact {
            for (s in prepared.segments) {
                assertEquals(Sign.ZERO, compare(s.events.first().parameter, rational(0)))
                assertEquals(Sign.ZERO, compare(s.events.last().parameter, rational(1)))
                for ((a,b) in s.events.zipWithNext())
                    assertEquals(Sign.NEGATIVE, compare(a.parameter,b.parameter))
            }
        })
    }

    @Test fun triangleAndSquarePrepareEndpointsWithoutClaimingRegionValidity() {
        for (input in listOf(triangle,square)) prepare(input).use { r ->
            assertEquals(input.size,r.segments.size)
            assertEquals(input.indices.toList(),r.segments.map { it.sourceIndex })
            assertTrue(r.segments.all { it.events.size == 2 })
            assertOrdered(r)
        }
    }

    @Test fun implicitAndExplicitClosureHaveSameDirectedEvents() {
        prepare(square).use { implicit ->
            prepare(square + square.first()).use { explicit ->
                assertEquals(signature(implicit), signature(explicit))
                assertEquals(5,explicit.suppliedVertices.size)
                assertEquals(4,explicit.normalizedVertices.size)
            }
        }
    }

    @Test fun consecutiveDuplicatesOnlyAreRemovedAndSuppliedIndicesSurvive() {
        val input = listOf(p(0,0),p(0,0),p(2,0),p(2,0),p(0,2),p(0,0),p(0,0))
        prepare(input).use {
            assertEquals(listOf(0,2,4),it.normalizedVertices.map { v -> v.suppliedIndex })
            assertEquals(input,it.suppliedVertices)
            assertEquals(3,it.segments.size)
        }
    }

    @Test fun signedZeroDuplicatesPreserveExactOriginalBits() {
        val input=listOf(p(-0.0,0),p(0.0,-0.0),p(2,0),p(0,2))
        prepare(input).use {
            assertEquals(listOf(0,2,3),it.normalizedVertices.map { v -> v.suppliedIndex })
            assertEquals((-0.0).toRawBits(),it.suppliedVertices[0].x.toRawBits())
            assertEquals((-0.0).toRawBits(),it.suppliedVertices[1].y.toRawBits())
        }
    }

    @Test fun nonconsecutiveRepeatedVerticesAndRetracesRemain() {
        val input=listOf(p(0,0),p(2,0),p(0,0),p(0,2))
        prepare(input).use {
            assertEquals(4,it.normalizedVertices.size)
            assertEquals(4,it.segments.size)
            assertOrdered(it)
            assertEquals(point(it.segments[0].start.point),point(it.segments[1].end.point))
        }
    }

    @Test fun bowTieCrossingHasExactThirdCoordinateAndDirectedParameters() {
        prepare(listOf(p(0,0),p(1,1),p(0,1),p(1,-1))).use {
            val first=it.segments[0].events[1]
            val other=it.segments[2].events[1]
            assertEquals("1/3,1/3",point(first.point))
            assertEquals("1/3",fraction(first.parameter))
            assertEquals(first.point,other.point)
            assertEquals("1/3",fraction(other.parameter))
            assertOrdered(it)
        }
    }

    @Test fun signedAreaZeroBowTieIsPreparedNotRejected() {
        prepare(listOf(p(0,0),p(2,2),p(0,2),p(2,0))).use {
            assertEquals("1/1,1/1",point(it.segments[0].events[1].point))
            assertEquals(3,it.segments[0].events.size)
            assertEquals(3,it.segments[2].events.size)
        }
    }

    @Test fun concaveWalkRetainsAllSourceSegments() {
        prepare(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4))).use {
            assertEquals(5,it.segments.size)
            assertOrdered(it)
        }
    }

    @Test fun partialOverlapAddsBothEndpointsAndDeduplicates() {
        prepare(listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2))).use {
            assertEquals(listOf("0/1","1/4","3/4","1/1"),
                it.segments[0].events.map { e -> fraction(e.parameter) })
            assertEquals(listOf("0/1","1/3","1/1"),
                it.segments[1].events.map { e -> fraction(e.parameter) })
            assertOrdered(it)
        }
    }

    @Test fun completeReversedOverlapKeepsDirectedParameterOrder() {
        prepare(listOf(p(0,0),p(3,0))).use {
            assertEquals(2,it.segments.size)
            assertEquals(listOf("0/1,0/1","3/1,0/1"),it.segments[0].events.map { e -> point(e.point) })
            assertEquals(listOf("3/1,0/1","0/1,0/1"),it.segments[1].events.map { e -> point(e.point) })
            assertOrdered(it)
        }
    }

    @Test fun severalSegmentsShareOneCrossingWithoutGlobalDiscard() {
        val input=listOf(p(-2,0),p(2,0),p(0,-2),p(0,2),p(-2,-2),p(2,2))
        prepare(input).use {
            for (index in listOf(0,2,4)) {
                assertEquals(1,it.segments[index].events.count { e -> point(e.point)=="0/1,0/1" })
            }
            assertOrdered(it)
        }
    }

    @Test fun nearCoincidentRationalEventsStayDistinct() {
        val n=Math.scalb(1.0,50)
        prepare(listOf(p(0,0),p(2,0),p(1,-1),p(2,n),p(1,-1),p(2,n+1))).use {
            val expected1="${BigInteger.ONE.shiftLeft(50).add(BigInteger.valueOf(2))}/${BigInteger.ONE.shiftLeft(50).add(BigInteger.ONE)}"
            val expected2="${BigInteger.ONE.shiftLeft(50).add(BigInteger.valueOf(3))}/${BigInteger.ONE.shiftLeft(50).add(BigInteger.valueOf(2))}"
            val xs=it.segments[0].events.map { e -> fraction(e.point.x) }
            assertTrue(xs.contains(expected1)); assertTrue(xs.contains(expected2))
            assertNotEquals(expected1,expected2)
            assertOrdered(it)
        }
    }

    @Test fun windingAndStartRotationPreserveSplitTopology() {
        val input=listOf(p(0,0),p(2,2),p(0,2),p(2,0))
        prepare(input).use { original ->
            val expected=topology(original)
            for (walk in listOf(input.reversed(),input.drop(1)+input.take(1))) {
                prepare(walk).use {
                    assertEquals(expected,topology(it))
                    assertOrdered(it)
                }
            }
        }
    }

    @Test fun emptySinglePointAndCollapsedWalkPrepareWithoutRegionAssertion() {
        for (input in listOf(emptyList(),listOf(p(0,0)),List(4) { p(0,0) })) {
            prepare(input).use { assertTrue(it.segments.isEmpty()) }
        }
        prepare(listOf(p(0,0),p(1,0),p(2,0))).use { assertEquals(3,it.segments.size) }
    }

    @Test fun nonfiniteInputRejectsAndReleasesReservations() {
        for (v in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            val b=SelectionQueryBudget()
            assertEquals(SelectionPredicateResult.InvalidCoordinates,
                SelectionLassoSplitEvents.prepare(triangle+ p(v,0),b))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun vertexQuotaCountsSuppliedDuplicatesBeforeExpansion() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.LASSO_VERTICES,3))
        assertTrue(SelectionLassoSplitEvents.prepare(List(4) { p(0,0) },b)
            is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        val accepted=SelectionQueryBudget(limits(SelectionQueryResource.LASSO_VERTICES,3))
        prepare(List(3) { p(0,0) },accepted).close()
        assertEquals(3L,accepted.used(SelectionQueryResource.LASSO_VERTICES))
    }

    @Test fun defaultVertexCeilingIsCheckedBeforeExactExpansion() {
        val b=SelectionQueryBudget()
        assertTrue(SelectionLassoSplitEvents.prepare(List(1_025) { p(0,0) },b)
            is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(0L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        val at=SelectionQueryBudget()
        prepare(List(1_024) { p(0,0) },at).use { assertTrue(it.segments.isEmpty()) }
        assertEquals(1_024L,at.used(SelectionQueryResource.LASSO_VERTICES))
    }

    @Test fun eventQuotaIncludesAttemptedDuplicatesAndExactBoundary() {
        // Three source edges: six endpoints plus six adjacent endpoint records.
        val exact=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EVENTS,12))
        prepare(triangle,exact).use {
            assertEquals(12L,exact.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
            assertEquals(6,it.segments.sumOf { s -> s.events.size })
        }
        val short=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EVENTS,11))
        val failure=SelectionLassoSplitEvents.prepare(triangle,short)
        assertTrue(failure is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(SelectionQueryResource.ARRANGEMENT_EVENTS,
            (failure as SelectionPredicateResult.WorkloadExceeded).failure.resource)
        assertEquals(11L,short.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        assertEquals(0L,short.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun workAndRepresentationExhaustionNeverPublishPartialPreparation() {
        for ((resource,limit) in listOf(SelectionQueryResource.WORK_UNITS to 200L,
            SelectionQueryResource.TEMPORARY_BYTES to 2_000L)) {
            val b=SelectionQueryBudget(limits(resource,limit))
            val result=SelectionLassoSplitEvents.prepare(square,b)
            assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
            assertEquals(resource,(result as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertNotNull(b.failure)
            assertTrue(b.used(SelectionQueryResource.WORK_UNITS)>0)
        }
    }

    @Test fun operandExhaustionIsTypedAndReleasesPreparation() {
        val b=SelectionQueryBudget(SelectionQueryLimits(exactBits=8))
        assertTrue(SelectionLassoSplitEvents.prepare(triangle,b) is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun cancellationDuringPairConstructionPropagatesAndCleansUp() {
        lateinit var b: SelectionQueryBudget
        var constructionChecks=0
        b=SelectionQueryBudget(cancellationCheckpoint={
            if (b.used(SelectionQueryResource.ARRANGEMENT_EVENTS)==8L && ++constructionChecks==50)
                throw CancellationException("pair construction")
        })
        assertThrows(CancellationException::class.java) { SelectionLassoSplitEvents.prepare(square,b) }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertNull(b.failure)
    }

    @Test fun cancellationDuringSortingPropagatesAndCleansUp() {
        lateinit var b: SelectionQueryBudget
        var sortingChecks=0
        b=SelectionQueryBudget(cancellationCheckpoint={
            // Final pair has inserted all sixteen attempted square events.
            if (b.used(SelectionQueryResource.ARRANGEMENT_EVENTS)==16L && ++sortingChecks==50)
                throw CancellationException("sorting")
        })
        assertThrows(CancellationException::class.java) { SelectionLassoSplitEvents.prepare(square,b) }
        assertEquals(50,sortingChecks)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertNull(b.failure)
    }

    @Test fun exhaustionDuringOrderingReturnsFailureNotPartialEvents() {
        lateinit var b: SelectionQueryBudget
        var checks=0
        var injected=false
        b=SelectionQueryBudget(cancellationCheckpoint={
            if (!injected && b.used(SelectionQueryResource.ARRANGEMENT_EVENTS)==16L && ++checks==50) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        assertTrue(SelectionLassoSplitEvents.prepare(square,b) is SelectionPredicateResult.WorkloadExceeded)
        assertTrue(injected)
        assertEquals(16L,b.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun cancellationAfterHandoffDisposesOwnedArena() {
        var cancel=false
        val b=SelectionQueryBudget(cancellationCheckpoint={ if (cancel) throw CancellationException() })
        val r=prepare(square,b)
        cancel=true
        assertThrows(CancellationException::class.java) { r.exact { rational(1) } }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { r.segments }
    }

    @Test fun ownedArenaClosesIdempotentlyAndRejectsFurtherAccess() {
        val b=SelectionQueryBudget()
        val r=prepare(square,b)
        assertTrue(b.used(SelectionQueryResource.TEMPORARY_BYTES)>0)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        r.close(); r.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertThrows(IllegalStateException::class.java) { r.segments }
        assertThrows(IllegalStateException::class.java) { r.normalizedVertices }
        assertThrows(IllegalStateException::class.java) { r.exact { rational(0) } }
    }

    @Test fun failedOwnedExactOperationDisposesPreparedResult() {
        val b=SelectionQueryBudget()
        val r=prepare(square,b)
        assertNull(b.charge(SelectionQueryResource.WORK_UNITS,
            b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)))
        assertTrue(r.exact { rational(1) } is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { r.segments }
        r.close()
    }

    @Test fun ownedIntersectionHelperAllowsSequentialArithmeticAttempts() {
        val b=SelectionQueryBudget()
        prepare(square,b).use { owner ->
            val points=known(owner.exact {
                listOf(point(p(0,0)),point(p(1,1)),point(p(0,1)),point(p(1,0)))
            })
            val r=known(owner.intersectExistingExactSegments(points[0],points[1],points[2],points[3]))
                as SelectionExactSegmentIntersection.Intersection.AtPoint
            assertEquals("1/2,1/2",point(r.point))
            assertEquals(Sign.ZERO,known(owner.exact { compare(r.firstParameter,rational(1,2)) }))
            assertTrue(owner.segments.isNotEmpty())
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun ownedIntersectionHelperWorkFailureClosesPreparationWithoutResettingWork() {
        val b=SelectionQueryBudget()
        val owner=prepare(square,b)
        val points=known(owner.exact { listOf(point(p(0,0)),point(p(1,1))) })
        val remaining=b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
            b.used(SelectionQueryResource.WORK_UNITS)
        assertNull(b.charge(SelectionQueryResource.WORK_UNITS,remaining))
        assertTrue(owner.intersectExistingExactSegments(points[0],points[1],points[0],points[1])
            is SelectionPredicateResult.WorkloadExceeded)
        assertEquals(b.limits.query.getValue(SelectionQueryResource.WORK_UNITS),
            b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){owner.segments}
        assertThrows(IllegalStateException::class.java){
            owner.intersectExistingExactSegments(points[0],points[1],points[0],points[1])
        }
    }

    @Test fun ownedIntersectionHelperPropagatesCancellationAndRejectsNestedAttempts() {
        for(nested in listOf(false,true)) {
            var cancel=false
            val b=SelectionQueryBudget(cancellationCheckpoint={if(cancel)throw CancellationException()})
            val owner=prepare(square,b)
            val points=known(owner.exact { listOf(point(p(0,0)),point(p(1,1))) })
            if(nested) {
                assertThrows(IllegalStateException::class.java){
                    owner.exact {
                        owner.intersectExistingExactSegments(points[0],points[1],points[0],points[1])
                    }
                }
            } else {
                cancel=true
                assertThrows(CancellationException::class.java){
                    owner.intersectExistingExactSegments(points[0],points[1],points[0],points[1])
                }
            }
            assertNull(b.failure)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){owner.segments}
        }
    }

    @Test fun immutableSnapshotsAndDeterministicRepeatedExecution() {
        val input=square.toMutableList()
        val original=input.toList()
        val signatures=mutableListOf<List<String>>()
        val work=mutableListOf<Long>()
        repeat(3) {
            val b=SelectionQueryBudget()
            prepare(input,b).use { r ->
                signatures.add(signature(r)); work.add(b.used(SelectionQueryResource.WORK_UNITS))
                assertThrows(UnsupportedOperationException::class.java) {
                    (r.segments as MutableList).clear()
                }
                assertThrows(UnsupportedOperationException::class.java) {
                    (r.segments[0].events as MutableList).clear()
                }
                assertThrows(UnsupportedOperationException::class.java) {
                    (r.normalizedVertices as MutableList).clear()
                }
                assertThrows(UnsupportedOperationException::class.java) {
                    (r.suppliedVertices as MutableList).clear()
                }
            }
        }
        assertEquals(1,signatures.distinct().size); assertEquals(1,work.distinct().size)
        assertEquals(original,input)
        prepare(input).use {
            input.clear()
            assertEquals(original,it.suppliedVertices)
            assertEquals(4,it.segments.size)
        }
    }
}
