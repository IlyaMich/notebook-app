package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorCycles.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorCycles.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SelectionLassoSuccessorCyclesTest {
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
    private fun permutation(p: Prepared): SelectionLassoSuccessorPermutation.Borrowed {
        val a=(SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        val g=(SelectionLassoCoincidentGeometry.build(p,a) as SelectionLassoCoincidentGeometry.Result.Ready).value
        val incident=(SelectionLassoIncidentAdjacency.build(p,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        return (SelectionLassoSuccessorPermutation.build(p,incident) as SelectionLassoSuccessorPermutation.Result.Ready).value
    }
    private fun cycles(p: Prepared,source: SelectionLassoSuccessorPermutation.Borrowed):
        SelectionLassoSuccessorCycles.Borrowed {
        val r=SelectionLassoSuccessorCycles.build(p,source)
        assertTrue("Expected Ready, got $r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    @Test fun cycleOwnershipPredicateIsPureAndSeparateFromClosedLifetime() {
        for(input in listOf(emptyList(),square)) {
            var armed=false; var calls=0
            val b=SelectionQueryBudget(cancellationCheckpoint={calls++;if(armed)throw CancellationException()})
            val p=prepare(input,b); val other=prepare(input)
            try {
                val c=cycles(p,permutation(p))
                val counters=SelectionQueryResource.entries.associateWith { b.used(it) }
                val failure=b.failure; val before=calls
                armed=true
                assertTrue(c.isOwnedBy(p)); assertFalse(c.isOwnedBy(other))
                assertEquals(before,calls)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertSame(failure,b.failure)
                p.close()
                val closedCounters=SelectionQueryResource.entries.associateWith { b.used(it) }
                assertTrue(c.isOwnedBy(p)); assertFalse(c.isOwnedBy(other))
                assertEquals(before,calls)
                assertEquals(closedCounters,SelectionQueryResource.entries.associateWith { b.used(it) })
                assertThrows(IllegalStateException::class.java){c.walks}
            } finally { p.close(); other.close() }
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
    private fun check(r: SelectionLassoSuccessorCycles.Borrowed) {
        val source=r.permutation; val all=mutableListOf<Int>()
        assertEquals(source.links.size,r.halfEdgeToWalk.size)
        var lastStart=-1
        for((i,walk) in r.walks.withIndex()) {
            assertEquals(i,walk.id)
            assertTrue(walk.length>0)
            assertEquals(walk.halfEdgeIds.size,walk.length)
            assertEquals(walk.halfEdgeIds.first(),walk.startHalfEdgeId)
            assertEquals(walk.halfEdgeIds.minOrNull(),walk.startHalfEdgeId)
            assertTrue(walk.startHalfEdgeId>lastStart); lastStart=walk.startHalfEdgeId
            for((position,h) in walk.halfEdgeIds.withIndex()) {
                assertTrue(h in source.links.indices)
                assertEquals(i,r.halfEdgeToWalk[h])
                val next=walk.halfEdgeIds[(position+1)%walk.length]
                assertEquals(next,source.links[h].next)
                assertEquals(h,source.links[next].previous)
                assertEquals(r.adjacency.halfEdges[h].destination,r.adjacency.halfEdges[next].origin)
                all.add(h)
            }
        }
        assertEquals(source.links.indices.toList(),all.sorted())
        assertEquals(all.size,all.distinct().size)
    }

    /** Exact validated adjacency supports synthetic disconnected and non-cycle local graphs. */
    private fun synthetic(points: List<DuPoint>,pairs: List<Pair<Int,Int>>):
        Pair<Prepared,SelectionLassoSuccessorPermutation.Borrowed> {
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
        val permutation=(SelectionLassoSuccessorPermutation.build(p,a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        return p to permutation
    }

    @Test fun emptyAndSinglePointInputsYieldEmptySuccessfulCandidateSets() {
        for(input in listOf(emptyList(),listOf(p(0,0)))) prepare(input).use {
            val r=cycles(it,permutation(it)); check(r)
            assertTrue(r.walks.isEmpty()); assertTrue(r.halfEdgeToWalk.isEmpty())
        }
    }

    @Test fun oneEdgeBacktrackingWalkIsRetainedWithoutAreaRequirement() {
        prepare(listOf(p(0,0),p(2,0))).use {
            val r=cycles(it,permutation(it)); check(r)
            assertEquals(listOf(listOf(0,1)),r.walks.map { w -> w.halfEdgeIds })
            assertEquals(listOf(0,0),r.halfEdgeToWalk)
        }
    }

    @Test fun triangleHasHandDerivedDirectedWalks() {
        prepare(triangle).use {
            val r=cycles(it,permutation(it)); check(r)
            assertEquals(listOf(listOf(0,4,3),listOf(1,2,5)),r.walks.map { w -> w.halfEdgeIds })
        }
    }

    @Test fun squareHasHandDerivedDirectedWalks() {
        prepare(square).use {
            val r=cycles(it,permutation(it)); check(r)
            assertEquals(listOf(listOf(0,4,7,3),listOf(1,2,6,5)),r.walks.map { w -> w.halfEdgeIds })
        }
    }

    @Test fun concaveAndSignedAreaZeroBowTieWalksRemainConnectivityOnly() {
        for(input in listOf(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4)),bowTie)) {
            prepare(input).use {
                val r=cycles(it,permutation(it)); check(r)
                assertTrue(r.walks.isNotEmpty())
                if(input==bowTie)assertEquals(3,r.walks.size)
            }
        }
    }

    @Test fun severalSegmentsMeetingAtOnePointHaveCompleteCoverage() {
        prepare(listOf(p(-2,0),p(2,0),p(0,-2),p(0,2),p(-2,-2),p(2,2))).use {
            check(cycles(it,permutation(it)))
        }
    }

    @Test fun TJunctionDegreeOneAndIsolatedVerticesAreRetained() {
        val (p,source)=synthetic(listOf(p(0,0),p(1,0),p(0,1),p(-1,0),p(5,5)),
            listOf(0 to 1,0 to 2,0 to 3))
        p.use {
            val r=cycles(p,source); check(r)
            assertEquals(1,r.walks.size); assertEquals(6,r.walks.single().length)
            assertTrue(source.adjacency.incidents.last().outgoing.isEmpty())
        }
    }

    @Test fun fullyRetracedAndPartiallyOverlappingSourcesAreNeverCancelled() {
        for(input in listOf(listOf(p(0,0),p(2,0),p(0,0),p(2,0)),
            listOf(p(0,0),p(4,0),p(1,0),p(3,0),p(3,2)))) {
            prepare(input).use {
                val source=permutation(it)
                val edges=source.adjacency.edgeGroups.toList()
                val occurrences=edges.flatMap { g -> g.occurrences }
                val links=source.links.toList()
                val r=cycles(it,source); check(r)
                assertSame(source,r.permutation); assertSame(source.adjacency,r.adjacency)
                assertEquals(links,source.links)
                for(i in edges.indices)assertSame(edges[i],r.adjacency.edgeGroups[i])
                assertEquals(occurrences,r.adjacency.edgeGroups.flatMap { g -> g.occurrences })
                assertEquals(input,it.suppliedVertices)
            }
        }
    }

    @Test fun disconnectedComponentsAndMultipleBoundaryWalksAreDeterministic() {
        val (p,source)=synthetic(listOf(p(0,0),p(2,0),p(0,2),p(5,0),p(7,0),p(5,2)),
            listOf(0 to 1,1 to 2,2 to 0,3 to 4,4 to 5,5 to 3))
        p.use {
            val r=cycles(p,source); check(r)
            assertEquals(listOf(0,1,6,7),r.walks.map { it.startHalfEdgeId })
            assertEquals(listOf(3,3,3,3),r.walks.map { it.length })
        }
    }

    @Test fun windingAndStartingVertexPreserveEquivalentGeometricWalks() {
        prepare(bowTie).use {
            val first=cycles(it,permutation(it))
            val walks=first.walks; val index=first.halfEdgeToWalk
            for(input in listOf(bowTie.reversed(),bowTie.drop(1)+bowTie.take(1))) {
                prepare(input).use { p ->
                    val r=cycles(p,permutation(p)); check(r)
                    assertEquals(walks,r.walks); assertEquals(index,r.halfEdgeToWalk)
                }
            }
        }
    }

    @Test fun immutableDeterministicOutputDoesNotRechargeGeometryQuotas() {
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_EDGES,12))
        prepare(triangle,b).use { p ->
            val source=permutation(p)
            val edges=b.used(SelectionQueryResource.ARRANGEMENT_EDGES)
            val faces=b.used(SelectionQueryResource.ARRANGEMENT_FACES)
            val first=cycles(p,source); val second=cycles(p,source)
            assertEquals(first.walks,second.walks)
            assertEquals(first.halfEdgeToWalk,second.halfEdgeToWalk)
            assertEquals(edges,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            assertEquals(faces,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
            assertThrows(UnsupportedOperationException::class.java){(first.walks as MutableList).clear()}
            assertThrows(UnsupportedOperationException::class.java){(first.walks[0].halfEdgeIds as MutableList).clear()}
            assertThrows(UnsupportedOperationException::class.java){(first.halfEdgeToWalk as MutableList).clear()}
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun closedOwnerInvalidatesBorrowerAndRejectsRebuild() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val source=permutation(p); val r=cycles(p,source)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        p.close(); p.close()
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){r.walks}
        assertThrows(IllegalStateException::class.java){r.halfEdgeToWalk}
        assertThrows(IllegalStateException::class.java){r.exact { rational(0) }}
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoSuccessorCycles.build(p,source))
    }

    @Test fun foreignPermutationWithArmedCancellationRemainsUntouched() {
        for(input in listOf(emptyList(),square)) {
            val primaryBudget=SelectionQueryBudget(); val primary=prepare(input,primaryBudget)
            var armed=false; var callbacks=0
            val foreignBudget=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            val foreign=prepare(input,foreignBudget)
            try {
                val source=permutation(foreign)
                val retained=source.links
                val counters=SelectionQueryResource.entries.associateWith { foreignBudget.used(it) }
                val failure=foreignBudget.failure; val calls=callbacks
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoSuccessorCycles.build(primary,source))
                assertEquals(calls,callbacks)
                assertEquals(counters,SelectionQueryResource.entries.associateWith { foreignBudget.used(it) })
                assertSame(failure,foreignBudget.failure); assertSame(retained,source.links)
                assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java){primary.segments}
                armed=false
                assertEquals(7,known(foreign.exact { 7 }))
            } finally { primary.close(); foreign.close() }
        }
    }

    @Test fun foreignAdjacencyInsidePrimaryPermutationRejectsBeforeAnyArithmetic() {
        val primaryBudget=SelectionQueryBudget(); val primary=prepare(square,primaryBudget)
        var armed=false; var callbacks=0
        val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
        val other=prepare(square,b)
        try {
            val foreign=permutation(other)
            val forged=SelectionLassoSuccessorPermutation.Borrowed(primary,foreign.adjacency,foreign.links)
            val counters=SelectionQueryResource.entries.associateWith { b.used(it) }; val calls=callbacks
            armed=true
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),SelectionLassoSuccessorCycles.build(primary,forged))
            assertEquals(calls,callbacks)
            assertEquals(counters,SelectionQueryResource.entries.associateWith { b.used(it) })
            assertEquals(8,foreign.adjacency.halfEdges.size)
            assertEquals(0L,primaryBudget.used(SelectionQueryResource.TEMPORARY_BYTES))
        } finally { primary.close(); other.close() }
    }

    @Test fun malformedLinksRejectWithoutPartialWalksOrUnboundedTraversal() {
        for(kind in 0..7) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val source=permutation(p)
            val good=source.links
            val bad=when(kind) {
                0 -> good.dropLast(1)
                1 -> listOf(good[0].copy(halfEdgeId=99))+good.drop(1)
                2 -> listOf(good[0].copy(next=99))+good.drop(1)
                3 -> listOf(good[0].copy(previous=-1))+good.drop(1)
                4 -> listOf(good[0].copy(next=good[1].next))+good.drop(1)
                5 -> listOf(good[0].copy(previous=good[1].previous))+good.drop(1)
                // A tail enters a different already-repeating mapping instead of closing at start.
                6 -> listOf(good[0].copy(next=4),good[1],good[2],good[3],good[4].copy(next=4))+good.drop(5)
                // Both starts try to share one successor, hence duplicate membership if unchecked.
                else -> listOf(good[0].copy(next=2))+good.drop(1)
            }
            val forged=SelectionLassoSuccessorPermutation.Borrowed(p,source.adjacency,bad)
            assertEquals(Result.InvalidInput(InvalidReason.INVALID_PERMUTATION),SelectionLassoSuccessorCycles.build(p,forged))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){source.links}
        }
    }

    @Test fun invalidHalfEdgeIdsAndInconsistentEndpointsRejectBeforeIndexedValidation() {
        for(kind in 0..3) {
            val b=SelectionQueryBudget(); val p=prepare(square,b); val source=permutation(p)
            val a=source.adjacency
            val edges=a.halfEdges.toMutableList()
            edges[0]=when(kind) {
                0 -> edges[0].copy(id=1)
                1 -> edges[0].copy(destination=99)
                2 -> edges[0].copy(twin=99)
                else -> edges[0].copy(destination=edges[0].origin)
            }
            val grouped=SelectionLassoCoincidentGeometry.Borrowed(p,a.vertices,a.edgeGroups)
            val malformed=SelectionLassoIncidentAdjacency.Borrowed(p,grouped,edges,a.incidents)
            val forged=SelectionLassoSuccessorPermutation.Borrowed(p,malformed,source.links)
            assertEquals(Result.InvalidInput(InvalidReason.INVALID_PERMUTATION),SelectionLassoSuccessorCycles.build(p,forged))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun closedForeignPermutationDisposesOnlyPrimaryOwner() {
        val b=SelectionQueryBudget(); val primary=prepare(square,b)
        val other=prepare(square); val source=permutation(other); other.close()
        assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),SelectionLassoSuccessorCycles.build(primary,source))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    private fun inPhase(name: String)=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoSuccessorCycles") && it.methodName.substringBefore('$')==name
    }

    @Test fun workExhaustionDuringTraversalDoesNotPublishPartialWalk() {
        var active=false; var injected=false; var visits=0
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && !injected && inPhase("extract") && !inPhase("validateMapping") &&
                !inPhase("validateLinks") && !inPhase("indexIncidents") && !inPhase("validateWalks") && ++visits==12) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        val p=prepare(square,b); val source=permutation(p); active=true
        assertEquals(SelectionQueryResource.WORK_UNITS,failure(SelectionLassoSuccessorCycles.build(p,source)).resource)
        assertTrue(injected); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){source.links}
    }

    @Test fun cancellationDuringTraversalReleasesPartialSequenceAndOwner() {
        var active=false; var visits=0
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(active && inPhase("extract") && !inPhase("validateMapping") &&
                !inPhase("validateLinks") && !inPhase("indexIncidents") && !inPhase("validateWalks") && ++visits==12)
                throw CancellationException()
        })
        val p=prepare(square,b); val source=permutation(p); active=true
        assertThrows(CancellationException::class.java){SelectionLassoSuccessorCycles.build(p,source)}
        assertEquals(12,visits)
        assertNull(b.failure); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun representationExhaustionReleasesEntireQuery() {
        val probe=SelectionQueryBudget()
        val retained: Long
        prepare(square,probe).use { cycles(it,permutation(it)); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        val p=prepare(square,b); val source=permutation(p)
        assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(SelectionLassoSuccessorCycles.build(p,source)).resource)
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun stickyBudgetFailureIsNotAnEmptySuccessfulWalkSet() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val source=permutation(p)
        val remaining=b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)
        val sticky=b.charge(SelectionQueryResource.WORK_UNITS,remaining+1)
        assertNotNull(sticky)
        val work=b.used(SelectionQueryResource.WORK_UNITS)
        assertSame(sticky,failure(SelectionLassoSuccessorCycles.build(p,source)))
        assertEquals(work,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun laterFailureInvalidatesEarlierCycleBorrower() {
        val b=SelectionQueryBudget(); val p=prepare(square,b); val source=permutation(p); val r=cycles(p,source)
        val remaining=b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)
        assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,remaining+1))
        failure(SelectionLassoSuccessorCycles.build(p,source))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){r.walks}
    }
}
