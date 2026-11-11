package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoSideWitnessEvidence.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoSideWitnessEvidence.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoSideWitnessEvidenceTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private fun prepare(input: List<DuPoint>, b: SelectionQueryBudget) =
        known(SelectionLassoSplitEvents.prepare(input, b))
    private data class Fixture(
        val owner: Prepared, val cycles: SelectionLassoSuccessorCycles.Borrowed,
        val selected: Int, val budget: SelectionQueryBudget,
    ) : AutoCloseable {
        override fun close() = owner.close()
    }
    private fun finish(p: Prepared, g: SelectionLassoCoincidentGeometry.Borrowed):
        SelectionLassoSuccessorCycles.Borrowed {
        val a = (SelectionLassoIncidentAdjacency.build(p, g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val s = (SelectionLassoSuccessorPermutation.build(p, a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        return (SelectionLassoSuccessorCycles.build(p, s) as SelectionLassoSuccessorCycles.Result.Ready).value
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val p = prepare(input, b)
        val a = (SelectionLassoAtomicEdges.build(p) as SelectionLassoAtomicEdges.Result.Ready).value
        val g = (SelectionLassoCoincidentGeometry.build(p, a) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return Fixture(p, finish(p, g), 0, b)
    }
    /** Synthetic geometry may have unsplit remote crossings; no embedding claim is made. */
    private fun synthetic(
        b: SelectionQueryBudget = SelectionQueryBudget(),
        segments: SelectionExactArithmetic.() -> List<Pair<Point, Point>>,
    ): Fixture {
        val p = prepare(emptyList(), b)
        val pair = known(p.exact {
            val pairs = segments()
            val sorted = pairs.flatMap { listOf(it.first, it.second) }.sortedWith { a, c ->
                when (compare(a, c)) { Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1 }
            }
            val unique = ArrayList<Point>()
            for (v in sorted) if (unique.isEmpty() || compare(unique.last(), v) != Sign.ZERO) unique.add(v)
            val vertices = unique.mapIndexed { i, v -> Vertex(i, v) }
            fun index(v: Point) = unique.indexOfFirst { compare(it, v) == Sign.ZERO }
            val groups = pairs.map { (a, c) ->
                val ai = index(a); val ci = index(c)
                val lo = minOf(ai, ci); val hi = maxOf(ai, ci)
                EdgeGroup(lo, hi, unique[lo], unique[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId })
            val first = pairs.firstOrNull()
            val selected = if (first == null) 0 else {
                val ai = index(first.first); val ci = index(first.second)
                2 * groups.indexOfFirst { it.startVertexId == minOf(ai, ci) && it.endVertexId == maxOf(ai, ci) } +
                    if (ai < ci) 0 else 1
            }
            SelectionLassoCoincidentGeometry.Borrowed(p, vertices, groups) to selected
        })
        return Fixture(p, finish(p, pair.first), pair.second, b)
    }
    private fun horizontal(
        obstacles: List<Double> = emptyList(), b: SelectionQueryBudget = SelectionQueryBudget(),
    ) = synthetic(b) {
        listOf(point(p(0, 0)) to point(p(2, 0))) +
            obstacles.map { y -> point(p(0, y)) to point(p(2, y)) }
    }
    private fun witness(f: Fixture, id: Int = f.selected): SelectionLassoSideWitnessEvidence.Borrowed {
        val r = SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, id)
        assertTrue("Expected Ready: $r", r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun fraction(r: Rational) = "${r.numerator}/${r.denominator}"
    private fun xy(p: Point) = "${fraction(p.x)},${fraction(p.y)}"
    private fun signature(r: SelectionLassoSideWitnessEvidence.Record) =
        listOf(r.halfEdgeId.toString(), r.edgeGroupIndex.toString(), xy(r.midpoint), xy(r.witness),
            fraction(r.offsetParameter), fraction(r.certifiedLimit), r.obstructionFound.toString(),
            r.examinedEdgeGroups.toString())
    private fun checkCertificate(f: Fixture, e: SelectionLassoSideWitnessEvidence.Borrowed) {
        val r = e.record
        val a = f.cycles.adjacency
        val h = a.halfEdges[r.halfEdgeId]
        assertSame(f.cycles, e.cycles)
        known(e.exact {
            assertEquals(Sign.POSITIVE, sign(cross(vector(a.vertices[h.destination].point,
                a.vertices[h.origin].point), vector(r.witness, a.vertices[h.origin].point))))
            assertEquals(Sign.POSITIVE, compare(r.offsetParameter, rational(0)))
            assertEquals(Sign.NEGATIVE, compare(r.offsetParameter, r.certifiedLimit))
            assertTrue(compare(r.offsetParameter, rational(1, 2)) != Sign.POSITIVE)
        })
        assertEquals(a.edgeGroups.size - 1, r.examinedEdgeGroups)
        for ((i, g) in a.edgeGroups.withIndex()) if (i != r.edgeGroupIndex) {
            assertEquals(Intersection.Disjoint, known(f.owner.intersectExistingExactSegments(
                r.midpoint, r.witness, g.start, g.end)))
        }
    }
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue("Expected unresolved: $r", r is Result.Unresolved)
        val cause = (r as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun reject(f: Fixture, c: SelectionLassoSuccessorCycles.Borrowed, reason: InvalidReason, id: Int = f.selected) {
        val work = f.budget.used(SelectionQueryResource.WORK_UNITS)
        assertEquals(Result.InvalidInput(reason), SelectionLassoSideWitnessEvidence.build(f.owner, c, id))
        assertThrows(IllegalStateException::class.java) { f.owner.segments }
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >= work)
    }
    private fun altered(
        f: Fixture, vertices: List<Vertex> = f.cycles.adjacency.vertices,
        groups: List<EdgeGroup> = f.cycles.adjacency.edgeGroups,
        halfEdges: List<SelectionLassoIncidentAdjacency.HalfEdge> = f.cycles.adjacency.halfEdges,
    ): SelectionLassoSuccessorCycles.Borrowed {
        val old = f.cycles
        val g = SelectionLassoCoincidentGeometry.Borrowed(f.owner, vertices, groups)
        val a = SelectionLassoIncidentAdjacency.Borrowed(f.owner, g, halfEdges, old.adjacency.incidents)
        val s = SelectionLassoSuccessorPermutation.Borrowed(f.owner, a, old.permutation.links)
        return SelectionLassoSuccessorCycles.Borrowed(f.owner, s, old.walks, old.halfEdgeToWalk)
    }

    @Test fun horizontalCapCertificateHasHandDerivedCoordinates() {
        horizontal().use {
            val e = witness(it); val r = e.record
            assertEquals("1/1,0/1", xy(r.midpoint))
            assertEquals("1/1,1/1", xy(r.witness))
            assertEquals("1/2", fraction(r.offsetParameter))
            assertEquals("1/1", fraction(r.certifiedLimit))
            assertFalse(r.obstructionFound); assertEquals(0, r.examinedEdgeGroups)
            checkCertificate(it, e)
        }
    }
    @Test fun verticalAndDiagonalNormalsAreExact() {
        for ((end, expected) in listOf(p(0, 2) to "-1/1,1/1", p(2, 2) to "0/1,2/1")) {
            synthetic { listOf(point(p(0, 0)) to point(end)) }.use {
                val e = witness(it)
                assertEquals(expected, xy(e.record.witness)); checkCertificate(it, e)
            }
        }
    }
    @Test fun twinUsesOppositeDirectedNormal() {
        horizontal().use {
            val first = witness(it)
            val twin = witness(it, it.selected xor 1)
            assertEquals("1/1,-1/1", xy(twin.record.witness))
            assertEquals(xy(first.record.midpoint), xy(twin.record.midpoint))
            checkCertificate(it, first); checkCertificate(it, twin)
        }
    }
    @Test fun triangleSquareConcaveAndBowTieCertificatesUseAtomicGeometry() {
        val inputs = listOf(
            listOf(p(0, 0), p(2, 0), p(0, 2)),
            listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2)),
            listOf(p(0, 0), p(4, 0), p(2, 1), p(4, 4), p(0, 4)),
            listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0)))
        for (input in inputs) production(input).use { f ->
            for (id in f.cycles.adjacency.halfEdges.indices) checkCertificate(f, witness(f, id))
        }
    }
    @Test fun nonDyadicIntersectionEndpointsStayExact() {
        production(listOf(p(0, 0), p(1, 1), p(0, 1), p(1, -1))).use { f ->
            val a = f.cycles.adjacency
            val id = a.halfEdges.first { h ->
                val q = a.vertices[h.origin].point
                fraction(q.x) == "1/3" && fraction(q.y) == "1/3"
            }.id
            checkCertificate(f, witness(f, id))
        }
    }
    @Test fun nearerObstructionSetsPositiveHalfDistance() {
        horizontal(listOf(1.0)).use {
            val e = witness(it); val r = e.record
            assertEquals("1/2", fraction(r.certifiedLimit))
            assertEquals("1/4", fraction(r.offsetParameter))
            assertEquals("1/1,1/2", xy(r.witness)); assertTrue(r.obstructionFound)
            checkCertificate(it, e)
        }
    }
    @Test fun obstructionAtCapIsDifferentFromArtificialCap() {
        horizontal(listOf(2.0)).use {
            val e = witness(it)
            assertEquals("1/1", fraction(e.record.certifiedLimit))
            assertTrue(e.record.obstructionFound); checkCertificate(it, e)
        }
    }
    @Test fun obstructionBeyondCapDoesNotBecomeBlockingEvidence() {
        horizontal(listOf(3.0)).use {
            val e = witness(it)
            assertFalse(e.record.obstructionFound)
            assertEquals(1, e.record.examinedEdgeGroups); checkCertificate(it, e)
        }
    }
    @Test fun nearestOfSeveralObstructionsWinsRegardlessOfVisitOrder() {
        horizontal(listOf(1.5, 0.25, 0.75)).use {
            val e = witness(it)
            assertEquals("1/8", fraction(e.record.certifiedLimit))
            assertEquals("1/1,1/8", xy(e.record.witness))
            assertEquals(3, e.record.examinedEdgeGroups); checkCertificate(it, e)
        }
    }
    @Test fun vertexTangencyCountsAsAnObstruction() {
        synthetic {
            listOf(point(p(0, 0)) to point(p(2, 0)),
                point(p(1, 1)) to point(p(2, 2)), point(p(1, 1)) to point(p(2, 0.5)))
        }.use {
            val e = witness(it)
            assertEquals("1/2", fraction(e.record.certifiedLimit)); checkCertificate(it, e)
        }
    }
    @Test fun collinearProbeOverlapUsesNearestDirectedParameter() {
        for (reverse in listOf(false, true)) synthetic {
            val direction = if (reverse) point(p(2, 0)) to point(p(0, 0)) else point(p(0, 0)) to point(p(2, 0))
            val sign = if (reverse) -1 else 1
            listOf(direction, point(p(1, sign * 0.5)) to point(p(1, sign * 1.5)))
        }.use {
            val e = witness(it)
            assertEquals("1/4", fraction(e.record.certifiedLimit))
            assertEquals("1/8", fraction(e.record.offsetParameter)); checkCertificate(it, e)
        }
    }
    @Test fun extremelyCloseParallelObstacleDoesNotNeedAnEpsilon() {
        horizontal(listOf(Math.scalb(1.0, -500))).use {
            val e = witness(it)
            known(e.exact {
                assertEquals(Sign.ZERO, compare(e.record.certifiedLimit, fromDouble(Math.scalb(1.0, -501))))
                assertEquals(Sign.ZERO, compare(e.record.witness.y, fromDouble(Math.scalb(1.0, -501))))
            })
            checkCertificate(it, e)
        }
    }
    @Test fun veryShortAndLongFiniteEdgesKeepStrictPositiveSide() {
        for (scale in listOf(Math.scalb(1.0, -400), Math.scalb(1.0, 400))) synthetic {
            listOf(point(p(0, 0)) to point(p(scale, 0)))
        }.use { checkCertificate(it, witness(it)) }
    }
    @Test fun deterministicEvidencePreservesOriginalSourcesAndAllTopologyQuotas() {
        val input = listOf(p(0, 0), p(2, 0), p(0, 2), p(0, 0), p(2, 0), p(0, 2))
        val signatures = ArrayList<List<String>>()
        val work = ArrayList<Long>()
        repeat(3) {
            production(input).use { f ->
                val groups = f.cycles.adjacency.edgeGroups
                val occurrences = groups.flatMap { it.occurrences }
                val sources = f.owner.segments.toList()
                val topology = listOf(SelectionQueryResource.ARRANGEMENT_EDGES,
                    SelectionQueryResource.ARRANGEMENT_EVENTS, SelectionQueryResource.ARRANGEMENT_FACES)
                val before = topology.map { f.budget.used(it) }
                val e = witness(f)
                signatures.add(signature(e.record)); work.add(f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertSame(groups, e.cycles.adjacency.edgeGroups)
                assertEquals(occurrences, groups.flatMap { it.occurrences })
                assertEquals(sources, f.owner.segments); assertEquals(input, f.owner.suppliedVertices)
                assertEquals(before, topology.map { f.budget.used(it) })
                assertEquals(0L, f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES))
            }
        }
        assertEquals(1, signatures.distinct().size); assertEquals(1, work.distinct().size)
    }
    @Test fun emptyAndOutOfRangeRequestsRejectExplicitly() {
        for (id in listOf(-1, 0, Int.MAX_VALUE)) production(emptyList()).use {
            reject(it, it.cycles, InvalidReason.HALF_EDGE, id)
        }
        horizontal().use { reject(it, it.cycles, InvalidReason.HALF_EDGE, 2) }
    }
    @Test fun forgedZeroLengthGroupRejects() {
        horizontal().use { f ->
            val g = f.cycles.adjacency.edgeGroups[0]
            reject(f, altered(f, groups = listOf(g.copy(endVertexId = g.startVertexId, end = g.start))),
                InvalidReason.ZERO_LENGTH)
        }
    }
    @Test fun malformedSelectedIdsEndpointsTwinsAndGroupAssociationsReject() {
        for (kind in 0..6) horizontal().use { f ->
            val edges = f.cycles.adjacency.halfEdges.toMutableList()
            edges[0] = when (kind) {
                0 -> edges[0].copy(id = 99)
                1 -> edges[0].copy(twin = 99)
                2 -> edges[0].copy(origin = -1)
                3 -> edges[0].copy(destination = 99)
                4 -> edges[0].copy(edgeGroupIndex = 99)
                5 -> edges[0].copy(canonicalForward = false)
                else -> edges[0]
            }
            if (kind == 6) edges[1] = edges[1].copy(twin = 1)
            reject(f, altered(f, halfEdges = edges), InvalidReason.HALF_EDGE)
        }
    }
    @Test fun inconsistentGroupEndpointsAndInvalidOtherGroupIdsReject() {
        for (other in listOf(false, true)) horizontal(listOf(1.0)).use { f ->
            val groups = f.cycles.adjacency.edgeGroups.toMutableList()
            val i = if (other) 1 else 0
            groups[i] = if (other) groups[i].copy(endVertexId = 99) else groups[i].copy(end = groups[i].start)
            reject(f, altered(f, groups = groups),
                if (other) InvalidReason.GRAPH_ASSOCIATION else InvalidReason.ZERO_LENGTH)
        }
    }
    @Test fun unequalGroupAndInternedCoordinatesReject() {
        horizontal().use { f ->
            val groups = known(f.owner.exact {
                listOf(f.cycles.adjacency.edgeGroups[0].copy(end = point(rational(3), rational(0))))
            })
            reject(f, altered(f, groups = groups), InvalidReason.GRAPH_ASSOCIATION)
        }
    }
    @Test fun foreignExactCoordinateIsRejectedWithoutExecutingForeignOwner() {
        var armed = false
        val budget = SelectionQueryBudget(cancellationCheckpoint = { if (armed) throw CancellationException() })
        val foreign = prepare(emptyList(), budget)
        try {
            val point = known(foreign.exact { point(rational(0), rational(0)) })
            horizontal().use { f ->
                val vertices = f.cycles.adjacency.vertices.toMutableList()
                vertices[0] = vertices[0].copy(point = point)
                val before = SelectionQueryResource.entries.map { budget.used(it) }
                armed = true
                reject(f, altered(f, vertices = vertices), InvalidReason.OWNER_MISMATCH)
                assertEquals(before, SelectionQueryResource.entries.map { budget.used(it) })
                armed = false; assertEquals(7, known(foreign.exact { 7 }))
            }
        } finally { foreign.close() }
    }
    @Test fun crossingAtSelectedMidpointRejects() {
        synthetic {
            listOf(point(p(0, 0)) to point(p(2, 0)), point(p(1, -1)) to point(p(1, 1)))
        }.use { reject(it, it.cycles, InvalidReason.MIDPOINT_CONTACT) }
    }
    @Test fun probeOverlapBeginningAtMidpointRejects() {
        synthetic {
            listOf(point(p(0, 0)) to point(p(2, 0)), point(p(1, 0)) to point(p(1, 1)))
        }.use { reject(it, it.cycles, InvalidReason.MIDPOINT_CONTACT) }
    }
    @Test fun closedPrimaryAndClosedBorrowerHaveTypedFailures() {
        horizontal().use { f ->
            f.owner.close()
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),
                SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected))
        }
        horizontal().use { primary ->
            horizontal().use { foreign ->
                foreign.owner.close()
                reject(primary, foreign.cycles, InvalidReason.OWNER_CLOSED)
            }
        }
    }
    @Test fun foreignAndForgedNestedOwnersNeverRunForeignCancellation() {
        for (kind in 0..2) for (sticky in listOf(false, true)) {
            var armed = false; var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
            horizontal().use { primary ->
                horizontal(b = b).use { foreign ->
                    val c = foreign.cycles
                    val supplied = when (kind) {
                        0 -> c
                        1 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner, c.permutation, c.walks, c.halfEdgeToWalk)
                        else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                            SelectionLassoSuccessorPermutation.Borrowed(primary.owner, c.adjacency, c.permutation.links),
                            c.walks, c.halfEdgeToWalk)
                    }
                    if (sticky) {
                        val remaining = b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) -
                            b.used(SelectionQueryResource.WORK_UNITS)
                        assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS, remaining + 1))
                    }
                    val counters = SelectionQueryResource.entries.map { b.used(it) }
                    val failure = b.failure; val calls = callbacks
                    val records = c.adjacency.edgeGroups
                    armed = true
                    reject(primary, supplied, InvalidReason.OWNER_MISMATCH)
                    assertEquals(calls, callbacks); assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                    assertSame(failure, b.failure); assertSame(records, c.adjacency.edgeGroups)
                    armed = false
                    if (!sticky) assertEquals(7, known(foreign.owner.exact { 7 }))
                }
            }
        }
    }
    @Test fun evidenceBorrowerRejectsAccessAfterOwnerCloses() {
        horizontal().use { f ->
            val e = witness(f)
            f.owner.close()
            assertThrows(IllegalStateException::class.java) { e.record }
            assertThrows(IllegalStateException::class.java) { e.cycles }
            assertThrows(IllegalStateException::class.java) { e.exact { rational(0) } }
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun finiteBinaryCoordinatesCanExhaustExactBitsWithoutApproximation() {
        synthetic {
            listOf(point(p(0, 0)) to point(p(Double.MIN_VALUE, Double.MAX_VALUE)))
        }.use { f ->
            val r = SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)
            assertEquals(SelectionBudgetConstraint.EXACT_BITS, failure(r).constraint)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    private fun kernelNow() = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionExactSegmentIntersection") && it.methodName == "construct"
    }
    @Test fun workExhaustionDuringIntersectionScanningPublishesNothing() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && kernelNow()) {
                injected = true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        horizontal(listOf(1.0), b).use { f ->
            val before = b.used(SelectionQueryResource.WORK_UNITS)
            armed = true
            assertEquals(SelectionQueryResource.WORK_UNITS,
                failure(SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)).resource)
            assertTrue(injected); assertTrue(b.used(SelectionQueryResource.WORK_UNITS) > before)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.cycles.walks }
        }
    }
    @Test fun representationExhaustionReleasesTheWholeArena() {
        val probe = SelectionQueryBudget()
        val retained: Long
        horizontal(listOf(1.0), probe).use { witness(it); retained = probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val limits = SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) retained - 1 else it.queryCeiling
        })
        val b = SelectionQueryBudget(limits)
        horizontal(listOf(1.0), b).use { f ->
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES,
                failure(SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)).resource)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun cancellationDuringScanPropagatesAndDisposesPrimary() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = { if (armed && kernelNow()) throw CancellationException() })
        horizontal(listOf(1.0), b).use { f ->
            armed = true
            assertThrows(CancellationException::class.java) {
                SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)
            }
            assertNull(b.failure); assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.cycles.walks }
        }
    }
    @Test fun cancellationDuringFinalWitnessArithmeticPublishesNothing() {
        var armed = false; var injected = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            val stack = Thread.currentThread().stackTrace
            if (armed && !kernelNow() &&
                stack.any { it.className.contains("SelectionLassoSideWitnessEvidence") } &&
                stack.none { it.className.contains("SelectionLassoSideWitnessEvidence") &&
                    (it.methodName == "validateAndProbe" || it.methodName == "obstructionParameter") } &&
                stack.any { it.className.endsWith("SelectionExactArithmetic") && it.methodName == "multiply" }) {
                injected = true
                throw CancellationException()
            }
        })
        horizontal(listOf(1.0), b).use { f ->
            armed = true
            assertThrows(CancellationException::class.java) {
                SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)
            }
            assertTrue(injected); assertNull(b.failure)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun stickyFailureNeverBecomesAnEmptyOrSuccessfulCertificate() {
        horizontal().use { f ->
            val b = f.budget
            val remaining = b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) -
                b.used(SelectionQueryResource.WORK_UNITS)
            val sticky = b.charge(SelectionQueryResource.WORK_UNITS, remaining + 1)
            assertNotNull(sticky)
            assertSame(sticky, failure(SelectionLassoSideWitnessEvidence.build(f.owner, f.cycles, f.selected)))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
}
