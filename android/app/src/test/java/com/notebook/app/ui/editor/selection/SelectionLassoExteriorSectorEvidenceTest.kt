package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.Borrowed
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.Evidence
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.IsolatedVertex
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.OutwardSector
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.Result
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.InvalidReason
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class SelectionLassoExteriorSectorEvidenceTest {
    private fun p(x: Number,y: Number)=DuPoint(x.toDouble(),y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>)=(r as SelectionPredicateResult.Known<T>).value
    private data class Fixture(val owner: SelectionLassoSplitEvents.Prepared,
        val source: SelectionLassoGraphComponents.Borrowed,val budget: SelectionQueryBudget): AutoCloseable {
        override fun close()=owner.close()
    }
    private fun fixture(input: List<DuPoint>,b: SelectionQueryBudget=SelectionQueryBudget()): Fixture {
        val owner=known(SelectionLassoSplitEvents.prepare(input,b))
        val atomic=(SelectionLassoAtomicEdges.build(owner) as SelectionLassoAtomicEdges.Result.Ready).value
        val grouped=(SelectionLassoCoincidentGeometry.build(owner,atomic) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return finish(owner,grouped,b)
    }
    private fun finish(owner: SelectionLassoSplitEvents.Prepared,g: SelectionLassoCoincidentGeometry.Borrowed,
        b: SelectionQueryBudget): Fixture {
        val a=(SelectionLassoIncidentAdjacency.build(owner,g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val s=(SelectionLassoSuccessorPermutation.build(owner,a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        val c=(SelectionLassoSuccessorCycles.build(owner,s) as SelectionLassoSuccessorCycles.Result.Ready).value
        val e=(SelectionLassoEmbeddingAdmission.build(owner,c) as SelectionLassoEmbeddingAdmission.Result.Ready).value
        val components=(SelectionLassoGraphComponents.build(owner,e) as SelectionLassoGraphComponents.Result.Ready).value
        return Fixture(owner,components,b)
    }
    private fun graph(points: List<DuPoint>,pairs: List<Pair<Int,Int>>,b: SelectionQueryBudget=SelectionQueryBudget()): Fixture {
        val owner=known(SelectionLassoSplitEvents.prepare(emptyList(),b))
        val g=known(owner.exact {
            val original=points.map { point(it) }
            val sorted=original.sortedWith { a,c -> when(compare(a,c)) {
                SelectionRobustPredicates.Sign.NEGATIVE -> -1
                SelectionRobustPredicates.Sign.ZERO -> 0
                SelectionRobustPredicates.Sign.POSITIVE -> 1
            } }
            val vertices=sorted.mapIndexed { i,q -> SelectionLassoCoincidentGeometry.Vertex(i,q) }
            val groups=pairs.map { (a,c) ->
                val ai=sorted.indexOfFirst { it===original[a] };val ci=sorted.indexOfFirst { it===original[c] }
                val lo=minOf(ai,ci);val hi=maxOf(ai,ci)
                SelectionLassoCoincidentGeometry.EdgeGroup(lo,hi,sorted[lo],sorted[hi],emptyList())
            }.sortedWith(compareBy<SelectionLassoCoincidentGeometry.EdgeGroup>{it.startVertexId}.thenBy{it.endVertexId})
            SelectionLassoCoincidentGeometry.Borrowed(owner,vertices,groups)
        })
        return finish(owner,g,b)
    }
    private val triangle=listOf(p(0,0),p(2,0),p(0,2))
    private val bow=listOf(p(0,0),p(2,2),p(0,2),p(2,0))
    private fun build(f: Fixture): Borrowed {
        val r=SelectionLassoExteriorSectorEvidence.build(f.owner,f.source)
        assertTrue("$r",r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun check(f: Fixture,r: Borrowed) {
        assertSame(f.source,r.components)
        assertEquals(f.source.componentCount,r.records.size)
        val c=f.source.cycles
        for((i,e) in r.records.withIndex()) {
            assertEquals(i,e.componentId)
            assertEquals(f.source.components[i].minimumVertexId,e.minimumVertexId)
            if(e is OutwardSector) {
                assertEquals(c.adjacency.incidents[e.minimumVertexId].outgoing.size,e.degree)
                assertEquals(e.previousOutgoing,c.permutation.links[e.incomingTwin].next)
                assertEquals(e.incomingTwin,c.permutation.links[e.previousOutgoing].previous)
                assertEquals(e.candidateWalkId,c.halfEdgeToWalk[e.previousOutgoing])
                assertEquals(e.candidateWalkId,c.halfEdgeToWalk[e.incomingTwin])
                assertEquals(i,f.source.walkToComponent[e.candidateWalkId])
            }
        }
    }
    private fun forged(f: Fixture, records: List<SelectionLassoGraphComponents.Component> = f.source.components,
        vertices: List<Int> = f.source.vertexToComponent): SelectionLassoGraphComponents.Borrowed =
        SelectionLassoGraphComponents.Borrowed(f.owner,f.source.admission,records,vertices,
            f.source.edgeGroupToComponent,f.source.halfEdgeToComponent,f.source.walkToComponent)

    @Test fun emptyAndIsolatedInventNoBoundary() {
        fixture(emptyList()).use { assertTrue(build(it).records.isEmpty()) }
        for(points in listOf(listOf(p(0,0)),listOf(p(0,0),p(1,1),p(2,2))))
            graph(points,emptyList()).use { f ->
                val r=build(f);check(f,r)
                assertEquals(points.indices.map { IsolatedVertex(it,it) },r.records)
            }
    }
    @Test fun triangleHasHandDerivedWestTransition() {
        fixture(triangle).use {
            val e=build(it).records.single() as OutwardSector
            assertEquals(OutwardSector(0,0,2,2,0,2,3,0),e)
        }
    }
    @Test fun degreeOneAndChainsAndTrees() {
        for(input in listOf(listOf(p(0,0),p(2,0)),listOf(p(0,0),p(1,0),p(2,0)),
            listOf(p(0,0),p(2,0),p(0,0),p(1,2),p(0,0),p(1,-2))))
            fixture(input).use { check(it,build(it)) }
    }
    @Test fun loopsCrossingsRetracesSpikesAndBridges() {
        val inputs=listOf(triangle,listOf(p(0,0),p(2,0),p(2,2),p(0,2)),bow,
            listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4)),
            listOf(p(0,0),p(2,0),p(1,2),p(0,0),p(-2,0),p(-1,2)),
            triangle+triangle,triangle+listOf(p(0,0),p(-1,-1)),
            triangle+listOf(p(0,0),p(5,0),p(7,0),p(5,2),p(5,0)))
        for(input in inputs) fixture(input).use { check(it,build(it)) }
    }
    @Test fun positiveAndNegativeYAndWrapSectors() {
        for(ends in listOf(listOf(p(1,1),p(1,2)),
            listOf(p(1,-1),p(1,-2)),listOf(p(1,1),p(1,-1)),
            listOf(p(1,0),p(1,1),p(1,2),p(1,-1),p(1,-2)))) {
            graph(listOf(p(0,0))+ends,ends.indices.map { 0 to it+1 }).use { f ->
                val e=build(f).records.single() as OutwardSector
                check(f,build(f))
                assertEquals(ends.size,e.degree)
                if(ends.all { it.y>0 }) assertEquals(e.degree,e.insertionPosition)
                if(ends.all { it.y<0 }) assertEquals(0,e.insertionPosition)
            }
        }
    }
    @Test fun disconnectedNestedAndIsolatedComponentsStayRelative() {
        for(nested in listOf(false,true)) {
            val points=if(nested) listOf(p(0,0),p(10,0),p(0,10),p(1,1),p(2,1),p(1,2),p(20,20))
                else listOf(p(0,0),p(2,0),p(0,2),p(5,0),p(7,0),p(5,2),p(20,20))
            graph(points,listOf(0 to 1,1 to 2,2 to 0,3 to 4,4 to 5,5 to 3)).use {
                val r=build(it);check(it,r)
                assertEquals(2,r.records.count { e -> e is OutwardSector })
                assertEquals(1,r.records.count { e -> e is IsolatedVertex })
            }
        }
    }
    @Test fun smallAndLargeScalesNeedNoTolerance() {
        for(s in listOf(Math.scalb(1.0,-400),Math.scalb(1.0,400)))
            fixture(listOf(p(0,0),p(s,0),p(0,s))).use { check(it,build(it)) }
    }
    @Test fun windingRotationSourcesAndQuotasArePreserved() {
        val results=ArrayList<List<Evidence>>()
        for(input in listOf(bow,bow.reversed(),bow.drop(1)+bow.take(1))) fixture(input).use { f ->
            val groups=f.source.cycles.adjacency.edgeGroups
            val quotas=listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,SelectionQueryResource.ARRANGEMENT_EDGES,
                SelectionQueryResource.ARRANGEMENT_FACES)
            val before=quotas.map { f.budget.used(it) }
            val r=build(f);check(f,r);results.add(r.records)
            assertSame(groups,r.components.cycles.adjacency.edgeGroups)
            assertEquals(input,f.owner.suppliedVertices)
            assertEquals(before,quotas.map { f.budget.used(it) })
            assertThrows(UnsupportedOperationException::class.java){(r.records as MutableList).clear()}
        }
        assertEquals(1,results.distinct().size)
    }
    @Test fun forgedMinimumAndMembershipReject() {
        for(kind in 0..1) fixture(triangle).use { f ->
            val supplied=if(kind==0) forged(f,listOf(f.source.components[0].copy(minimumVertexId=1)))
                else forged(f,vertices=List(f.source.vertexToComponent.size){99})
            assertEquals(Result.InvalidInput(InvalidReason.COMPONENT),
                SelectionLassoExteriorSectorEvidence.build(f.owner,supplied))
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun closedOwnerAndBorrowedAccess() {
        fixture(triangle).use {
            val r=build(it);it.owner.close()
            assertThrows(IllegalStateException::class.java){r.records}
            assertThrows(IllegalStateException::class.java){r.components}
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),
                SelectionLassoExteriorSectorEvidence.build(it.owner,it.source))
        }
    }
    @Test fun foreignOwnersAreNeverProbed() {
        for(kind in 0..4) {
            var armed=false;var calls=0
            val b=SelectionQueryBudget(cancellationCheckpoint={calls++;if(armed)throw CancellationException()})
            fixture(triangle).use { primary -> fixture(triangle,b).use { foreign ->
                val fc=foreign.source.cycles
                val cycle=when(kind) {
                    0,1,2 -> fc
                    3 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,fc.permutation,fc.walks,fc.halfEdgeToWalk)
                    else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                        SelectionLassoSuccessorPermutation.Borrowed(primary.owner,fc.adjacency,fc.permutation.links),
                        fc.walks,fc.halfEdgeToWalk)
                }
                val admission=if(kind<=1) foreign.source.admission else
                    SelectionLassoEmbeddingAdmission.Borrowed(primary.owner,cycle,foreign.source.admission.record)
                val supplied=if(kind==0) foreign.source else SelectionLassoGraphComponents.Borrowed(primary.owner,
                    admission,foreign.source.components,foreign.source.vertexToComponent,
                    foreign.source.edgeGroupToComponent,foreign.source.halfEdgeToComponent,foreign.source.walkToComponent)
                val counters=SelectionQueryResource.entries.map { b.used(it) };val before=calls
                armed=true
                assertEquals(Result.InvalidInput(InvalidReason.OWNER_MISMATCH),
                    SelectionLassoExteriorSectorEvidence.build(primary.owner,supplied))
                assertEquals(before,calls);assertEquals(counters,SelectionQueryResource.entries.map { b.used(it) })
                armed=false;assertEquals(7,known(foreign.owner.exact { 7 }))
                assertThrows(IllegalStateException::class.java){primary.owner.segments}
            } }
        }
    }
    @Test fun malformedIncidentTransitionsAndWalksRejectWithoutPartialEvidence() {
        for(kind in 0..7) fixture(triangle).use { f ->
            val c=f.source.cycles;val a=c.adjacency
            val incidents=a.incidents.toMutableList()
            val edges=a.halfEdges.toMutableList()
            val links=c.permutation.links.toMutableList()
            var membership=c.halfEdgeToWalk
            when(kind) {
                0 -> incidents[0]=incidents[0].copy(vertexId=99)
                1 -> incidents[0]=incidents[0].copy(outgoing=listOf(99))
                2 -> incidents[0]=incidents[0].copy(outgoing=emptyList())
                3 -> incidents[0]=incidents[0].copy(outgoing=incidents[0].outgoing.reversed())
                4 -> edges[0]=edges[0].copy(destination=edges[0].origin)
                5 -> links[0]=links[0].copy(next=99)
                6 -> membership=List(membership.size){99}
                else -> links[0]=links[0].copy(previous=99)
            }
            val g=SelectionLassoCoincidentGeometry.Borrowed(f.owner,a.vertices,a.edgeGroups)
            val badA=SelectionLassoIncidentAdjacency.Borrowed(f.owner,g,edges,incidents)
            val badS=SelectionLassoSuccessorPermutation.Borrowed(f.owner,badA,links)
            val badC=SelectionLassoSuccessorCycles.Borrowed(f.owner,badS,c.walks,membership)
            val admission=SelectionLassoEmbeddingAdmission.Borrowed(f.owner,badC,f.source.admission.record)
            val supplied=SelectionLassoGraphComponents.Borrowed(f.owner,admission,f.source.components,
                f.source.vertexToComponent,f.source.edgeGroupToComponent,f.source.halfEdgeToComponent,
                f.source.walkToComponent)
            assertTrue(SelectionLassoExteriorSectorEvidence.build(f.owner,supplied) is Result.InvalidInput)
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){f.owner.segments}
        }
    }
    @Test fun cancellationAtFinalPublicationReleasesCompletedRecords() {
        var armed=false;var injected=false
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed && Thread.currentThread().stackTrace.any {
                it.className.contains("SelectionLassoExteriorSectorEvidence") && it.methodName=="publish"
            }) { injected=true;throw CancellationException() }
        })
        fixture(triangle,b).use {
            armed=true
            assertThrows(CancellationException::class.java){SelectionLassoExteriorSectorEvidence.build(it.owner,it.source)}
            assertTrue(injected);assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    private fun inSector()=Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoExteriorSectorEvidence") && it.methodName=="construct"
    }
    @Test fun cancellationAndWorkExhaustionInSectorFailClosed() {
        for(cancel in listOf(false,true)) {
            var armed=false;var injected=false
            lateinit var b: SelectionQueryBudget
            b=SelectionQueryBudget(cancellationCheckpoint={
                if(armed && !injected && inSector()) {
                    injected=true
                    if(cancel)throw CancellationException()
                    b.charge(SelectionQueryResource.WORK_UNITS,b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                        b.used(SelectionQueryResource.WORK_UNITS))
                }
            })
            fixture(triangle,b).use {
                armed=true
                if(cancel) assertThrows(CancellationException::class.java){SelectionLassoExteriorSectorEvidence.build(it.owner,it.source)}
                else assertTrue(SelectionLassoExteriorSectorEvidence.build(it.owner,it.source) is Result.Unresolved)
                assertTrue(injected);assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }
    @Test fun representationAndStickyFailureNeverReturnEmptySuccess() {
        val probe=SelectionQueryBudget();val retained: Long
        fixture(triangle,probe).use { build(it);retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(SelectionQueryLimits(query=SelectionQueryResource.entries.associateWith {
            if(it==SelectionQueryResource.TEMPORARY_BYTES)retained-1 else it.queryCeiling }))
        fixture(triangle,b).use {
            assertTrue(SelectionLassoExteriorSectorEvidence.build(it.owner,it.source) is Result.Unresolved)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
        fixture(triangle).use {
            val q=it.budget
            q.charge(SelectionQueryResource.WORK_UNITS,q.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                q.used(SelectionQueryResource.WORK_UNITS)+1)
            assertTrue(SelectionLassoExteriorSectorEvidence.build(it.owner,it.source) is Result.Unresolved)
        }
    }
}
