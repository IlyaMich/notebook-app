package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.Result
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SelectionLassoIncidentAdjacencyTest {
    private fun p(x: Number,y: Number)=DuPoint(x.toDouble(),y.toDouble())
    private val triangle=listOf(p(0,0),p(2,0),p(0,2))
    private val square=listOf(p(0,0),p(2,0),p(2,2),p(0,2))
    private val bowTie=listOf(p(0,0),p(2,2),p(0,2),p(2,0))
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known, got $r",r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private fun prepare(input: List<DuPoint>,b: SelectionQueryBudget=SelectionQueryBudget()) =
        known(SelectionLassoSplitEvents.prepare(input,b))
    private fun grouped(p: SelectionLassoSplitEvents.Prepared): SelectionLassoCoincidentGeometry.Borrowed {
        val a=(SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        return (SelectionLassoCoincidentGeometry.build(p,a) as SelectionLassoCoincidentGeometry.Result.Ready).value
    }
    private fun adjacency(p: SelectionLassoSplitEvents.Prepared,g: SelectionLassoCoincidentGeometry.Borrowed):
        SelectionLassoIncidentAdjacency.Borrowed {
        val r=SelectionLassoIncidentAdjacency.build(p,g)
        assertTrue("Expected Ready, got $r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun fraction(r: SelectionExactArithmetic.Rational)="${r.numerator}/${r.denominator}"
    private fun point(p: Point)="${fraction(p.x)},${fraction(p.y)}"
    private fun destinations(a: SelectionLassoIncidentAdjacency.Borrowed,origin: String): List<String> {
        val id=a.vertices.single { point(it.point)==origin }.id
        return a.incidents[id].outgoing.map { point(a.vertices[a.halfEdges[it].destination].point) }
    }
    @Test fun ownerMismatchNeverInvokesForeignCancellationOrChangesRetainedState() {
        for(input in listOf(emptyList(),square)) {
            val primaryBudget=SelectionQueryBudget()
            val primary=prepare(input,primaryBudget)
            var armed=false; var callbacks=0
            val foreignBudget=SelectionQueryBudget(cancellationCheckpoint={
                callbacks++
                if(armed)throw CancellationException("Foreign owner must not be probed")
            })
            val foreign=prepare(input,foreignBudget)
            try {
                val borrower=grouped(foreign)
                val retained=borrower.edges
                val counters=SelectionQueryResource.entries.associateWith { foreignBudget.used(it) }
                val failure=foreignBudget.failure
                val checkpoints=callbacks
                val primaryWork=primaryBudget.used(SelectionQueryResource.WORK_UNITS)
                armed=true
                assertTrue(borrower.isOwnedBy(foreign))
                assertFalse(borrower.isOwnedBy(primary))
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),
                    SelectionLassoIncidentAdjacency.build(primary,borrower))
                assertEquals(checkpoints,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { foreignBudget.used(it) })
                assertSame(failure,foreignBudget.failure)
                assertSame(retained,borrower.edges)
                assertEquals(input,foreign.suppliedVertices)
                assertThrows(IllegalStateException::class.java){primary.segments}
                assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertEquals(primaryWork,primaryBudget.used(SelectionQueryResource.WORK_UNITS))
                armed=false
                assertEquals(7,known(foreign.exact { 7 })) // still usable after rejection
            } finally { primary.close(); foreign.close() }
        }
    }

    @Test fun ownerMismatchPreservesForeignStickyFailureWithoutExecutingIt() {
        for(input in listOf(emptyList(),square)) {
            val primaryBudget=SelectionQueryBudget()
            val primary=prepare(input,primaryBudget)
            var armed=false; var callbacks=0
            val foreignBudget=SelectionQueryBudget(cancellationCheckpoint={
                callbacks++
                if(armed)throw CancellationException("Foreign failure must not be probed")
            })
            val foreign=prepare(input,foreignBudget)
            try {
                val borrower=grouped(foreign)
                val retained=borrower.edges
                val remaining=foreignBudget.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                    foreignBudget.used(SelectionQueryResource.WORK_UNITS)
                assertNotNull(foreignBudget.charge(SelectionQueryResource.WORK_UNITS,remaining+1))
                val counters=SelectionQueryResource.entries.associateWith { foreignBudget.used(it) }
                val failure=foreignBudget.failure
                val checkpoints=callbacks
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),
                    SelectionLassoIncidentAdjacency.build(primary,borrower))
                assertEquals(checkpoints,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { foreignBudget.used(it) })
                assertSame(failure,foreignBudget.failure)
                assertSame(retained,borrower.edges)
                assertEquals(input,foreign.suppliedVertices)
                assertThrows(IllegalStateException::class.java){primary.segments}
                assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
            } finally { primary.close(); foreign.close() }
        }
    }

    private fun limits(resource: SelectionQueryResource,n: Long)=SelectionQueryLimits(
        query=SelectionQueryResource.entries.associateWith { if(it==resource)n else it.queryCeiling })
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue(r is Result.Unresolved)
        val c=(r as Result.Unresolved).cause
        assertTrue(c is SelectionPredicateResult.WorkloadExceeded)
        return (c as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun validate(a: SelectionLassoIncidentAdjacency.Borrowed) {
        assertEquals(2*a.edgeGroups.size,a.halfEdges.size)
        val assigned=mutableListOf<Int>()
        for((i,e) in a.halfEdges.withIndex()) {
            assertEquals(i,e.id); assertEquals(i/2,e.edgeGroupIndex)
            assertEquals(i%2==0,e.canonicalForward)
            assertEquals(i xor 1,e.twin)
            val twin=a.halfEdges[e.twin]
            assertEquals(e.id,twin.twin); assertEquals(e.origin,twin.destination)
            assertEquals(e.destination,twin.origin)
            val g=a.edgeGroups[e.edgeGroupIndex]
            assertEquals(if(e.canonicalForward)g.startVertexId else g.endVertexId,e.origin)
            assertEquals(if(e.canonicalForward)g.endVertexId else g.startVertexId,e.destination)
        }
        for((i,incident) in a.incidents.withIndex()) {
            assertEquals(i,incident.vertexId)
            for(id in incident.outgoing) {
                assertEquals(i,a.halfEdges[id].origin); assigned.add(id)
            }
        }
        assertEquals(a.halfEdges.indices.toList(),assigned.sorted())
    }

    /** Synthetic grouped geometry for exact local-order tests, including non-cycle T junctions. */
    private fun synthetic(
        pairs: List<Pair<Int,Int>>, budget: SelectionQueryBudget=SelectionQueryBudget(),
        points: SelectionExactArithmetic.()->List<Point>,
    ): Pair<SelectionLassoSplitEvents.Prepared,SelectionLassoCoincidentGeometry.Borrowed> {
        val p=prepare(emptyList(),budget)
        val g=known(p.exact {
            val original=points()
            val sorted=original.sortedWith { a,b -> when(compare(a,b)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices=sorted.mapIndexed { i,v -> Vertex(i,v) }
            val edges=pairs.map { (a,b) ->
                val ai=sorted.indexOfFirst { it===original[a] }; val bi=sorted.indexOfFirst { it===original[b] }
                val lo=minOf(ai,bi); val hi=maxOf(ai,bi)
                EdgeGroup(lo,hi,sorted[lo],sorted[hi],emptyList())
            }.sortedWith(compareBy<EdgeGroup>{it.startVertexId}.thenBy{it.endVertexId})
            SelectionLassoCoincidentGeometry.Borrowed(p,vertices,edges)
        })
        return p to g
    }

    @Test fun triangleHasTwoOutgoingRecordsPerVertexAndReciprocalTwins() {
        prepare(triangle).use {
            val a=adjacency(it,grouped(it)); validate(a)
            assertEquals(listOf(2,2,2),a.incidents.map { v -> v.outgoing.size })
            assertEquals(listOf("2/1,0/1","0/1,2/1"),destinations(a,"0/1,0/1"))
        }
    }

    @Test fun squareCounterclockwiseOrderStartsAtPositiveXAxis() {
        prepare(square).use {
            val a=adjacency(it,grouped(it)); validate(a)
            assertEquals(listOf("2/1,0/1","0/1,2/1"),destinations(a,"0/1,0/1"))
            assertEquals(listOf("2/1,2/1","0/1,0/1"),destinations(a,"2/1,0/1"))
            assertEquals(listOf("0/1,2/1","2/1,0/1"),destinations(a,"2/1,2/1"))
            assertEquals(listOf("2/1,2/1","0/1,0/1"),destinations(a,"0/1,2/1"))
        }
    }

    @Test fun concavePolygonPreservesCompleteOutgoingCollections() {
        prepare(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4))).use {
            val a=adjacency(it,grouped(it)); validate(a)
            assertEquals(5,a.incidents.size); assertTrue(a.incidents.all { v -> v.outgoing.size==2 })
        }
    }

    @Test fun bowTieCrossingHasDegreeFourAndHandDerivedOrder() {
        prepare(bowTie).use {
            val a=adjacency(it,grouped(it)); validate(a)
            assertEquals(listOf("2/1,2/1","0/1,2/1","0/1,0/1","2/1,0/1"),
                destinations(a,"1/1,1/1"))
        }
    }

    @Test fun severalSegmentsMeetAtOneExactVertex() {
        prepare(listOf(p(-2,0),p(2,0),p(0,-2),p(0,2),p(-2,-2),p(2,2))).use {
            val a=adjacency(it,grouped(it)); validate(a)
            assertEquals(6,destinations(a,"0/1,0/1").size)
        }
    }

    @Test fun exactAxesAndAllQuadrantsHaveNoAngleOrMagnitudeDivision() {
        val (p,g)=synthetic((1..8).map { 0 to it }) {
            listOf(point(p(0,0)),point(p(1,0)),point(p(1,1)),point(p(0,1)),point(p(-1,1)),
                point(p(-1,0)),point(p(-1,-1)),point(p(0,-1)),point(p(1,-1)))
        }
        p.use {
            val a=adjacency(p,g); validate(a)
            assertEquals(listOf("1/1,0/1","1/1,1/1","0/1,1/1","-1/1,1/1",
                "-1/1,0/1","-1/1,-1/1","0/1,-1/1","1/1,-1/1"),destinations(a,"0/1,0/1"))
        }
    }

    @Test fun collinearTJunctionAndOppositeRaysAreValid() {
        val (p,g)=synthetic(listOf(0 to 1,0 to 2,0 to 3)) {
            listOf(point(p(0,0)),point(p(1,0)),point(p(0,1)),point(p(-1,0)))
        }
        p.use {
            val a=adjacency(p,g); validate(a)
            assertEquals(listOf("1/1,0/1","0/1,1/1","-1/1,0/1"),destinations(a,"0/1,0/1"))
        }
    }

    @Test fun isolatedVerticesHaveExplicitEmptyCollections() {
        val (p,g)=synthetic(listOf(0 to 1)) { listOf(point(p(0,0)),point(p(1,0)),point(p(2,2))) }
        p.use {
            val a=adjacency(p,g); validate(a)
            assertTrue(a.incidents[2].outgoing.isEmpty())
        }
    }

    @Test fun nearCollinearRationalAnglesBelowDoubleResolutionRemainDistinct() {
        val (p,g)=synthetic(listOf(0 to 1,0 to 2,0 to 3)) {
            val tiny=rational(1,1L shl 60)
            listOf(point(rational(0),rational(0)),point(rational(1),rational(1)),
                point(rational(1),add(rational(1),tiny)),
                point(rational(1),subtract(rational(1),tiny)))
        }
        p.use {
            val a=adjacency(p,g); validate(a)
            val ids=a.incidents[a.vertices.single { point(it.point)=="0/1,0/1" }.id].outgoing
                .map { a.halfEdges[it].destination }
            known(a.exact {
                assertEquals(Sign.NEGATIVE,compare(a.vertices[ids[0]].point.y,rational(1)))
                assertEquals(Sign.ZERO,compare(a.vertices[ids[1]].point.y,rational(1)))
                assertEquals(Sign.POSITIVE,compare(a.vertices[ids[2]].point.y,rational(1)))
            })
        }
    }

    @Test fun verySmallAndLargeBinaryScalesKeepOrdering() {
        for(scale in listOf(Math.scalb(1.0,-400),Math.scalb(1.0,400))) {
            val (p,g)=synthetic(listOf(0 to 1,0 to 2,0 to 3)) {
                listOf(point(p(0,0)),point(p(scale,0)),point(p(0,scale)),point(p(-scale,0)))
            }
            p.use {
                val a=adjacency(p,g); validate(a)
                assertEquals(listOf(3,2,0),a.incidents[1].outgoing.map { a.halfEdges[it].destination })
            }
        }
    }

    @Test fun rationalThirdCrossingsRemainExactInAdjacency() {
        prepare(listOf(p(0,0),p(1,1),p(0,1),p(1,-1))).use {
            val a=adjacency(it,grouped(it)); validate(a)
            val cross=a.vertices.single { v -> point(v.point)=="1/3,1/3" }
            assertEquals(4,a.incidents[cross.id].outgoing.size)
        }
    }

    @Test fun sourceMultiplicityAndDirectedParametersAreUntouched() {
        for(input in listOf(listOf(p(0,0),p(2,0),p(0,0),p(2,0)),
            listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2)))) {
            prepare(input).use {
                val g=grouped(it); val before=g.edges.toList()
                val occurrences=g.edges.flatMap { e -> e.occurrences }.toList()
                val a=adjacency(it,g); validate(a)
                assertEquals(before,g.edges)
                for(i in before.indices) assertSame(before[i],a.edgeGroups[i])
                assertEquals(occurrences,a.edgeGroups.flatMap { e -> e.occurrences })
                assertEquals(input,it.suppliedVertices)
            }
        }
    }

    @Test fun windingAndStartRotationPreserveGeometricAdjacency() {
        prepare(bowTie).use {
            val original=adjacency(it,grouped(it))
            val expected=original.halfEdges to original.incidents
            for(input in listOf(bowTie.reversed(),bowTie.drop(1)+bowTie.take(1))) {
                prepare(input).use { p ->
                    val a=adjacency(p,grouped(p)); validate(a)
                    assertEquals(expected,a.halfEdges to a.incidents)
                }
            }
        }
    }

    @Test fun emptyAndFullyRetracedWalksDoNotImplyFilledRegions() {
        for(input in listOf(emptyList(),listOf(p(0,0)),listOf(p(0,0),p(2,0)))) {
            prepare(input).use {
                val a=adjacency(it,grouped(it)); validate(a)
                assertEquals(if(input.size<2)0 else 2,a.halfEdges.size)
            }
        }
    }

    @Test fun ownerClosureInvalidatesBorrowedOutputAndImmutableLists() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val g=grouped(p); val a=adjacency(p,g)
        assertThrows(UnsupportedOperationException::class.java){(a.halfEdges as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(a.incidents as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(a.incidents[0].outgoing as MutableList).clear()}
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertThrows(IllegalStateException::class.java){a.halfEdges}
        assertThrows(IllegalStateException::class.java){a.incidents}
        assertThrows(IllegalStateException::class.java){a.exact { rational(0) }}
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoIncidentAdjacency.build(p,g))
    }

    @Test fun unrelatedOwnerMismatchDoesNotDisposeOtherGeometry() {
        val b=SelectionQueryBudget(); val p=prepare(square,b)
        prepare(square).use { other ->
            val g=grouped(other)
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoIncidentAdjacency.build(p,g))
            assertEquals(4,g.vertices.size)
            assertEquals(square,other.suppliedVertices)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun closedGroupedOwnerDisposesPrimaryWithoutReturningPartialResult() {
        val b=SelectionQueryBudget(); val p=prepare(square,b)
        val other=prepare(square); val g=grouped(other); other.close()
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoIncidentAdjacency.build(p,g))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun invalidVerticesAndGeometricEdgesRejectExplicitly() {
        val cases=listOf(InvalidReason.VERTEX_ORDER,InvalidReason.ENDPOINT_ID,InvalidReason.ZERO_LENGTH,
            InvalidReason.EDGE_ORDER,InvalidReason.ENDPOINT_ASSOCIATION)
        for(reason in cases) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val g=grouped(p)
            val bad=known(p.exact {
                val vertices=if(reason==InvalidReason.VERTEX_ORDER)
                    listOf(g.vertices[0].copy(id=9))+g.vertices.drop(1) else g.vertices
                val edges=when(reason) {
                    InvalidReason.ENDPOINT_ID -> listOf(g.edges[0].copy(endVertexId=99))+g.edges.drop(1)
                    InvalidReason.ZERO_LENGTH -> listOf(g.edges[0].copy(endVertexId=g.edges[0].startVertexId,
                        end=g.edges[0].start))+g.edges.drop(1)
                    InvalidReason.EDGE_ORDER -> listOf(g.edges[0],g.edges[0])+g.edges.drop(1)
                    InvalidReason.ENDPOINT_ASSOCIATION -> listOf(g.edges[0].copy(end=point(rational(99),rational(99))))+g.edges.drop(1)
                    else -> g.edges
                }
                SelectionLassoCoincidentGeometry.Borrowed(p,vertices,edges)
            })
            assertEquals(Result.InvalidInput(reason),SelectionLassoIncidentAdjacency.build(p,bad))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){g.edges}
        }
    }

    @Test fun distinctGroupsOnSameRayAreInvalidRatherThanTieBroken() {
        for(points in listOf(listOf(p(0,0),p(1,0),p(2,0)),
            listOf(p(0,0),p(1,1),p(2,2)),
            listOf(p(0,0),p(-1,-1),p(-2,-2)))) {
            val (p,g)=synthetic(listOf(0 to 1,0 to 2)){points.map { point(it) }}
            p.use {
                assertEquals(Result.InvalidInput(InvalidReason.SAME_RAY),SelectionLassoIncidentAdjacency.build(p,g))
                assertThrows(IllegalStateException::class.java){g.edges}
            }
        }
    }

    @Test fun halfEdgesConsumeAdditionalSharedQuotaAtBelowAndAboveBoundary() {
        for(maximum in listOf(11L,12L,13L)) {
            val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,maximum))
            val p=prepare(triangle,b); val g=grouped(p)
            assertEquals(6L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            val r=SelectionLassoIncidentAdjacency.build(p,g)
            if(maximum==11L) assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,failure(r).resource)
            else { assertTrue(r is Result.Ready); validate((r as Result.Ready).value) }
            assertEquals(minOf(12,maximum),b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            p.close(); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    private fun angularComparisonNow()=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoIncidentAdjacency") && it.methodName=="angleCompare"
    }

    @Test fun exactAngularWorkExhaustionRemainsTypedAndDisposesQuery() {
        var active=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && !injected && angularComparisonNow() && Thread.currentThread().stackTrace.any {
                it.className.endsWith("SelectionExactArithmetic") && it.methodName=="cross"
            }) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(bowTie,b); val g=grouped(p); active=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoIncidentAdjacency.build(p,g)).resource)
        assertTrue(injected); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun exactOperandExhaustionInAngularCrossProductIsNotAFalseOrder() {
        val b=SelectionQueryBudget()
        val (p,g)=synthetic(listOf(0 to 1,0 to 2),b) {
            var tiny=rational(1)
            val step=rational(1,1L shl 60)
            repeat(35) { tiny=multiply(tiny,step) } // 2^-2100, admitted individually
            listOf(point(rational(0),rational(0)),point(rational(1),tiny),point(tiny,rational(1)))
        }
        val result=SelectionLassoIncidentAdjacency.build(p,g)
        assertEquals(SelectionBudgetConstraint.EXACT_BITS,failure(result).constraint)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){g.vertices}
    }

    @Test fun halfEdgeIdOverflowRejectsBeforeAllocatingHugeArrays() {
        val b=SelectionQueryBudget(); val p=prepare(emptyList(),b)
        val oversized=object: AbstractList<EdgeGroup>() {
            override val size=Int.MAX_VALUE
            override fun get(index: Int): EdgeGroup=error("Must not read oversized edge list")
        }
        val g=SelectionLassoCoincidentGeometry.Borrowed(p,emptyList(),oversized)
        assertEquals(Result.InvalidInput(InvalidReason.ID_RANGE),SelectionLassoIncidentAdjacency.build(p,g))
        assertEquals(0L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun representationExhaustionReleasesEverything() {
        val probe=SelectionQueryBudget()
        val retained: Long
        prepare(square,probe).use { adjacency(it,grouped(it)); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        val p=prepare(square,b); val g=grouped(p)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(SelectionLassoIncidentAdjacency.build(p,g)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun cancellationDuringAngularComparisonIsNotAnInvalidResult() {
        var active=false
        val b=SelectionQueryBudget(cancellationCheckpoint={if(active && angularComparisonNow())throw CancellationException()})
        val p=prepare(bowTie,b); val g=grouped(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoIncidentAdjacency.build(p,g)}
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){g.edges}
    }

    @Test fun laterFailureInvalidatesEarlierAdjacencyWithoutResettingQuota() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,12))
        val p=prepare(triangle,b); val g=grouped(p); val a=adjacency(p,g)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,failure(SelectionLassoIncidentAdjacency.build(p,g)).resource)
        assertEquals(12L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertTrue(b.used(SelectionQueryResource.WORK_UNITS)>work)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.incidents}
    }
}
