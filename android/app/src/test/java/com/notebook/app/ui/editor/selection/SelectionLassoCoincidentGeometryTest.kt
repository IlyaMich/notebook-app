package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Result
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoCoincidentGeometryTest {
    private fun p(x: Number,y: Number)=DuPoint(x.toDouble(),y.toDouble())
    private val triangle=listOf(p(0,0),p(2,0),p(0,2))
    private val square=listOf(p(0,0),p(2,0),p(2,2),p(0,2))
    private val bowTie=listOf(p(0,0),p(1,1),p(0,1),p(1,0))
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known, got $r",r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private fun prepare(input: List<DuPoint>,b: SelectionQueryBudget=SelectionQueryBudget()) =
        known(SelectionLassoSplitEvents.prepare(input,b))
    private fun atomic(p: SelectionLassoSplitEvents.Prepared) =
        (SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
    private fun grouped(p: SelectionLassoSplitEvents.Prepared,a: SelectionLassoAtomicEdges.Borrowed) :
        SelectionLassoCoincidentGeometry.Borrowed {
        val r=SelectionLassoCoincidentGeometry.build(p,a)
        assertTrue("Expected Ready, got $r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun fraction(r: SelectionExactArithmetic.Rational)="${r.numerator}/${r.denominator}"
    private fun point(p: SelectionExactArithmetic.Point)="${fraction(p.x)},${fraction(p.y)}"
    private fun limits(resource: SelectionQueryResource,n: Long)=SelectionQueryLimits(
        query=SelectionQueryResource.entries.associateWith { if(it==resource)n else it.queryCeiling })
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue(r is Result.Unresolved)
        val cause=(r as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun geometry(g: SelectionLassoCoincidentGeometry.Borrowed) =
        g.vertices.map { "${it.id}@${point(it.point)}" } to
            g.edges.map { "${it.startVertexId}>${it.endVertexId}@${point(it.start)}>${point(it.end)}" }
    private fun signature(g: SelectionLassoCoincidentGeometry.Borrowed) = geometry(g) to g.edges.map { e ->
        e.occurrences.map {
            val f=it.fragment
            "${f.sourceIndex}:${f.suppliedStartIndex}>${f.suppliedEndIndex}:${f.ordinal}:"+
                "${fraction(f.startParameter)}>${fraction(f.endParameter)}:${it.followsCanonical}"
        }
    }
    private fun validate(g: SelectionLassoCoincidentGeometry.Borrowed) {
        known(g.exact {
            for((a,b) in g.vertices.zipWithNext()) assertEquals(Sign.NEGATIVE,compare(a.point,b.point))
            assertEquals(g.vertices.indices.toList(),g.vertices.map { it.id })
            for(e in g.edges) {
                assertTrue(e.startVertexId<e.endVertexId)
                assertSame(g.vertices[e.startVertexId].point,e.start)
                assertSame(g.vertices[e.endVertexId].point,e.end)
                val keys=e.occurrences.map { it.fragment.sourceIndex to it.fragment.ordinal }
                assertEquals(keys.sortedWith(compareBy<Pair<Int,Int>>{it.first}.thenBy{it.second}),keys)
                for(o in e.occurrences) {
                    assertEquals(Sign.ZERO,compare(o.fragment.start,if(o.followsCanonical)e.start else e.end))
                    assertEquals(Sign.ZERO,compare(o.fragment.end,if(o.followsCanonical)e.end else e.start))
                }
            }
        })
        val keys=g.edges.map { it.startVertexId to it.endVertexId }
        assertEquals(keys.sortedWith(compareBy<Pair<Int,Int>>{it.first}.thenBy{it.second}),keys)
    }

    @Test fun triangleAndSquareHaveLexicographicVertexIdsAndCanonicalGroups() {
        for(input in listOf(triangle,square)) prepare(input).use {
            val a=atomic(it); val g=grouped(it,a)
            assertEquals(input.size,g.vertices.size); assertEquals(input.size,g.edges.size)
            assertEquals(a.fragments.size,g.edges.sumOf { e -> e.occurrences.size })
            validate(g)
        }
        prepare(square).use {
            val g=grouped(it,atomic(it))
            assertEquals(listOf("0/1,0/1","0/1,2/1","2/1,0/1","2/1,2/1"),g.vertices.map { v -> point(v.point) })
        }
    }

    @Test fun bowTieInternsOneExactCrossingForEverySourceFragment() {
        prepare(bowTie).use {
            val g=grouped(it,atomic(it))
            assertEquals(5,g.vertices.size)
            val crossing=g.vertices.single { v -> point(v.point)=="1/2,1/2" }
            assertEquals(4,g.edges.count { e -> e.startVertexId==crossing.id || e.endVertexId==crossing.id })
            assertEquals(6,g.edges.sumOf { e -> e.occurrences.size })
            validate(g)
        }
    }

    @Test fun concaveWalkAndSeveralSegmentsMeetingShareExactVertices() {
        for(input in listOf(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4)),
            listOf(p(-2,0),p(2,0),p(0,-2),p(0,2),p(-2,-2),p(2,2)))) {
            prepare(input).use {
                val a=atomic(it); val g=grouped(it,a)
                assertEquals(a.fragments.size,g.edges.sumOf { e -> e.occurrences.size })
                validate(g)
                if(input.size==6) assertEquals(1,g.vertices.count { v -> point(v.point)=="0/1,0/1" })
            }
        }
    }

    @Test fun sameAndOppositeTraversalsShareGroupWithoutCancellation() {
        prepare(listOf(p(0,0),p(2,0),p(0,0),p(2,0))).use {
            val a=atomic(it); val g=grouped(it,a)
            assertEquals(2,g.vertices.size); assertEquals(1,g.edges.size)
            val occurrences=g.edges.single().occurrences
            assertEquals(listOf(0,1,2,3),occurrences.map { o -> o.fragment.sourceIndex })
            assertEquals(listOf(true,false,true,false),occurrences.map { o -> o.followsCanonical })
            assertEquals(4,occurrences.size)
            for(o in occurrences) assertSame(a.fragments[o.fragment.sourceIndex],o.fragment)
            validate(g)
        }
    }

    @Test fun partialCollinearOverlapGroupsAllAtomicOccurrences() {
        prepare(listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2))).use {
            val a=atomic(it); val g=grouped(it,a)
            val overlap=g.edges.single { e -> point(e.start)=="1/1,0/1" && point(e.end)=="3/1,0/1" }
            assertEquals(listOf(0,1,2),overlap.occurrences.map { o -> o.fragment.sourceIndex })
            assertEquals(listOf(true,false,true),overlap.occurrences.map { o -> o.followsCanonical })
            assertEquals(listOf("1/4","1/3","0/1"),overlap.occurrences.map { o -> fraction(o.fragment.startParameter) })
            assertEquals(a.fragments.size,g.edges.sumOf { e -> e.occurrences.size })
            validate(g)
        }
    }

    @Test fun emptySinglePointRetracedAndCollapsedWalksMakeNoRegionClaim() {
        for(input in listOf(emptyList(),listOf(p(0,0)),List(3){p(0,0)})) {
            prepare(input).use {
                val g=grouped(it,atomic(it)); assertTrue(g.vertices.isEmpty()); assertTrue(g.edges.isEmpty())
            }
        }
        for(input in listOf(listOf(p(0,0),p(2,0)),listOf(p(0,0),p(1,0),p(2,0)))) {
            prepare(input).use {
                val a=atomic(it); val g=grouped(it,a)
                assertEquals(a.fragments.size,g.edges.sumOf { e -> e.occurrences.size })
                assertTrue(g.edges.isNotEmpty())
            }
        }
    }

    @Test fun independentlyConstructedEqualRationalsInternTogether() {
        prepare(bowTie).use { owner ->
            val a=atomic(owner)
            val copies=known(owner.exact {
                a.fragments.map { f ->
                    fun independent(p: SelectionExactArithmetic.Point)=
                        point(add(p.x,rational(0,7)),multiply(p.y,rational(3,3)))
                    f.copy(start=independent(f.start),end=independent(f.end))
                }
            })
            assertNotSame(a.fragments[0].end,copies[0].end)
            val altered=SelectionLassoAtomicEdges.Borrowed(owner,copies)
            val g=grouped(owner,altered)
            assertEquals(1,g.vertices.count { v -> point(v.point)=="1/2,1/2" })
            assertEquals(5,g.vertices.size)
            validate(g)
        }
    }

    @Test fun nearCoincidentRationalEndpointsRemainDistinct() {
        val n=Math.scalb(1.0,50)
        prepare(listOf(p(0,0),p(2,0),p(1,-1),p(2,n),p(1,-1),p(2,n+1))).use {
            val a=atomic(it); val g=grouped(it,a)
            val tiny=a.fragments.single { f -> f.sourceIndex==0 &&
                f.start.x.numerator>f.start.x.denominator && f.end.x.numerator>f.end.x.denominator &&
                f.end.x.numerator<f.end.x.denominator.shiftLeft(1) }
            val endpoints=known(g.exact {
                g.vertices.filter { v -> compare(v.point,tiny.start)==Sign.ZERO || compare(v.point,tiny.end)==Sign.ZERO }
            })
            assertEquals(2,endpoints.size)
            assertNotEquals(endpoints[0].id,endpoints[1].id)
            validate(g)
        }
    }

    @Test fun shuffledInputDoesNotDetermineGeometricIdsOrOccurrenceOrder() {
        prepare(bowTie).use {
            val a=atomic(it); val expected=signature(grouped(it,a))
            val shuffled=SelectionLassoAtomicEdges.Borrowed(it,a.fragments.reversed())
            assertEquals(expected,signature(grouped(it,shuffled)))
        }
    }

    @Test fun reversedWindingAndRotatedStartKeepExactGeometricIds() {
        prepare(bowTie).use {
            val expected=geometry(grouped(it,atomic(it)))
            for(input in listOf(bowTie.reversed(),bowTie.drop(1)+bowTie.take(1))) {
                prepare(input).use { p ->
                    val g=grouped(p,atomic(p))
                    assertEquals(expected,geometry(g)); validate(g)
                }
            }
        }
    }

    @Test fun allOriginalIndicesOrdinalsAndDirectedParametersArePreserved() {
        val input=listOf(p(0,0),p(0,0),p(4,0),p(1,0),p(3,0),p(3,2),p(0,0))
        prepare(input).use {
            val a=atomic(it); val g=grouped(it,a)
            val occurrences=g.edges.flatMap { e -> e.occurrences }
            assertEquals(a.fragments.size,occurrences.size)
            for(f in a.fragments) assertEquals(1,occurrences.count { o -> o.fragment === f })
            assertEquals(input,it.suppliedVertices)
        }
    }

    @Test fun deterministicImmutableOutputDoesNotMutateInputsOrSources() {
        val signatures=mutableListOf<Any>(); val work=mutableListOf<Long>()
        val input=bowTie.toMutableList()
        repeat(3) {
            val b=SelectionQueryBudget()
            prepare(input,b).use { p ->
                val a=atomic(p); val before=a.fragments.toList(); val sources=p.segments.toList()
                val g=grouped(p,a); signatures.add(signature(g)); work.add(b.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(before,a.fragments); assertEquals(sources,p.segments)
                assertEquals(bowTie,input)
                assertThrows(UnsupportedOperationException::class.java){(g.vertices as MutableList).clear()}
                assertThrows(UnsupportedOperationException::class.java){(g.edges as MutableList).clear()}
                assertThrows(UnsupportedOperationException::class.java){(g.edges[0].occurrences as MutableList).clear()}
            }
        }
        assertEquals(1,signatures.distinct().size); assertEquals(1,work.distinct().size)
    }

    @Test fun closedOwnerRejectsAndGroupedBorrowerIsInvalidated() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val a=atomic(p); val g=grouped(p,a)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertThrows(IllegalStateException::class.java){g.vertices}
        assertThrows(IllegalStateException::class.java){g.edges}
        assertThrows(IllegalStateException::class.java){g.exact { rational(0) }}
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoCoincidentGeometry.build(p,a))
    }

    @Test fun mismatchedOwnerEvenForEmptyGeometryRejectsWithoutClosingUnrelatedOwner() {
        for(input in listOf(emptyList(),square)) {
            val b=SelectionQueryBudget(); val p=prepare(input,b)
            prepare(input).use { other ->
                val a=atomic(other)
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoCoincidentGeometry.build(p,a))
                assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertEquals(input,other.suppliedVertices)
                assertEquals(input.size,a.fragments.size)
            }
        }
    }

    @Test fun closedAtomicOwnerRejectsAndDisposesPrimaryOwner() {
        val b=SelectionQueryBudget(); val p=prepare(square,b)
        val other=prepare(square); val a=atomic(other); other.close()
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoCoincidentGeometry.build(p,a))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun contradictoryFragmentsRejectExplicitlyWithoutPartialGroups() {
        val reasons=listOf(InvalidReason.SOURCE_ASSOCIATION,InvalidReason.ZERO_LENGTH,
            InvalidReason.PARAMETER_INTERVAL,InvalidReason.ENDPOINT_ASSOCIATION)
        for(reason in reasons) {
            val b=SelectionQueryBudget()
            val p=prepare(square,b); val a=atomic(p)
            val modified=known(p.exact {
                val f=a.fragments.first()
                val changed=when(reason) {
                    InvalidReason.SOURCE_ASSOCIATION -> f.copy(suppliedStartIndex=99)
                    InvalidReason.ZERO_LENGTH -> f.copy(end=f.start)
                    InvalidReason.PARAMETER_INTERVAL -> f.copy(startParameter=f.endParameter)
                    else -> f.copy(start=point(rational(99),rational(99)))
                }
                listOf(changed)+a.fragments.drop(1)
            })
            assertEquals(Result.InvalidInput(reason),SelectionLassoCoincidentGeometry.build(p,
                SelectionLassoAtomicEdges.Borrowed(p,modified)))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){a.fragments}
        }
    }

    @Test fun missingOrRepeatedSourceOccurrencesRejectRatherThanCancel() {
        for(duplicate in listOf(false,true)) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val a=atomic(p)
            val altered=if(duplicate)listOf(a.fragments[0],a.fragments[0])+a.fragments.drop(2) else a.fragments.drop(1)
            assertEquals(Result.InvalidInput(InvalidReason.SOURCE_ASSOCIATION),
                SelectionLassoCoincidentGeometry.build(p,SelectionLassoAtomicEdges.Borrowed(p,altered)))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun groupsChargeAdditionalEdgeRecordsAgainstSharedQuota() {
        for(maximum in listOf(5L,6L,7L)) {
            val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,maximum))
            val p=prepare(triangle,b); val a=atomic(p)
            val before=b.used(SelectionQueryResource.WORK_UNITS)
            assertEquals(3L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            val r=SelectionLassoCoincidentGeometry.build(p,a)
            if(maximum==5L) {
                assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,failure(r).resource)
                assertEquals(5L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
                assertThrows(IllegalStateException::class.java){a.fragments}
            } else {
                assertTrue(r is Result.Ready); assertEquals(6L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            }
            assertTrue(b.used(SelectionQueryResource.WORK_UNITS)>before)
            p.close(); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun laterGroupQuotaFailureInvalidatesEarlierGroupedBorrowerWithoutReset() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,6))
        val p=prepare(triangle,b); val a=atomic(p); val g=grouped(p,a)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        assertEquals(6L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,
            failure(SelectionLassoCoincidentGeometry.build(p,a)).resource)
        assertEquals(6L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertTrue(b.used(SelectionQueryResource.WORK_UNITS)>work)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){g.vertices}
        assertThrows(IllegalStateException::class.java){a.fragments}
    }

    private fun sortingNow()=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoCoincidentGeometry") && it.methodName=="ordered"
    }

    @Test fun exactComparisonWorkExhaustionDuringSortingIsUnresolved() {
        var active=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && !injected && sortingNow() && Thread.currentThread().stackTrace.any {
                it.className.endsWith("SelectionExactArithmetic") && it.methodName=="compare"
            }) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(bowTie,b); val a=atomic(p); active=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoCoincidentGeometry.build(p,a)).resource)
        assertTrue(injected)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun temporaryRepresentationExhaustionDisposesEntireQuery() {
        val probe=SelectionQueryBudget()
        val retained: Long
        prepare(square,probe).use { grouped(it,atomic(it)); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        val p=prepare(square,b); val a=atomic(p)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(SelectionLassoCoincidentGeometry.build(p,a)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.fragments}
    }

    @Test fun cancellationDuringSortingPropagatesWithNoPartialResult() {
        var active=false
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && sortingNow() && Thread.currentThread().stackTrace.any {
                it.className.endsWith("SelectionExactArithmetic") && it.methodName=="compare"
            })throw CancellationException()
        })
        val p=prepare(square,b); val a=atomic(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoCoincidentGeometry.build(p,a)}
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.fragments}
    }

    @Test fun cancellationAfterOneGroupDisposesOwnerAndBorrowers() {
        var active=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==6L)throw CancellationException()
        })
        val p=prepare(square,b); val a=atomic(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoCoincidentGeometry.build(p,a)}
        assertEquals(6L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
}
