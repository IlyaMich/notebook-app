package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoExactPointLocation as PointLocation
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoExactPointLocationTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private val square = listOf(p(0,0), p(4,0), p(4,4), p(0,4))
    private val triangle = listOf(p(0,0), p(4,0), p(0,4))
    private val bow = listOf(p(0,0), p(4,4), p(0,4), p(4,0))
    private val nested = listOf(p(0,0),p(10,0),p(10,10),p(0,10),
        p(0,0),p(2,2),p(4,2),p(4,4),p(2,4),p(2,2),p(0,0))

    private fun <T> known(result: SelectionPredicateResult<T>): T {
        assertTrue("Expected known $result", result is SelectionPredicateResult.Known)
        return (result as SelectionPredicateResult.Known<T>).value
    }
    private data class Fixture(
        val owner: Prepared, val budget: SelectionQueryBudget,
        val parity: Parity.Borrowed, val region: Filled.Borrowed,
        val groups: MutableList<Coincident.EdgeGroup>? = null,
    ) : AutoCloseable { override fun close() = owner.close() }

    private fun production(
        points: List<DuPoint>, budget: SelectionQueryBudget = SelectionQueryBudget(),
        mutableGroups: Boolean = false,
    ): Fixture {
        val owner = known(SelectionLassoSplitEvents.prepare(points, budget))
        val atomic = SelectionLassoAtomicEdges.build(owner)
        assertTrue("atomic $atomic", atomic is SelectionLassoAtomicEdges.Result.Ready)
        val grouped = Coincident.build(owner, (atomic as SelectionLassoAtomicEdges.Result.Ready).value)
        assertTrue("grouped $grouped", grouped is Coincident.Result.Ready)
        val original = (grouped as Coincident.Result.Ready).value
        val aliases = if (mutableGroups) original.edges.toMutableList() else null
        val g = if (aliases == null) original else Coincident.Borrowed(owner, original.vertices, aliases)
        val adjacency = SelectionLassoIncidentAdjacency.build(owner, g)
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
        val faceResult = Faces.build(owner,
            (approach as SelectionLassoContactApproachEvidence.Result.Ready).value)
        assertTrue("faces $faceResult", faceResult is Faces.Result.Ready)
        val faces = (faceResult as Faces.Result.Ready).value
        val parityResult = Parity.build(owner, faces)
        assertTrue("parity $parityResult", parityResult is Parity.Result.Ready)
        val parity = (parityResult as Parity.Result.Ready).value
        val filledResult = Filled.build(owner, parity)
        assertTrue("filled $filledResult", filledResult is Filled.Result.Ready)
        return Fixture(owner, budget, parity, (filledResult as Filled.Result.Ready).value, aliases)
    }

    private fun locate(f: Fixture, q: DuPoint, where: PointLocation.Location): PointLocation.Borrowed {
        val before = SelectionQueryResource.entries.associateWith { f.budget.used(it) }
        val result = PointLocation.locate(f.owner, f.region, q)
        assertTrue("point $q: $result", result is PointLocation.Result.Ready)
        val found = (result as PointLocation.Result.Ready).value
        assertSame(f.region, found.region)
        assertEquals(q, found.point)
        assertEquals(where, found.location)
        assertTrue(found.isAuthenticatedFor(f.owner))
        for (resource in listOf(SelectionQueryResource.ARRANGEMENT_EVENTS,
            SelectionQueryResource.ARRANGEMENT_EDGES, SelectionQueryResource.ARRANGEMENT_FACES)) {
            assertEquals(before.getValue(resource), f.budget.used(resource))
        }
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >
            before.getValue(SelectionQueryResource.WORK_UNITS))
        return found
    }
    private val odd = PointLocation.Location.ODD_INTERIOR
    private val even = PointLocation.Location.EVEN_EXTERIOR
    private val boundary = PointLocation.Location.EXACT_FILL_BOUNDARY

    @Test fun squareClassifiesInsideOutsideBoundaryAndAllCorners() {
        production(square).use { f ->
            locate(f,p(2,2),odd)
            for (q in listOf(p(-1,2),p(5,2),p(2,-1),p(2,5),p(-1,-1))) locate(f,q,even)
            for (q in listOf(p(0,2),p(2,0),p(4,2),p(2,4),
                p(0,0),p(4,0),p(4,4),p(0,4))) locate(f,q,boundary)
        }
    }

    @Test fun reversalAndRotatedStartGiveSamePointClassification() {
        for (source in listOf(square, square.reversed(), square.drop(2)+square.take(2))) {
            production(source).use { f ->
                locate(f,p(2,2),odd)
                locate(f,p(5,2),even)
                locate(f,p(2,4),boundary)
            }
        }
    }

    @Test fun triangularAndDiagonalBoundariesUseExactOrientation() {
        production(triangle).use { f ->
            locate(f,p(1,1),odd)
            locate(f,p(2,2),boundary)
            locate(f,p(3,3),even)
            locate(f,p(1,0),boundary)
        }
    }

    @Test fun bowTieHasTwoOddLobesAndCrossingIsBoundary() {
        production(bow).use { f ->
            locate(f,p(2,1),odd)
            locate(f,p(2,3),odd)
            locate(f,p(1,2),even)
            locate(f,p(2,2),boundary)
        }
    }

    @Test fun nestedLoopsHaveEvenHoleAndExactTwoPartBoundary() {
        production(nested).use { f ->
            locate(f,p(8,8),odd)
            locate(f,p(3,3),even)
            locate(f,p(1,1),odd) // doubled connector from outer vertex is not a fill boundary
            locate(f,p(2,3),boundary) // inner hole boundary
            locate(f,p(0,5),boundary) // outer boundary
            locate(f,p(12,5),even)
        }
    }

    @Test fun doubledInteriorSpikeIsNotBoundaryOrHole() {
        val withSpike = square + listOf(p(0,0),p(2,2),p(0,0))
        production(withSpike).use { f ->
            locate(f,p(1,1),odd)
            locate(f,p(2,2),odd)
            locate(f,p(2,0),boundary)
            locate(f,p(5,1),even)
        }
    }

    @Test fun halfOpenVertexRuleDistinguishesAdjacentRepresentableDoubles() {
        production(square).use { f ->
            locate(f,DuPoint(2.0, Math.nextDown(4.0)),odd)
            locate(f,p(2,4),boundary)
            locate(f,DuPoint(2.0, Math.nextUp(4.0)),even)
            locate(f,DuPoint(Math.nextDown(4.0),2.0),odd)
            locate(f,DuPoint(Math.nextUp(4.0),2.0),even)
        }
    }

    @Test fun binaryFractionCoordinatesHaveNoRoundedGeometryFallback() {
        production(listOf(p(0,0),p(0.5,0),p(0.5,0.5),p(0,0.5))).use { f ->
            locate(f,p(0.25,0.25),odd)
            locate(f,p(0.5,0.25),boundary)
            locate(f,p(0.75,0.25),even)
        }
    }

    @Test fun nonfiniteQueryRejectsBeforeArithmeticAndClosesOwner() {
        production(square).use { f ->
            val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = PointLocation.locate(f.owner,f.region,DuPoint(Double.NaN,1.0))
            assertEquals(PointLocation.Result.InvalidInput(PointLocation.InvalidReason.INVALID_POINT),result)
            assertEquals(before,f.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun syntheticCompletedRegionCopyCannotAuthenticate() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner, f.parity, f.region.oddFaceIds)
            assertFalse(fake.isAuthenticatedFor(f.owner))
            val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = PointLocation.locate(f.owner, fake, p(2,2))
            assertEquals(PointLocation.Result.InvalidInput(PointLocation.InvalidReason.UNAUTHENTICATED_REGION),result)
            assertEquals(before,f.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun foreignBorrowerDoesNotInvokePrimaryOrForeignBudget() {
        production(square).use { foreign ->
            var checkpoints = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { checkpoints++ })
            val primary = known(SelectionLassoSplitEvents.prepare(emptyList(),b))
            val before = checkpoints
            val foreignWork = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = PointLocation.locate(primary, foreign.region, p(2,2))
            assertEquals(PointLocation.Result.InvalidInput(PointLocation.InvalidReason.OWNER_MISMATCH),result)
            assertEquals(before,checkpoints)
            assertEquals(foreignWork,foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertTrue(foreign.region.isAuthenticatedFor(foreign.owner))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun mutatedPostAdmissionGroupMultiplicityIsRejected() {
        production(square, mutableGroups=true).use { f ->
            val list = f.groups!!
            val g = list.first()
            list[0] = g.copy(occurrences = g.occurrences + g.occurrences.first())
            val result = PointLocation.locate(f.owner,f.region,p(2,2))
            assertEquals(PointLocation.Result.InvalidInput(PointLocation.InvalidReason.GROUP_ASSOCIATION),result)
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun exhaustedWorkBudgetIsUnresolvedAndReleasesRetainedTemporary() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val limit = f.budget.limits.query.getValue(resource)
            val remaining = limit - f.budget.used(resource)
            assertNull(f.budget.charge(resource,remaining))
            val result = PointLocation.locate(f.owner,f.region,p(2,2))
            assertTrue(result is PointLocation.Result.Unresolved)
            assertEquals(resource, ((result as PointLocation.Result.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationPropagatesSeparatelyAndClosesOnlyPrimary() {
        var armed = false
        val budget = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("exact membership cancelled")
        })
        production(square,budget).use { f ->
            armed = true
            try { PointLocation.locate(f.owner,f.region,p(2,2)); fail("Expected cancellation") }
            catch (_: CancellationException) { /* expected */ }
            assertEquals(0L,budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun outputRemainsAuthenticatedHistoricallyButUnavailableAfterClose() {
        val f = production(square)
        val result = locate(f,p(2,2),odd)
        assertTrue(result.isOwnedBy(f.owner))
        f.close()
        assertTrue(result.isAuthenticatedFor(f.owner))
        try { result.location; fail("Closed owner exposed borrowed result") }
        catch (_: IllegalStateException) { /* expected */ }
        try { result.region; fail("Closed owner exposed borrowed source") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
