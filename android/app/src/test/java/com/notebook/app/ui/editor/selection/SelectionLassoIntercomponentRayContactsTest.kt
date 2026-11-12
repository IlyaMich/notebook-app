package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.Borrowed
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.ContactKind
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.FirstContact
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.IsolatedVertex
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.NoOtherComponentContact
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.InvalidReason
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts.Result
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoIntercomponentRayContactsTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private data class Fixture(
        val owner: Prepared, val exterior: SelectionLassoExteriorSectorEvidence.Borrowed,
        val budget: SelectionQueryBudget,
    ) : AutoCloseable {
        val components get() = exterior.components
        val cycles get() = components.cycles
        override fun close() = owner.close()
    }
    private fun finish(
        owner: Prepared, g: SelectionLassoCoincidentGeometry.Borrowed, b: SelectionQueryBudget,
        forgedAdmission: Boolean = false,
    ): Fixture {
        val a = (SelectionLassoIncidentAdjacency.build(owner, g) as SelectionLassoIncidentAdjacency.Result.Ready).value
        val s = (SelectionLassoSuccessorPermutation.build(owner, a) as SelectionLassoSuccessorPermutation.Result.Ready).value
        val c = (SelectionLassoSuccessorCycles.build(owner, s) as SelectionLassoSuccessorCycles.Result.Ready).value
        val e = if (forgedAdmission) {
            val n = a.vertices.size.toLong(); val count = g.edges.size.toLong()
            SelectionLassoEmbeddingAdmission.Borrowed(owner, c,
                SelectionLassoEmbeddingAdmission.Record(n.toInt(), count.toInt(),
                    count * (count - 1) / 2, count * (n - 2)))
        } else {
            val admitted = SelectionLassoEmbeddingAdmission.build(owner, c)
            assertTrue("Fixture admission failed: $admitted", admitted is SelectionLassoEmbeddingAdmission.Result.Ready)
            (admitted as SelectionLassoEmbeddingAdmission.Result.Ready).value
        }
        val components = (SelectionLassoGraphComponents.build(owner, e) as SelectionLassoGraphComponents.Result.Ready).value
        val sectors = (SelectionLassoExteriorSectorEvidence.build(owner, components) as SelectionLassoExteriorSectorEvidence.Result.Ready).value
        return Fixture(owner, sectors, b)
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(input, b))
        val atomic = (SelectionLassoAtomicEdges.build(owner) as SelectionLassoAtomicEdges.Result.Ready).value
        val g = (SelectionLassoCoincidentGeometry.build(owner, atomic) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return finish(owner, g, b)
    }
    private data class Geometry(val points: List<Point>, val edges: List<Pair<Int, Int>>)
    private fun synthetic(
        b: SelectionQueryBudget = SelectionQueryBudget(), forgedAdmission: Boolean = false,
        geometry: SelectionExactArithmetic.() -> Geometry,
    ): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
        val g = known(owner.exact {
            val data = geometry()
            val sorted = data.points.sortedWith { a, c -> when (compare(a, c)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices = sorted.mapIndexed { i, q -> Vertex(i, q) }
            fun index(i: Int) = sorted.indexOfFirst { compare(it, data.points[i]) == Sign.ZERO }
            val edges = data.edges.map { (a, c) ->
                val lo = minOf(index(a), index(c)); val hi = maxOf(index(a), index(c))
                EdgeGroup(lo, hi, sorted[lo], sorted[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId })
            SelectionLassoCoincidentGeometry.Borrowed(owner, vertices, edges)
        })
        return finish(owner, g, b, forgedAdmission)
    }
    private fun graph(
        points: List<DuPoint>, edges: List<Pair<Int, Int>>, b: SelectionQueryBudget = SelectionQueryBudget(),
        forgedAdmission: Boolean = false,
    ) = synthetic(b, forgedAdmission) { Geometry(points.map { point(it) }, edges) }
    private fun crossing(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(0, -1), p(0, 1), p(4, 0), p(4, 1)), listOf(0 to 1, 2 to 3), b)
    private fun vertexTie(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(-1, -1), p(-1, 1), p(0, 0), p(4, 0), p(4, 2)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4), b)
    private fun build(f: Fixture): Borrowed {
        val r = SelectionLassoIntercomponentRayContacts.build(f.owner, f.exterior)
        assertTrue("Expected Ready: $r", r is Result.Ready)
        return (r as Result.Ready).value
    }
    private fun fraction(r: SelectionExactArithmetic.Rational) = "${r.numerator}/${r.denominator}"
    private fun xy(p: Point) = "${fraction(p.x)},${fraction(p.y)}"
    private fun failure(r: Result): SelectionWorkloadFailure {
        assertTrue("Expected unresolved: $r", r is Result.Unresolved)
        val cause = (r as Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }
    private fun check(f: Fixture, r: Borrowed) {
        assertSame(f.exterior, r.exterior); assertSame(f.components, r.components)
        assertEquals(f.components.componentCount, r.records.size)
        for ((id, record) in r.records.withIndex()) {
            assertEquals(id, record.sourceComponentId)
            assertEquals(f.components.components[id].minimumVertexId, record.minimumVertexId)
            if (record is IsolatedVertex) continue
            val end = when (record) {
                is FirstContact -> record.probeEnd
                is NoOtherComponentContact -> record.probeEnd
                else -> error("Unexpected")
            }
            known(f.owner.exact {
                for (v in f.cycles.adjacency.vertices) assertEquals(Sign.NEGATIVE, compare(end.x, v.point.x))
            })
            assertEquals(f.cycles.adjacency.edgeGroups.size.toLong() -
                f.components.components[id].edgeGroupCount, record.examinedOtherEdges)
            if (record is FirstContact) {
                assertTrue(record.contacts.isNotEmpty())
                assertEquals(record.contacts.map { it.edgeGroupId }.sorted(), record.contacts.map { it.edgeGroupId })
                known(f.owner.exact {
                    assertEquals(Sign.POSITIVE, sign(record.probeParameter))
                    for (contact in record.contacts) {
                        assertEquals(Sign.ZERO, compare(contact.probeParameter, record.probeParameter))
                        assertEquals(Sign.ZERO, compare(contact.point, record.point))
                        assertNotEquals(id, contact.otherComponentId)
                    }
                })
            }
        }
    }
    private fun reject(f: Fixture, reason: InvalidReason, source: SelectionLassoExteriorSectorEvidence.Borrowed = f.exterior) {
        val result = SelectionLassoIntercomponentRayContacts.build(f.owner, source)
        assertEquals(Result.InvalidInput(reason), result)
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { f.owner.segments }
    }
    private fun wrapComponents(
        owner: Prepared, source: SelectionLassoGraphComponents.Borrowed,
        admission: SelectionLassoEmbeddingAdmission.Borrowed = source.admission,
        vertices: List<Int> = source.vertexToComponent,
    ) = SelectionLassoGraphComponents.Borrowed(owner, admission, source.components, vertices,
        source.edgeGroupToComponent, source.halfEdgeToComponent, source.walkToComponent)

    @Test fun authenticationIsPureOwnerBoundAndSyntheticConstructorsCannotMintIt() {
        var armed = false; var callbacks = 0
        val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
        vertexTie(b).use { f ->
            val genuine = build(f)
            val other = known(SelectionLassoSplitEvents.prepare(emptyList(), SelectionQueryBudget()))
            try {
                val clone = SelectionLassoIntercomponentRayContacts.Borrowed(f.owner, genuine.exterior, genuine.records)
                val subclass = object : SelectionLassoIntercomponentRayContacts.Borrowed(
                    f.owner, genuine.exterior, genuine.records) {}
                val before = callbacks
                val counters = SelectionQueryResource.entries.map { b.used(it) }
                val failure = b.failure
                armed = true
                assertTrue(genuine.isAuthenticatedFor(f.owner))
                assertFalse(genuine.isAuthenticatedFor(other))
                assertFalse(clone.isAuthenticatedFor(f.owner))
                assertFalse(subclass.isAuthenticatedFor(f.owner))
                assertEquals(before, callbacks)
                assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                assertSame(failure, b.failure)
                f.owner.close()
                assertTrue(genuine.isAuthenticatedFor(f.owner)) // provenance is separate from liveness
                assertEquals(before, callbacks)
                assertThrows(IllegalStateException::class.java) { genuine.records }
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            } finally { other.close() }
        }
    }
    @Test fun authenticatedInventoryIsImmutableAndCannotBeTransferredToSyntheticReplacement() {
        vertexTie().use { f ->
            val genuine = build(f)
            val count = genuine.records.size
            val replacement = genuine.records.toMutableList()
            val fake = SelectionLassoIntercomponentRayContacts.Borrowed(f.owner, genuine.exterior, replacement)
            replacement.clear()
            assertFalse(fake.isAuthenticatedFor(f.owner))
            assertTrue(genuine.isAuthenticatedFor(f.owner))
            assertEquals(count, genuine.records.size)
            assertThrows(UnsupportedOperationException::class.java) { (genuine.records as MutableList).clear() }
            val contact = genuine.records.filterIsInstance<FirstContact>().single()
            assertThrows(UnsupportedOperationException::class.java) { (contact.contacts as MutableList).clear() }
        }
    }

    @Test fun emptyAndIsolatedInventNoProbe() {
        production(emptyList()).use { assertTrue(build(it).records.isEmpty()) }
        graph(listOf(p(0, 0), p(1, 1)), emptyList()).use {
            val r = build(it); check(it, r)
            assertEquals(listOf(IsolatedVertex(0, 0), IsolatedVertex(1, 1)), r.records)
        }
    }
    @Test fun connectedBowTieFigureEightAndTreesHaveNoForeignContact() {
        val inputs = listOf(listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0)),
            listOf(p(0, 0), p(2, 0), p(1, 2), p(0, 0), p(-2, 0), p(-1, 2)),
            listOf(p(0, 0), p(2, 0), p(0, 0), p(1, 2)))
        for (input in inputs) production(input).use {
            val r = build(it); check(it, r)
            assertTrue(r.records.single() is NoOtherComponentContact)
            assertEquals(0L, r.records.single().examinedOtherEdges)
        }
    }
    @Test fun interiorCrossingHasExactFourFifthsParameter() {
        crossing().use {
            val r = build(it); check(it, r)
            assertTrue(r.records[0] is NoOtherComponentContact)
            val first = r.records[1] as FirstContact
            assertEquals("0/1,0/1", xy(first.point))
            assertEquals("4/5", fraction(first.probeParameter))
            assertEquals(ContactKind.EDGE_INTERIOR, first.contacts.single().kind)
            assertEquals(0, first.contacts.single().otherComponentId)
        }
    }
    @Test fun vertexTouchRetainsAllNearestEdgesAndExcludesFartherCrossing() {
        vertexTie().use {
            val r = build(it); check(it, r)
            val first = r.records[1] as FirstContact
            assertEquals("0/1,0/1", xy(first.point))
            assertEquals("2/3", fraction(first.probeParameter))
            assertEquals(listOf(1, 2), first.contacts.map { c -> c.edgeGroupId })
            assertTrue(first.contacts.all { c -> c.kind == ContactKind.EDGE_ENDPOINT })
        }
    }
    @Test fun oneEndpointContactIsDistinctFromInterior() {
        graph(listOf(p(0, 0), p(0, 1), p(3, 0), p(3, 1)), listOf(0 to 1, 2 to 3)).use {
            val r = build(it); check(it, r)
            assertEquals(ContactKind.EDGE_ENDPOINT, (r.records[1] as FirstContact).contacts.single().kind)
        }
    }
    @Test fun horizontalOverlapPreservesReversedDirectedProbeInterval() {
        graph(listOf(p(0, 0), p(2, 0), p(4, 0), p(4, 1)), listOf(0 to 1, 2 to 3)).use {
            val r = build(it); check(it, r)
            val first = r.records[1] as FirstContact
            assertEquals("2/1,0/1", xy(first.point))
            assertEquals("2/5", fraction(first.probeParameter))
            val contact = first.contacts.single()
            assertEquals(ContactKind.COLLINEAR_OVERLAP, contact.kind)
            assertEquals("4/5", fraction(contact.overlap!!.firstProbeParameter))
            assertEquals("2/5", fraction(contact.overlap.lastProbeParameter))
        }
    }
    @Test fun overlapAndEndpointAtTheSameNearestVertexAreBothRetained() {
        graph(listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2), p(4, 0), p(4, 1)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5)).use {
            val r = build(it); check(it, r)
            val first = r.records[1] as FirstContact
            assertEquals("2/1,0/1", xy(first.point))
            assertEquals("2/5", fraction(first.probeParameter))
            assertEquals(2, first.contacts.size)
            assertEquals(setOf(ContactKind.COLLINEAR_OVERLAP, ContactKind.EDGE_ENDPOINT),
                first.contacts.map { c -> c.kind }.toSet())
        }
    }
    @Test fun severalCollinearIntervalsChooseNearestAndDifferentSourcesDiffer() {
        graph(listOf(p(0, 0), p(1, 0), p(2, 0), p(3, 0), p(5, 0), p(5, 1)),
            listOf(0 to 1, 2 to 3, 4 to 5)).use {
            val r = build(it); check(it, r)
            assertTrue(r.records[0] is NoOtherComponentContact)
            assertEquals("1/1,0/1", xy((r.records[1] as FirstContact).point))
            assertEquals("3/1,0/1", xy((r.records[2] as FirstContact).point))
            assertEquals(1, (r.records[2] as FirstContact).contacts.single().otherComponentId)
        }
    }
    @Test fun nearestOfSeveralInteriorCrossingsWins() {
        graph(listOf(p(0, -1), p(0, 1), p(2, -1), p(2, 1), p(5, 0), p(5, 1)),
            listOf(0 to 1, 2 to 3, 4 to 5)).use {
            val r = build(it); check(it, r)
            assertEquals("2/1,0/1", xy((r.records[2] as FirstContact).point))
            assertEquals("1/2", fraction((r.records[2] as FirstContact).probeParameter))
        }
    }
    @Test fun subUlpExactContactsNeverCollapseIntoATie() {
        synthetic {
            val near = add(rational(1), rational(1, 1L shl 60))
            Geometry(listOf(point(rational(1), rational(-1)), point(rational(1), rational(1)),
                point(near, rational(-1)), point(near, rational(1)),
                point(rational(2), rational(0)), point(rational(2), rational(1))),
                listOf(0 to 1, 2 to 3, 4 to 5))
        }.use {
            val r = build(it); check(it, r)
            val first = r.records[2] as FirstContact
            assertEquals(1, first.contacts.size)
            assertEquals(1, first.contacts.single().otherComponentId)
            known(it.owner.exact {
                assertEquals(Sign.ZERO, compare(first.probeParameter,
                    rational((1L shl 60) - 1, 1L shl 61)))
            })
        }
    }
    @Test fun disjointPolygonsWithSeparatedYRangesHaveNoContact() {
        graph(listOf(p(0, 0), p(2, 0), p(0, 2), p(5, 5), p(7, 5), p(5, 7)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3)).use {
            val r = build(it); check(it, r)
            assertTrue(r.records.all { e -> e is NoOtherComponentContact })
        }
    }
    @Test fun disjointPolygonsCanObstructEachOthersWestRay() {
        graph(listOf(p(0, -1), p(2, -1), p(2, 3), p(0, 3),
            p(5, 0), p(7, 0), p(5, 2)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 4)).use {
            val r = build(it); check(it, r)
            assertEquals("2/1,0/1", xy((r.records[1] as FirstContact).point))
        }
    }
    @Test fun nestedTrianglesReportContactsWithoutContainmentLabels() {
        graph(listOf(p(0, 0), p(10, 0), p(0, 10), p(1, 1), p(2, 1), p(1, 2)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4, 4 to 5, 5 to 3)).use {
            val r = build(it); check(it, r)
            assertEquals("0/1,1/1", xy((r.records[1] as FirstContact).point))
        }
    }
    @Test fun threeNestedRectanglesReturnNearestInventoryEdgesOnly() {
        graph(listOf(p(0, 0), p(10, 0), p(10, 10), p(0, 10),
            p(2, 2), p(4, 2), p(4, 4), p(2, 4),
            p(3, 2.5), p(3.5, 2.5), p(3.5, 3), p(3, 3)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4,
                8 to 9, 9 to 10, 10 to 11, 11 to 8)).use {
            val r = build(it); check(it, r)
            assertEquals("0/1,2/1", xy((r.records[1] as FirstContact).point))
            assertEquals("2/1,5/2", xy((r.records[2] as FirstContact).point))
        }
    }
    @Test fun treesAndIsolatedVerticesDoNotInventEdgeContacts() {
        graph(listOf(p(0, -1), p(0, 1), p(1, 1), p(4, 0), p(4, 2), p(9, 9)),
            listOf(0 to 1, 1 to 2, 3 to 4)).use {
            val r = build(it); check(it, r)
            assertEquals(1, r.records.count { e -> e is IsolatedVertex })
            assertEquals("0/1,0/1", xy((r.records[1] as FirstContact).point))
        }
    }
    @Test fun veryLargeAndSmallBinaryCoordinatesStayExact() {
        for (scale in listOf(Math.scalb(1.0, -400), Math.scalb(1.0, 400))) {
            graph(listOf(p(0, -scale), p(0, scale), p(4 * scale, 0), p(4 * scale, scale)),
                listOf(0 to 1, 2 to 3)).use {
                val r = build(it); check(it, r)
                assertEquals("0/1,0/1", xy((r.records[1] as FirstContact).point))
            }
        }
    }
    @Test fun sourceReversalAndRotationKeepEquivalentContactInventory() {
        val input = listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0))
        val signatures = ArrayList<String>()
        for (walk in listOf(input, input.reversed(), input.drop(1) + input.take(1))) production(walk).use {
            val r = build(it); check(it, r)
            val e = r.records.single() as NoOtherComponentContact
            signatures.add("${e.sourceComponentId}:${e.minimumVertexId}:${e.sourceWalkId}:${xy(e.probeEnd)}")
        }
        assertEquals(1, signatures.distinct().size)
    }
    @Test fun deterministicImmutableTiesPreserveAllOriginalStructuresAndTopologyQuotas() {
        val signatures = ArrayList<String>(); val work = ArrayList<Long>()
        repeat(3) {
            vertexTie().use { f ->
                val groups = f.cycles.adjacency.edgeGroups; val vertices = f.cycles.adjacency.vertices
                val links = f.cycles.permutation.links; val walks = f.cycles.walks
                val occurrences = groups.flatMap { it.occurrences }
                val resources = listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,
                    SelectionQueryResource.ARRANGEMENT_EDGES, SelectionQueryResource.ARRANGEMENT_FACES)
                val before = resources.map { f.budget.used(it) }
                val r = build(f)
                val e = r.records[1] as FirstContact
                signatures.add("${fraction(e.probeParameter)}:${xy(e.point)}:${e.contacts.map { it.edgeGroupId }}")
                work.add(f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertSame(groups, r.components.cycles.adjacency.edgeGroups)
                assertSame(vertices, r.components.cycles.adjacency.vertices)
                assertSame(links, r.components.cycles.permutation.links)
                assertSame(walks, r.components.cycles.walks)
                assertEquals(occurrences, groups.flatMap { it.occurrences })
                assertEquals(before, resources.map { f.budget.used(it) })
                assertThrows(UnsupportedOperationException::class.java) { (e.contacts as MutableList).clear() }
                assertThrows(UnsupportedOperationException::class.java) { (r.records as MutableList).clear() }
            }
        }
        assertEquals(1, signatures.distinct().size); assertEquals(1, work.distinct().size)
    }
    @Test fun samePointAtSourceFromForgedSeparatedGraphIsRejected() {
        graph(listOf(p(0, 0), p(2, 0), p(1, 0), p(1, 1)),
            listOf(0 to 1, 2 to 3), forgedAdmission = true).use {
            reject(it, InvalidReason.CONTACT)
        }
    }
    @Test fun horizontalOverlapStartingAtSourceIsContradictoryContact() {
        // Foreign horizontal interior contains a source vertex; the forged certificate must
        // not let a zero probe parameter become a successful nearest contact.
        graph(listOf(p(0, 0), p(2, 0), p(1, 0), p(1, -1)),
            listOf(0 to 1, 2 to 3), forgedAdmission = true).use {
            // The source minimum is (1,-1); its west ray is clear. Use a source with minimum
            // at the interior contact by changing the vertical endpoint to positive Y instead.
            val r = build(it)
            assertTrue(r.records[1] is NoOtherComponentContact)
        }
        graph(listOf(p(0, 0), p(2, 0), p(1, 0), p(1, 2)),
            listOf(0 to 1, 2 to 3), forgedAdmission = true).use {
            reject(it, InvalidReason.CONTACT)
        }
    }
    @Test fun invalidExteriorRecordIdsAndTransitionFieldsRejectBeforeScan() {
        for (kind in 0..4) crossing().use { f ->
            val records = f.exterior.records.toMutableList()
            val e = records[0] as SelectionLassoExteriorSectorEvidence.OutwardSector
            records[0] = when (kind) {
                0 -> e.copy(componentId = 99)
                1 -> e.copy(minimumVertexId = 99)
                2 -> e.copy(candidateWalkId = 99)
                3 -> e.copy(previousOutgoing = 99)
                else -> e.copy(incomingTwin = 99)
            }
            reject(f, InvalidReason.EXTERIOR_SOURCE,
                SelectionLassoExteriorSectorEvidence.Borrowed(f.owner, f.components, records))
        }
    }
    @Test fun incorrectComponentMappingAndMalformedEdgeGroupReject() {
        for (kind in 0..1) crossing().use { f ->
            val source = if (kind == 0) wrapComponents(f.owner, f.components,
                vertices = List(f.components.vertexToComponent.size) { 99 }) else {
                val c = f.cycles; val a = c.adjacency
                val groups = listOf(a.edgeGroups[0].copy(endVertexId = 99)) + a.edgeGroups.drop(1)
                val g = SelectionLassoCoincidentGeometry.Borrowed(f.owner, a.vertices, groups)
                val adjacency = SelectionLassoIncidentAdjacency.Borrowed(f.owner, g, a.halfEdges, a.incidents)
                val s = SelectionLassoSuccessorPermutation.Borrowed(f.owner, adjacency, c.permutation.links)
                val cycles = SelectionLassoSuccessorCycles.Borrowed(f.owner, s, c.walks, c.halfEdgeToWalk)
                val admission = SelectionLassoEmbeddingAdmission.Borrowed(f.owner, cycles, f.components.admission.record)
                wrapComponents(f.owner, f.components, admission)
            }
            reject(f, InvalidReason.EXTERIOR_SOURCE,
                SelectionLassoExteriorSectorEvidence.Borrowed(f.owner, source, f.exterior.records))
        }
    }
    @Test fun foreignAndNestedOwnersWithArmedCallbacksRemainUnmodified() {
        for (kind in 0..5) for (empty in listOf(false, true)) for (sticky in listOf(false, true)) {
            var armed = false; var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
            production(if (empty) emptyList() else listOf(p(0, 0), p(1, 0))).use { primary ->
                production(if (empty) emptyList() else listOf(p(0, 0), p(1, 0)), b).use { foreign ->
                    val fc = foreign.components; val c = foreign.cycles
                    val nestedCycle = when (kind) {
                        0, 1, 2, 3 -> c
                        4 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner, c.permutation, c.walks, c.halfEdgeToWalk)
                        else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                            SelectionLassoSuccessorPermutation.Borrowed(primary.owner, c.adjacency, c.permutation.links),
                            c.walks, c.halfEdgeToWalk)
                    }
                    val nestedAdmission = if (kind <= 2) fc.admission else
                        SelectionLassoEmbeddingAdmission.Borrowed(primary.owner, nestedCycle, fc.admission.record)
                    val nestedComponents = if (kind <= 1) fc else wrapComponents(primary.owner, fc, nestedAdmission)
                    val supplied = if (kind == 0) foreign.exterior else
                        SelectionLassoExteriorSectorEvidence.Borrowed(primary.owner, nestedComponents, foreign.exterior.records)
                    if (sticky) assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,
                        b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1))
                    val counters = SelectionQueryResource.entries.map { b.used(it) }
                    val calls = callbacks; val failure = b.failure; val retained = foreign.exterior.records
                    armed = true
                    reject(primary, InvalidReason.OWNER_MISMATCH, supplied)
                    assertEquals(calls, callbacks); assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                    assertSame(failure, b.failure); assertSame(retained, foreign.exterior.records)
                    armed = false
                    if (!sticky) assertEquals(7, known(foreign.owner.exact { 7 }))
                }
            }
        }
    }
    @Test fun closedOwnersAndEvidenceAccessAreRejected() {
        crossing().use {
            val r = build(it)
            it.owner.close()
            assertThrows(IllegalStateException::class.java) { r.exterior }
            assertThrows(IllegalStateException::class.java) { r.components }
            assertThrows(IllegalStateException::class.java) { r.records }
            assertThrows(IllegalStateException::class.java) { r.exact { rational(0) } }
            assertEquals(Result.InvalidInput(InvalidReason.OWNER_CLOSED),
                SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior))
        }
        crossing().use { primary ->
            crossing().use { foreign ->
                foreign.owner.close()
                reject(primary, InvalidReason.OWNER_CLOSED, foreign.exterior)
            }
        }
    }
    private fun phase(name: String) = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoIntercomponentRayContacts") && it.methodName.substringBefore('$') == name
    }
    @Test fun workExhaustionDuringScanningNeverPublishesNoContact() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && phase("scan")) {
                injected = true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        crossing(b).use {
            armed = true
            assertEquals(SelectionQueryResource.WORK_UNITS,
                failure(SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior)).resource)
            assertTrue(injected); assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun exactBitsAdmissionFailureDuringProbeConstructionIsTyped() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && phase("globalCap")) {
                injected = true
                b.checkExactBits(b.limits.exactBits.toLong() + 1)
            }
        })
        crossing(b).use {
            armed = true
            assertEquals(SelectionBudgetConstraint.EXACT_BITS,
                failure(SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior)).constraint)
            assertTrue(injected); assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun representationExhaustionReleasesRetainedInventoryAndOwner() {
        val probe = SelectionQueryBudget()
        val retained: Long
        vertexTie(probe).use { build(it); retained = probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b = SelectionQueryBudget(SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) retained - 1 else it.queryCeiling
        }))
        vertexTie(b).use {
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES,
                failure(SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior)).resource)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun cancellationDuringScanTieCollectionAndPublicationPropagates() {
        for (target in listOf("scan", "collect", "publish")) {
            var armed = false; var injected = false; var collections = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = {
                if (armed && phase(target)) {
                    val collectionEntry = Thread.currentThread().stackTrace.any {
                        it.className.endsWith("SelectionExactArithmetic") && it.methodName.startsWith("chargeIncidentLassoWork")
                    }
                    // First candidate is farther; second replaces it; third is an exact tie.
                    val cancel = target != "collect" || collectionEntry && ++collections == 3
                    if (cancel) { injected = true; throw CancellationException() }
                }
            })
            vertexTie(b).use {
                armed = true
                assertThrows(CancellationException::class.java) {
                    SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior)
                }
                assertTrue(injected); assertNull(b.failure)
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java) { it.owner.segments }
            }
        }
    }
    @Test fun stickyPreviousFailureCannotBecomeEmptySuccess() {
        crossing().use {
            val b = it.budget
            val sticky = b.charge(SelectionQueryResource.WORK_UNITS,
                b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1)
            assertNotNull(sticky)
            assertSame(sticky, failure(SelectionLassoIntercomponentRayContacts.build(it.owner, it.exterior)))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
}
