package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoSourceParityEvidenceTest {
    private fun p(x: Int, y: Int) = DuPoint(x.toDouble(), y.toDouble())
    private val triangle = listOf(p(0, 0), p(4, 0), p(0, 4))
    private val square = listOf(p(0, 0), p(4, 0), p(4, 4), p(0, 4))

    private fun <T> known(outcome: SelectionPredicateResult<T>): T {
        assertTrue("Expected Known, got $outcome", outcome is SelectionPredicateResult.Known)
        return (outcome as SelectionPredicateResult.Known<T>).value
    }

    private data class Fixture(
        val owner: Prepared,
        val faces: Faces.Borrowed,
        val budget: SelectionQueryBudget,
        val mutableGroups: MutableList<Coincident.EdgeGroup>? = null,
    ) : AutoCloseable {
        override fun close() = owner.close()
    }

    private fun complete(
        owner: Prepared,
        grouped: Coincident.Borrowed,
        budget: SelectionQueryBudget,
        groups: MutableList<Coincident.EdgeGroup>? = null,
    ): Fixture {
        val adjacency = SelectionLassoIncidentAdjacency.build(owner, grouped)
        assertTrue("adjacency: $adjacency", adjacency is SelectionLassoIncidentAdjacency.Result.Ready)
        val permutation = SelectionLassoSuccessorPermutation.build(owner,
            (adjacency as SelectionLassoIncidentAdjacency.Result.Ready).value)
        assertTrue("permutation: $permutation", permutation is SelectionLassoSuccessorPermutation.Result.Ready)
        val cycles = SelectionLassoSuccessorCycles.build(owner,
            (permutation as SelectionLassoSuccessorPermutation.Result.Ready).value)
        assertTrue("cycles: $cycles", cycles is SelectionLassoSuccessorCycles.Result.Ready)
        val admission = SelectionLassoEmbeddingAdmission.build(owner,
            (cycles as SelectionLassoSuccessorCycles.Result.Ready).value)
        assertTrue("admission: $admission", admission is SelectionLassoEmbeddingAdmission.Result.Ready)
        val components = SelectionLassoGraphComponents.build(owner,
            (admission as SelectionLassoEmbeddingAdmission.Result.Ready).value)
        assertTrue("components: $components", components is SelectionLassoGraphComponents.Result.Ready)
        val exterior = SelectionLassoExteriorSectorEvidence.build(owner,
            (components as SelectionLassoGraphComponents.Result.Ready).value)
        assertTrue("exterior: $exterior", exterior is SelectionLassoExteriorSectorEvidence.Result.Ready)
        val rays = SelectionLassoIntercomponentRayContacts.build(owner,
            (exterior as SelectionLassoExteriorSectorEvidence.Result.Ready).value)
        assertTrue("rays: $rays", rays is SelectionLassoIntercomponentRayContacts.Result.Ready)
        val approach = SelectionLassoContactApproachEvidence.build(owner,
            (rays as SelectionLassoIntercomponentRayContacts.Result.Ready).value)
        assertTrue("approach: $approach", approach is SelectionLassoContactApproachEvidence.Result.Ready)
        val faces = Faces.build(owner, (approach as SelectionLassoContactApproachEvidence.Result.Ready).value)
        assertTrue("faces: $faces", faces is Faces.Result.Ready)
        return Fixture(owner, (faces as Faces.Result.Ready).value, budget, groups)
    }

    private fun production(
        supplied: List<DuPoint>,
        budget: SelectionQueryBudget = SelectionQueryBudget(),
        mutableGroups: Boolean = false,
    ): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(supplied, budget))
        val atomic = SelectionLassoAtomicEdges.build(owner)
        assertTrue("atomic: $atomic", atomic is SelectionLassoAtomicEdges.Result.Ready)
        val grouped = Coincident.build(owner, (atomic as SelectionLassoAtomicEdges.Result.Ready).value)
        assertTrue("grouped: $grouped", grouped is Coincident.Result.Ready)
        val original = (grouped as Coincident.Result.Ready).value
        val groups = if (mutableGroups) original.edges.toMutableList() else null
        val selected = if (groups == null) original else Coincident.Borrowed(owner, original.vertices, groups)
        return complete(owner, selected, budget, groups)
    }

    private fun ready(f: Fixture): Parity.Borrowed {
        val beforeFace = f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES)
        val beforeEdges = f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES)
        val beforeEvents = f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS)
        val outcome = Parity.build(f.owner, f.faces)
        assertTrue("Expected parity Ready: $outcome", outcome is Parity.Result.Ready)
        val evidence = (outcome as Parity.Result.Ready).value
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        assertEquals(beforeFace, f.budget.used(SelectionQueryResource.ARRANGEMENT_FACES))
        assertEquals(beforeEdges, f.budget.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(beforeEvents, f.budget.used(SelectionQueryResource.ARRANGEMENT_EVENTS))
        assertEquals(f.faces.faceCount, evidence.faceParities.size)
        assertEquals(f.faces.counts.edgeGroupCount, evidence.edgeGroupToggles.size)
        assertEquals(f.faces.counts.edgeGroupCount, evidence.verifiedConstraintCount)
        assertEquals(0, evidence.faceParities.first().faceId)
        assertFalse(evidence.faceParities.first().isOdd)
        return evidence
    }

    @Test fun emptyAndPointSourcesAreEvenWithNoOccurrences() {
        for (input in listOf(emptyList(), listOf(p(2, 3)), listOf(p(2, 3), p(2, 3)))) {
            production(input).use { f ->
                val r = ready(f)
                assertEquals(listOf(Parity.FaceParity(0, false)), r.faceParities)
                assertEquals(0L, r.verifiedOccurrenceCount)
                assertTrue(r.oddFaceIds.isEmpty())
                assertTrue(r.edgeGroupToggles.isEmpty())
            }
        }
    }

    @Test fun simplePolygonOrientationAndStartRotationDoNotAffectOddInside() {
        for (input in listOf(triangle, triangle.reversed(), square, square.reversed(),
            square.drop(1) + square.take(1))) {
            production(input).use { f ->
                val r = ready(f)
                assertEquals(2, r.faceParities.size)
                assertEquals(listOf(1), r.oddFaceIds)
                assertTrue(r.edgeGroupToggles.all { it })
                assertEquals(input.size.toLong(), r.verifiedOccurrenceCount)
            }
        }
    }

    @Test fun retracedLineAndCollinearOverlapHaveEvenParity() {
        for (input in listOf(listOf(p(0, 0), p(4, 0)),
            listOf(p(0, 0), p(4, 0), p(2, 0), p(4, 0)))) {
            production(input).use { f ->
                val r = ready(f)
                assertEquals(1, r.faceParities.size)
                assertTrue(r.oddFaceIds.isEmpty())
                assertTrue(r.edgeGroupToggles.all { !it })
            }
        }
    }

    @Test fun sameSquareTwiceIsEvenAndThreeTimesIsOdd() {
        production(square + square).use { f ->
            val r = ready(f)
            assertEquals(2, r.faceParities.size)
            assertTrue(r.oddFaceIds.isEmpty())
            assertTrue(r.edgeGroupToggles.all { !it })
            assertEquals(8L, r.verifiedOccurrenceCount)
        }
        production(square + square + square).use { f ->
            val r = ready(f)
            assertEquals(2, r.faceParities.size)
            assertEquals(listOf(1), r.oddFaceIds)
            assertTrue(r.edgeGroupToggles.all { it })
            assertEquals(12L, r.verifiedOccurrenceCount)
        }
    }

    @Test fun splitBowTieHasTwoOddBoundedFaces() {
        val input = listOf(p(0, 0), p(4, 4), p(0, 4), p(4, 0))
        production(input).use { f ->
            val r = ready(f)
            assertEquals(3, r.faceParities.size)
            assertEquals(listOf(1, 2), r.oddFaceIds)
            assertTrue(r.edgeGroupToggles.all { it })
        }
    }

    @Test fun connectedNestedLoopsUseEvenInnerAndOddAnnulus() {
        val input = listOf(p(0, 0), p(10, 0), p(10, 10), p(0, 10),
            p(0, 0), p(2, 2), p(4, 2), p(4, 4), p(2, 4), p(2, 2), p(0, 0))
        production(input).use { f ->
            val r = ready(f)
            assertEquals(3, r.faceParities.size)
            assertEquals(1, r.oddFaceIds.size)
            assertEquals(1, r.faceParities.count { it.isOdd })
            assertTrue(r.edgeGroupToggles.any { !it }) // retraced connector
            assertTrue(r.edgeGroupToggles.any { it })
        }
    }

    @Test fun sourceFreeSyntheticEdgesAreRejectedRatherThanSilentlyEven() {
        val b = SelectionQueryBudget()
        val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
        val geometry = known(owner.exact {
            val first = point(p(0, 0)); val second = point(p(2, 0))
            Coincident.Borrowed(owner,
                listOf(Coincident.Vertex(0, first), Coincident.Vertex(1, second)),
                listOf(Coincident.EdgeGroup(0, 1, first, second, emptyList())))
        })
        val f = complete(owner, geometry, b)
        f.use {
            val result = Parity.build(f.owner, f.faces)
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.GROUP_ASSOCIATION), result)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun missingAndDuplicatedOccurrencesAreRejectedAtCoverageBoundary() {
        production(triangle, mutableGroups = true).use { f ->
            val groups = f.mutableGroups!!
            groups[0] = groups[0].copy(occurrences = emptyList())
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.GROUP_ASSOCIATION),
                Parity.build(f.owner, f.faces))
        }
        production(triangle, mutableGroups = true).use { f ->
            val groups = f.mutableGroups!!
            groups[0] = groups[0].copy(occurrences = groups[0].occurrences + groups[0].occurrences.first())
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.OCCURRENCE),
                Parity.build(f.owner, f.faces))
        }
    }

    @Test fun falseDirectionAndEndpointEvidenceAreRejected() {
        production(triangle, mutableGroups = true).use { f ->
            val groups = f.mutableGroups!!
            val original = groups[0].occurrences.first()
            groups[0] = groups[0].copy(occurrences = listOf(original.copy(
                followsCanonical = !original.followsCanonical)))
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.GROUP_ASSOCIATION),
                Parity.build(f.owner, f.faces))
        }
    }

    @Test fun syntheticGlobalFaceCopiesCannotClaimAuthentication() {
        production(triangle).use { f ->
            val copied = Faces.Borrowed(f.owner, f.faces.approach, f.faces.counts,
                f.faces.walkToFace, f.faces.faces)
            assertFalse(copied.isAuthenticatedFor(f.owner))
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.UNAUTHENTICATED_FACES),
                Parity.build(f.owner, copied))
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun foreignOwnerIsRejectedWithoutTouchingItsCallbackOrBudget() {
        production(triangle).use { foreign ->
            var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val primary = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
            val before = callbacks
            assertEquals(Parity.Result.InvalidInput(Parity.InvalidReason.OWNER_MISMATCH),
                Parity.build(primary, foreign.faces))
            assertEquals(before, callbacks)
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.faces.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun cancellationAndStickyWorkExhaustionCloseOnlyPrimaryOwner() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("parity cancellation")
        })
        val f = production(triangle, b)
        armed = true
        try {
            Parity.build(f.owner, f.faces)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        } finally { f.close() }

        production(triangle).use { exhausted ->
            val budget = exhausted.budget
            val limit = budget.limits.query.getValue(SelectionQueryResource.WORK_UNITS)
            val remaining = limit - budget.used(SelectionQueryResource.WORK_UNITS)
            assertNull(budget.charge(SelectionQueryResource.WORK_UNITS, remaining))
            val outcome = Parity.build(exhausted.owner, exhausted.faces)
            assertTrue(outcome is Parity.Result.Unresolved)
            assertEquals(SelectionQueryResource.WORK_UNITS,
                ((outcome as Parity.Result.Unresolved).cause as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun borrowerLifetimeAndNoNewFaceEdgeEventCharges() {
        val f = production(triangle)
        val result = ready(f)
        assertEquals(f.faces.counts.edgeGroupCount.toLong(), result.verifiedOccurrenceCount)
        assertTrue(result.isOwnedBy(f.owner))
        assertTrue(result.isAuthenticatedFor(f.owner))
        f.close()
        assertTrue(result.isAuthenticatedFor(f.owner))
        try { result.faceParities; fail("Closed borrowed value remained accessible") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
