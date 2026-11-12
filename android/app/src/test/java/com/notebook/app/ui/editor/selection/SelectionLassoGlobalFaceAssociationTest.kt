package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Global
import com.notebook.app.ui.editor.selection.SelectionLassoContactApproachEvidence as Approach
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts as Rays
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoGlobalFaceAssociationTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private data class Aliases(
        val vertices: MutableList<Vertex>, val groups: MutableList<EdgeGroup>,
        val edges: MutableList<SelectionLassoIncidentAdjacency.HalfEdge>,
        val incidents: MutableList<SelectionLassoIncidentAdjacency.Incident>,
        val links: MutableList<SelectionLassoSuccessorPermutation.Link>,
        val walks: MutableList<SelectionLassoSuccessorCycles.Walk>, val membership: MutableList<Int>,
    )
    private data class Fixture(
        val owner: Prepared, val approach: Approach.Borrowed, val budget: SelectionQueryBudget,
        val aliases: Aliases? = null,
    ) : AutoCloseable {
        val components get() = approach.components
        val cycles get() = components.cycles
        override fun close() = owner.close()
    }
    private fun finish(
        owner: Prepared, g: SelectionLassoCoincidentGeometry.Borrowed, b: SelectionQueryBudget,
        mutable: Boolean = false,
    ): Fixture {
        val original = (SelectionLassoIncidentAdjacency.build(owner, g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val edges = original.halfEdges.toMutableList(); val incidents = original.incidents.toMutableList()
        val a = if (mutable) SelectionLassoIncidentAdjacency.Borrowed(owner, g, edges, incidents) else original
        val originalPermutation = (SelectionLassoSuccessorPermutation.build(owner, a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        val links = originalPermutation.links.toMutableList()
        val s = if (mutable) SelectionLassoSuccessorPermutation.Borrowed(owner, a, links) else originalPermutation
        val originalCycles = (SelectionLassoSuccessorCycles.build(owner, s) as SelectionLassoSuccessorCycles.Result.Ready).value
        val walks = originalCycles.walks.toMutableList(); val membership = originalCycles.halfEdgeToWalk.toMutableList()
        val c = if (mutable) SelectionLassoSuccessorCycles.Borrowed(owner, s, walks, membership) else originalCycles
        val admissionResult = SelectionLassoEmbeddingAdmission.build(owner, c)
        assertTrue("Fixture embedding: $admissionResult", admissionResult is SelectionLassoEmbeddingAdmission.Result.Ready)
        val admission = (admissionResult as SelectionLassoEmbeddingAdmission.Result.Ready).value
        val comp = (SelectionLassoGraphComponents.build(owner, admission) as SelectionLassoGraphComponents.Result.Ready).value
        val ext = (SelectionLassoExteriorSectorEvidence.build(owner, comp) as SelectionLassoExteriorSectorEvidence.Result.Ready).value
        val rays = (Rays.build(owner, ext) as Rays.Result.Ready).value
        val approach = (Approach.build(owner, rays) as Approach.Result.Ready).value
        @Suppress("UNCHECKED_CAST") // only test-owned mutable lists created below
        val aliases = if (mutable) Aliases(g.vertices as MutableList<Vertex>, g.edges as MutableList<EdgeGroup>,
            edges, incidents, links, walks, membership) else null
        return Fixture(owner, approach, b, aliases)
    }
    private fun graph(
        points: List<DuPoint>, pairs: List<Pair<Int, Int>>,
        b: SelectionQueryBudget = SelectionQueryBudget(), mutable: Boolean = false,
    ): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
        val g = known(owner.exact {
            val original = points.map { point(it) }
            val sorted = original.sortedWith { a, c -> when(compare(a,c)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices = sorted.mapIndexed { i, q -> Vertex(i, q) }.toMutableList()
            fun index(i: Int) = sorted.indexOfFirst { compare(it, original[i]) == Sign.ZERO }
            val groups = pairs.map { (a, c) ->
                val lo = minOf(index(a), index(c)); val hi = maxOf(index(a), index(c))
                EdgeGroup(lo, hi, sorted[lo], sorted[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId }).toMutableList()
            SelectionLassoCoincidentGeometry.Borrowed(owner, vertices, groups)
        })
        return finish(owner, g, b, mutable)
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(input, b))
        val atomic = (SelectionLassoAtomicEdges.build(owner) as SelectionLassoAtomicEdges.Result.Ready).value
        val g = (SelectionLassoCoincidentGeometry.build(owner, atomic) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return finish(owner, g, b)
    }
    private val triangle = listOf(p(0,0),p(2,0),p(0,2))
    private val square = listOf(p(0,0),p(2,0),p(2,2),p(0,2))
    private val bow = listOf(p(0,0),p(2,2),p(0,2),p(2,0))
    private fun polygonEdges(offset: Int, size: Int) = (0 until size).map { offset+it to offset+(it+1)%size }
    private fun squares(
        boxes: List<List<DuPoint>>, b: SelectionQueryBudget = SelectionQueryBudget(),
        mutable: Boolean = false,
    ) = graph(boxes.flatten(), boxes.indices.flatMap { polygonEdges(it*4,4) }, b, mutable)
    private fun box(x: Int, y: Int, side: Int) =
        listOf(p(x,y),p(x+side,y),p(x+side,y+side),p(x,y+side))
    private fun nested(b: SelectionQueryBudget = SelectionQueryBudget(), mutable: Boolean = false) =
        squares(listOf(box(0,0,10),box(2,2,2)),b,mutable)
    private fun ready(f: Fixture): Global.Borrowed {
        val faces = f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES)
        val edges = f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES)
        val events = f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS)
        val r = Global.build(f.owner, f.approach)
        assertTrue("Expected Ready: $r", r is Global.Result.Ready)
        val value = (r as Global.Result.Ready).value
        assertTrue(value.isOwnedBy(f.owner))
        assertTrue(value.isAuthenticatedFor(f.owner))
        assertEquals(faces + value.faceCount, f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES))
        assertEquals(edges, f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(events, f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        return value
    }
    private fun check(f: Fixture, r: Global.Borrowed, expected: Int, mapping: List<Int>? = null) {
        assertSame(f.approach,r.approach); assertEquals(expected,r.faceCount); assertEquals(0,r.unboundedFaceId)
        val c = r.counts
        assertEquals(expected.toLong(),c.edgeGroupCount.toLong()-c.vertexCount+c.componentCount+1)
        assertEquals(expected,c.walkCount+1-c.nonIsolatedComponentCount)
        assertEquals(f.cycles.walks.size,r.walkToFace.size)
        assertEquals((0 until c.walkCount).toList(),r.faces.flatMap { it.boundaryWalkIds }.sorted())
        val minima = mutableListOf<Int>()
        for ((id,face) in r.faces.withIndex()) {
            assertEquals(id,face.id); assertEquals(id==0,face.isUnbounded)
            assertEquals(face.boundaryWalkIds.sorted(),face.boundaryWalkIds)
            val components = face.boundaryWalkIds.map { f.components.walkToComponent[it] }
            assertEquals(components.size,components.distinct().size)
            assertEquals(components.size,face.componentParticipationCount)
            if(id!=0)minima.add(face.boundaryWalkIds.first())
            for(w in face.boundaryWalkIds)assertEquals(id,r.walkToFace[w])
        }
        assertEquals(minima.sorted(),minima)
        if(mapping!=null)assertEquals(mapping,r.walkToFace)
        for (record in f.approach.records) when(record) {
            is Approach.IsolatedSource -> Unit
            is Approach.NoForeignContact -> assertEquals(0,r.walkToFace[record.original.sourceWalkId])
            is Approach.ContactApproach -> {
                assertEquals(r.walkToFace[record.original.sourceWalkId],r.walkToFace[record.targetWalkId])
                val a=f.cycles.adjacency
                known(f.owner.exact {
                    val source=a.vertices[record.original.minimumVertexId].point.x
                    val target=a.vertices[f.components.components[record.targetComponentId].minimumVertexId].point.x
                    assertEquals(Sign.NEGATIVE,compare(target,source))
                })
            }
        }
    }
    private fun reject(f: Fixture, supplied: Approach.Borrowed, reason: Global.InvalidReason) {
        assertEquals(Global.Result.InvalidInput(reason),Global.build(f.owner,supplied))
        assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){f.owner.segments}
    }
    private fun failure(result: Global.Result): SelectionWorkloadFailure {
        assertTrue("Expected workload result: $result",result is Global.Result.Unresolved)
        val cause=(result as Global.Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun phase(name: String) = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoGlobalFaceAssociation") && it.methodName.substringBefore('$')==name
    }
    private fun limits(resource: SelectionQueryResource, limit: Long) = SelectionQueryLimits(
        query=SelectionQueryResource.entries.associateWith { if(it==resource)limit else it.queryCeiling })


    @Test fun syntheticCopiesPlausibleCountsAndSubclassesCannotTransferCompletion() {
        nested().use { f ->
            val genuine=ready(f)
            val copies=listOf(
                Global.Borrowed(f.owner,genuine.approach,genuine.counts,genuine.walkToFace,genuine.faces),
                Global.Borrowed(f.owner,genuine.approach,genuine.counts.copy(),genuine.walkToFace,genuine.faces),
                Global.Borrowed(f.owner,genuine.approach,genuine.counts,genuine.walkToFace.toList(),genuine.faces),
                Global.Borrowed(f.owner,genuine.approach,genuine.counts,genuine.walkToFace,
                    genuine.faces.map { it.copy(boundaryWalkIds=it.boundaryWalkIds.toList()) }),
                Global.Borrowed(f.owner,genuine.approach,genuine.counts,genuine.walkToFace,
                    genuine.faces.map { it.copy(id=it.id+99) }),
                Global.Borrowed(f.owner,genuine.approach,genuine.counts,genuine.walkToFace,
                    genuine.faces.map { it.copy(isUnbounded=!it.isUnbounded) }),
                // Wrong nested face association with a plausible, unchanged Euler face count.
                Global.Borrowed(f.owner,genuine.approach,genuine.counts.copy(),listOf(0,1,0,2),
                    listOf(Global.Face(0,listOf(0,2),true),Global.Face(1,listOf(1),false),
                        Global.Face(2,listOf(3),false))),
                object : Global.Borrowed(f.owner,genuine.approach,genuine.counts,
                    genuine.walkToFace,genuine.faces) {},
            )
            for(copy in copies) {
                assertTrue(copy.isOwnedBy(f.owner))
                assertFalse(copy.isAuthenticatedFor(f.owner))
            }
            check(f,genuine,3,listOf(0,1,1,2))
            assertTrue(genuine.isAuthenticatedFor(f.owner))
        }
    }

    @Test fun identityAndAuthenticationArePureForArmedOwnersAndSeparateFromLiveness() {
        var armed=false; var primaryCalls=0; var foreignCalls=0
        val b=SelectionQueryBudget(cancellationCheckpoint={primaryCalls++;if(armed)throw CancellationException()})
        val foreignBudget=SelectionQueryBudget(cancellationCheckpoint={foreignCalls++;if(armed)throw CancellationException()})
        nested(b).use { f -> production(emptyList(),foreignBudget).use { foreign ->
            val genuine=ready(f); val other=ready(foreign)
            val primaryCounters=SelectionQueryResource.entries.map { b.used(it) }
            val foreignCounters=SelectionQueryResource.entries.map { foreignBudget.used(it) }
            val primaryFailure=b.failure; val foreignFailure=foreignBudget.failure
            val beforePrimary=primaryCalls; val beforeForeign=foreignCalls
            armed=true
            repeat(3) {
                assertTrue(genuine.isOwnedBy(f.owner)); assertFalse(genuine.isOwnedBy(foreign.owner))
                assertTrue(genuine.isAuthenticatedFor(f.owner)); assertFalse(genuine.isAuthenticatedFor(foreign.owner))
                assertTrue(other.isOwnedBy(foreign.owner)); assertFalse(other.isOwnedBy(f.owner))
                assertTrue(other.isAuthenticatedFor(foreign.owner)); assertFalse(other.isAuthenticatedFor(f.owner))
            }
            assertEquals(beforePrimary,primaryCalls); assertEquals(beforeForeign,foreignCalls)
            assertEquals(primaryCounters,SelectionQueryResource.entries.map { b.used(it) })
            assertEquals(foreignCounters,SelectionQueryResource.entries.map { foreignBudget.used(it) })
            assertSame(primaryFailure,b.failure); assertSame(foreignFailure,foreignBudget.failure)
            f.owner.close()
            val closedCounters=SelectionQueryResource.entries.map { b.used(it) }
            assertTrue(genuine.isOwnedBy(f.owner)); assertTrue(genuine.isAuthenticatedFor(f.owner))
            assertFalse(genuine.isOwnedBy(foreign.owner)); assertFalse(genuine.isAuthenticatedFor(foreign.owner))
            assertEquals(beforePrimary,primaryCalls); assertEquals(beforeForeign,foreignCalls)
            assertEquals(closedCounters,SelectionQueryResource.entries.map { b.used(it) })
            assertEquals(foreignCounters,SelectionQueryResource.entries.map { foreignBudget.used(it) })
            for(getter in listOf<()->Any>({genuine.approach},{genuine.counts},{genuine.walkToFace},
                {genuine.faces},{genuine.faceCount},{genuine.unboundedFaceId}))
                assertThrows(IllegalStateException::class.java){getter()}
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            armed=false
            assertEquals(7,known(foreign.owner.exact { 7 }))
            assertTrue(other.isAuthenticatedFor(foreign.owner))
        } }
    }

    @Test fun historicalCompletionDoesNotReplaceValidationAfterSourceAliasMutation() {
        nested(mutable=true).use { f ->
            val genuine=ready(f)
            f.aliases!!.groups[0]=f.aliases.groups[0].copy(endVertexId=99)
            assertTrue(genuine.isAuthenticatedFor(f.owner))
            // A completed historical result cannot authorize a new build over changed sources.
            reject(f,f.approach,Global.InvalidReason.SOURCE_ASSOCIATION)
            assertTrue(genuine.isOwnedBy(f.owner)); assertTrue(genuine.isAuthenticatedFor(f.owner))
            assertThrows(IllegalStateException::class.java){genuine.faces}
        }
    }

    @Test fun finalPublicationWorkExhaustionCannotMintCompletedBorrower() {
        var armed=false; var injected=false; var publicationCalls=0; var finalCall=0
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed && !injected && phase("publish") && ++publicationCalls==finalCall) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                    b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        nested(b).use { f ->
            finalCall=f.cycles.walks.size+2
            armed=true
            val result=Global.build(f.owner,f.approach)
            assertEquals(SelectionQueryResource.WORK_UNITS,failure(result).resource)
            assertTrue(injected)
            assertEquals(3L,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(b.limits.query.getValue(SelectionQueryResource.WORK_UNITS),
                b.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java){f.owner.segments}
        }
    }

    @Test fun emptyAndIsolatedVerticesHaveOneUnboundedFaceAndNoBoundaryWalk() {
        production(emptyList()).use { check(it,ready(it),1,emptyList()) }
        for(points in listOf(listOf(p(0,0)),listOf(p(2,2),p(0,0),p(1,1))))
            graph(points,emptyList()).use { check(it,ready(it),1,emptyList()) }
    }
    @Test fun oneEdgeTreesAndFullyRetracedLinesHaveOneFace() {
        val tree=listOf(p(0,0),p(2,0),p(0,0),p(1,2),p(0,0),p(1,-2))
        for(input in listOf(listOf(p(0,0),p(2,0)),tree,
            listOf(p(0,0),p(2,0),p(0,0),p(2,0))))
            production(input).use { check(it,ready(it),1,listOf(0)) }
    }
    @Test fun triangleAndSquareUseHandDerivedWalkMappings() {
        for(input in listOf(triangle,square))production(input).use { check(it,ready(it),2,listOf(0,1)) }
    }
    @Test fun concaveAndFullyRetracedPolygonRetainTwoGeometricFaces() {
        for(input in listOf(listOf(p(0,0),p(4,0),p(2,1),p(4,4),p(0,4)),
            triangle+listOf(triangle.first())+triangle.reversed()))
            production(input).use { check(it,ready(it),2,listOf(0,1)) }
    }
    @Test fun splitBowTieFigureEightAndVertexSharedLoopsHaveThreeFaces() {
        val eight=listOf(p(0,0),p(2,0),p(1,2),p(0,0),p(-2,0),p(-1,2))
        for(input in listOf(bow,eight))production(input).use { check(it,ready(it),3) }
        graph(listOf(p(0,0),p(2,0),p(1,2),p(-2,0),p(-1,2)),
            listOf(0 to 1,1 to 2,2 to 0,0 to 3,3 to 4,4 to 0)).use { check(it,ready(it),3) }
    }
    @Test fun bridgeConnectedLoopsAndDanglingSpikeDoNotInventFaces() {
        val bridge=listOf(p(0,0),p(2,0),p(0,2),p(0,0),p(5,0),p(7,0),p(5,2),p(5,0))
        production(bridge).use { check(it,ready(it),3) }
        production(triangle+listOf(p(0,0),p(-1,-1))).use { check(it,ready(it),2) }
    }
    @Test fun disconnectedTrianglesJoinOnlyTheirExteriorWalks() {
        graph(triangle+listOf(p(5,0),p(7,0),p(5,2)),
            polygonEdges(0,3)+polygonEdges(3,3)).use { check(it,ready(it),3,listOf(0,1,0,2)) }
    }
    @Test fun sideBySideSquaresAndOverlapTiesJoinTheirExteriorWalks() {
        squares(listOf(box(0,0,2),box(4,0,2))).use {
            assertTrue(it.approach.records[1] is Approach.OverlapStartApproach)
            check(it,ready(it),3,listOf(0,1,0,2))
        }
    }
    @Test fun nestedSquareHasAnnularFaceWithTwoBoundaryWalks() {
        nested().use {
            val r=ready(it); check(it,r,3,listOf(0,1,1,2))
            assertEquals(listOf(1,2),r.faces[1].boundaryWalkIds)
        }
    }
    @Test fun threeNestedLoopsProduceFourFacesWithoutNestingMetadata() {
        squares(listOf(box(0,0,12),box(2,2,8),box(4,4,2))).use {
            check(it,ready(it),4,listOf(0,1,1,2,2,3))
        }
    }
    @Test fun treeInsideSquareJoinsBoundedSideAndDoesNotAddFace() {
        graph(box(0,0,10)+listOf(p(2,2),p(3,2),p(3,3)),
            polygonEdges(0,4)+listOf(4 to 5,5 to 6)).use { check(it,ready(it),2,listOf(0,1,1)) }
    }
    @Test fun outerPolygonContainingTwoLoopsAssociatesTransitiveBoundaryWalks() {
        squares(listOf(box(0,0,12),box(2,2,2),box(6,2,2))).use {
            check(it,ready(it),4,listOf(0,1,1,2,1,3))
        }
    }
    @Test fun loopInFigureEightLobeJoinsThatSpecificBoundedWalk() {
        graph(listOf(p(0,0),p(-4,-1),p(-4,-4),p(-4,1),p(-4,4),
            p(-3,-2),p(-2.5,-2),p(-3,-1.8)),
            listOf(0 to 1,1 to 2,2 to 0,0 to 3,3 to 4,4 to 0)+polygonEdges(5,3)).use {
            check(it,ready(it),4,listOf(0,1,2,1,3))
        }
    }
    @Test fun disconnectedTreesAndMixedIsolatedComponentsKeepCorrectCounts() {
        graph(listOf(p(0,0),p(1,1),p(3,0),p(4,1),p(6,0)),listOf(0 to 1,2 to 3)).use {
            check(it,ready(it),1,listOf(0,0))
        }
        graph(box(0,0,2)+listOf(p(5,0),p(7,0),p(5,2),p(10,0),p(11,1),p(20,20)),
            polygonEdges(0,4)+polygonEdges(4,3)+listOf(7 to 8)).use {
            check(it,ready(it),3,listOf(0,1,0,2,0))
        }
    }
    @Test fun transverseVerticalAndRationalDiagonalContactsUseApproachedSide() {
        graph(box(0,0,2)+listOf(p(4,1),p(4,1.5)),polygonEdges(0,4)+listOf(4 to 5)).use {
            val contact=it.approach.records[1] as Approach.EdgeInteriorApproach
            assertEquals(7,contact.selectedHalfEdgeId); assertEquals(0,contact.targetWalkId)
            check(it,ready(it),2,listOf(0,1,0))
        }
        graph(listOf(p(0,0),p(1,3),p(0,3),p(2,1),p(2,2)),
            polygonEdges(0,3)+listOf(3 to 4)).use {
            val contact=it.approach.records[1] as Approach.EdgeInteriorApproach
            assertEquals("1",contact.original.point.x.numerator.toString())
            assertEquals("3",contact.original.point.x.denominator.toString())
            check(it,ready(it),2,listOf(0,1,0))
        }
    }
    @Test fun endpointDegreeOneAndHighDegreeSectorsJoinBacktrackingWalks() {
        graph(listOf(p(0,0),p(0,1),p(2,0),p(2,1)),listOf(0 to 1,2 to 3)).use {
            val sector=(it.approach.records[1] as Approach.VertexSectorApproach).sector
            assertEquals(1,sector.degree); check(it,ready(it),1,listOf(0,0))
        }
        graph(listOf(p(0,0),p(-1,-1),p(-1,1),p(0,1),p(3,0),p(3,1)),
            listOf(0 to 1,0 to 2,0 to 3,4 to 5)).use {
            val sector=(it.approach.records[1] as Approach.VertexSectorApproach).sector
            assertEquals(3,sector.degree); check(it,ready(it),1,listOf(0,0))
        }
    }
    @Test fun horizontalBridgeAndMixedPointOverlapContactsKeepOneApproachSector() {
        graph(listOf(p(0,0),p(2,0),p(4,0),p(4,1)),listOf(0 to 1,2 to 3)).use {
            assertTrue(it.approach.records[1] is Approach.OverlapStartApproach)
            check(it,ready(it),1,listOf(0,0))
        }
        squares(listOf(box(0,0,2),box(4,0,2))).use {
            val contact=it.approach.records[1] as Approach.OverlapStartApproach
            assertEquals(2,contact.original.contacts.size)
            check(it,ready(it),3,listOf(0,1,0,2))
        }
    }
    @Test fun equalMinimumXAndMultipleSourcesToOneTargetAreDeterministic() {
        squares(listOf(box(0,0,2),box(0,4,2))).use {
            assertTrue(it.approach.records.all { r -> r is Approach.NoForeignContact })
            check(it,ready(it),3,listOf(0,1,0,2))
        }
        graph(box(0,0,10)+listOf(p(2,2),p(3,2),p(2,6),p(3,6)),
            polygonEdges(0,4)+listOf(4 to 5,6 to 7)).use {
            assertEquals(listOf(0,0),it.approach.records.filterIsInstance<Approach.ContactApproach>().map { r -> r.targetComponentId })
            check(it,ready(it),2,listOf(0,1,1,1))
        }
    }
    @Test fun tinyAndLargeBinaryCoordinatesRequireNoTolerance() {
        for(s in listOf(Math.scalb(1.0,-400),Math.scalb(1.0,400))) {
            graph(listOf(p(0,-s),p(0,s),p(4*s,0),p(4*s,s)),listOf(0 to 1,2 to 3)).use {
                check(it,ready(it),1,listOf(0,0))
            }
        }
    }
    @Test fun reversalAndRotationPreserveGeometricPartitionsAndSources() {
        val signatures=mutableListOf<Any>()
        for(input in listOf(bow,bow.reversed(),bow.drop(1)+bow.take(1)))production(input).use {
            val before=it.cycles.adjacency.edgeGroups.flatMap { g -> g.occurrences }
            val r=ready(it); check(it,r,3)
            signatures.add(listOf(r.walkToFace,r.faces))
            assertEquals(input,it.owner.suppliedVertices)
            assertEquals(before,it.cycles.adjacency.edgeGroups.flatMap { g -> g.occurrences })
        }
        assertEquals(1,signatures.distinct().size)
    }
    @Test fun repeatedBuildsChargeEachFaceOnceAndDoNotReplayIntersections() {
        var armed=false
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed && Thread.currentThread().stackTrace.any { it.className.contains("SelectionExactSegmentIntersection") })
                error("No intersection or ray replay is allowed")
        })
        nested(b).use { f ->
            val a=f.cycles.adjacency; val vertices=a.vertices; val groups=a.edgeGroups
            val half=a.halfEdges; val walks=f.cycles.walks; val links=f.cycles.permutation.links
            val edges=b.used(SelectionQueryResource.ARRANGEMENT_EDGES)
            val events=b.used(SelectionQueryResource.ARRANGEMENT_EVENTS)
            val startFaces=b.used(SelectionQueryResource.ARRANGEMENT_FACES)
            armed=true
            val first=ready(f); val second=ready(f)
            assertEquals(first.faces,second.faces); assertEquals(first.walkToFace,second.walkToFace)
            assertEquals(startFaces+6,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(edges,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            assertEquals(events,b.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
            assertSame(vertices,f.cycles.adjacency.vertices); assertSame(groups,f.cycles.adjacency.edgeGroups)
            assertSame(half,f.cycles.adjacency.halfEdges); assertSame(walks,f.cycles.walks)
            assertSame(links,f.cycles.permutation.links)
            assertThrows(UnsupportedOperationException::class.java){(first.faces as MutableList).clear()}
            assertThrows(UnsupportedOperationException::class.java){(first.walkToFace as MutableList).clear()}
            assertThrows(UnsupportedOperationException::class.java){(first.faces[1].boundaryWalkIds as MutableList).clear()}
        }
    }

    @Test fun unauthenticatedCopiesAndAllFabricatedAssociationsRejectBeforeArithmetic() {
        // Topology contradictions that cannot be completed by the genuine builder stay outside
        // the authentication boundary: no test-only seal or completed-source factory.
        for(kind in 0..10) {
            var armed=false; var callbacks=0
            val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            nested(b).use { f ->
                val records=f.approach.records.toMutableList()
                val original=records[1] as Approach.EdgeInteriorApproach
                when(kind) {
                    0 -> Unit // exact copied record inventory
                    1 -> records[1]=Approach.NoForeignContact(Rays.NoOtherComponentContact(
                        original.original.sourceComponentId,original.original.minimumVertexId,
                        original.original.sourceWalkId,original.original.probeStart,original.original.probeEnd,
                        original.original.examinedOtherEdges))
                    2 -> records[1]=original.copy(targetWalkId=99)
                    3 -> records[1]=original.copy(targetComponentId=99)
                    4 -> records[1]=original.copy(targetComponentId=1) // self/cyclic association
                    5 -> records[1]=original.copy(targetWalkId=original.original.sourceWalkId) // redundant union
                    6 -> records.removeAt(1)
                    7 -> records.add(records[1])
                    8 -> records[0]=records[1] // missing/duplicate source component
                    9 -> records[1]=original.copy(original=original.original.copy(minimumVertexId=99))
                    else -> records[1]=Approach.IsolatedSource(Rays.IsolatedVertex(1,99))
                }
                val fake=Approach.Borrowed(f.owner,f.approach.contacts,records)
                val before=callbacks
                val counters=SelectionQueryResource.entries.associateWith { b.used(it) }
                armed=true
                reject(f,fake,Global.InvalidReason.UNAUTHENTICATED_APPROACH)
                assertEquals(before,callbacks)
                for(r in SelectionQueryResource.entries)assertEquals(
                    if(r==SelectionQueryResource.TEMPORARY_BYTES)0L else counters.getValue(r),b.used(r))
            }
        }
    }
    @Test fun nestedForeignOwnersRejectBeforeForeignCallbacksAndRemainUsable() {
        for(kind in 0..7) {
            var armed=false; var callbacks=0
            val b=SelectionQueryBudget(cancellationCheckpoint={callbacks++;if(armed)throw CancellationException()})
            nested().use { primary -> nested(b).use { foreign ->
                val fc=foreign.components; val c=foreign.cycles
                val permutation=if(kind<=6)c.permutation else
                    SelectionLassoSuccessorPermutation.Borrowed(primary.owner,c.adjacency,c.permutation.links)
                val cycles=if(kind<=5)c else SelectionLassoSuccessorCycles.Borrowed(
                    primary.owner,permutation,c.walks,c.halfEdgeToWalk)
                val admission=if(kind<=4)fc.admission else SelectionLassoEmbeddingAdmission.Borrowed(
                    primary.owner,cycles,fc.admission.record)
                val comp=if(kind<=3)fc else SelectionLassoGraphComponents.Borrowed(primary.owner,admission,
                    fc.components,fc.vertexToComponent,fc.edgeGroupToComponent,fc.halfEdgeToComponent,fc.walkToComponent)
                val ext=if(kind<=2)foreign.approach.contacts.exterior else
                    SelectionLassoExteriorSectorEvidence.Borrowed(primary.owner,comp,foreign.approach.contacts.exterior.records)
                val rays=if(kind<=1)foreign.approach.contacts else
                    Rays.Borrowed(primary.owner,ext,foreign.approach.contacts.records)
                val supplied=if(kind==0)foreign.approach else Approach.Borrowed(primary.owner,rays,foreign.approach.records)
                val counters=SelectionQueryResource.entries.map { b.used(it) }
                val failure=b.failure; val before=callbacks
                armed=true
                reject(primary,supplied,Global.InvalidReason.OWNER_MISMATCH)
                assertEquals(before,callbacks); assertEquals(counters,SelectionQueryResource.entries.map { b.used(it) })
                assertSame(failure,b.failure)
                armed=false; assertEquals(7,known(foreign.owner.exact { 7 }))
            } }
        }
    }
    @Test fun closedPrimaryAndBorrowerLifetimesRejectExplicitly() {
        nested().use { f ->
            val result=ready(f)
            f.owner.close()
            for(getter in listOf<()->Any>({result.approach},{result.counts},{result.walkToFace},
                {result.faces},{result.faceCount},{result.unboundedFaceId}))
                assertThrows(IllegalStateException::class.java){getter()}
            assertEquals(Global.Result.InvalidInput(Global.InvalidReason.OWNER_CLOSED),
                Global.build(f.owner,f.approach))
        }
        nested().use { primary -> nested().use { foreign ->
            foreign.owner.close()
            reject(primary,foreign.approach,Global.InvalidReason.OWNER_CLOSED)
        } }
    }
    @Test fun authenticatedUpstreamAliasesCannotHideContradictoryGraphOrWalkCoverage() {
        for(kind in 0..9)nested(mutable=true).use { f ->
            val m=f.aliases!!
            assertTrue(f.approach.isAuthenticatedFor(f.owner))
            when(kind) {
                0 -> m.vertices[0]=m.vertices[0].copy(id=99)
                1 -> m.groups[0]=m.groups[0].copy(endVertexId=99)
                2 -> m.edges[0]=m.edges[0].copy(twin=99)
                3 -> m.links[0]=m.links[0].copy(next=99)
                4 -> m.walks.removeAt(m.walks.lastIndex)
                5 -> m.walks[0]=m.walks[0].copy(halfEdgeIds=m.walks[0].halfEdgeIds+m.walks[0].halfEdgeIds)
                6 -> m.membership[0]=99
                7 -> m.incidents[0]=m.incidents[0].copy(vertexId=99)
                8 -> m.walks[0]=m.walks[0].copy(id=99)
                else -> m.groups.removeAt(m.groups.lastIndex) // stale E/Euler input rejects at source validation
            }
            reject(f,f.approach,Global.InvalidReason.SOURCE_ASSOCIATION)
        }
    }
    @Test fun exactBitsExhaustionDuringRevalidationIsUnresolvedNotEmptyFaces() {
        var armed=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed && !injected) { injected=true; b.checkExactBits(4097) }
        })
        nested(b).use { f ->
            armed=true
            assertEquals(SelectionBudgetConstraint.EXACT_BITS,failure(Global.build(f.owner,f.approach)).constraint)
            assertTrue(injected); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun workExhaustionDuringDsuPublishesNoPartialInventory() {
        var armed=false; var injected=false
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed && !injected && phase("union")) {
                injected=true
                b.charge(SelectionQueryResource.WORK_UNITS,b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-
                    b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        nested(b).use { f ->
            armed=true
            assertEquals(SelectionQueryResource.WORK_UNITS,failure(Global.build(f.owner,f.approach)).resource)
            assertTrue(injected); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertEquals(0L,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
        }
    }
    @Test fun representationExhaustionReleasesAllEarlierReservations() {
        val probe=SelectionQueryBudget()
        val retained: Long
        nested(probe).use { ready(it); retained=probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.TEMPORARY_BYTES,retained-1))
        nested(b).use { f ->
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES,failure(Global.build(f.owner,f.approach)).resource)
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun faceQuotaCountsUnboundedFaceAndNeverResetsPriorConsumption() {
        for(limit in listOf(0L,1L,2L)) {
            val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_FACES,limit))
            production(square,b).use { f ->
                if(limit==2L) {
                    check(f,ready(f),2,listOf(0,1))
                    assertEquals(2L,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
                    assertEquals(SelectionQueryResource.ARRANGEMENT_FACES,
                        failure(Global.build(f.owner,f.approach)).resource)
                } else {
                    assertEquals(SelectionQueryResource.ARRANGEMENT_FACES,
                        failure(Global.build(f.owner,f.approach)).resource)
                }
                assertEquals(limit,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
                assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
        val b=SelectionQueryBudget(limits(SelectionQueryResource.ARRANGEMENT_FACES,0))
        production(emptyList(),b).use { assertEquals(SelectionQueryResource.ARRANGEMENT_FACES,
            failure(Global.build(it.owner,it.approach)).resource) }
    }
    @Test fun cancellationDuringDsuFaceAdmissionAndPublicationIsSeparate() {
        for(target in listOf("find","createFaces","publish")) {
            var armed=false; var injected=false
            var publishCalls=0; var finalPublishCall=0
            val b=SelectionQueryBudget(cancellationCheckpoint={
                if(armed && phase(target) && (target!="createFaces" ||
                    Thread.currentThread().stackTrace.any { it.methodName.substringBefore('$')=="chargeGlobalLassoFaceRecord" }) &&
                    (target!="publish" || ++publishCalls==finalPublishCall)) {
                    injected=true; throw CancellationException()
                }
            })
            nested(b).use { f ->
                var result: Global.Result?=null
                val consumed=b.used(SelectionQueryResource.WORK_UNITS)
                // One bulk charge, K copied IDs, then the final pre-publication checkpoint.
                finalPublishCall=f.cycles.walks.size+2
                armed=true
                assertThrows("phase $target",CancellationException::class.java){result=Global.build(f.owner,f.approach)}
                assertTrue(injected); assertNull(result); assertNull(b.failure)
                assertTrue(b.used(SelectionQueryResource.WORK_UNITS)>=consumed)
                assertEquals(if(target=="publish")3L else 0L,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
                assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java){f.owner.segments}
            }
        }
    }
    @Test fun stickyFailureNeverBecomesSuccessfulEmptyFaceInventory() {
        production(emptyList()).use { f ->
            val b=f.budget
            val sticky=b.charge(SelectionQueryResource.WORK_UNITS,
                b.limits.query.getValue(SelectionQueryResource.WORK_UNITS)-b.used(SelectionQueryResource.WORK_UNITS)+1)
            assertNotNull(sticky)
            assertSame(sticky,failure(Global.build(f.owner,f.approach)))
            assertEquals(0L,b.used(SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
}
