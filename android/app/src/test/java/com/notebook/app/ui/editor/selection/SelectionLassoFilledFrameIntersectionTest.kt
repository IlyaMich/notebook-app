package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoFilledFrameIntersection as Intersection
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoFilledFrameIntersectionTest {
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

    private val identity = requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(
        listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)))
    private fun affine(vararg coefficient: Double): CanonicalElementAffine =
        requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(coefficient.toList()))
    private fun frame(x: Number, y: Number, w: Number, h: Number) =
        CanonicalFrame(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

    private fun assertRelation(
        f: Fixture, frame: CanonicalFrame, expected: Intersection.Relation,
        transform: CanonicalElementAffine = identity,
    ): Intersection.Borrowed {
        val before = SelectionQueryResource.entries.associateWith { f.budget.used(it) }
        val result = Intersection.intersect(f.owner, f.region, frame, transform)
        assertTrue("frame=$frame expected=$expected got=$result", result is Intersection.Result.Ready)
        val evidence = (result as Intersection.Result.Ready).value
        assertTrue(evidence.isOwnedBy(f.owner))
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        assertSame(f.region, evidence.region)
        assertEquals(frame, evidence.frame)
        assertSame(transform, evidence.affine)
        assertEquals(expected, evidence.relation)
        for (resource in listOf(SelectionQueryResource.ARRANGEMENT_EDGES,
            SelectionQueryResource.ARRANGEMENT_EVENTS, SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(before.getValue(resource), f.budget.used(resource))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >
            before.getValue(SelectionQueryResource.WORK_UNITS))
        return evidence
    }
    private val inside = Intersection.Relation.INTERIOR_OVERLAP
    private val touch = Intersection.Relation.BOUNDARY_ONLY_CONTACT
    private val outside = Intersection.Relation.DISJOINT

    @Test fun filledFrameStrictlyInsideOrContainingEntireLassoOverlaps() {
        production(square).use { f ->
            assertRelation(f, frame(1, 1, 2, 2), inside)
            assertRelation(f, frame(-3, -3, 10, 10), inside)
            assertRelation(f, frame(0, 0, 4, 4), inside)
        }
    }

    @Test fun disconnectedFrameIsDisjoint() {
        production(square).use { f ->
            assertRelation(f, frame(6, 6, 2, 2), outside)
            assertRelation(f, frame(4.25, 0.25, 1.5, 2.0), outside)
        }
    }

    @Test fun frameCenterOutsideButAnEdgeCutsOpenLassoStillOverlaps() {
        production(square).use { f ->
            assertRelation(f, frame(3, 1, 4, 2), inside)
            assertRelation(f, frame(-1, 1, 2, 2), inside)
            assertRelation(f, frame(1, -1, 2, 2), inside)
            assertRelation(f, frame(1, 3, 2, 2), inside)
        }
    }

    @Test fun cornerOnlyContactDoesNotCreatePositiveAreaHit() {
        production(square).use { f ->
            for (q in listOf(frame(4, 4, 2, 2), frame(-2, -2, 2, 2),
                frame(-2, 4, 2, 2), frame(4, -2, 2, 2)))
                assertRelation(f, q, touch)
        }
    }

    @Test fun coincidentFullAndPartialEdgeContactOnlyAreDistinctFromInteriorOverlap() {
        production(square).use { f ->
            assertRelation(f, frame(4, 1, 2, 2), touch)
            assertRelation(f, frame(-2, 1, 2, 2), touch)
            assertRelation(f, frame(1, 4, 2, 2), touch)
            assertRelation(f, frame(1, -2, 2, 2), touch)
            assertRelation(f, frame(4, 0, 2, 4), touch)
        }
    }

    @Test fun tangentialBoundaryOnlyContactWithTriangle() {
        production(triangle).use { f ->
            assertRelation(f, frame(4, 0, 2, 2), touch)
            assertRelation(f, frame(3, 2, 2, 2), outside) // safely away from x+y=4
            assertRelation(f, frame(1, 1, 1, 1), inside)
        }
    }

    @Test fun transformedFractionalShearedAndRotatedFramesRetainExactCorners() {
        production(square).use { f ->
            val shear = affine(1.0, 0.0, 0.5, 1.0, 0.5, 0.5)
            assertRelation(f, frame(0, 0, 2, 2), inside, shear)
            val quarterTurn = affine(0.0, 1.0, -1.0, 0.0, 3.0, 1.0)
            assertRelation(f, frame(0, 0, 2, 2), inside, quarterTurn)
            val displaced = affine(1.0, 0.0, 0.5, 1.0, 10.0, 10.0)
            assertRelation(f, frame(0, 0, 2, 2), outside, displaced)
        }
    }

    @Test fun reflectedQuadrilateralIsAValidConvexFilledFrame() {
        production(square).use { f ->
            val reflected = affine(-1.0, 0.0, 0.0, 1.0, 4.0, 0.0)
            assertRelation(f, frame(0, 0, 4, 4), inside, reflected)
            val displaced = affine(-1.0, 0.0, 0.0, 1.0, 10.0, 0.0)
            assertRelation(f, frame(0, 0, 4, 4), outside, displaced)
        }
    }

    @Test fun concaveAndSelfIntersectingSourceContributeOnlyOddInterior() {
        production(bow).use { f ->
            assertRelation(f, frame(1.75, 0.5, 0.5, 0.5), inside)
            assertRelation(f, frame(1.75, 2.5, 0.5, 0.5), inside)
            assertRelation(f, frame(0.25, 1.75, 0.5, 0.5), outside)
            assertRelation(f, frame(4, 0, 1, 1), touch) // lobe tip at (4,0)
        }
    }

    @Test fun nestedHoleIsDisjointWhenEntireFrameIsWithinEvenFace() {
        production(nested).use { f ->
            assertRelation(f, frame(2.5, 2.5, 1, 1), outside)
            assertRelation(f, frame(1, 1, 1, 1), inside)
            assertRelation(f, frame(3, 3, 6, 6), inside)
            assertRelation(f, frame(1.5, 2.5, 1, 1), inside)
        }
    }

    @Test fun retracedConnectorIsNotConsideredAnIndependentFillBoundary() {
        val withSpike = square + listOf(p(0, 0), p(2, 2), p(0, 0))
        production(withSpike).use { f ->
            assertRelation(f, frame(1, 1, 0.5, 0.5), inside)
            assertRelation(f, frame(1.5, 1.5, 1, 1), inside)
            assertRelation(f, frame(4, 1, 1, 1), touch)
        }
    }

    @Test fun reversingSourceAndRotatingStartDoNotChangeIntersection() {
        for (source in listOf(square, square.reversed(), square.drop(2) + square.take(2))) {
            production(source).use { f ->
                assertRelation(f, frame(1, 1, 2, 2), inside)
                assertRelation(f, frame(4, 1, 2, 2), touch)
                assertRelation(f, frame(5, 1, 2, 2), outside)
            }
        }
    }

    @Test fun invalidFrameRejectsBeforeExactWorkAndClosesOnlyPrimary() {
        for (q in listOf(frame(0, 0, 0, 2), frame(0, 0, 2, -1),
            frame(Double.NaN, 0, 2, 2), frame(0, 0, Double.POSITIVE_INFINITY, 2))) {
            production(square).use { f ->
                val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
                val r = Intersection.intersect(f.owner, f.region, q, identity)
                assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.INVALID_FRAME), r)
                assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun sameOwnerFabricatedFilledRegionRejectsBeforeArithmetic() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner, f.parity, f.region.oddFaceIds)
            val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val r = Intersection.intersect(f.owner, fake, frame(1, 1, 2, 2), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.UNAUTHENTICATED_REGION), r)
            assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
        }
    }

    @Test fun foreignSourceNeverTouchesForeignOrPrimaryCallbacks() {
        production(square).use { foreign ->
            var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
            val before = callbacks
            val foreignWork = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val r = Intersection.intersect(owner, foreign.region, frame(1, 1, 2, 2), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.OWNER_MISMATCH), r)
            assertEquals(before, callbacks)
            assertEquals(foreignWork, foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.region.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun postCertificationOccurrenceMultiplicityMutationRejects() {
        production(square, mutableGroups = true).use { f ->
            val groups = f.groups!!
            val old = groups[0]
            groups[0] = old.copy(occurrences = old.occurrences + old.occurrences.first())
            val r = Intersection.intersect(f.owner, f.region, frame(1, 1, 2, 2), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.GROUP_ASSOCIATION), r)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun exhaustingWorkIsTypedAndReleasesArenaReservations() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining = f.budget.limits.query.getValue(resource) - f.budget.used(resource)
            assertNull(f.budget.charge(resource, remaining))
            val r = Intersection.intersect(f.owner, f.region, frame(1, 1, 2, 2), identity)
            assertTrue(r is Intersection.Result.Unresolved)
            assertEquals(resource, ((r as Intersection.Result.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationPropagatesAndClosesTheSuppliedOwner() {
        var armed = false
        val b = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("frame intersection cancelled")
        })
        production(square, b).use { f ->
            armed = true
            try {
                Intersection.intersect(f.owner, f.region, frame(1, 1, 2, 2), identity)
                fail("Expected cancellation")
            } catch (_: CancellationException) { /* expected */ }
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun completedBorrowerEnforcesOwnerLifetimeAfterClose() {
        val f = production(square)
        val r = assertRelation(f, frame(1, 1, 2, 2), inside)
        f.close()
        assertTrue(r.isAuthenticatedFor(f.owner))
        try { r.relation; fail("Closed borrowed value remained accessible") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
