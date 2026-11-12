package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Interior
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoFilledRegionAdmissionTest {
    private fun p(x: Int, y: Int) = DuPoint(x.toDouble(), y.toDouble())
    private val square = listOf(p(0, 0), p(4, 0), p(4, 4), p(0, 4))
    private val triangle = listOf(p(0, 0), p(4, 0), p(0, 4))

    private fun <T> known(result: SelectionPredicateResult<T>): T {
        assertTrue("Expected Known: $result", result is SelectionPredicateResult.Known)
        return (result as SelectionPredicateResult.Known<T>).value
    }

    private data class Fixture(val owner: Prepared, val budget: SelectionQueryBudget,
        val faces: Faces.Borrowed, val parity: Parity.Borrowed) : AutoCloseable {
        override fun close() = owner.close()
    }

    private fun production(points: List<DuPoint>, b: SelectionQueryBudget = SelectionQueryBudget()): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(points, b))
        val atomic = SelectionLassoAtomicEdges.build(owner)
        assertTrue("atomic $atomic", atomic is SelectionLassoAtomicEdges.Result.Ready)
        val grouped = Coincident.build(owner, (atomic as SelectionLassoAtomicEdges.Result.Ready).value)
        assertTrue("grouped $grouped", grouped is Coincident.Result.Ready)
        val adjacency = SelectionLassoIncidentAdjacency.build(owner, (grouped as Coincident.Result.Ready).value)
        assertTrue("adjacency $adjacency", adjacency is SelectionLassoIncidentAdjacency.Result.Ready)
        val permutation = SelectionLassoSuccessorPermutation.build(owner,
            (adjacency as SelectionLassoIncidentAdjacency.Result.Ready).value)
        assertTrue("permutation $permutation", permutation is SelectionLassoSuccessorPermutation.Result.Ready)
        val cycles = SelectionLassoSuccessorCycles.build(owner,
            (permutation as SelectionLassoSuccessorPermutation.Result.Ready).value)
        assertTrue("cycles $cycles", cycles is SelectionLassoSuccessorCycles.Result.Ready)
        val admission = SelectionLassoEmbeddingAdmission.build(owner,
            (cycles as SelectionLassoSuccessorCycles.Result.Ready).value)
        assertTrue("admission $admission", admission is SelectionLassoEmbeddingAdmission.Result.Ready)
        val components = SelectionLassoGraphComponents.build(owner,
            (admission as SelectionLassoEmbeddingAdmission.Result.Ready).value)
        assertTrue("components $components", components is SelectionLassoGraphComponents.Result.Ready)
        val exterior = SelectionLassoExteriorSectorEvidence.build(owner,
            (components as SelectionLassoGraphComponents.Result.Ready).value)
        assertTrue("exterior $exterior", exterior is SelectionLassoExteriorSectorEvidence.Result.Ready)
        val rays = SelectionLassoIntercomponentRayContacts.build(owner,
            (exterior as SelectionLassoExteriorSectorEvidence.Result.Ready).value)
        assertTrue("rays $rays", rays is SelectionLassoIntercomponentRayContacts.Result.Ready)
        val approach = SelectionLassoContactApproachEvidence.build(owner,
            (rays as SelectionLassoIntercomponentRayContacts.Result.Ready).value)
        assertTrue("approach $approach", approach is SelectionLassoContactApproachEvidence.Result.Ready)
        val faceOutcome = Faces.build(owner, (approach as SelectionLassoContactApproachEvidence.Result.Ready).value)
        assertTrue("faces $faceOutcome", faceOutcome is Faces.Result.Ready)
        val faces = (faceOutcome as Faces.Result.Ready).value
        val parityOutcome = Parity.build(owner, faces)
        assertTrue("parity $parityOutcome", parityOutcome is Parity.Result.Ready)
        return Fixture(owner, b, faces, (parityOutcome as Parity.Result.Ready).value)
    }

    private fun admitted(f: Fixture, count: Int): Interior.Borrowed {
        val beforeFace = f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES)
        val beforeEdge = f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES)
        val beforeEvent = f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS)
        val result = Interior.build(f.owner, f.parity)
        assertTrue("Expected admitted interior: $result", result is Interior.Result.Ready)
        val ready = (result as Interior.Result.Ready).value
        assertEquals(count, ready.oddFaceCount)
        assertEquals(f.parity.oddFaceIds, ready.oddFaceIds)
        assertSame(f.parity, ready.parity)
        assertTrue(ready.isAuthenticatedFor(f.owner))
        assertEquals(beforeFace, f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES))
        assertEquals(beforeEdge, f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(beforeEvent, f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        return ready
    }

    private fun assertEmpty(f: Fixture) {
        val outcome = Interior.build(f.owner, f.parity)
        assertEquals(Interior.Result.EmptyInterior(Interior.EmptyReason.NO_ODD_INTERIOR), outcome)
        assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        try { f.parity.oddFaceIds; fail("Owner was not closed") }
        catch (_: IllegalStateException) { /* expected */ }
    }

    @Test fun emptySinglePointAndTwoPointClosedSourcesAreNotFilled() {
        for (vertices in listOf(emptyList(), listOf(p(1, 1)), listOf(p(1, 1), p(1, 1)),
            listOf(p(0, 0), p(4, 0)))) production(vertices).use(::assertEmpty)
    }

    @Test fun completelyRetracedPolygonAndTwiceTracedSquareAreNotFilled() {
        production(square + square.reversed()).use(::assertEmpty)
        production(square + square).use(::assertEmpty)
    }

    @Test fun clockwiseCounterclockwiseAndRotatedSourcesAdmitSameInterior() {
        for (vertices in listOf(square, square.reversed(), square.drop(1) + square.take(1),
            triangle, triangle.reversed())) production(vertices).use { f ->
            assertEquals(listOf(1), admitted(f, 1).oddFaceIds)
        }
    }

    @Test fun threeTraversalsOfSquareAdmitWhileTwoCancel() {
        production(square + square).use(::assertEmpty)
        production(square + square + square).use { f -> admitted(f, 1) }
    }

    @Test fun selfIntersectingBowTieAdmitsBothOddFaces() {
        production(listOf(p(0, 0), p(4, 4), p(0, 4), p(4, 0))).use { f ->
            assertEquals(listOf(1, 2), admitted(f, 2).oddFaceIds)
        }
    }

    @Test fun nestedLoopsAdmitAnnulusButDoNotInventInteriorHoleMembership() {
        val input = listOf(p(0, 0), p(10, 0), p(10, 10), p(0, 10),
            p(0, 0), p(2, 2), p(4, 2), p(4, 4), p(2, 4), p(2, 2), p(0, 0))
        production(input).use { f ->
            val result = admitted(f, 1)
            assertEquals(3, f.faces.faceCount)
            assertTrue(f.parity.faceParities.count { it.isOdd } == 1)
            assertEquals(1, result.oddFaceIds.size)
        }
    }

    @Test fun oppositeWindingSeparateLobesRemainFilledDespiteSignedAreaCancellation() {
        // Two equal squares of opposite signed traversal orientation, joined by a doubled
        // connector. Global signed source area cancels; even-odd interior does not.
        val input = listOf(p(0, 0), p(4, 0), p(4, 4), p(0, 4), p(0, 0),
            p(8, 0), p(8, 4), p(12, 4), p(12, 0), p(8, 0), p(0, 0))
        production(input).use { f -> admitted(f, 2) }
    }

    @Test fun syntheticSameOwnerParityCopyCannotAuthenticate() {
        production(square).use { f ->
            val copy = Parity.Borrowed(f.owner, f.faces, f.parity.faceParities,
                f.parity.edgeGroupToggles, f.parity.oddFaceIds,
                f.parity.verifiedOccurrenceCount, f.parity.verifiedConstraintCount)
            assertFalse(copy.isAuthenticatedFor(f.owner))
            val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
            assertEquals(Interior.Result.InvalidInput(Interior.InvalidReason.UNAUTHENTICATED_PARITY),
                Interior.build(f.owner, copy))
            assertEquals(before, f.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun foreignOwnerCannotExecutePrimaryOrForeignCallbacksDuringRejection() {
        production(square).use { foreign ->
            var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val primary = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
            val previous = callbacks
            assertEquals(Interior.Result.InvalidInput(Interior.InvalidReason.OWNER_MISMATCH),
                Interior.build(primary, foreign.parity))
            assertEquals(previous, callbacks)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.parity.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun workLimitRejectionClosesOwnerWithoutPublishingAdmission() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining = f.budget.limits.query.getValue(resource) - f.budget.used(resource)
            assertNull(f.budget.charge(resource, remaining))
            val result = Interior.build(f.owner, f.parity)
            assertTrue(result is Interior.Result.Unresolved)
            assertEquals(resource,
                ((result as Interior.Result.Unresolved).cause as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationClosesOnlyOriginalPreparedOwnerAndReleasesTemporaryReservations() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("admission cancellation")
        })
        production(square, b).use { f ->
            armed = true
            try { Interior.build(f.owner, f.parity); fail("Cancellation was not propagated") }
            catch (_: CancellationException) { /* expected */ }
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun borrowedOutputIsImmutableAndHistoricalAuthenticationDoesNotReopenOwner() {
        val f = production(square)
        val result = admitted(f, 1)
        assertTrue(result.isOwnedBy(f.owner))
        assertFalse(result.isAuthenticatedFor(known(SelectionLassoSplitEvents.prepare(emptyList(), SelectionQueryBudget())).also { it.close() }))
        try {
            @Suppress("UNCHECKED_CAST")
            (result.oddFaceIds as MutableList<Int>).add(99)
            fail("Published face list was mutable")
        } catch (_: UnsupportedOperationException) { /* expected */ }
        f.close()
        assertTrue(result.isAuthenticatedFor(f.owner))
        try { result.oddFaceIds; fail("Closed borrowed value remained readable") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
