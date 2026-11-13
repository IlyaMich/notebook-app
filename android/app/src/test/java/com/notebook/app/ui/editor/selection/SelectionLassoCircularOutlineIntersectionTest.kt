package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoCircularOutlineIntersection as Intersection
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoCircularOutlineIntersectionTest {
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
    private fun affine(vararg coefficients: Double): CanonicalElementAffine =
        requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(coefficients.toList()))
    private fun frame(x: Number, y: Number, width: Number, height: Number) =
        CanonicalFrame(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
    private fun circle(x: Number, y: Number, diameter: Number, stroke: Number,
        filled: Boolean = false) = SelectionPaintGeometry.Primitive.Ellipse(
        frame(x, y, diameter, diameter), stroke.toDouble(), filled)
    private fun oval(x: Number, y: Number, width: Number, height: Number, stroke: Number) =
        SelectionPaintGeometry.Primitive.Ellipse(frame(x, y, width, height), stroke.toDouble(), false)
    private val inside = Intersection.Relation.INTERIOR_OVERLAP
    private val touch = Intersection.Relation.BOUNDARY_ONLY_CONTACT
    private val outside = Intersection.Relation.DISJOINT

    private fun assertRelation(
        f: Fixture, ellipse: SelectionPaintGeometry.Primitive.Ellipse,
        expected: Intersection.Relation, transform: CanonicalElementAffine = identity,
    ): Intersection.Borrowed {
        val before = SelectionQueryResource.entries.associateWith { f.budget.used(it) }
        val result = Intersection.intersect(f.owner, f.region, ellipse, transform)
        assertTrue("frame=$ellipse expected=$expected got=$result", result is Intersection.Result.Ready)
        val evidence = (result as Intersection.Result.Ready).value
        assertEquals(expected, evidence.relation)
        assertTrue(evidence.isOwnedBy(f.owner))
        assertTrue(evidence.isAuthenticatedFor(f.owner))
        assertSame(f.region, evidence.region)
        assertEquals(ellipse, evidence.ellipse)
        assertSame(transform, evidence.affine)
        for (resource in listOf(SelectionQueryResource.ARRANGEMENT_EDGES,
            SelectionQueryResource.ARRANGEMENT_EVENTS, SelectionQueryResource.ARRANGEMENT_FACES))
            assertEquals(before.getValue(resource), f.budget.used(resource))
        assertTrue(f.budget.used(SelectionQueryResource.WORK_UNITS) >
            before.getValue(SelectionQueryResource.WORK_UNITS))
        return evidence
    }

    @Test fun smallCircularOutlineInsideFilledSquareOverlaps() {
        production(square).use { f ->
            assertRelation(f, circle(1, 1, 2, 0.25), inside)
            assertRelation(f, circle(1.5, 1.5, 1, 0.125), inside)
        }
    }

    @Test fun circleAroundSquareWithHollowCenterDoesNotPaintSquare() {
        production(square).use { f ->
            assertRelation(f, circle(-6, -6, 16, 0.25), outside) // centered at (2,2), inner=7.75
        }
    }

    @Test fun circularOutlineOutsideSquareIsDisjoint() {
        production(square).use { f ->
            assertRelation(f, circle(8, 8, 2, 0.25), outside)
            assertRelation(f, circle(5, 1, 2, 0.25), outside)
        }
    }

    @Test fun outerCircularStrokeTangencyToSquareEdgeIsBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, circle(5, 1, 2, 1), touch) // center (6,2), outer=2
            assertRelation(f, circle(-3, 1, 2, 1), touch) // center (-2,2), outer=2
            assertRelation(f, circle(1, 5, 2, 1), touch)
            assertRelation(f, circle(1, -3, 2, 1), touch)
        }
    }

    @Test fun outerStrokeBeyondTangencyOverlapsAndBeforeTangencyMisses() {
        production(square).use { f ->
            assertRelation(f, circle(4, 1, 2, 0.5), inside) // center(5,2), outer 1.5
            assertRelation(f, circle(4, 1, 2, 0.125), inside)
            assertRelation(f, circle(5, 1, 2, 0.5), outside) // center(6,2), outer 1.5
        }
    }

    @Test fun outerStrokeTangencyToCornerIsBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, circle(5, 2, 4, 1), touch) // center(7,4), outer3 -> corner(4,4)
            assertRelation(f, circle(-5, 2, 4, 1), touch)
        }
    }

    @Test fun filledFlagDoesNotChangeOutlineOnlyRelation() {
        production(square).use { f ->
            assertRelation(f, circle(-6, -6, 16, 0.25, filled = false), outside)
            assertRelation(f, circle(-6, -6, 16, 0.25, filled = true), outside)
        }
    }

    @Test fun concentricLassoInsideHoleTouchesInnerRadiusOnlyAtDiamondVertices() {
        val diamond = listOf(p(2,0), p(4,2), p(2,4), p(0,2))
        production(diamond).use { f ->
            // Circle center (2,2), centerline R=3. Inner radius 2 when stroke=1.
            assertRelation(f, circle(-1, -1, 6, 1), touch)
            assertRelation(f, circle(-1, -1, 6, 0.5), outside)
            assertRelation(f, circle(-1, -1, 6, 1.25), inside)
        }
    }

    @Test fun circleEntirelyInsideEvenNestedHoleIsDisjoint() {
        production(nested).use { f ->
            assertRelation(f, circle(2.5, 2.5, 1, 0.25), outside)
            assertRelation(f, circle(2.25, 2.25, 1.5, 0.25), touch) // outer radius 1 at hole edge
            assertRelation(f, circle(2, 2, 2, 0.25), inside)
        }
    }

    @Test fun ringCrossesLassoEdgeWithNoCenterInside() {
        production(square).use { f ->
            assertRelation(f, circle(3.5, 1, 2, 0.25), inside)
            assertRelation(f, circle(-1.5, 1, 2, 0.25), inside)
        }
    }

    @Test fun bowTieOddLobesAreNotReplacedWithBoundingBox() {
        production(bow).use { f ->
            assertRelation(f, circle(1.75, 0.25, 0.5, 0.125), inside)
            assertRelation(f, circle(1.75, 3.25, 0.5, 0.125), inside)
            assertRelation(f, circle(0.25, 1.75, 0.5, 0.125), outside)
        }
    }

    @Test fun evenRetracedConnectorDoesNotCreateFillBoundary() {
        val withSpike = square + listOf(p(0, 0), p(2, 2), p(0, 0))
        production(withSpike).use { f ->
            assertRelation(f, circle(1.25, 1.25, 0.5, 0.125), inside)
            assertRelation(f, circle(4.5, 1, 2, 0.25), outside)
        }
    }

    @Test fun reversingAndRotatingLassoPreservesRelation() {
        for (source in listOf(square, square.reversed(), square.drop(1) + square.take(1))) {
            production(source).use { f ->
                assertRelation(f, circle(1, 1, 2, 0.25), inside)
                assertRelation(f, circle(5, 1, 2, 1), touch)
                assertRelation(f, circle(8, 8, 2, 0.25), outside)
            }
        }
    }

    @Test fun nonUniformAffineTreatsLocalCircularOutlineAsEllipticalPaint() {
        production(square).use { f ->
            val scaled = affine(2.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            // Local center (2.5,2), page center (5,2). Page outer x reach is 2*(0.25+stroke).
            assertRelation(f, circle(2.25, 1.75, 0.5, 0.25), touch, scaled)
            assertRelation(f, circle(2.25, 1.75, 0.5, 0.375), inside, scaled)
            assertRelation(f, circle(2.25, 1.75, 0.5, 0.125), outside, scaled)
        }
    }

    @Test fun shearRotationAndReflectionKeepExactLocalCircleMetric() {
        production(square).use { f ->
            assertRelation(f, circle(0.75, 1.75, 0.5, 0.125), inside,
                affine(1.0, 0.0, 1.0, 1.0, 0.0, 0.0))
            assertRelation(f, circle(1.75, 1.75, 0.5, 0.125), inside,
                affine(-1.0, 0.0, 0.0, 1.0, 4.0, 0.0))
            assertRelation(f, circle(0.75, 0.75, 0.5, 0.125), inside,
                affine(0.0, 1.0, -1.0, 0.0, 3.0, 1.0))
        }
    }

    @Test fun genuineEllipseRemainsExplicitlyUnresolvedAndClosesOwner() {
        production(square).use { f ->
            val prior = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(f.owner, f.region,
                oval(1, 1, 4, 2, 0.25), identity)
            assertEquals(Intersection.Result.Unresolved(SelectionPredicateResult.Indeterminate(
                SelectionNumericalReason.UNCERTIFIED_COMPARISON)), result)
            assertEquals(prior, f.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            try { f.region.oddFaceIds; fail("Owner remained open after unresolved ellipse") }
            catch (_: IllegalStateException) { /* expected */ }
        }
    }

    @Test fun malformedFrameAndStrokeFailBeforeExactArithmetic() {
        for (e in listOf(circle(1, 1, 0, 0.25), circle(1, 1, 2, 0),
            circle(1, 1, 2, -1), circle(Double.NaN, 1, 2, 0.25),
            circle(1, 1, 2, Double.POSITIVE_INFINITY), oval(1, 1, Double.NaN, 2, 0.25))) {
            production(square).use { f ->
                val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
                val result = Intersection.intersect(f.owner, f.region, e, identity)
                assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.INVALID_ELLIPSE), result)
                assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun syntheticRegionCannotClaimAuthentication() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner, f.parity, f.region.oddFaceIds)
            val used = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(f.owner, fake, circle(1, 1, 2, 0.25), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.UNAUTHENTICATED_REGION), result)
            assertEquals(used, f.budget.used(SelectionQueryResource.WORK_UNITS))
        }
    }

    @Test fun foreignOwnerIsRejectedWithoutTouchingForeignBudget() {
        production(square).use { foreign ->
            var callbacks = 0
            val budget = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val primary = known(SelectionLassoSplitEvents.prepare(emptyList(), budget))
            val before = callbacks
            val work = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(primary, foreign.region,
                circle(1, 1, 2, 0.25), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.OWNER_MISMATCH), result)
            assertEquals(before, callbacks)
            assertEquals(work, foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertTrue(foreign.region.isAuthenticatedFor(foreign.owner))
        }
    }

    @Test fun occurrenceMutationAfterAuthenticationIsDetected() {
        production(square, mutableGroups = true).use { f ->
            val groups = f.groups!!
            val original = groups[0]
            groups[0] = original.copy(occurrences = original.occurrences + original.occurrences.first())
            val result = Intersection.intersect(f.owner, f.region, circle(1, 1, 2, 0.25), identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.GROUP_ASSOCIATION), result)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun stickyWorkBudgetFailureIsTypedAndClosesOwner() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining = f.budget.limits.query.getValue(resource) - f.budget.used(resource)
            assertNull(f.budget.charge(resource, remaining))
            val result = Intersection.intersect(f.owner, f.region, circle(1, 1, 2, 0.25), identity)
            assertTrue(result is Intersection.Result.Unresolved)
            assertEquals(resource, ((result as Intersection.Result.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun cancellationPropagatesAndClosesPrimary() {
        var armed = false
        val budget = SelectionQueryBudget(cancellationCheckpoint = {
            if (armed) throw CancellationException("outlined-circle cancelled")
        })
        production(square, budget).use { f ->
            armed = true
            try { Intersection.intersect(f.owner, f.region, circle(1, 1, 2, 0.25), identity)
                fail("Expected cancellation")
            } catch (_: CancellationException) { /* expected */ }
            assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun completedBorrowerExpiresAfterOwnerClose() {
        val f = production(square)
        val result = assertRelation(f, circle(1, 1, 2, 0.25), inside)
        f.close()
        assertTrue(result.isAuthenticatedFor(f.owner))
        try { result.relation; fail("Closed owner exposed borrowed relation") }
        catch (_: IllegalStateException) { /* expected */ }
    }
}
