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

class SelectionLassoFrameOutlineIntersectionTest {
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

    private fun outline(x: Number, y: Number, w: Number, h: Number, radius: Number) =
        SelectionPaintGeometry.Primitive.FrameOutline(
            CanonicalFrame(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()),
            radius.toDouble())

    private fun assertRelation(
        f: Fixture, outline: SelectionPaintGeometry.Primitive.FrameOutline,
        expected: Intersection.Relation, transform: CanonicalElementAffine = identity,
    ): Intersection.OutlineBorrowed {
        val before = SelectionQueryResource.entries.associateWith { f.budget.used(it) }
        val result = Intersection.intersectOutline(f.owner, f.region, outline, transform)
        assertTrue("outline=$outline expected=$expected got=$result", result is Intersection.OutlineResult.Ready)
        val evidence = (result as Intersection.OutlineResult.Ready).value
        assertEquals(expected, evidence.relation)
        assertTrue(evidence.isOwnedBy(f.owner))
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        assertSame(f.region, evidence.region)
        assertSame(outline, evidence.outline)
        assertSame(transform, evidence.affine)
        for (resource in listOf(SelectionQueryResource.ARRANGEMENT_EDGES,
            SelectionQueryResource.ARRANGEMENT_EVENTS, SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(before.getValue(resource), f.budget.used(resource))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >
            before.getValue(SelectionQueryResource.WORK_UNITS))
        return evidence
    }

    @Test fun outlineInsideLassoSharesOpenInterior() {
        production(square).use { f ->
            assertRelation(f, outline(1, 1, 2, 2, 0.125), inside)
            assertRelation(f, outline(0, 0, 4, 4, 0.25), inside)
        }
    }

    @Test fun enclosingUnfilledFrameOutlineDoesNotBehaveLikeFilledFrame() {
        production(square).use { f ->
            assertRelation(f, outline(-10, -10, 24, 24, 0.25), outside)
            assertRelation(f, outline(-3, -3, 10, 10, 0.25), outside)
        }
    }

    @Test fun parallelSideTangencyAtRadiusIsBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, outline(5, 1, 2, 2, 1), touch)
            assertRelation(f, outline(5, 1, 2, 2, 0.75), outside)
            assertRelation(f, outline(5, 1, 2, 2, 1.25), inside)
        }
    }

    @Test fun cornerThreeFourFiveTangencyIsExact() {
        production(square).use { f ->
            assertRelation(f, outline(7, 8, 2, 2, 5), touch)
            assertRelation(f, outline(7, 8, 2, 2, 4.5), outside)
            assertRelation(f, outline(7, 8, 2, 2, 5.5), inside)
        }
    }

    @Test fun filledRectangleInHoleButOutlinedBorderBeyondHoleHits() {
        production(nested).use { f ->
            assertRelation(f, outline(2.5, 2.5, 1, 1, 0.25), outside)
            assertRelation(f, outline(2.5, 2.5, 1, 1, 0.5), touch)
            assertRelation(f, outline(2.5, 2.5, 1, 1, 0.75), inside)
        }
    }

    @Test fun frameOutsideWithOnlyOneSideCrossingLassoIntersects() {
        production(square).use { f ->
            assertRelation(f, outline(3, 1, 4, 2, 0.125), inside)
            assertRelation(f, outline(-1, 1, 2, 2, 0.125), inside)
        }
    }

    @Test fun thinOutlineWithFillFullyContainedButUnpaintedCenterIsNotAHit() {
        production(square).use { f ->
            assertRelation(f, outline(-20, -20, 44, 44, 1), outside)
            assertRelation(f, outline(-20, -20, 44, 44, 20), touch)
            assertRelation(f, outline(-20, -20, 44, 44, 21), inside)
        }
    }

    @Test fun nonUniformScalingUsesLocalRadiusAndNotPageCircle() {
        val stretch = affine(2.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        production(square).use { f ->
            assertRelation(f, outline(2.5, 1, 1, 2, 0.5), touch, stretch)
            assertRelation(f, outline(2.5, 1, 1, 2, 0.25), outside, stretch)
            assertRelation(f, outline(2.5, 1, 1, 2, 0.75), inside, stretch)
        }
    }

    @Test fun rotatedReflectedAndShearedOutlinesUseExactInverseAffine() {
        production(square).use { f ->
            assertRelation(f, outline(0, 0, 2, 2, 0.125), inside,
                affine(0.0, 1.0, -1.0, 0.0, 3.0, 1.0))
            assertRelation(f, outline(0, 0, 2, 2, 0.125), inside,
                affine(-1.0, 0.0, 0.0, 1.0, 4.0, 1.0))
            assertRelation(f, outline(0, 0, 2, 2, 0.125), inside,
                affine(1.0, 0.0, 0.5, 1.0, 0.0, 0.5))
        }
    }

    @Test fun bowTieLobeAndEvenWedgeAreDistinct() {
        production(bow).use { f ->
            assertRelation(f, outline(1.8, 0.4, 0.4, 0.2, 0.125), inside)
            assertRelation(f, outline(1.8, 3.4, 0.4, 0.2, 0.125), inside)
            assertRelation(f, outline(-2, 1.5, 0.4, 1, 0.125), outside)
        }
    }

    @Test fun retracedConnectorIsNotAnIndependentBoundary() {
        val withSpike = square + listOf(p(0, 0), p(2, 2), p(0, 0))
        production(withSpike).use { f ->
            assertRelation(f, outline(1, 1, 1, 1, 0.125), inside)
            assertRelation(f, outline(5, 1, 2, 2, 1), touch)
        }
    }

    @Test fun reversalAndStartRotationRetainOutlineRelation() {
        for (source in listOf(square, square.reversed(), square.drop(1) + square.take(1))) {
            production(source).use { f ->
                assertRelation(f, outline(1, 1, 2, 2, 0.125), inside)
                assertRelation(f, outline(5, 1, 2, 2, 1), touch)
                assertRelation(f, outline(7, 1, 2, 2, 1), outside)
            }
        }
    }

    @Test fun exactUnrepresentableCornerDoesNotRoundDownToFrameOrigin() {
        val base = 9007199254740992.0 // 2^53: base + 1 is NOT representable as Double
        val far = base + 2.0
        val far2 = base + 4.0
        val largeSquare = listOf(DuPoint(far, 0.0), DuPoint(far2, 0.0),
            DuPoint(far2, 4.0), DuPoint(far, 4.0))
        production(largeSquare).use { f ->
            assertTrue("The fixture must expose Double rounding at 2^53", base == base + 1.0)
            assertRelation(f, outline(base, 0, 1, 4, 0.5), outside)
            assertRelation(f, outline(base, 0, 1, 4, 1), touch)
            assertRelation(f, outline(base, 0, 1, 4, 1.5), inside)
        }
    }

    @Test fun unionMatchesFourExistingCapsuleClassificationsWhenCornersRepresentable() {
        production(square).use { f ->
            for (q in listOf(outline(1, 1, 2, 2, 0.25),
                outline(5, 1, 2, 2, 1), outline(7, 1, 2, 2, 1),
                outline(-5, 1, 2, 2, 1.25))) {
                val x = q.frame.x; val y = q.frame.y
                val x1 = x + q.frame.width; val y1 = y + q.frame.height
                val corners = listOf(DuPoint(x, y), DuPoint(x1, y),
                    DuPoint(x1, y1), DuPoint(x, y1))
                val relations = ArrayList<Intersection.Relation>()
                for (i in corners.indices) {
                    val capsule = SelectionPaintGeometry.Primitive.Capsule(corners[i],
                        corners[(i + 1) % 4], q.radius)
                    val r = Intersection.intersect(f.owner, f.region, capsule, identity)
                    assertTrue("Capsule result: $r", r is Intersection.Result.Ready)
                    relations.add((r as Intersection.Result.Ready).value.relation)
                }
                val expected = when {
                    inside in relations -> inside
                    touch in relations -> touch
                    else -> outside
                }
                assertRelation(f, q, expected)
            }
        }
    }

    @Test fun invalidFrameAndRadiusRejectBeforeArithmetic() {
        for (q in listOf(outline(0, 0, 0, 2, 1), outline(0, 0, -1, 2, 1),
            outline(0, 0, 2, 0, 1), outline(0, 0, 2, 2, 0),
            outline(Double.NaN, 0, 2, 2, 1),
            outline(0, 0, 2, 2, Double.POSITIVE_INFINITY))) {
            production(square).use { f ->
                val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
                val result = Intersection.intersectOutline(f.owner, f.region, q, identity)
                assertEquals(Intersection.OutlineResult.InvalidInput(Intersection.InvalidReason.INVALID_OUTLINE), result)
                assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun syntheticFilledRegionAndOutlineRecordsCannotAuthenticate() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner, f.parity, f.region.oddFaceIds)
            val q = outline(1, 1, 2, 2, 0.125)
            val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
            assertEquals(Intersection.OutlineResult.InvalidInput(Intersection.InvalidReason.UNAUTHENTICATED_REGION),
                Intersection.intersectOutline(f.owner, fake, q, identity))
            assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
        }
    }

    @Test fun foreignOwnerRejectedWithoutTouchingCallbacksOrForeignBudget() {
        production(square).use { foreign ->
            var callbacks = 0
            val budget = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val owner = known(SelectionLassoSplitEvents.prepare(emptyList(), budget))
            val before = callbacks
            val foreignWork = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersectOutline(owner, foreign.region,
                outline(1, 1, 2, 2, 0.125), identity)
            assertEquals(Intersection.OutlineResult.InvalidInput(Intersection.InvalidReason.OWNER_MISMATCH), result)
            assertEquals(before, callbacks)
            assertEquals(foreignWork, foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.region.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun mutableOccurrenceEvidenceRejectsAfterAuthentication() {
        production(square, mutableGroups = true).use { f ->
            val groups = f.groups!!
            val group = groups[0]
            groups[0] = group.copy(occurrences = group.occurrences + group.occurrences.first())
            val result = Intersection.intersectOutline(f.owner, f.region,
                outline(1, 1, 2, 2, 0.125), identity)
            assertEquals(Intersection.OutlineResult.InvalidInput(Intersection.InvalidReason.GROUP_ASSOCIATION), result)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationAndStickyWorkLimitFailClosed() {
        var armed = false
        val budget = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("frame outline cancelled")
        })
        production(square, budget).use { f ->
            armed = true
            try {
                Intersection.intersectOutline(f.owner, f.region, outline(1, 1, 2, 2, 0.25), identity)
                fail("Expected cancellation")
            } catch (_: CancellationException) { /* expected */ }
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining = f.budget.limits.query.getValue(resource) - f.budget.used(resource)
            assertNull(f.budget.charge(resource, remaining))
            val result = Intersection.intersectOutline(f.owner, f.region, outline(1, 1, 2, 2, 0.25), identity)
            assertTrue(result is Intersection.OutlineResult.Unresolved)
            assertEquals(resource, ((result as Intersection.OutlineResult.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun completedOutlineBorrowerEnforcesLifetimeAfterClose() {
        val f = production(square)
        val q = outline(1, 1, 2, 2, 0.125)
        val r = assertRelation(f, q, inside)
        val synthetic = Intersection.OutlineBorrowed(f.owner, f.region, q, identity, inside)
        assertFalse(synthetic.isAuthenticatedFor(f.owner))
        f.close()
        assertTrue(r.isAuthenticatedFor(f.owner))
        try { r.relation; fail("Closed owner leaked region relation") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
