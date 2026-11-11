package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoEmbeddingAdmission.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoEmbeddingAdmission.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoEmbeddingAdmissionTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private data class Fixture(
        val owner: Prepared, val cycles: SelectionLassoSuccessorCycles.Borrowed,
        val budget: SelectionQueryBudget,
    ) : AutoCloseable {
        override fun close() = owner.close()
    }
    private fun prepare(input: List<DuPoint>, b: SelectionQueryBudget) =
        known(SelectionLassoSplitEvents.prepare(input, b))
    private fun finish(owner: Prepared, g: SelectionLassoCoincidentGeometry.Borrowed):
        SelectionLassoSuccessorCycles.Borrowed {
        val a = (SelectionLassoIncidentAdjacency.build(owner, g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val s = (SelectionLassoSuccessorPermutation.build(owner, a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        return (SelectionLassoSuccessorCycles.build(owner, s) as SelectionLassoSuccessorCycles.Result.Ready).value
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val owner = prepare(input, b)
        val atomic = (SelectionLassoAtomicEdges.build(owner) as SelectionLassoAtomicEdges.Result.Ready).value
        val grouped = (SelectionLassoCoincidentGeometry.build(owner, atomic) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return Fixture(owner, finish(owner, grouped), b)
    }
    private data class Geometry(val points: List<Point>, val pairs: List<Pair<Int, Int>>)
    /** Constructs local adjacency only; deliberately permits remote unsplit intersections. */
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
            val unique = ArrayList<Point>()
            for (v in sorted) if (unique.isEmpty() || compare(unique.last(), v) != Sign.ZERO) unique.add(v)
            val vertices = unique.mapIndexed { i, v -> Vertex(i, v) }
            fun index(i: Int) = unique.indexOfFirst { compare(it, data.points[i]) == Sign.ZERO }
            val groups = data.pairs.map { (a, c) ->
                val lo = minOf(index(a), index(c)); val hi = maxOf(index(a), index(c))
                EdgeGroup(lo, hi, unique[lo], unique[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId })
            SelectionLassoCoincidentGeometry.Borrowed(owner, vertices, groups)
        })
        return Fixture(owner, finish(owner, grouped), b)
    }
    private fun graph(points: List<DuPoint>, pairs: List<Pair<Int, Int>>, b: SelectionQueryBudget = SelectionQueryBudget()) =
        synthetic(b) { Geometry(points.map { point(it) }, pairs) }
    private val triangle = listOf(p(0, 0), p(2, 0), p(0, 2))
    private val square = listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2))
    private val bowTie = listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0))
    private fun twoEdges(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(0, 0), p(2, 0), p(0, 1), p(2, 1)), listOf(0 to 1, 2 to 3), b)
    private fun admit(f: Fixture): SelectionLassoEmbeddingAdmission.Borrowed {
        val r = SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles)
        assertTrue("Expected Ready: $r", r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun reject(f: Fixture, reason: InvalidReason, source: SelectionLassoSuccessorCycles.Borrowed = f.cycles) {
        val work = f.budget.used(SelectionQueryResource.WORK_UNITS)
        val r = SelectionLassoEmbeddingAdmission.build(f.owner, source)
        assertTrue("Expected $reason: $r", r is Result.InvalidInput)
        assertEquals(reason, (r as Result.InvalidInput).reason)
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >= work)
        assertThrows(IllegalStateException::class.java) { f.owner.segments }
    }
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue("Expected unresolved: $r", r is Result.Unresolved)
        val cause = (r as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun altered(
        f: Fixture,
        vertices: List<Vertex> = f.cycles.adjacency.vertices,
        groups: List<EdgeGroup> = f.cycles.adjacency.edgeGroups,
        halfEdges: List<SelectionLassoIncidentAdjacency.HalfEdge> = f.cycles.adjacency.halfEdges,
        incidents: List<SelectionLassoIncidentAdjacency.Incident> = f.cycles.adjacency.incidents,
        links: List<SelectionLassoSuccessorPermutation.Link> = f.cycles.permutation.links,
        walks: List<SelectionLassoSuccessorCycles.Walk> = f.cycles.walks,
        membership: List<Int> = f.cycles.halfEdgeToWalk,
    ): SelectionLassoSuccessorCycles.Borrowed {
        val g = SelectionLassoCoincidentGeometry.Borrowed(f.owner, vertices, groups)
        val a = SelectionLassoIncidentAdjacency.Borrowed(f.owner, g, halfEdges, incidents)
        val s = SelectionLassoSuccessorPermutation.Borrowed(f.owner, a, links)
        return SelectionLassoSuccessorCycles.Borrowed(f.owner, s, walks, membership)
    }

    @Test fun emptyAndSingleEdgeEmbeddingsDoNotRequireAreaOrFaces() {
        for (input in listOf(emptyList(), listOf(p(0, 0)), listOf(p(0, 0), p(2, 0)))) production(input).use {
            val e = admit(it)
            assertEquals(0L, e.record.examinedEdgePairs)
            assertEquals(0L, e.record.examinedNonincidentVertexEdgeRelations)
            assertEquals(if (input.size == 2) 1 else 0, e.record.edgeGroupCount)
            assertEquals(SelectionLassoEmbeddingAdmission.Scope.REPRESENTED_PLANAR_STRAIGHT_LINE_EMBEDDING,
                e.record.scope)
        }
    }
    @Test fun triangleAndSquareHaveHandDerivedCompleteRelationCounts() {
        for ((input, counts) in listOf(triangle to (3L to 3L), square to (6L to 8L))) production(input).use {
            val r = admit(it).record
            assertEquals(input.size, r.vertexCount)
            assertEquals(input.size, r.edgeGroupCount)
            assertEquals(counts.first, r.examinedEdgePairs)
            assertEquals(counts.second, r.examinedNonincidentVertexEdgeRelations)
        }
    }
    @Test fun concaveAndSplitBowTieAreAdmitted() {
        for (input in listOf(listOf(p(0, 0), p(4, 0), p(2, 1), p(4, 4), p(0, 4)), bowTie)) {
            production(input).use {
                val r = admit(it).record
                if (input == bowTie) {
                    assertEquals(5, r.vertexCount); assertEquals(6, r.edgeGroupCount)
                    assertEquals(15L, r.examinedEdgePairs)
                    assertEquals(18L, r.examinedNonincidentVertexEdgeRelations)
                }
            }
        }
    }
    @Test fun figureEightAndRetracedBridgeAndSpikePreserveEmbedding() {
        val figureEight = listOf(p(0, 0), p(2, 0), p(1, 2), p(0, 0), p(-2, 0), p(-1, 2))
        val bridge = listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(5, 0),
            p(7, 0), p(5, 2), p(5, 0))
        val spike = listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(-1, -1))
        for (input in listOf(figureEight, bridge, spike)) production(input).use { admit(it) }
    }
    @Test fun fullyRetracedLineAndPolygonAreNotRejectedAsInvalidRegions() {
        for (input in listOf(listOf(p(0, 0), p(2, 0), p(0, 0), p(2, 0)),
            listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(0, 2), p(2, 0)))) {
            production(input).use { f ->
                val groups = f.cycles.adjacency.edgeGroups
                assertTrue(groups.any { it.occurrences.size > 1 })
                admit(f)
                assertSame(groups, f.cycles.adjacency.edgeGroups)
            }
        }
    }
    @Test fun correctlyGroupedPartialSourceOverlapsAreAllowed() {
        production(listOf(p(0, 0), p(4, 0), p(1, 0), p(3, 0), p(3, 2))).use {
            val e = admit(it)
            assertEquals(5, e.record.edgeGroupCount)
            assertTrue(it.cycles.adjacency.edgeGroups.any { g -> g.occurrences.size == 3 })
        }
    }
    @Test fun severalIncidentEdgesAndExactThirdCrossingVerticesAreAllowed() {
        for (input in listOf(listOf(p(-2, 0), p(2, 0), p(0, -2), p(0, 2), p(-2, -2), p(2, 2)),
            listOf(p(0, 0), p(1, 1), p(0, 1), p(1, -1)))) production(input).use {
            val evidence = admit(it)
            assertSame(it.cycles, evidence.cycles)
            if (input.size == 4) assertTrue(it.cycles.adjacency.vertices.any { v ->
                v.point.x.numerator.toString() == "1" && v.point.x.denominator.toString() == "3"
            })
        }
    }
    @Test fun disconnectedAndNestedTrianglesRemainUnassociatedGeometricComponents() {
        for (inner in listOf(false, true)) {
            val points = if (inner)
                listOf(p(0, 0), p(10, 0), p(0, 10), p(1, 1), p(2, 1), p(1, 2))
            else listOf(p(0, 0), p(2, 0), p(0, 2), p(5, 0), p(7, 0), p(5, 2))
            graph(points, listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3)).use {
                val e = admit(it)
                assertEquals(6, e.record.vertexCount); assertEquals(6, e.record.edgeGroupCount)
                assertEquals(4, e.cycles.walks.size)
                assertTrue(e.cycles.adjacency.edgeGroups.all { g -> g.occurrences.isEmpty() })
            }
        }
    }
    @Test fun splitTJunctionAndFourWayIntersectionArePlanar() {
        for (degree in listOf(3, 4)) graph(
            listOf(p(0, 0), p(1, 0), p(0, 1), p(-1, 0), p(0, -1)),
            (1..degree).map { 0 to it },
        ).use { assertEquals(degree, admit(it).record.edgeGroupCount) }
    }
    @Test fun isolatedVerticesOutsideEdgesAndMultipleBacktrackingComponentsAreAllowed() {
        graph(listOf(p(0, 0), p(2, 0), p(0, 1), p(2, 1), p(3, 0), p(5, 5)),
            listOf(0 to 1, 2 to 3)).use {
            val r = admit(it).record
            assertEquals(6, r.vertexCount)
            assertEquals(8L, r.examinedNonincidentVertexEdgeRelations)
        }
        graph(listOf(p(0, 0)), emptyList()).use { assertEquals(1, admit(it).record.vertexCount) }
    }
    @Test fun subUlpSeparationAndNearlyCollinearIsolatedPointStayDistinct() {
        synthetic {
            val one = rational(1)
            val above = add(one, rational(1, 1L shl 60))
            Geometry(listOf(point(rational(0), one), point(rational(2), one),
                point(rational(0), above), point(rational(2), above),
                point(one, add(one, rational(1, 1L shl 61)))),
                listOf(0 to 1, 2 to 3))
        }.use {
            assertEquals(5, admit(it).record.vertexCount)
        }
    }
    @Test fun unsplitCrossingWhoseEndpointsWouldCollapseUnderDoubleRoundingRejects() {
        synthetic {
            val one = rational(1); val tiny = rational(1, 1L shl 60)
            Geometry(listOf(point(rational(0), one), point(rational(2), one),
                point(one, subtract(one, tiny)), point(one, add(one, tiny))),
                listOf(0 to 1, 2 to 3))
        }.use { reject(it, InvalidReason.UNSPLIT_CROSSING) }
    }
    @Test fun smallAndLargeFiniteBinaryScalesRemainExact() {
        for (s in listOf(Math.scalb(1.0, -400), Math.scalb(1.0, 400))) {
            graph(listOf(p(0, 0), p(s, 0), p(0, s)), listOf(0 to 1, 1 to 2, 2 to 0)).use { admit(it) }
        }
    }
    @Test fun unsplitProperCrossingsRejectWithPairIdentity() {
        graph(listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0)), listOf(0 to 1, 2 to 3)).use {
            val r = SelectionLassoEmbeddingAdmission.build(it.owner, it.cycles) as Result.InvalidInput
            assertEquals(InvalidReason.UNSPLIT_CROSSING, r.reason)
            assertEquals(0, r.firstEdge); assertEquals(1, r.secondEdge)
            assertEquals(0L, it.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun unsplitEndpointTouchRejects() {
        graph(listOf(p(0, 0), p(2, 0), p(1, 0), p(1, 1)), listOf(0 to 1, 2 to 3)).use {
            reject(it, InvalidReason.UNSPLIT_ENDPOINT_CONTACT)
        }
    }
    @Test fun isolatedVertexStrictlyInsideEdgeRejects() {
        graph(listOf(p(0, 0), p(2, 0), p(1, 0)), listOf(0 to 1)).use {
            reject(it, InvalidReason.UNSPLIT_VERTEX)
        }
    }
    @Test fun partialAndContainedCollinearOverlapsReject() {
        for (contained in listOf(false, true)) {
            val points = if (contained) listOf(p(0, 0), p(4, 0), p(1, 0), p(3, 0))
                else listOf(p(0, 0), p(3, 0), p(1, 0), p(4, 0))
            graph(points, listOf(0 to 1, 2 to 3)).use { reject(it, InvalidReason.POSITIVE_LENGTH_OVERLAP) }
        }
    }
    @Test fun duplicateAndReversedDuplicateGroupsRejectBeforeTopologyUse() {
        for (reverse in listOf(false, true)) twoEdges().use { f ->
            val first = f.cycles.adjacency.edgeGroups[0]
            val duplicate = if (reverse) first.copy(startVertexId = first.endVertexId,
                endVertexId = first.startVertexId, start = first.end, end = first.start) else first
            reject(f, if (reverse) InvalidReason.EDGE_ORDER else InvalidReason.DUPLICATE_EDGE,
                altered(f, groups = listOf(first, duplicate)))
        }
    }
    @Test fun malformedVertexIdsOrderingAndConflictingEqualCoordinatesReject() {
        for (kind in 0..2) twoEdges().use { f ->
            val vertices = f.cycles.adjacency.vertices.toMutableList()
            vertices[1] = when (kind) {
                0 -> vertices[1].copy(id = 99)
                1 -> vertices[1].copy(point = vertices[0].point)
                else -> vertices[1].copy(point = vertices.last().point)
            }
            reject(f, if (kind == 0) InvalidReason.VERTEX_ID else InvalidReason.VERTEX_ORDER,
                altered(f, vertices = vertices))
        }
    }
    @Test fun invalidEndpointIdsZeroLengthAndNoncanonicalGroupOrderReject() {
        for (kind in 0..3) twoEdges().use { f ->
            val groups = f.cycles.adjacency.edgeGroups.toMutableList()
            val g = groups[0]
            val reason = when (kind) {
                0 -> { groups[0] = g.copy(endVertexId = 99); InvalidReason.EDGE_ID }
                1 -> { groups[0] = g.copy(endVertexId = g.startVertexId, end = g.start); InvalidReason.ZERO_LENGTH }
                2 -> { groups.reverse(); InvalidReason.EDGE_ORDER }
                else -> { groups[0] = g.copy(end = groups[1].end); InvalidReason.ENDPOINT_ASSOCIATION }
            }
            reject(f, reason, altered(f, groups = groups))
        }
    }
    @Test fun foreignExactCoordinatesRejectWithoutForeignCancellation() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = { if (armed) throw CancellationException() })
        val other = prepare(emptyList(), b)
        try {
            val q = known(other.exact { point(rational(0), rational(0)) })
            twoEdges().use { f ->
                val vertices = f.cycles.adjacency.vertices.toMutableList()
                vertices[0] = vertices[0].copy(point = q)
                val counters = SelectionQueryResource.entries.map { b.used(it) }
                armed = true
                reject(f, InvalidReason.OWNER_MISMATCH, altered(f, vertices = vertices))
                assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                armed = false; assertEquals(7, known(other.exact { 7 }))
            }
        } finally { other.close() }
    }
    @Test fun malformedTwinsAndHalfEdgeEndpointsReject() {
        for (kind in 0..3) twoEdges().use { f ->
            val edges = f.cycles.adjacency.halfEdges.toMutableList()
            val reason = when (kind) {
                0 -> { edges[0] = edges[0].copy(twin = 99); InvalidReason.INVALID_ADJACENCY }
                1 -> { edges[1] = edges[1].copy(twin = 1); InvalidReason.INVALID_ADJACENCY }
                2 -> { edges[0] = edges[0].copy(origin = 99); InvalidReason.ENDPOINT_ASSOCIATION }
                else -> { edges[0] = edges[0].copy(edgeGroupIndex = 99); InvalidReason.EDGE_ID }
            }
            reject(f, reason, altered(f, halfEdges = edges))
        }
    }
    @Test fun contradictoryIncidentMembershipAndAngularOrderReject() {
        for (kind in 0..2) production(bowTie).use { f ->
            val incidents = f.cycles.adjacency.incidents.toMutableList()
            val index = incidents.indexOfFirst { it.outgoing.size == 4 }
            val v = incidents[index]
            incidents[index] = when (kind) {
                0 -> v.copy(outgoing = v.outgoing.reversed())
                1 -> v.copy(outgoing = v.outgoing + v.outgoing.first())
                else -> v.copy(vertexId = 99)
            }
            reject(f, InvalidReason.INVALID_ADJACENCY, altered(f, incidents = incidents))
        }
    }
    @Test fun inconsistentSuccessorLinksReject() {
        for (kind in 0..3) production(square).use { f ->
            val links = f.cycles.permutation.links.toMutableList()
            links[0] = when (kind) {
                0 -> links[0].copy(next = 99)
                1 -> links[0].copy(previous = 99)
                2 -> links[0].copy(halfEdgeId = 99)
                else -> links[0].copy(next = links[1].next)
            }
            reject(f, InvalidReason.INVALID_SUCCESSOR, altered(f, links = links))
        }
    }
    @Test fun malformedCycleCoverageStartsAndMembershipReject() {
        for (kind in 0..5) production(square).use { f ->
            val walks = f.cycles.walks.toMutableList()
            val first = walks[0]
            var membership = f.cycles.halfEdgeToWalk
            when (kind) {
                0 -> walks[0] = first.copy(id = 99)
                1 -> walks[0] = first.copy(startHalfEdgeId = 99)
                2 -> walks[0] = first.copy(halfEdgeIds = first.halfEdgeIds.drop(1))
                3 -> walks[0] = first.copy(halfEdgeIds = listOf(99))
                4 -> walks[0] = first.copy(halfEdgeIds = first.halfEdgeIds + first.halfEdgeIds)
                else -> membership = List(membership.size) { 0 }
            }
            reject(f, InvalidReason.INVALID_CYCLES, altered(f, walks = walks, membership = membership))
        }
    }
    @Test fun closedOwnersRejectAndBorrowerAccessIsInvalidated() {
        twoEdges().use { f ->
            val e = admit(f)
            f.owner.close()
            assertThrows(IllegalStateException::class.java) { e.record }
            assertThrows(IllegalStateException::class.java) { e.cycles }
            assertThrows(IllegalStateException::class.java) { e.exact { rational(0) } }
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),
                SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles))
        }
        twoEdges().use { primary ->
            twoEdges().use { foreign ->
                foreign.owner.close()
                reject(primary, InvalidReason.OWNER_CLOSED, foreign.cycles)
            }
        }
    }
    @Test fun foreignAndNestedOwnersPreserveForeignCallbacksBudgetsAndUsability() {
        for (kind in 0..2) for (empty in listOf(false, true)) for (sticky in listOf(false, true)) {
            var armed = false; var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
            production(if (empty) emptyList() else triangle).use { primary ->
                production(if (empty) emptyList() else triangle, b).use { foreign ->
                    val c = foreign.cycles
                    val supplied = when (kind) {
                        0 -> c
                        1 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner, c.permutation, c.walks, c.halfEdgeToWalk)
                        else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                            SelectionLassoSuccessorPermutation.Borrowed(primary.owner, c.adjacency, c.permutation.links),
                            c.walks, c.halfEdgeToWalk)
                    }
                    if (sticky) assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,
                        b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1))
                    val counters = SelectionQueryResource.entries.map { b.used(it) }
                    val failure = b.failure; val calls = callbacks; val groups = c.adjacency.edgeGroups
                    armed = true
                    reject(primary, InvalidReason.OWNER_MISMATCH, supplied)
                    assertEquals(calls, callbacks); assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                    assertSame(failure, b.failure); assertSame(groups, c.adjacency.edgeGroups)
                    armed = false
                    if (!sticky) assertEquals(7, known(foreign.owner.exact { 7 }))
                }
            }
        }
    }
    @Test fun extremeFiniteBinaryEndpointsCanExhaustExactBits() {
        graph(listOf(p(0, 0), p(Double.MIN_VALUE, Double.MAX_VALUE),
            p(0, 1), p(Double.MAX_VALUE, Double.MIN_VALUE)), listOf(0 to 1, 2 to 3)).use { f ->
            val r = SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles)
            assertEquals(SelectionBudgetConstraint.EXACT_BITS, failure(r).constraint)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    private fun inPhase(name: String) = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoEmbeddingAdmission") && it.methodName.substringBefore('$') == name
    }
    @Test fun pairwiseWorkExhaustionNeverPublishesPartialEvidence() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && inPhase("scanPairs")) {
                injected = true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        twoEdges(b).use { f ->
            val before = b.used(SelectionQueryResource.WORK_UNITS)
            armed = true
            assertEquals(SelectionQueryResource.WORK_UNITS,
                failure(SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles)).resource)
            assertTrue(injected); assertTrue(b.used(SelectionQueryResource.WORK_UNITS) > before)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.cycles.walks }
        }
    }
    @Test fun representationExhaustionDisposesTheWholeOwner() {
        val probe = SelectionQueryBudget()
        val retained: Long
        twoEdges(probe).use { admit(it); retained = probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val limits = SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) retained - 1 else it.queryCeiling
        })
        val b = SelectionQueryBudget(limits)
        twoEdges(b).use { f ->
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES,
                failure(SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles)).resource)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun cancellationDuringPairAndVertexScansPropagatesAndCleansUp() {
        for (phase in listOf("scanPairs", "checkVertices")) {
            var armed = false; var injected = false
            val b = SelectionQueryBudget(cancellationCheckpoint = {
                if (armed && inPhase(phase)) { injected = true; throw CancellationException() }
            })
            twoEdges(b).use { f ->
                armed = true
                assertThrows(CancellationException::class.java) { SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles) }
                assertTrue(injected); assertNull(b.failure)
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java) { f.cycles.walks }
            }
        }
    }
    @Test fun stickyBudgetFailureNeverBecomesEmptySuccess() {
        twoEdges().use { f ->
            val b = f.budget
            val sticky = b.charge(SelectionQueryResource.WORK_UNITS,
                b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1)
            assertNotNull(sticky)
            assertSame(sticky, failure(SelectionLassoEmbeddingAdmission.build(f.owner, f.cycles)))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun immutableEvidencePreservesAllIdsSourcesAndPriorTopologyQuotas() {
        val input = triangle + triangle
        val records = ArrayList<SelectionLassoEmbeddingAdmission.Record>()
        val work = ArrayList<Long>()
        repeat(3) {
            production(input).use { f ->
                val source = f.cycles; val a = source.adjacency
                val groups = a.edgeGroups; val occurrences = groups.flatMap { it.occurrences }
                val links = source.permutation.links; val walks = source.walks
                val quotas = listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,
                    SelectionQueryResource.ARRANGEMENT_EDGES, SelectionQueryResource.ARRANGEMENT_FACES)
                val before = quotas.map { f.budget.used(it) }
                val e = admit(f)
                records.add(e.record); work.add(f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertSame(source, e.cycles); assertSame(groups, e.cycles.adjacency.edgeGroups)
                assertSame(links, e.cycles.permutation.links); assertSame(walks, e.cycles.walks)
                assertEquals(occurrences, e.cycles.adjacency.edgeGroups.flatMap { it.occurrences })
                assertEquals(input, f.owner.suppliedVertices)
                assertEquals(before, quotas.map { f.budget.used(it) })
                assertEquals(0L, f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES))
            }
        }
        assertEquals(1, records.distinct().size); assertEquals(1, work.distinct().size)
    }
}
