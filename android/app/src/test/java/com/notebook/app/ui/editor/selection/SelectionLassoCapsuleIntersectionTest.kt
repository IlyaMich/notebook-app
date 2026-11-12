package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoCapsuleIntersection as Intersection
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoCapsuleIntersectionTest {
    private fun p(x: Number, y: Number) = DuPoint(x.toDouble(), y.toDouble())
    private val square = listOf(p(0, 0), p(4, 0), p(4, 4), p(0, 4))
    private val triangle = listOf(p(0, 0), p(4, 0), p(0, 4))
    private val bow = listOf(p(0, 0), p(4, 4), p(0, 4), p(4, 0))
    private val nested = listOf(p(0, 0), p(10, 0), p(10, 10), p(0, 10),
        p(0, 0), p(2, 2), p(4, 2), p(4, 4), p(2, 4), p(2, 2), p(0, 0))

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
    private fun affine(vararg c: Double): CanonicalElementAffine =
        requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(c.toList()))
    private fun capsule(x1: Number, y1: Number, x2: Number, y2: Number, radius: Number) =
        SelectionPaintGeometry.Primitive.Capsule(p(x1, y1), p(x2, y2), radius.toDouble())
    private fun disk(x: Number, y: Number, radius: Number) = capsule(x, y, x, y, radius)
    private val inside = Intersection.Relation.INTERIOR_OVERLAP
    private val touch = Intersection.Relation.BOUNDARY_ONLY_CONTACT
    private val outside = Intersection.Relation.DISJOINT

    private fun assertRelation(
        f: Fixture, capsule: SelectionPaintGeometry.Primitive.Capsule,
        expected: Intersection.Relation, transform: CanonicalElementAffine = identity,
    ): Intersection.Borrowed {
        val before = SelectionQueryResource.entries.associateWith { f.budget.used(it) }
        val result = Intersection.intersect(f.owner, f.region, capsule, transform)
        assertTrue("capsule=$capsule expected=$expected got=$result", result is Intersection.Result.Ready)
        val evidence = (result as Intersection.Result.Ready).value
        assertEquals(expected, evidence.relation)
        assertTrue(evidence.isOwnedBy(f.owner))
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        assertSame(f.region, evidence.region)
        assertSame(capsule, evidence.capsule)
        assertSame(transform, evidence.affine)
        for (resource in listOf(SelectionQueryResource.ARRANGEMENT_EDGES,
            SelectionQueryResource.ARRANGEMENT_EVENTS, SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(before.getValue(resource), f.budget.used(resource))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >
            before.getValue(SelectionQueryResource.WORK_UNITS))
        return evidence
    }

    @Test fun diskStrictlyInsideFilledSquare() {
        production(square).use { f ->
            assertRelation(f, disk(2, 2, 0.25), inside)
            assertRelation(f, disk(2, 2, 100), inside)
            assertRelation(f, disk(0, 0, 0.5), inside)
        }
    }

    @Test fun diskOutsideAndSeparatedFromLasso() {
        production(square).use { f ->
            assertRelation(f, disk(6, 6, 1), outside)
            assertRelation(f, disk(5.5, 2, 1), outside)
            assertRelation(f, disk(-2, 2, 1), outside)
        }
    }

    @Test fun diskTangencyAgainstEdgeIsBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, disk(5, 2, 1), touch)
            assertRelation(f, disk(-1, 2, 1), touch)
            assertRelation(f, disk(2, -1, 1), touch)
            assertRelation(f, disk(2, 5, 1), touch)
        }
    }

    @Test fun diskTangencyAgainstCornerIsBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, disk(7, 4, 3), touch)
            assertRelation(f, disk(-3, 0, 3), touch)
            assertRelation(f, disk(4, 7, 3), touch)
        }
    }

    @Test fun diskWithCenterOutsideButRadiusEntersFilledRegion() {
        production(square).use { f ->
            assertRelation(f, disk(4.5, 2, 1), inside)
            assertRelation(f, disk(5, 5, 2), inside)
            assertRelation(f, disk(-0.5, 2, 1), inside)
        }
    }

    @Test fun centerlineInsideOrEnclosingEntireLassoOverlaps() {
        production(square).use { f ->
            assertRelation(f, capsule(1, 1, 3, 3, 0.125), inside)
            assertRelation(f, capsule(-4, 2, 8, 2, 0.125), inside)
            assertRelation(f, capsule(-4, 2, -4, 2, 7), inside)
        }
    }

    @Test fun crossingCenterlineWithBothEndpointsOutsideStillOverlaps() {
        production(square).use { f ->
            assertRelation(f, capsule(2, -2, 2, 6, 0.25), inside)
            assertRelation(f, capsule(-2, -2, 6, 6, 0.125), inside)
            assertRelation(f, capsule(5, 2, 8, 2, 0.25), outside)
        }
    }

    @Test fun capsuleParallelToBoundaryAtExactRadiusTouchesOnly() {
        production(square).use { f ->
            assertRelation(f, capsule(5, 1, 5, 3, 1), touch)
            assertRelation(f, capsule(-1, 1, -1, 3, 1), touch)
            assertRelation(f, capsule(5, 1, 5, 3, 1.25), inside)
            assertRelation(f, capsule(5, 1, 5, 3, 0.75), outside)
        }
    }

    @Test fun partiallyOffsetDiagonalAndEndpointCapsTouch() {
        production(square).use { f ->
            assertRelation(f, capsule(5, 2, 7, 2, 1), touch)
            assertRelation(f, capsule(5, 2, 7, 2, 1.25), inside)
            assertRelation(f, capsule(5, 2, 7, 2, 0.75), outside)
        }
    }

    @Test fun nestedHoleSeparatesEvenDiskFromAnnularPaint() {
        production(nested).use { f ->
            assertRelation(f, disk(3, 3, 0.5), outside)
            assertRelation(f, disk(3, 3, 1), touch)
            assertRelation(f, disk(3, 3, 1.5), inside)
            assertRelation(f, disk(1, 1, 0.25), inside)
            assertRelation(f, disk(12, 12, 1), outside)
        }
    }

    @Test fun bowTieOnlyOddLobesAreFilled() {
        production(bow).use { f ->
            assertRelation(f, disk(2, 0.5, 0.125), inside)
            assertRelation(f, disk(2, 3.5, 0.125), inside)
            assertRelation(f, disk(0.5, 2, 0.125), outside)
            assertRelation(f, disk(2, 2, 0.125), inside)
        }
    }

    @Test fun retracedConnectorDoesNotBecomePaintBoundary() {
        val withSpike = square + listOf(p(0, 0), p(2, 2), p(0, 0))
        production(withSpike).use { f ->
            assertRelation(f, disk(1, 1, 0.25), inside)
            assertRelation(f, capsule(1, 1, 2, 2, 0.125), inside)
            assertRelation(f, disk(5, 2, 1), touch)
        }
    }

    @Test fun reversingAndRotatingSourcePreservesResult() {
        for (source in listOf(square, square.reversed(), square.drop(1) + square.take(1))) {
            production(source).use { f ->
                assertRelation(f, disk(2, 2, 0.5), inside)
                assertRelation(f, disk(5, 2, 1), touch)
                assertRelation(f, disk(6, 2, 1), outside)
            }
        }
    }

    @Test fun nonUniformScaleTangencyUsesLocalBrushNotPageCircle() {
        production(square).use { f ->
            val scaled = affine(2.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            assertRelation(f, disk(2.5, 2, 0.5), touch, scaled)
            assertRelation(f, disk(2.5, 2, 0.75), inside, scaled)
            assertRelation(f, disk(2.5, 2, 0.25), outside, scaled)
        }
    }

    @Test fun shearReflectionAndRotationUseExactInverseAffine() {
        production(square).use { f ->
            assertRelation(f, disk(0, 2, 0.25), inside,
                affine(1.0, 0.0, 1.0, 1.0, 0.0, 0.0))
            assertRelation(f, disk(2, 2, 0.25), inside,
                affine(-1.0, 0.0, 0.0, 1.0, 4.0, 0.0))
            assertRelation(f, disk(1, 1, 0.25), inside,
                affine(0.0, 1.0, -1.0, 0.0, 3.0, 1.0))
            assertRelation(f, disk(2, 2, 0.25), outside,
                affine(1.0, 0.0, 0.5, 1.0, 10.0, 10.0))
        }
    }

    @Test fun invalidCapsulesRejectBeforeExactWork() {
        for (q in listOf(disk(2, 2, 0), disk(2, 2, -1),
            disk(Double.NaN, 2, 1), disk(2, 2, Double.POSITIVE_INFINITY),
            capsule(1, 1, Double.NEGATIVE_INFINITY, 2, 1))) {
            production(square).use { f ->
                val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
                val result = Intersection.intersect(f.owner, f.region, q, identity)
                assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.INVALID_CAPSULE), result)
                assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun fabricatedFilledRegionFailsAuthenticationWithoutArithmetic() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner, f.parity, f.region.oddFaceIds)
            val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(f.owner, fake, disk(2, 2, 1), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.UNAUTHENTICATED_REGION), result)
            assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
        }
    }

    @Test fun foreignOwnerDoesNotTriggerCallbacksOrConsumeForeignBudget() {
        production(square).use { foreign ->
            var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), b))
            val before = callbacks
            val foreignWork = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(owner, foreign.region, disk(2, 2, 1), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.OWNER_MISMATCH), result)
            assertEquals(before, callbacks)
            assertEquals(foreignWork, foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.region.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun postCertificationOccurrenceMutationRejects() {
        production(square, mutableGroups = true).use { f ->
            val groups = f.groups!!
            val original = groups[0]
            groups[0] = original.copy(occurrences = original.occurrences + original.occurrences.first())
            val result = Intersection.intersect(f.owner, f.region, disk(2, 2, 1), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.GROUP_ASSOCIATION), result)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun workExhaustionIsTypedAndReleasesTemporaryBytes() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining = f.budget.limits.query.getValue(resource) - f.budget.used(resource)
            assertNull(f.budget.charge(resource, remaining))
            val result = Intersection.intersect(f.owner, f.region, disk(2, 2, 1), identity)
            assertTrue(result is Intersection.Result.Unresolved)
            assertEquals(resource, ((result as Intersection.Result.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationPropagatesAndClosesPrimaryOwner() {
        var armed = false
        val budget = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("capsule cancellation")
        })
        production(square, budget).use { f ->
            armed = true
            try {
                Intersection.intersect(f.owner, f.region, disk(2, 2, 1), identity)
                fail("Expected cancellation")
            } catch (_: CancellationException) { /* expected */ }
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun completedBorrowerRejectsGettersAfterOwnerCloses() {
        val f = production(square)
        val evidence = assertRelation(f, disk(2, 2, 0.25), inside)
        f.close()
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        try { evidence.relation; fail("Closed borrowed value remained accessible") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
