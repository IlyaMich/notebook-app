package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.HalfEdge
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.Incident
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorPermutation.Link
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorPermutation.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorPermutation.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SelectionLassoSuccessorPermutationTest {
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
    private fun adjacency(p: Prepared): SelectionLassoIncidentAdjacency.Borrowed {
        val a=(SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        val g=(SelectionLassoCoincidentGeometry.build(p,a) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return (SelectionLassoIncidentAdjacency.build(p,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
    }
    private fun permutation(p: Prepared,a: SelectionLassoIncidentAdjacency.Borrowed):
        SelectionLassoSuccessorPermutation.Borrowed {
        val r=SelectionLassoSuccessorPermutation.build(p,a)
        assertTrue("Expected Ready, got $r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    @Test fun permutationOwnerIdentityIsPureAndSeparateFromLiveness() {
        for(input in listOf(emptyList(),square)) {
            var armed=false; var callbacks=0
            val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            val owner=prepare(input,b); val other=prepare(input)
            try {
                val r=permutation(owner,adjacency(owner))
                val counters=SelectionQueryResource.entries.associateWith { b.used(it) }
                val failure=b.failure; val calls=callbacks
                armed=true
                assertTrue(r.isOwnedBy(owner)); assertFalse(r.isOwnedBy(other))
                assertEquals(calls,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertSame(failure,b.failure)
                owner.close()
                val after=SelectionQueryResource.entries.associateWith { b.used(it) }
                assertTrue(r.isOwnedBy(owner)); assertFalse(r.isOwnedBy(other))
                assertEquals(calls,callbacks)
                assertEquals(after,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertThrows(IllegalStateException::class.java){r.links}
            } finally { owner.close(); other.close() }
        }
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
                val borrower=adjacency(foreign)
                val retained=borrower.halfEdges
                val counters=SelectionQueryResource.entries.associateWith { foreignBudget.used(it) }
                val failure=foreignBudget.failure
                val checkpoints=callbacks
                val primaryWork=primaryBudget.used(SelectionQueryResource.WORK_UNITS)
                armed=true
                assertTrue(borrower.isOwnedBy(foreign))
                assertFalse(borrower.isOwnedBy(primary))
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),
                    SelectionLassoSuccessorPermutation.build(primary,borrower))
                assertEquals(checkpoints,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { foreignBudget.used(it) })
                assertSame(failure,foreignBudget.failure)
                assertSame(retained,borrower.halfEdges)
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
                val borrower=adjacency(foreign)
                val retained=borrower.halfEdges
                val remaining=foreignBudget.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                    foreignBudget.used(SelectionQueryResource.WORK_UNITS)
                assertNotNull(foreignBudget.charge(SelectionQueryResource.WORK_UNITS,remaining+1))
                val counters=SelectionQueryResource.entries.associateWith { foreignBudget.used(it) }
                val failure=foreignBudget.failure
                val checkpoints=callbacks
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),
                    SelectionLassoSuccessorPermutation.build(primary,borrower))
                assertEquals(checkpoints,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { foreignBudget.used(it) })
                assertSame(failure,foreignBudget.failure)
                assertSame(retained,borrower.halfEdges)
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

    /** Direct indexing only: no test enumerates components or walks the permutation. */
    private fun check(r: SelectionLassoSuccessorPermutation.Borrowed) {
        val a=r.adjacency; val edges=a.halfEdges
        assertEquals(edges.size,r.links.size)
        val successors=mutableListOf<Int>(); val predecessors=mutableListOf<Int>()
        for((i,link) in r.links.withIndex()) {
            assertEquals(i,link.halfEdgeId)
            assertTrue(link.next in edges.indices); assertTrue(link.previous in edges.indices)
            assertEquals(i,r.links[link.next].previous); assertEquals(i,r.links[link.previous].next)
            assertEquals(edges[i].destination,edges[link.next].origin)
            assertEquals(edges[i].origin,edges[link.previous].destination)
            val outgoing=a.incidents[edges[i].destination].outgoing
            val position=outgoing.indexOf(edges[i].twin)
            assertTrue(position>=0)
            assertEquals(outgoing[if(position==0)outgoing.lastIndex else position-1],link.next)
            successors.add(link.next); predecessors.add(link.previous)
        }
        assertEquals(edges.indices.toList(),successors.sorted())
        assertEquals(edges.indices.toList(),predecessors.sorted())
    }

    private fun synthetic(points: List<DuPoint>,pairs: List<Pair<Int,Int>>):
        Pair<Prepared,SelectionLassoIncidentAdjacency.Borrowed> {
        val p=prepare(emptyList())
        val g=known(p.exact {
            val original=points.map { point(it) }
            val sorted=original.sortedWith { a,b -> when(compare(a,b)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices=sorted.mapIndexed { i,v -> Vertex(i,v) }
            val groups=pairs.map { (a,b) ->
                val ai=sorted.indexOfFirst { it===original[a] }; val bi=sorted.indexOfFirst { it===original[b] }
                val lo=minOf(ai,bi); val hi=maxOf(ai,bi)
                EdgeGroup(lo,hi,sorted[lo],sorted[hi],emptyList())
            }.sortedWith(compareBy<EdgeGroup>{it.startVertexId}.thenBy{it.endVertexId})
            SelectionLassoCoincidentGeometry.Borrowed(p,vertices,groups)
        })
        val a=(SelectionLassoIncidentAdjacency.build(p,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        return p to a
    }
    private fun changed(
        p: Prepared,a: SelectionLassoIncidentAdjacency.Borrowed,
        edges: List<HalfEdge> = a.halfEdges, incidents: List<Incident> = a.incidents,
    ): SelectionLassoIncidentAdjacency.Borrowed {
        val g=SelectionLassoCoincidentGeometry.Borrowed(p,a.vertices,a.edgeGroups)
        return SelectionLassoIncidentAdjacency.Borrowed(p,g,edges,incidents)
    }

    @Test fun triangleMatchesHandDerivedSuccessorAndPredecessorMappings() {
        prepare(triangle).use {
            val r=permutation(it,adjacency(it)); check(r)
            assertEquals(listOf(4,2,5,0,3,1),r.links.map { l -> l.next })
            assertEquals(listOf(3,5,1,4,0,2),r.links.map { l -> l.previous })
        }
    }

    @Test fun squareMatchesHandDerivedMappings() {
        prepare(square).use {
            val r=permutation(it,adjacency(it)); check(r)
            assertEquals(listOf(4,2,6,0,7,1,5,3),r.links.map { l -> l.next })
            assertEquals(listOf(3,5,1,7,0,6,2,4),r.links.map { l -> l.previous })
        }
    }

    @Test fun concaveAndBowTieGraphsHaveCompleteInverseMappings() {
        for(input in listOf(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4)),bowTie)) {
            prepare(input).use { check(permutation(it,adjacency(it))) }
        }
    }

    @Test fun TJunctionAndFourWayCrossingUsePrecedingIncidentEntry() {
        for(input in listOf(listOf(p(0,0),p(1,0),p(0,1),p(-1,0)),
            listOf(p(0,0),p(1,0),p(0,1),p(-1,0),p(0,-1)))) {
            val (p,a)=synthetic(input,(1 until input.size).map { 0 to it })
            p.use { check(permutation(p,a)) }
        }
    }

    @Test fun degreeOneUsesItsOnlyOutgoingEntryAndIsolatedVertexIsHarmless() {
        val (p,a)=synthetic(listOf(p(0,0),p(1,0),p(2,2)),listOf(0 to 1))
        p.use {
            val r=permutation(p,a); check(r)
            assertEquals(listOf(Link(0,1,1),Link(1,0,0)),r.links)
            assertTrue(a.incidents[2].outgoing.isEmpty())
        }
    }

    @Test fun disconnectedValidatedAdjacencyProducesOneCompletePermutation() {
        val (p,a)=synthetic(listOf(p(0,0),p(1,0),p(3,0),p(4,0)),listOf(0 to 1,2 to 3))
        p.use {
            val r=permutation(p,a); check(r)
            assertEquals(listOf(1,0,3,2),r.links.map { it.next })
        }
    }

    @Test fun emptyAndOneEdgeArrangementsHaveNoRegionInference() {
        for(input in listOf(emptyList(),listOf(p(0,0)),listOf(p(0,0),p(2,0)))) {
            prepare(input).use {
                val r=permutation(it,adjacency(it)); check(r)
                assertEquals(if(input.size<2)0 else 2,r.links.size)
            }
        }
    }

    @Test fun retracedAndPartiallyOverlappingSourcesRemainUntouched() {
        for(input in listOf(listOf(p(0,0),p(2,0),p(0,0),p(2,0)),
            listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2)))) {
            prepare(input).use {
                val a=adjacency(it)
                val edges=a.halfEdges.toList(); val groups=a.edgeGroups.toList()
                val occurrences=a.edgeGroups.flatMap { g -> g.occurrences }
                val r=permutation(it,a); check(r)
                assertSame(a,r.adjacency)
                assertEquals(edges,a.halfEdges); assertEquals(groups,a.edgeGroups)
                for(i in groups.indices) assertSame(groups[i],r.adjacency.edgeGroups[i])
                assertEquals(occurrences,r.adjacency.edgeGroups.flatMap { g -> g.occurrences })
                assertEquals(input,it.suppliedVertices)
            }
        }
    }

    @Test fun reversedWindingAndRotatedStartPreserveMapping() {
        prepare(bowTie).use {
            val expected=permutation(it,adjacency(it)).links
            for(input in listOf(bowTie.reversed(),bowTie.drop(1)+bowTie.take(1))) {
                prepare(input).use { p ->
                    val r=permutation(p,adjacency(p)); check(r); assertEquals(expected,r.links)
                }
            }
        }
    }

    @Test fun repeatedBuildsAreDeterministicAndDoNotChargeExistingEdgesAgain() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,12))
        prepare(triangle,b).use { p ->
            val a=adjacency(p)
            val quota=b.used(SelectionQueryResource.ARRANGEMENT_EDGES)
            val before=a.incidents.toList()
            val first=permutation(p,a); val second=permutation(p,a)
            assertEquals(first.links,second.links); check(first); check(second)
            assertEquals(quota,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            assertEquals(before,a.incidents)
            assertThrows(UnsupportedOperationException::class.java){(first.links as MutableList).clear()}
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun closedPrimaryOwnerRejectsAndBorrowerAccessFails() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val a=adjacency(p); val r=permutation(p,a)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){r.links}
        assertThrows(IllegalStateException::class.java){r.adjacency}
        assertThrows(IllegalStateException::class.java){r.exact { rational(0) }}
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoSuccessorPermutation.build(p,a))
    }

    @Test fun mismatchedOwnerDoesNotDisposeUnrelatedAdjacency() {
        for(input in listOf(emptyList(),square)) {
            val b=SelectionQueryBudget(); val p=prepare(input,b)
            prepare(input).use { other ->
                val a=adjacency(other)
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoSuccessorPermutation.build(p,a))
                assertEquals(input,other.suppliedVertices)
                assertEquals(if(input.isEmpty())0 else 8,a.halfEdges.size)
                assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun mismatchedEmptyOwnerMustNotBeDisposedByIdentityProbe() {
        val primary=prepare(emptyList())
        var cancel=false
        val budget=SelectionQueryBudget(cancellationCheckpoint={if(cancel)throw CancellationException()})
        val other=prepare(emptyList(),budget)
        val foreign=adjacency(other)
        cancel=true
        try {
            var result: Result?=null
            var cancelled=false
            try { result=SelectionLassoSuccessorPermutation.build(primary,foreign) }
            catch (_: CancellationException) { cancelled=true }
            val unrelatedStillOpen=runCatching { other.segments }.isSuccess
            assertTrue("Owner-identity probing must not dispose an unrelated Prepared",unrelatedStillOpen)
            assertFalse("Owner-identity probing must not execute an unrelated cancellation checkpoint",cancelled)
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),result)
        } finally { primary.close(); other.close() }
    }

    @Test fun closedAdjacencyOwnerRejectsAndDisposesPrimaryOwner() {
        val b=SelectionQueryBudget(); val p=prepare(square,b)
        val other=prepare(square); val a=adjacency(other); other.close()
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoSuccessorPermutation.build(p,a))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun invalidHalfEdgeIdsTwinsAndEndpointsRejectExplicitly() {
        for(kind in 0..4) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val a=adjacency(p)
            val edges=a.halfEdges.toMutableList()
            val expected=when(kind) {
                0 -> { edges[0]=edges[0].copy(id=99); InvalidReason.EDGE_ID }
                1 -> { edges[0]=edges[0].copy(twin=99); InvalidReason.TWIN }
                2 -> { edges[1]=edges[1].copy(twin=2); InvalidReason.TWIN }
                3 -> { edges[0]=edges[0].copy(origin=99); InvalidReason.ENDPOINT }
                else -> { edges[0]=edges[0].copy(destination=edges[0].origin); InvalidReason.ENDPOINT }
            }
            assertEquals(Result.InvalidInput(expected),SelectionLassoSuccessorPermutation.build(p,changed(p,a,edges=edges)))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){a.halfEdges}
        }
    }

    @Test fun wrongIncidentVertexDuplicatesMissingAndAbsentTwinsReject() {
        for(kind in 0..4) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val a=adjacency(p)
            val incidents=a.incidents.toMutableList()
            val expected=when(kind) {
                0 -> { incidents[0]=incidents[0].copy(vertexId=1); InvalidReason.INCIDENT_VERTEX }
                1 -> { incidents[0]=incidents[0].copy(outgoing=incidents[0].outgoing+incidents[0].outgoing[0])
                    InvalidReason.INCIDENT_MEMBERSHIP }
                2 -> { incidents[0]=incidents[0].copy(outgoing=incidents[0].outgoing.drop(1))
                    InvalidReason.INCIDENT_MEMBERSHIP }
                3 -> { incidents[1]=incidents[1].copy(outgoing=emptyList()); InvalidReason.INCIDENT_MEMBERSHIP }
                else -> { incidents[0]=incidents[0].copy(outgoing=incidents[1].outgoing)
                    InvalidReason.INCIDENT_MEMBERSHIP }
            }
            assertEquals(Result.InvalidInput(expected),
                SelectionLassoSuccessorPermutation.build(p,changed(p,a,incidents=incidents)))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun contradictoryAngularOrderIsRejectedWithoutResorting() {
        val b=SelectionQueryBudget(); val p=prepare(bowTie,b); val a=adjacency(p)
        val incident=a.incidents.first { it.outgoing.size==4 }
        val altered=a.incidents.map { if(it.vertexId==incident.vertexId)it.copy(outgoing=it.outgoing.reversed()) else it }
        assertEquals(Result.InvalidInput(InvalidReason.ANGULAR_ORDER),
            SelectionLassoSuccessorPermutation.build(p,changed(p,a,incidents=altered)))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun successorAndPredecessorFixtureContradictionsAreDetected() {
        prepare(triangle).use { p ->
            val a=adjacency(p); val good=permutation(p,a).links
            val fixtures=listOf(
                good.dropLast(1),
                listOf(good[0].copy(halfEdgeId=99))+good.drop(1),
                listOf(good[0].copy(next=99))+good.drop(1),
                listOf(good[0].copy(previous=99))+good.drop(1),
                listOf(good[0].copy(next=good[1].next))+good.drop(1),
                listOf(good[0].copy(previous=good[1].previous))+good.drop(1),
                good.map { it.copy(next=a.halfEdges[it.halfEdgeId].twin,previous=a.halfEdges[it.halfEdgeId].twin) },
            )
            for(fixture in fixtures) {
                assertEquals(InvalidReason.PERMUTATION,known(p.exact {
                    with(SelectionLassoSuccessorPermutation) { validateLinks(a,fixture) }
                }))
            }
            assertNull(known(p.exact { with(SelectionLassoSuccessorPermutation) { validateLinks(a,good) } }))
        }
    }

    private fun inPhase(name: String)=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoSuccessorPermutation") && it.methodName.substringBefore('$')==name
    }

    @Test fun workExhaustionDuringFinalValidationIsTypedAndPublishesNothing() {
        var active=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && !injected && inPhase("validateLinks")) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(square,b); val a=adjacency(p); active=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoSuccessorPermutation.build(p,a)).resource)
        assertTrue(injected); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.halfEdges}
    }

    @Test fun representationExhaustionClosesOwnerWithoutPartialLinks() {
        val probe=SelectionQueryBudget()
        val retained: Long
        prepare(square,probe).use { permutation(it,adjacency(it)); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        val p=prepare(square,b); val a=adjacency(p)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(SelectionLassoSuccessorPermutation.build(p,a)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun cancellationDuringConstructionPropagatesAndCleansReservations() {
        var active=false; var checks=0
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && inPhase("constructLinks") && !inPhase("validateAdjacency") &&
                !inPhase("indexIncidents") && !inPhase("validateLinks") && ++checks==12)
                throw CancellationException()
        })
        val p=prepare(square,b); val a=adjacency(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoSuccessorPermutation.build(p,a)}
        assertEquals(12,checks)
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun cancellationDuringValidationPropagatesAndCleansReservations() {
        var active=false
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && inPhase("validateLinks"))throw CancellationException()
        })
        val p=prepare(square,b); val a=adjacency(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoSuccessorPermutation.build(p,a)}
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.incidents}
    }

    @Test fun laterFailureInvalidatesEarlierPermutationWithoutResettingHistory() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val a=adjacency(p); val r=permutation(p,a)
        val quota=b.used(SelectionQueryResource.ARRANGEMENT_EDGES)
        assertNull(b.charge(SelectionQueryResource.WORK_UNITS,
            b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)))
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoSuccessorPermutation.build(p,a)).resource)
        assertEquals(quota,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){r.links}
    }
}
