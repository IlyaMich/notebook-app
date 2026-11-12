package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoContactApproachEvidence as Approach
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts as Rays
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoContactApproachEvidenceTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private fun <T> known(r: SelectionPredicateResult<T>): T {
        assertTrue("Expected known: $r", r is SelectionPredicateResult.Known)
        return (r as SelectionPredicateResult.Known<T>).value
    }
    private data class Fixture(val owner: Prepared, val rays: Rays.Borrowed,
        val budget: SelectionQueryBudget) : AutoCloseable {
        val components get() = rays.components
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
            val n = g.vertices.size.toLong(); val count = g.edges.size.toLong()
            SelectionLassoEmbeddingAdmission.Borrowed(owner, c, SelectionLassoEmbeddingAdmission.Record(
                n.toInt(), count.toInt(), count * (count - 1) / 2, count * (n - 2)))
        } else {
            val r = SelectionLassoEmbeddingAdmission.build(owner, c)
            assertTrue("Fixture admission: $r", r is SelectionLassoEmbeddingAdmission.Result.Ready)
            (r as SelectionLassoEmbeddingAdmission.Result.Ready).value
        }
        val components = (SelectionLassoGraphComponents.build(owner, e) as SelectionLassoGraphComponents.Result.Ready).value
        val exterior = (SelectionLassoExteriorSectorEvidence.build(owner, components) as SelectionLassoExteriorSectorEvidence.Result.Ready).value
        val rays = Rays.build(owner, exterior)
        assertTrue("Fixture rays: $rays", rays is Rays.Result.Ready)
        return Fixture(owner, (rays as Rays.Result.Ready).value, b)
    }
    private fun graph(
        points: List<DuPoint>, pairs: List<Pair<Int, Int>>,
        b: SelectionQueryBudget = SelectionQueryBudget(), forgedAdmission: Boolean = false,
    ): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
        val g = known(owner.exact {
            val original = points.map { point(it) }
            val sorted = original.sortedWith { a, c -> when (compare(a, c)) {
                Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1
            } }
            val vertices = sorted.mapIndexed { i, v -> Vertex(i, v) }
            fun index(i: Int) = sorted.indexOfFirst { compare(it, original[i]) == Sign.ZERO }
            val groups = pairs.map { (a, c) ->
                val lo = minOf(index(a), index(c)); val hi = maxOf(index(a), index(c))
                EdgeGroup(lo, hi, sorted[lo], sorted[hi], emptyList())
            }.sortedWith(compareBy<EdgeGroup> { it.startVertexId }.thenBy { it.endVertexId })
            SelectionLassoCoincidentGeometry.Borrowed(owner, vertices, groups)
        })
        return finish(owner, g, b, forgedAdmission)
    }
    private fun production(input: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(input, b))
        val atomic = (SelectionLassoAtomicEdges.build(owner) as SelectionLassoAtomicEdges.Result.Ready).value
        val g = (SelectionLassoCoincidentGeometry.build(owner, atomic) as SelectionLassoCoincidentGeometry.Result.Ready).value
        return finish(owner, g, b)
    }
    private fun square(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2), p(4, 1), p(4, 2)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5), b)
    private fun tip(b: SelectionQueryBudget = SelectionQueryBudget()) =
        graph(listOf(p(-1, -1), p(-1, 1), p(0, 0), p(4, 0), p(4, 2)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4), b)
    private fun build(f: Fixture): Approach.Borrowed {
        val r = Approach.build(f.owner, f.rays)
        assertTrue("Expected Ready: $r", r is Approach.Result.Ready)
        return (r as Approach.Result.Ready).value
    }
    private fun check(f: Fixture, r: Approach.Borrowed) {
        assertSame(f.rays, r.contacts); assertSame(f.components, r.components)
        assertEquals(f.rays.records.size, r.records.size)
        for ((i, e) in r.records.withIndex()) {
            assertSame(f.rays.records[i], e.original)
            if (e is Approach.ContactApproach) {
                assertEquals(e.targetComponentId, f.components.walkToComponent[e.targetWalkId])
                val sector = when (e) {
                    is Approach.VertexSectorApproach -> e.sector
                    is Approach.OverlapStartApproach -> e.sector
                    else -> null
                }
                if (sector != null) {
                    val links = f.cycles.permutation.links
                    assertEquals(sector.previousOutgoing, links[sector.incomingTwin].next)
                    assertEquals(sector.incomingTwin, links[sector.previousOutgoing].previous)
                    assertEquals(e.targetWalkId, f.cycles.halfEdgeToWalk[sector.incomingTwin])
                    assertEquals(e.targetWalkId, f.cycles.halfEdgeToWalk[sector.previousOutgoing])
                }
            }
        }
    }
    private fun reject(f: Fixture, supplied: Rays.Borrowed, reason: Approach.InvalidReason) {
        val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
        assertEquals(Approach.Result.InvalidInput(reason), Approach.build(f.owner, supplied))
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >= before)
        assertThrows(IllegalStateException::class.java) { f.owner.segments }
    }
    private fun failure(r: Approach.Result): SelectionWorkloadFailure {
        assertTrue("Expected unresolved: $r", r is Approach.Result.Unresolved)
        val cause = (r as Approach.Result.Unresolved).cause
        assertTrue(cause is SelectionPredicateResult.WorkloadExceeded)
        return (cause as SelectionPredicateResult.WorkloadExceeded).failure
    }


    @Test fun completedVariantsAuthenticateWithoutChangingTheirSourceRecords() {
        val variants = mutableSetOf<String>()
        val fixtures = listOf(production(emptyList()),
            graph(listOf(p(0, 0)), emptyList()), square(), tip(),
            graph(listOf(p(0, 0), p(2, 0), p(4, 0), p(4, 1)), listOf(0 to 1, 2 to 3)))
        try {
            for (f in fixtures) {
                val result = build(f)
                assertTrue(result.isAuthenticatedFor(f.owner))
                check(f, result)
                result.records.forEach { variants.add(it.javaClass.simpleName) }
            }
            assertEquals(setOf("IsolatedSource", "NoForeignContact", "EdgeInteriorApproach",
                "VertexSectorApproach", "OverlapStartApproach"), variants)
        } finally { fixtures.forEach { it.close() } }
    }

    @Test fun authenticationIsPureOwnerBoundAndIndependentOfLiveness() {
        var armed = false; var callbacks = 0
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            callbacks++; if (armed) throw CancellationException()
        })
        tip(b).use { f ->
            val result = build(f)
            val foreign = known(SelectionLassoSplitEvents.prepare(emptyList(), SelectionQueryBudget()))
            try {
                val counters = SelectionQueryResource.entries.map { b.used(it) }
                val failure = b.failure; val before = callbacks
                armed = true
                repeat(3) {
                    assertTrue(result.isAuthenticatedFor(f.owner))
                    assertFalse(result.isAuthenticatedFor(foreign))
                }
                assertEquals(before, callbacks)
                assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                assertSame(failure, b.failure)
                assertEquals(7, known(foreign.exact { 7 }))
                f.owner.close()
                val closedCounters = SelectionQueryResource.entries.map { b.used(it) }
                assertTrue(result.isAuthenticatedFor(f.owner))
                assertFalse(result.isAuthenticatedFor(foreign))
                assertEquals(before, callbacks)
                assertEquals(closedCounters, SelectionQueryResource.entries.map { b.used(it) })
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java) { result.records }
                assertThrows(IllegalStateException::class.java) { result.contacts }
                assertThrows(IllegalStateException::class.java) { result.components }
            } finally { foreign.close() }
        }
    }

    @Test fun constructorsSubclassesAndCopiedRecordsCannotTransferAuthentication() {
        tip().use { f ->
            val genuine = build(f)
            val copy = Approach.Borrowed(f.owner, genuine.contacts, genuine.records)
            val subclass = object : Approach.Borrowed(f.owner, genuine.contacts, genuine.records) {}
            assertFalse(copy.isAuthenticatedFor(f.owner))
            assertFalse(subclass.isAuthenticatedFor(f.owner))
            val replacement = genuine.records.toMutableList()
            val mutableCopy = Approach.Borrowed(f.owner, genuine.contacts, replacement)
            replacement.clear()
            assertFalse(mutableCopy.isAuthenticatedFor(f.owner))
            assertTrue(genuine.isAuthenticatedFor(f.owner))
            assertEquals(2, genuine.records.size)
            assertThrows(UnsupportedOperationException::class.java) { (genuine.records as MutableList).clear() }
        }
    }

    @Test fun fabricatedNoContactAndTargetWalkCannotClaimApproachCompletion() {
        tip().use { f ->
            val genuine = build(f)
            val first = (genuine.records[1] as Approach.VertexSectorApproach).original
            val falseNoContact = Rays.NoOtherComponentContact(first.sourceComponentId,
                first.minimumVertexId, first.sourceWalkId, first.probeStart, first.probeEnd,
                first.examinedOtherEdges)
            val noContact = Approach.Borrowed(f.owner, genuine.contacts,
                listOf(genuine.records[0], Approach.NoForeignContact(falseNoContact)))
            val actual = genuine.records[1] as Approach.VertexSectorApproach
            val falseTarget = Approach.Borrowed(f.owner, genuine.contacts, listOf(genuine.records[0],
                actual.copy(sector = actual.sector.copy(targetWalkId = 99))))
            assertTrue(genuine.contacts.isAuthenticatedFor(f.owner))
            assertFalse(noContact.isAuthenticatedFor(f.owner))
            assertFalse(falseTarget.isAuthenticatedFor(f.owner))
            assertTrue(genuine.isAuthenticatedFor(f.owner))
        }
    }

    @Test fun workloadFailureNeverPublishesAuthenticatedApproach() {
        tip().use { f ->
            val remaining = f.budget.limits.query.getValue(SelectionQueryResource.WORK_UNITS) -
                f.budget.used(SelectionQueryResource.WORK_UNITS)
            assertNull(f.budget.charge(SelectionQueryResource.WORK_UNITS, remaining))
            val result = Approach.build(f.owner, f.rays)
            assertTrue(result is Approach.Result.Unresolved)
            assertEquals(SelectionQueryResource.WORK_UNITS, failure(result).resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.owner.segments }
        }
    }

    @Test fun cancellationAtPublicationCannotReturnCompletedApproach() {
        var armed = false; var injected = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && Thread.currentThread().stackTrace.any {
                it.className.contains("SelectionLassoContactApproachEvidence") && it.methodName == "publish"
            }) { injected = true; throw CancellationException() }
        })
        tip(b).use { f ->
            var result: Approach.Result? = null
            armed = true
            assertThrows(CancellationException::class.java) { result = Approach.build(f.owner, f.rays) }
            assertTrue(injected)
            assertNull(result)
            assertNull(b.failure)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertThrows(IllegalStateException::class.java) { f.owner.segments }
        }
    }

    @Test fun emptyIsolatedAndNoContactKeepDistinctVariants() {
        production(emptyList()).use { assertTrue(build(it).records.isEmpty()) }
        graph(listOf(p(0, 0), p(1, 1)), emptyList()).use { f ->
            val r = build(f); check(f, r)
            assertTrue(r.records.all { it is Approach.IsolatedSource })
        }
        production(listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0))).use { f ->
            val r = build(f); check(f, r)
            assertTrue(r.records.single() is Approach.NoForeignContact)
        }
    }
    @Test fun squareExteriorSelectsHandDerivedTwinSevenAndWalkZero() {
        square().use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.EdgeInteriorApproach
            assertEquals(7, e.selectedHalfEdgeId); assertEquals(0, e.targetWalkId)
            assertEquals(0, e.targetComponentId)
            assertEquals(2, e.original.sourceWalkId)
        }
    }
    @Test fun squareInteriorApproachesLeftEdgeViaTwinOneAndBoundedSideWalkOne() {
        graph(listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2), p(1, 1), p(1, 1.5)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.EdgeInteriorApproach
            assertEquals(1, e.selectedHalfEdgeId); assertEquals(1, e.targetWalkId)
        }
    }
    @Test fun triangleNegativeDyChoosesCanonicalFourAndExteriorWalkZero() {
        graph(listOf(p(0, 0), p(2, 0), p(0, 2), p(3, 1), p(3, 1.5)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.EdgeInteriorApproach
            assertEquals(4, e.selectedHalfEdgeId); assertEquals(0, e.targetWalkId)
        }
    }
    @Test fun rationalThirdContactSelectsTwinOfPositiveDyEdge() {
        graph(listOf(p(0, 0), p(1, 3), p(0, 3), p(2, 1), p(2, 2)),
            listOf(0 to 1, 1 to 2, 2 to 0, 3 to 4)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.EdgeInteriorApproach
            assertEquals(3, e.selectedHalfEdgeId); assertEquals(0, e.targetWalkId)
            assertEquals("1", e.original.point.x.numerator.toString())
            assertEquals("3", e.original.point.x.denominator.toString())
        }
    }
    @Test fun vertexTipWraparoundHasHandDerivedIncomingFourToOutgoingThree() {
        tip().use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.VertexSectorApproach
            assertEquals(Approach.Sector(2, 2, 3, 5, 4, 0), e.sector)
            assertNotEquals(f.cycles.halfEdgeToWalk[e.sector.previousOutgoing],
                f.cycles.halfEdgeToWalk[e.sector.nextOutgoing])
        }
    }
    @Test fun degreeOneEndpointHasOneOutgoingBoundAndItsIncomingTwin() {
        graph(listOf(p(0, 0), p(0, 1), p(2, 0), p(2, 1)), listOf(0 to 1, 2 to 3)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.VertexSectorApproach
            assertEquals(Approach.Sector(0, 1, 0, 0, 1, 0), e.sector)
        }
    }
    @Test fun degreeThreeTreeHasHandDerivedSectorWithoutAreaRequirement() {
        graph(listOf(p(0, 0), p(-1, -1), p(-1, 1), p(0, 1), p(3, 0), p(3, 1)),
            listOf(0 to 1, 0 to 2, 0 to 3, 4 to 5)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.VertexSectorApproach
            assertEquals(Approach.Sector(2, 3, 1, 4, 5, 0), e.sector)
        }
    }
    @Test fun splitBowTieTargetChoosesCenterEastSectorOnWalkZero() {
        graph(listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0), p(1, 1), p(3, 1), p(3, 1.5)),
            listOf(0 to 4, 4 to 1, 1 to 2, 2 to 4, 4 to 3, 3 to 0, 5 to 6)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.VertexSectorApproach
            assertEquals(Approach.Sector(2, 4, 8, 10, 11, 0), e.sector)
        }
    }
    @Test fun figureEightTargetRetainsRepeatedBoundaryVertexWithoutSplittingFaces() {
        graph(listOf(p(0, 0), p(-2, 1), p(-2, 2), p(-2, -1), p(-2, -2), p(3, 0), p(3, 1)),
            listOf(0 to 1, 1 to 2, 2 to 0, 0 to 3, 3 to 4, 4 to 0, 5 to 6)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.VertexSectorApproach
            assertEquals(Approach.Sector(4, 4, 3, 11, 10, 0), e.sector)
        }
    }
    @Test fun horizontalBridgeOverlapUsesRightEndpointAndBacktrackingWalk() {
        graph(listOf(p(0, 0), p(2, 0), p(4, 0), p(4, 1)), listOf(0 to 1, 2 to 3)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.OverlapStartApproach
            assertEquals(Approach.Sector(1, 1, 1, 1, 0, 0), e.sector)
            assertEquals("2", e.original.point.x.numerator.toString())
        }
    }
    @Test fun overlapAndPerpendicularEndpointTiesUseOneCompleteVertexSector() {
        graph(listOf(p(0, 0), p(2, 0), p(2, 2), p(0, 2), p(4, 0), p(4, 1)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.OverlapStartApproach
            assertEquals(Approach.Sector(2, 2, 3, 6, 7, 0), e.sector)
            assertEquals(2, e.original.contacts.size)
        }
    }
    @Test fun severalNestedComponentsSelectLocalTargetWalksWithoutParentIds() {
        graph(listOf(p(0, 0), p(10, 0), p(10, 10), p(0, 10),
            p(2, 2), p(4, 2), p(4, 4), p(2, 4), p(3, 2.5), p(3, 3)),
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 8 to 9)).use { f ->
            val r = build(f); check(f, r)
            val inner = r.records[1] as Approach.EdgeInteriorApproach
            val source = r.records[2] as Approach.EdgeInteriorApproach
            assertEquals(1, inner.selectedHalfEdgeId); assertEquals(1, inner.targetWalkId)
            assertEquals(7, source.selectedHalfEdgeId); assertEquals(3, source.targetWalkId)
            assertEquals(1, source.targetComponentId)
        }
    }
    @Test fun tinyAndLargeBinaryCoordinatesKeepTheSameSideRule() {
        for (s in listOf(Math.scalb(1.0, -400), Math.scalb(1.0, 400))) graph(
            listOf(p(0, -s), p(0, s), p(4 * s, 0), p(4 * s, s)), listOf(0 to 1, 2 to 3)).use { f ->
            val r = build(f); check(f, r)
            val e = r.records[1] as Approach.EdgeInteriorApproach
            assertEquals(1, e.selectedHalfEdgeId); assertEquals(0, e.targetWalkId)
        }
    }
    @Test fun deterministicRepeatedBuildsAndSourceTraversalChangesPreserveGeometry() {
        val input = listOf(p(0, 0), p(2, 2), p(0, 2), p(2, 0))
        val signatures = ArrayList<List<Pair<Int, Int>>>()
        for (walk in listOf(input, input.reversed(), input.drop(1) + input.take(1))) production(walk).use { f ->
            val first = build(f); val second = build(f); check(f, first); check(f, second)
            assertEquals(first.records, second.records)
            signatures.add(first.records.map { it.original.sourceComponentId to it.original.minimumVertexId })
            assertEquals(walk, f.owner.suppliedVertices)
        }
        assertEquals(1, signatures.distinct().size)
    }
    @Test fun genuineProcessingDoesNotReplaySegmentIntersectionsOrChangeTopologyQuotas() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && Thread.currentThread().stackTrace.any {
                it.className.contains("SelectionExactSegmentIntersection")
            }) error("Approach builder must not replay the ray scan")
        })
        square(b).use { f ->
            val a = f.cycles.adjacency
            val vertices = a.vertices; val groups = a.edgeGroups; val edges = a.halfEdges
            val walks = f.cycles.walks; val links = f.cycles.permutation.links
            val occurrences = groups.flatMap { it.occurrences }
            val resources = listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,
                SelectionQueryResource.ARRANGEMENT_EDGES, SelectionQueryResource.ARRANGEMENT_FACES)
            val before = resources.map { b.used(it) }
            armed = true
            val r = build(f); check(f, r)
            assertSame(vertices, r.components.cycles.adjacency.vertices)
            assertSame(groups, r.components.cycles.adjacency.edgeGroups)
            assertSame(edges, r.components.cycles.adjacency.halfEdges)
            assertSame(walks, r.components.cycles.walks); assertSame(links, r.components.cycles.permutation.links)
            assertEquals(occurrences, groups.flatMap { it.occurrences })
            assertEquals(before, resources.map { b.used(it) })
            assertThrows(UnsupportedOperationException::class.java) { (r.records as MutableList).clear() }
        }
    }
    @Test fun unauthenticatedSameOwnerRejectionRunsNoArithmeticOrCancellationCallback() {
        var armed = false; var callbacks = 0
        val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
        tip(b).use { f ->
            val fake = Rays.Borrowed(f.owner, f.rays.exterior, f.rays.records)
            val before = callbacks
            val work = b.used(SelectionQueryResource.WORK_UNITS)
            armed = true
            reject(f, fake, Approach.InvalidReason.UNAUTHENTICATED_SOURCE)
            assertEquals(before, callbacks)
            assertEquals(work, b.used(SelectionQueryResource.WORK_UNITS))
            assertNull(b.failure)
        }
    }
    @Test fun fabricatedSameOwnerInventoriesIncludingFalseNoContactAndMissingTiesAreUnauthenticated() {
        for (kind in 0..8) tip().use { f ->
            val inventory = f.rays.records.toMutableList()
            val first = inventory[1] as Rays.FirstContact
            inventory[1] = when (kind) {
                0 -> Rays.NoOtherComponentContact(first.sourceComponentId, first.minimumVertexId,
                    first.sourceWalkId, first.probeStart, first.probeEnd, first.examinedOtherEdges)
                1 -> first.copy(contacts = first.contacts.dropLast(1))
                2 -> first.copy(contacts = emptyList())
                3 -> first.copy(contacts = listOf(first.contacts[0].copy(edgeGroupId = 99)))
                4 -> first.copy(contacts = listOf(first.contacts[0].copy(otherComponentId = 99)))
                5 -> first.copy(contacts = listOf(first.contacts[0].copy(kind = Rays.ContactKind.COLLINEAR_OVERLAP)))
                6 -> first.copy(contacts = first.contacts + first.contacts[0])
                7 -> first.copy(contacts = listOf(first.contacts[0].copy(sourceWalkId = 99)))
                else -> first.copy(contacts = listOf(first.contacts[0].copy(otherComponentId = first.sourceComponentId)))
            }
            val fake = Rays.Borrowed(f.owner, f.rays.exterior, inventory)
            assertFalse(fake.isAuthenticatedFor(f.owner))
            reject(f, fake, Approach.InvalidReason.UNAUTHENTICATED_SOURCE)
        }
    }
    @Test fun inventedContactPointAlongAnOccupiedEastRayCannotClaimAuthentication() {
        tip().use { f ->
            val first = f.rays.records[1] as Rays.FirstContact
            val p = known(f.owner.exact { point(rational(-1), rational(0)) })
            val inventory = listOf(f.rays.records[0], first.copy(point = p))
            reject(f, Rays.Borrowed(f.owner, f.rays.exterior, inventory),
                Approach.InvalidReason.UNAUTHENTICATED_SOURCE)
        }
    }
    @Test fun contradictoryAuthenticatedTiesFromForgedUpstreamAdmissionFailClosed() {
        // Real 3A scan, deliberately false upstream embedding. The private completion seal
        // certifies that scan, not the forged admission: local cross-component ties reject.
        graph(listOf(p(-1, 0), p(0, 0), p(0, -1), p(0, 1), p(4, 0), p(4, 1)),
            listOf(0 to 1, 2 to 3, 4 to 5), forgedAdmission = true).use { f ->
            assertTrue(f.rays.isAuthenticatedFor(f.owner))
            val r = Approach.build(f.owner, f.rays)
            assertTrue(r is Approach.Result.InvalidInput)
            assertTrue((r as Approach.Result.InvalidInput).reason in
                listOf(Approach.InvalidReason.CONTACT, Approach.InvalidReason.TIES))
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun malformedUpstreamGraphAfterScanCannotBypassDownstreamValidation() {
        for (kind in 0..3) {
            // Synthetic borrowed lists deliberately retain a mutable fixture alias; genuine
            // production graph builders publish immutable lists. No authentication bypass.
            val base = square()
            base.use { f ->
                val a = f.cycles.adjacency
                val groups = a.edgeGroups.toMutableList()
                val edges = a.halfEdges.toMutableList()
                val links = f.cycles.permutation.links.toMutableList()
                val membership = f.cycles.halfEdgeToWalk.toMutableList()
                val g = SelectionLassoCoincidentGeometry.Borrowed(f.owner, a.vertices, groups)
                val adjacency = SelectionLassoIncidentAdjacency.Borrowed(f.owner, g, edges, a.incidents)
                val s = SelectionLassoSuccessorPermutation.Borrowed(f.owner, adjacency, links)
                val c = SelectionLassoSuccessorCycles.Borrowed(f.owner, s, f.cycles.walks, membership)
                val admission = SelectionLassoEmbeddingAdmission.Borrowed(f.owner, c, f.components.admission.record)
                val components = (SelectionLassoGraphComponents.build(f.owner, admission) as SelectionLassoGraphComponents.Result.Ready).value
                val exterior = (SelectionLassoExteriorSectorEvidence.build(f.owner, components) as SelectionLassoExteriorSectorEvidence.Result.Ready).value
                val scan = (Rays.build(f.owner, exterior) as Rays.Result.Ready).value
                when (kind) {
                    0 -> groups[0] = groups[0].copy(endVertexId = 99)
                    1 -> edges[0] = edges[0].copy(twin = 99)
                    2 -> links[0] = links[0].copy(next = 99)
                    else -> membership[0] = 99
                }
                reject(f, scan, Approach.InvalidReason.SOURCE)
            }
        }
    }
    @Test fun foreignGenuineAndNestedOwnersNeverTriggerForeignCallbacks() {
        for (kind in 0..6) for (empty in listOf(false, true)) for (sticky in listOf(false, true)) {
            var armed = false; var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++; if (armed) throw CancellationException() })
            production(if (empty) emptyList() else listOf(p(0, 0), p(1, 0))).use { primary ->
                production(if (empty) emptyList() else listOf(p(0, 0), p(1, 0)), b).use { foreign ->
                    val comp = foreign.components; val c = foreign.cycles
                    val cycle = when (kind) {
                        0, 1, 2, 3, 4 -> c
                        5 -> SelectionLassoSuccessorCycles.Borrowed(primary.owner, c.permutation, c.walks, c.halfEdgeToWalk)
                        else -> SelectionLassoSuccessorCycles.Borrowed(primary.owner,
                            SelectionLassoSuccessorPermutation.Borrowed(primary.owner, c.adjacency, c.permutation.links),
                            c.walks, c.halfEdgeToWalk)
                    }
                    val admission = if (kind <= 3) comp.admission else
                        SelectionLassoEmbeddingAdmission.Borrowed(primary.owner, cycle, comp.admission.record)
                    val components = if (kind <= 2) comp else SelectionLassoGraphComponents.Borrowed(
                        primary.owner, admission, comp.components, comp.vertexToComponent,
                        comp.edgeGroupToComponent, comp.halfEdgeToComponent, comp.walkToComponent)
                    val ext = if (kind <= 1) foreign.rays.exterior else
                        SelectionLassoExteriorSectorEvidence.Borrowed(primary.owner, components, foreign.rays.exterior.records)
                    val supplied = if (kind == 0) foreign.rays else Rays.Borrowed(primary.owner, ext, foreign.rays.records)
                    if (sticky) assertNotNull(b.charge(SelectionQueryResource.WORK_UNITS,
                        b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1))
                    val counters = SelectionQueryResource.entries.map { b.used(it) }
                    val before = callbacks; val failure = b.failure
                    armed = true
                    reject(primary, supplied, Approach.InvalidReason.OWNER_MISMATCH)
                    assertEquals(before, callbacks)
                    assertEquals(counters, SelectionQueryResource.entries.map { b.used(it) })
                    assertSame(failure, b.failure)
                    armed = false
                    if (!sticky) assertEquals(7, known(foreign.owner.exact { 7 }))
                }
            }
        }
    }
    @Test fun closedOwnerAndResultAccessAreRejected() {
        square().use { f ->
            val r = build(f)
            f.owner.close()
            assertThrows(IllegalStateException::class.java) { r.records }
            assertThrows(IllegalStateException::class.java) { r.contacts }
            assertThrows(IllegalStateException::class.java) { r.components }
            assertThrows(IllegalStateException::class.java) { r.exact { rational(0) } }
            assertEquals(Approach.Result.InvalidInput(Approach.InvalidReason.OWNER_CLOSED),
                Approach.build(f.owner, f.rays))
        }
        square().use { primary -> square().use { foreign ->
            foreign.owner.close()
            reject(primary, foreign.rays, Approach.InvalidReason.OWNER_CLOSED)
        } }
    }
    private fun phase(name: String) = Thread.currentThread().stackTrace.any {
        it.className.contains("SelectionLassoContactApproachEvidence") && it.methodName.substringBefore('$') == name
    }
    @Test fun workExhaustionDuringLocalContactValidationPublishesNoPartialApproach() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && phase("resolve")) {
                injected = true
                b.charge(SelectionQueryResource.WORK_UNITS,
                    b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS))
            }
        })
        square(b).use {
            val before = b.used(SelectionQueryResource.WORK_UNITS)
            armed = true
            assertEquals(SelectionQueryResource.WORK_UNITS, failure(Approach.build(it.owner, it.rays)).resource)
            assertTrue(injected); assertTrue(b.used(SelectionQueryResource.WORK_UNITS) > before)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun exactBitsAdmissionFailureDuringLocalPredicatesIsTyped() {
        var armed = false; var injected = false
        lateinit var b: SelectionQueryBudget
        b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed && !injected && phase("resolve")) {
                injected = true
                b.checkExactBits(b.limits.exactBits.toLong() + 1)
            }
        })
        square(b).use {
            armed = true
            assertEquals(SelectionBudgetConstraint.EXACT_BITS, failure(Approach.build(it.owner, it.rays)).constraint)
            assertTrue(injected); assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun representationExhaustionClosesPrimaryAndReleasesReservations() {
        val probe = SelectionQueryBudget(); val retained: Long
        tip(probe).use { build(it); retained = probe.used(SelectionQueryResource.TEMPORARY_BYTES) }
        val b = SelectionQueryBudget(SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) retained - 1 else it.queryCeiling
        }))
        tip(b).use {
            assertEquals(SelectionQueryResource.TEMPORARY_BYTES, failure(Approach.build(it.owner, it.rays)).resource)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun cancellationDuringTieSectorAndPublicationPropagates() {
        for (target in listOf("resolve", "sector", "publish")) {
            var armed = false; var injected = false
            val b = SelectionQueryBudget(cancellationCheckpoint = {
                if (armed && phase(target)) { injected = true; throw CancellationException() }
            })
            tip(b).use {
                armed = true
                assertThrows(CancellationException::class.java) { Approach.build(it.owner, it.rays) }
                assertTrue(injected); assertNull(b.failure)
                assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
                assertThrows(IllegalStateException::class.java) { it.owner.segments }
            }
        }
    }
    @Test fun stickyPreviousFailureCannotBecomeSuccessfulNoContact() {
        square().use {
            val b = it.budget
            val sticky = b.charge(SelectionQueryResource.WORK_UNITS,
                b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) - b.used(SelectionQueryResource.WORK_UNITS) + 1)
            assertNotNull(sticky)
            assertSame(sticky, failure(Approach.build(it.owner, it.rays)))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
}
