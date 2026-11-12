package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoGraphComponents.Component
import com.notebook.app.ui.editor.selection.SelectionLassoGraphComponents.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoGraphComponents.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoGraphComponentsTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private data class Fixture(
        val owner: Prepared, val admission: SelectionLassoEmbeddingAdmission.Borrowed,
        val budget: SelectionQueryBudget,
    ) : AutoCloseable {
        val cycles get() = admission.cycles
        override fun close() = owner.close()
    }
    private fun prepare(input: List<DuPoint>, b: SelectionQueryBudget) =
        known(SelectionLassoSplitEvents.prepare(input, b))
    private fun finish(p: Prepared, g: SelectionLassoCoincidentGeometry.Borrowed, b: SelectionQueryBudget): Fixture {
        val a = (SelectionLassoIncidentAdjacency.build(p, g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val s = (SelectionLassoSuccessorPermutation.build(p, a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        val c = (SelectionLassoSuccessorCycles.build(p, s) as SelectionLassoSuccessorCycles.Result.Ready).value
        val admitted = SelectionLassoEmbeddingAdmission.build(p, c)
        assertTrue("Fixture must be admitted: $admitted", admitted is SelectionLassoEmbeddingAdmission.Result.Ready)
        return Fixture(p, (admitted as SelectionLassoEmbeddingAdmission.Result.Ready).value, b)
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val p = prepare(input, b)
        val a = (SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        val g = (SelectionLassoCoincidentGeometry.build(p, a) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return finish(p, g, b)
    }
    private data class Geometry(val points: List<Point>, val pairs: List<Pair<Int, Int>>)
    private fun synthetic(
        b: SelectionQueryBudget = SelectionQueryBudget(),
        geometry: SelectionExactArithmetic.() -> Geometry,
    ): Fixture {
        val owner = prepare(emptyList(), b)
        val grouped = known(owner.exact {
            val data = geometry()
            val sorted = data.points.sortedWith { a, c ->
                when (compare(a, c)) { Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1 }
            }
            val vertices = sorted.mapIndexed { i, q -> Vertex(i, q) }
            fun index(i: Int) = sorted.indexOfFirst { compare(it, data.points[i]) == Sign.ZERO }
            val groups = data.pairs.map { (a, c) ->
                val lo = minOf(index(a), index(c)); val hi = maxOf(index(a), index(c))
                EdgeGroup(lo, hi, sorted[lo], sorted[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId })
            SelectionLassoCoincidentGeometry.Borrowed(owner, vertices, groups)
        })
        return finish(owner, grouped, b)
    }
    private fun graph(
        points: List<DuPoint>, pairs: List<Pair<Int, Int>>, b: SelectionQueryBudget = SelectionQueryBudget(),
    ) = synthetic(b) { Geometry(points.map { point(it) }, pairs) }
    private val triangle = listOf(p(0, 0), p(2, 0), p(0, 2))
    private val square = listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2))
    private val bowTie = listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0))
    private fun mixed(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(0, 0), p(0, 1), p(1, 0), p(2, 1), p(3, 0)), listOf(0 to 2, 1 to 3), b)
    private fun build(f: Fixture): SelectionLassoGraphComponents.Borrowed {
        val r = SelectionLassoGraphComponents.build(f.owner, f.admission)
        assertTrue("Expected Ready: $r", r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun check(f: Fixture, result: SelectionLassoGraphComponents.Borrowed) {
        val a = f.cycles.adjacency
        assertSame(f.admission, result.admission); assertSame(f.cycles, result.cycles)
        assertEquals(a.vertices.size, result.vertexToComponent.size)
        assertEquals(a.edgeGroups.size, result.edgeGroupToComponent.size)
        assertEquals(a.halfEdges.size, result.halfEdgeToComponent.size)
        assertEquals(f.cycles.walks.size, result.walkToComponent.size)
        for ((i, c) in result.components.withIndex()) {
            assertEquals(i, c.id)
            assertEquals(result.vertexToComponent.indices.first { result.vertexToComponent[it] == i }, c.minimumVertexId)
            assertEquals(result.vertexToComponent.count { it == i }, c.vertexCount)
            assertEquals(result.edgeGroupToComponent.count { it == i }, c.edgeGroupCount)
            assertEquals(result.halfEdgeToComponent.count { it == i }, c.halfEdgeCount)
            assertEquals(result.walkToComponent.count { it == i }, c.walkCount)
        }
        for ((i, g) in a.edgeGroups.withIndex()) {
            assertEquals(result.vertexToComponent[g.startVertexId], result.edgeGroupToComponent[i])
            assertEquals(result.vertexToComponent[g.endVertexId], result.edgeGroupToComponent[i])
        }
        for ((i, h) in a.halfEdges.withIndex()) {
            assertEquals(result.edgeGroupToComponent[h.edgeGroupIndex], result.halfEdgeToComponent[i])
            assertEquals(result.halfEdgeToComponent[h.twin], result.halfEdgeToComponent[i])
        }
        for (w in f.cycles.walks) for (id in w.halfEdgeIds)
            assertEquals(result.walkToComponent[w.id], result.halfEdgeToComponent[id])
    }
    private fun forged(
        f: Fixture, cycles: SelectionLassoSuccessorCycles.Borrowed = f.cycles,
        record: SelectionLassoEmbeddingAdmission.Record = f.admission.record,
    ) = SelectionLassoEmbeddingAdmission.Borrowed(f.owner, cycles, record)
    private fun altered(
        f: Fixture,
        vertices: List<Vertex> = f.cycles.adjacency.vertices,
        groups: List<EdgeGroup> = f.cycles.adjacency.edgeGroups,
        edges: List<SelectionLassoIncidentAdjacency.HalfEdge> = f.cycles.adjacency.halfEdges,
        incidents: List<SelectionLassoIncidentAdjacency.Incident> = f.cycles.adjacency.incidents,
        links: List<SelectionLassoSuccessorPermutation.Link> = f.cycles.permutation.links,
        walks: List<SelectionLassoSuccessorCycles.Walk> = f.cycles.walks,
        membership: List<Int> = f.cycles.halfEdgeToWalk,
    ): SelectionLassoEmbeddingAdmission.Borrowed {
        val g = SelectionLassoCoincidentGeometry.Borrowed(f.owner, vertices, groups)
        val a = SelectionLassoIncidentAdjacency.Borrowed(f.owner, g, edges, incidents)
        val s = SelectionLassoSuccessorPermutation.Borrowed(f.owner, a, links)
        val c = SelectionLassoSuccessorCycles.Borrowed(f.owner, s, walks, membership)
        return forged(f, c)
    }
    private fun reject(
        f: Fixture, reason: InvalidReason, source: SelectionLassoEmbeddingAdmission.Borrowed = f.admission,
    ) {
        val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
        assertEquals(Result.InvalidInput(reason), SelectionLassoGraphComponents.build(f.owner, source))
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >= before)
        assertThrows(IllegalStateException::class.java) { f.owner.segments }
    }
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue("Expected workload rejection: $r", r is Result.Unresolved)
        val cause = (r as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }

    @Test fun emptyGraphHasNoComponents() {
        production(emptyList()).use { f ->
            val r = build(f); check(f, r)
            assertEquals(0, r.componentCount)
            assertTrue(r.components.isEmpty())
        }
    }
    @Test fun isolatedVerticesAreSingletonComponentsInExactVertexOrder() {
        for (points in listOf(listOf(p(0, 0)), listOf(p(2, 2), p(0, 0), p(1, 1)))) graph(points, emptyList()).use {
            val r = build(it); check(it, r)
            assertEquals(points.indices.toList(), r.vertexToComponent)
            assertEquals(points.indices.map { i -> Component(i, i, 1, 0, 0, 0) }, r.components)
        }
    }
    @Test fun oneEdgeAndBacktrackingWalkShareOneComponent() {
        production(listOf(p(0, 0), p(2, 0))).use {
            val r = build(it); check(it, r)
            assertEquals(listOf(Component(0, 0, 2, 1, 2, 1)), r.components)
            assertEquals(listOf(0, 0), r.vertexToComponent)
            assertEquals(listOf(0), r.edgeGroupToComponent)
            assertEquals(listOf(0, 0), r.halfEdgeToComponent)
            assertEquals(listOf(0), r.walkToComponent)
        }
    }
    @Test fun triangleSquareBowTieAndFigureEightRemainSingleComponents() {
        val figureEight = listOf(p(0, 0), p(2, 0), p(1, 2), p(0, 0), p(-2, 0), p(-1, 2))
        for (input in listOf(triangle, square, bowTie, figureEight)) production(input).use {
            val r = build(it); check(it, r)
            assertEquals(1, r.componentCount)
            assertTrue(r.vertexToComponent.all { c -> c == 0 })
            if (input == bowTie) assertEquals(Component(0, 0, 5, 6, 12, 3), r.components.single())
        }
    }
    @Test fun retracedPathsBridgeAndDanglingSpikeDoNotAddComponents() {
        val bridge = listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(5, 0), p(7, 0), p(5, 2), p(5, 0))
        val spike = triangle + listOf(p(0, 0), p(-1, -1))
        for (input in listOf(listOf(p(0, 0), p(2, 0), p(0, 0), p(2, 0)),
            listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(0, 2), p(2, 0)), bridge, spike)) {
            production(input).use { val r = build(it); check(it, r); assertEquals(1, r.componentCount) }
        }
    }
    @Test fun disjointAndNestedTrianglesAreTwoComponentsWithoutContainmentClaims() {
        for (nested in listOf(false, true)) {
            val points = if (nested) listOf(p(0, 0), p(10, 0), p(0, 10), p(1, 1), p(2, 1), p(1, 2))
                else listOf(p(0, 0), p(2, 0), p(0, 2), p(5, 0), p(7, 0), p(5, 2))
            graph(points, listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3)).use {
                val r = build(it); check(it, r); assertEquals(2, r.componentCount)
                assertTrue(r.components.all { c -> c.vertexCount == 3 && c.edgeGroupCount == 3 &&
                    c.halfEdgeCount == 6 && c.walkCount == 2 })
            }
        }
    }
    @Test fun handDerivedInterleavedVertexAndEdgeMappingsIncludeAnIsolatedComponent() {
        mixed().use {
            val r = build(it); check(it, r)
            assertEquals(listOf(0, 1, 0, 1, 2), r.vertexToComponent)
            assertEquals(listOf(0, 1), r.edgeGroupToComponent)
            assertEquals(listOf(0, 0, 1, 1), r.halfEdgeToComponent)
            assertEquals(listOf(0, 1), r.walkToComponent)
            assertEquals(listOf(Component(0, 0, 2, 1, 2, 1), Component(1, 1, 2, 1, 2, 1),
                Component(2, 4, 1, 0, 0, 0)), r.components)
        }
    }
    @Test fun minimumVertexNotUnionFindRootDeterminesCanonicalId() {
        graph(listOf(p(0, 0), p(1, 0), p(1, 1), p(2, 0), p(3, -1), p(5, 5)),
            listOf(0 to 4, 1 to 2, 1 to 3, 1 to 4)).use {
            val r = build(it); check(it, r)
            assertEquals(listOf(0, 0, 0, 0, 0, 1), r.vertexToComponent)
            assertEquals(listOf(0, 5), r.components.map { c -> c.minimumVertexId })
        }
    }
    @Test fun polygonsTreesAndIsolatedVerticesHaveIndependentComponents() {
        graph(listOf(p(0, 0), p(2, 0), p(0, 2), p(5, 0), p(6, 0), p(6, 1), p(8, 0), p(9, 0), p(10, 5)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 6 to 7)).use {
            val r = build(it); check(it, r)
            assertEquals(4, r.componentCount)
            assertEquals(listOf(3, 3, 2, 1), r.components.map { c -> c.vertexCount })
            assertEquals(listOf(2, 1, 1, 0), r.components.map { c -> c.walkCount })
        }
    }
    @Test fun windingAndStartRotationPreserveEveryGeometricMapping() {
        val signatures = ArrayList<Any>()
        for (input in listOf(bowTie, bowTie.reversed(), bowTie.drop(1) + bowTie.take(1))) production(input).use {
            val r = build(it); check(it, r)
            signatures.add(listOf(r.components, r.vertexToComponent, r.edgeGroupToComponent,
                r.halfEdgeToComponent, r.walkToComponent))
        }
        assertEquals(1, signatures.distinct().size)
    }
    @Test fun exactRationalCrossingCoordinatesAndSourcesAreNeverReplaced() {
        production(listOf(p(0, 0), p(1, 1), p(0, 1), p(1, -1))).use {
            val vertices = it.cycles.adjacency.vertices
            val r = build(it); check(it, r)
            assertSame(vertices, r.cycles.adjacency.vertices)
            assertEquals(1, r.componentCount)
            assertTrue(vertices.any { v -> v.point.x.denominator.toString() == "3" })
        }
    }
    @Test fun immutableDeterministicOutputPreservesOriginalStructuresAndQuotas() {
        val signatures = ArrayList<Any>(); val work = ArrayList<Long>()
        repeat(3) {
            production(triangle + triangle).use { f ->
                val source = f.cycles; val a = source.adjacency
                val vertices = a.vertices; val groups = a.edgeGroups; val edges = a.halfEdges
                val incidents = a.incidents; val links = source.permutation.links; val walks = source.walks
                val occurrences = groups.flatMap { it.occurrences }
                val quotas = listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,
                    SelectionQueryResource.ARRANGEMENT_EDGES, SelectionQueryResource.ARRANGEMENT_FACES)
                val before = quotas.map { f.budget.used(it) }
                val r = build(f); check(f, r)
                assertSame(vertices, r.cycles.adjacency.vertices); assertSame(groups, r.cycles.adjacency.edgeGroups)
                assertSame(edges, r.cycles.adjacency.halfEdges); assertSame(incidents, r.cycles.adjacency.incidents)
                assertSame(links, r.cycles.permutation.links); assertSame(walks, r.cycles.walks)
                assertEquals(occurrences, groups.flatMap { it.occurrences })
                assertEquals(triangle + triangle, f.owner.suppliedVertices)
                assertEquals(before, quotas.map { f.budget.used(it) })
                for (list in listOf(r.components, r.vertexToComponent, r.edgeGroupToComponent,
                    r.halfEdgeToComponent, r.walkToComponent)) {
                    assertThrows(UnsupportedOperationException::class.java) { (list as MutableList).clear() }
                }
                signatures.add(listOf(r.components, r.vertexToComponent, r.edgeGroupToComponent,
                    r.halfEdgeToComponent, r.walkToComponent))
                work.add(f.budget.used(SelectionQueryResource.WORK_UNITS))
            }
        }
        assertEquals(1, signatures.distinct().size); assertEquals(1, work.distinct().size)
    }
    @Test fun componentConstructionDoesNotRerunIntersectionKernel() {
        var armed = false; var forbidden = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && Thread.currentThread().stackTrace.any { it.className.contains("SelectionExactSegmentIntersection") }) {
                forbidden = true
                error("Component indexing must not repeat geometric intersection admission")
            }
        })
        mixed(b).use { armed = true; check(it, build(it)); assertFalse(forbidden) }
    }
    @Test fun closedPrimaryAndForeignBorrowerHaveTypedFailures() {
        mixed().use {
            it.owner.close()
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),
                SelectionLassoGraphComponents.build(it.owner, it.admission))
        }
        mixed().use { primary ->
            mixed().use { foreign ->
                foreign.owner.close()
                reject(primary, InvalidReason.OWNER_CLOSED, foreign.admission)
            }
        }
    }
    @Test fun foreignAdmissionAndNestedOwnersRemainUnchangedWithArmedCancellation() {
        for (kind in 0..3) for (empty in listOf(false, true)) for (sticky in listOf(false, true)) {
            var armed = false; var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
            production(if (empty) emptyList() else triangle).use { primary ->
                production(if (empty) emptyList() else triangle, b).use { foreign ->
                    val c = foreign.cycles
                    val nested = when (kind) {
                        0, 1 -> c
                        2 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner, c.permutation, c.walks, c.halfEdgeToWalk)
                        else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                            SelectionLassoSuccessorPermutation.Borrowed(primary.owner, c.adjacency, c.permutation.links),
                            c.walks, c.halfEdgeToWalk)
                    }
                    val supplied = if (kind == 0) foreign.admission
                        else SelectionLassoEmbeddingAdmission.Borrowed(primary.owner, nested, foreign.admission.record)
                    if (sticky) assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,
                        b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1))
                    val before = SelectionQueryResource.entries.map { b.used(it) }
                    val failure = b.failure; val calls = callbacks; val retained = c.adjacency.edgeGroups
                    armed = true
                    reject(primary, InvalidReason.OWNER_MISMATCH, supplied)
                    assertEquals(calls, callbacks); assertEquals(before, SelectionQueryResource.entries.map { b.used(it) })
                    assertSame(failure, b.failure); assertSame(retained, c.adjacency.edgeGroups)
                    armed = false
                    if (!sticky) assertEquals(7, known(foreign.owner.exact { 7 }))
                }
            }
        }
    }
    @Test fun contradictoryAdmissionCountsRejectWithoutTrustingClaimedSuccess() {
        for (kind in 0..3) mixed().use { f ->
            val r = f.admission.record
            val altered = when (kind) {
                0 -> r.copy(vertexCount = 99)
                1 -> r.copy(edgeGroupCount = 99)
                2 -> r.copy(examinedEdgePairs = -1)
                else -> r.copy(examinedNonincidentVertexEdgeRelations = -1)
            }
            reject(f, InvalidReason.INVALID_ADMISSION, forged(f, record = altered))
        }
    }
    @Test fun invalidVertexAndEndpointIdsRejectBeforeIndexedAccess() {
        for (kind in 0..2) mixed().use { f ->
            val a = f.cycles.adjacency
            val malformed = when (kind) {
                0 -> altered(f, vertices = listOf(a.vertices[0].copy(id = 99)) + a.vertices.drop(1))
                1 -> altered(f, groups = listOf(a.edgeGroups[0].copy(startVertexId = -1)) + a.edgeGroups.drop(1))
                else -> altered(f, groups = listOf(a.edgeGroups[0].copy(endVertexId = 99)) + a.edgeGroups.drop(1))
            }
            reject(f, InvalidReason.INVALID_GRAPH, malformed)
        }
    }
    @Test fun wrongGroupHalfEdgeAndTwinAssociationsReject() {
        for (kind in 0..3) mixed().use { f ->
            val edges = f.cycles.adjacency.halfEdges.toMutableList()
            edges[0] = when (kind) {
                0 -> edges[0].copy(edgeGroupIndex = 99)
                1 -> edges[0].copy(origin = 99)
                2 -> edges[0].copy(twin = 99)
                else -> edges[0].copy(canonicalForward = false)
            }
            reject(f, InvalidReason.INVALID_GRAPH, altered(f, edges = edges))
        }
    }
    @Test fun invalidIncidentAndSuccessorAssociationsReject() {
        for (kind in 0..3) mixed().use { f ->
            val a = f.cycles.adjacency
            val malformed = when (kind) {
                0 -> altered(f, incidents = listOf(a.incidents[0].copy(vertexId = 99)) + a.incidents.drop(1))
                1 -> altered(f, incidents = listOf(a.incidents[0].copy(outgoing = listOf(99))) + a.incidents.drop(1))
                2 -> altered(f, links = listOf(f.cycles.permutation.links[0].copy(next = 99)) +
                    f.cycles.permutation.links.drop(1))
                else -> altered(f, links = f.cycles.permutation.links.dropLast(1))
            }
            reject(f, InvalidReason.INVALID_GRAPH, malformed)
        }
    }
    @Test fun malformedAndComponentInconsistentWalksRejectCompletely() {
        for (kind in 0..6) mixed().use { f ->
            val walks = f.cycles.walks.toMutableList()
            val first = walks[0]
            var membership = f.cycles.halfEdgeToWalk
            when (kind) {
                0 -> walks[0] = first.copy(id = 99)
                1 -> walks[0] = first.copy(startHalfEdgeId = 99)
                2 -> walks[0] = first.copy(halfEdgeIds = listOf(99))
                3 -> walks[0] = first.copy(halfEdgeIds = emptyList())
                4 -> walks[0] = first.copy(halfEdgeIds = first.halfEdgeIds + first.halfEdgeIds)
                5 -> walks[0] = first.copy(halfEdgeIds = listOf(0, 3)) // switches disconnected components
                else -> membership = List(membership.size) { 0 }
            }
            reject(f, InvalidReason.INVALID_WALK, altered(f, walks = walks, membership = membership))
        }
    }
    @Test fun foreignCoordinateContextRejectsWithoutForeignExecution() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = { if (armed) throw CancellationException() })
        val other = prepare(emptyList(), b)
        try {
            val q = known(other.exact { point(rational(0), rational(0)) })
            mixed().use { f ->
                val vertices = f.cycles.adjacency.vertices.toMutableList()
                vertices[0] = vertices[0].copy(point = q)
                val before = SelectionQueryResource.entries.map { b.used(it) }
                armed = true
                reject(f, InvalidReason.OWNER_MISMATCH, altered(f, vertices = vertices))
                assertEquals(before, SelectionQueryResource.entries.map { b.used(it) })
                armed = false; assertEquals(7, known(other.exact { 7 }))
            }
        } finally { other.close() }
    }
    private fun inPhase(name: String) = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoGraphComponents") && it.methodName.substringBefore('$') == name
    }
    @Test fun workExhaustionDuringUnionFindPreservesConsumedHistoryAndPublishesNothing() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && inPhase("find")) {
                injected = true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        mixed(b).use { f ->
            val before = b.used(SelectionQueryResource.WORK_UNITS)
            armed = true
            assertEquals(SelectionQueryResource.WORK_UNITS,
                failure(SelectionLassoGraphComponents.build(f.owner, f.admission)).resource)
            assertTrue(injected); assertTrue(b.used(SelectionQueryResource.WORK_UNITS) > before)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.admission.record }
        }
    }
    @Test fun representationExhaustionReleasesAllReservations() {
        val probe = SelectionQueryBudget()
        val retained: Long
        mixed(probe).use { build(it); retained = probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val limits = SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) retained - 1 else it.queryCeiling
        })
        val b = SelectionQueryBudget(limits)
        mixed(b).use {
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES,
                failure(SelectionLassoGraphComponents.build(it.owner, it.admission)).resource)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun cancellationDuringConstructionAndWalkMembershipClosesOnlyPrimaryOwner() {
        for (phase in listOf("union", "mapWalks")) {
            var armed = false; var injected = false
            val b = SelectionQueryBudget(cancellationCheckpoint = {
                if (armed && inPhase(phase)) { injected = true; throw CancellationException() }
            })
            mixed(b).use {
                armed = true
                assertThrows(CancellationException::class.java) { SelectionLassoGraphComponents.build(it.owner, it.admission) }
                assertTrue(injected); assertNull(b.failure)
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java) { it.admission.record }
            }
        }
    }
    @Test fun stickyBudgetFailureCannotBecomeSuccessfulEmptyComponents() {
        mixed().use {
            val b = it.budget
            val sticky = b.charge(SelectionQueryResource.WORK_UNITS,
                b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1)
            assertNotNull(sticky)
            assertSame(sticky, failure(SelectionLassoGraphComponents.build(it.owner, it.admission)))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun ownerClosureInvalidatesEveryBorrowedIndexAndIdentityRemainsPure() {
        var armed = false; var callbacks = 0
        val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
        mixed(b).use { f ->
            val r = build(f); val before = callbacks
            armed = true
            assertTrue(r.isOwnedBy(f.owner)); assertEquals(before, callbacks)
            f.owner.close()
            assertTrue(r.isOwnedBy(f.owner)); assertEquals(before, callbacks)
            val accessors = listOf<() -> Any>({ r.admission }, { r.cycles }, { r.components }, { r.componentCount },
                { r.vertexToComponent }, { r.edgeGroupToComponent }, { r.halfEdgeToComponent }, { r.walkToComponent })
            for (access in accessors) assertThrows(IllegalStateException::class.java) { access() }
            assertThrows(IllegalStateException::class.java) { r.exact { rational(0) } }
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
}
