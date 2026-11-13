package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry as Coincident
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoGeneralEllipseOutlineCertificates as Intersection
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionLassoGeneralEllipseOutlineCertificatesTest {
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

    @Test fun narrowOvalFullyWithinOddSquareIsCertifiedOverlap() {
        production(listOf(p(0,0),p(10,0),p(10,10),p(0,10))).use { f ->
            assertRelation(f, oval(3,4,4,2,0.25), inside)
            assertRelation(f, oval(4,4,2,1,0.25), inside)
        }
    }

    @Test fun farOvalAndSeparatedOuterStrokeAreCertifiedDisjoint() {
        production(square).use { f ->
            assertRelation(f, oval(8,8,4,2,0.25), outside)
            assertRelation(f, oval(4.5,1,4,2,0.25), outside)
            assertRelation(f, oval(-12,-8,2,1,0.125), outside)
        }
    }

    @Test fun largeHollowOvalContainingSquareIsNotASelectionHit() {
        production(square).use { f ->
            assertRelation(f, oval(-10,-6,24,16,0.25), outside)
        }
    }

    @Test fun CrossingCenterlineWithExteriorWitnessProvesOverlap() {
        production(square).use { f ->
            assertRelation(f, oval(3.5,1,4,2,0.25), inside)
            assertRelation(f, oval(-3.5,1,4,2,0.25), inside)
        }
    }

    @Test fun ArcPointRadiusStrictInequalityProvesOverlap() {
        production(square).use { f ->
            assertRelation(f, oval(4.5,1,4,2,0.75), inside)
            assertRelation(f, oval(4.5,1,4,2,1.0), inside)
        }
    }

    @Test fun ExactAxisTangencyIsCertifiedBoundaryOnly() {
        production(square).use { f ->
            assertRelation(f, oval(4.5, 1, 4, 2, 0.5), touch)
            assertRelation(f, oval(-4.5, 1, 4, 2, 0.5), touch)
            assertRelation(f, oval(1, 4.5, 2, 4, 0.5), touch)
            assertRelation(f, oval(1, -4.5, 2, 4, 0.5), touch)
        }
    }

    @Test fun TouchAtOnlyAnOddBoundaryVertexStillProvesContact() {
        production(triangle).use { f ->
            assertRelation(f, oval(4.5, -1, 4, 2, 0.5), touch)
        }
    }

    @Test fun CertifiedAxialTouchSurvivesReflectionAndShear() {
        production(square).use { f ->
            assertRelation(f, oval(4.5, 1, 4, 2, 0.5), touch,
                affine(-1.0, 0.0, 0.0, 1.0, 13.0, 0.0))
        }
        val shearedSquare = listOf(p(0,0),p(4,0),p(6,4),p(2,4))
        production(shearedSquare).use { f ->
            assertRelation(f, oval(4.5, 1, 4, 2, 0.5), touch,
                affine(1.0, 0.0, 0.5, 1.0, 0.0, 0.0))
        }
    }

    @Test fun ExactObliqueThreeFourSupportTangencyIsCertified() {
        // Ellipse radii 4 and 5/4, stroke 1/2. Unit normal u=(3/5,4/5).
        // The exact ellipse normal at rational quarter parameter t=1/5 is u.
        // Support: 3*x+4*y=31/2; source triangle is beyond it and its odd
        // edge contains the unique stroked-ellipse support point.
        val lasso = listOf(p(1.5, 2.75), p(5.5, -0.25), p(6.5, 3))
        production(lasso).use { f ->
            assertRelation(f, oval(-4, -1.25, 8, 2.5, 0.5), touch)
        }
    }

    @Test fun ExactObliqueFourThreeSupportTangencyIsCertified() {
        // Ellipse radii (2,5), outward unit normal (4/5,3/5).
        // t=3/5 yields exact stroked support 4*x+3*y=39/2.
        val lasso = listOf(p(0, 6.5), p(4.875, 0), p(6, 8))
        production(lasso).use { f ->
            assertRelation(f, oval(-2, -5, 4, 10, 0.5), touch)
        }
    }

    @Test fun ExactObliqueFiveTwelveSupportTangencyIsCertified() {
        // Ellipse radii (9,2), u=(5/13,12/13), t=1/4.
        // Unique stroke support: 5*x+12*y=115/2.
        val lasso = listOf(p(-0.5, 5), p(11.5, 0), p(12.5, 6))
        production(lasso).use { f ->
            assertRelation(f, oval(-9, -2, 18, 4, 0.5), touch)
        }
    }

    @Test fun ObliqueContactCertificateSurvivesReflectionAndShear() {
        val base = listOf(p(1.5, 2.75), p(5.5, -0.25), p(6.5, 3))
        // Exactly representable reflection and shear preserve support contact.
        val reflected = base.map { p(10.0 - it.x, it.y) }
        production(reflected).use { f ->
            assertRelation(f, oval(-4, -1.25, 8, 2.5, 0.5), touch,
                affine(-1.0, 0.0, 0.0, 1.0, 10.0, 0.0))
        }
        val sheared = base.map { p(it.x + 0.5 * it.y + 2.0, it.y) }
        production(sheared).use { f ->
            assertRelation(f, oval(-4, -1.25, 8, 2.5, 0.5), touch,
                affine(1.0, 0.0, 0.5, 1.0, 2.0, 0.0))
        }
    }

    @Test fun StrictlyMovingAcrossObliqueSupportDoesNotCreateFalseTangency() {
        val base = listOf(p(1.5, 2.75), p(5.5, -0.25), p(6.5, 3))
        production(base.map { p(it.x - 0.25, it.y) }).use { f ->
            assertRelation(f, oval(-4, -1.25, 8, 2.5, 0.5), inside)
        }
        production(base.map { p(it.x + 0.25, it.y) }).use { f ->
            assertRelation(f, oval(-4, -1.25, 8, 2.5, 0.5), outside)
        }
    }

    @Test fun UnsampledEightFifteenExactTangencyRemainsUnresolved() {
        // A real nonaxial exact tangency at direction (8/17,15/17).
        // The finite rational unit-normal table intentionally does NOT include it.
        // Radii (5,2), stroke 1/2, support 8*x+15*y=117/2.
        val lasso = listOf(p(2.625, 2.5), p(6.375, 0.5), p(9, 5))
        production(lasso).use { f ->
            val result = Intersection.intersect(f.owner, f.region,
                oval(-5, -2, 10, 4, 0.5), identity)
            assertTrue("Uncertified exact oblique tangency must not be guessed: $result",
                result is Intersection.Result.Unresolved)
            assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun TangencyProofCannotOverrideRealInteriorOverlap() {
        production(square).use { f ->
            assertRelation(f, oval(4.5, 1, 4, 2, 0.625), inside)
            assertRelation(f, oval(4.5, 1, 4, 2, 0.375), outside)
            assertRelation(f, oval(1, 1, 4, 2, 0.5), inside)
        }
    }

    @Test fun EvenHoleInteriorDisjointButAnnulusCrossingOverlaps() {
        production(nested).use { f ->
            assertRelation(f, oval(2.5,2.75,1,0.5,0.125), outside)
            assertRelation(f, oval(1.5,2.7,2,0.6,0.25), inside)
        }
    }

    @Test fun BowTieOuterEvenWingsRemainDisjoint() {
        production(bow).use { f ->
            assertRelation(f, oval(0.2,1.7,0.8,0.6,0.1), outside)
            assertRelation(f, oval(1.5,0.3,1,0.4,0.25), inside)
        }
    }

    @Test fun RetracedEvenConnectorDoesNotCreateBoundary() {
        val spike = square + listOf(p(0,0),p(2,2),p(0,0))
        production(spike).use { f ->
            assertRelation(f, oval(1.0,1.0,1.0,0.5,0.1), inside)
        }
    }

    @Test fun ReversingOrRotatingSourcePreservesCertificates() {
        for (input in listOf(square,square.reversed(),square.drop(2)+square.take(2))) {
            production(input).use { f ->
                assertRelation(f, oval(4.5,1,4,2,0.25), outside)
                assertRelation(f, oval(3.5,1,4,2,0.25), inside)
            }
        }
    }

    @Test fun ReflectionShearAndScaleUseExactLocalStrokeDistance() {
        production(square).use { f ->
            assertRelation(f, oval(0,0,4,2,0.25), inside,
                affine(1.0,0.0,0.5,1.0,1.0,1.0))
            assertRelation(f, oval(0,0,4,2,0.25), inside,
                affine(-1.0,0.0,0.0,1.0,4.0,1.0))
            assertRelation(f, oval(0,0,4,2,0.25), outside,
                affine(2.0,0.0,0.0,0.5,10.0,10.0))
        }
    }

    @Test fun LargeUnrepresentableBinaryCornerNeverUsesRoundedFrameAddition() {
        val huge = 9007199254740992.0 // 2^53: base + 1 would round to base
        assertTrue(huge == huge + 1.0)
        val q = listOf(p(huge-4,0),p(huge+4,0),p(huge+4,4),p(huge-4,4))
        production(q).use { f ->
            assertRelation(f, oval(huge,1,1,0.5,0.125), inside)
        }
    }

    @Test fun CircularAndMalformedFramesRejectWithoutExactWork() {
        for (invalid in listOf(oval(0,0,2,0,0.25), oval(0,0,2,1,0),
            oval(Double.NaN,0,2,1,0.25), oval(0,0,2,1,Double.POSITIVE_INFINITY),
            circle(0,0,2,0.25))) {
            production(square).use { f ->
                val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
                val result = Intersection.intersect(f.owner, f.region, invalid, identity)
                assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.INVALID_ELLIPSE), result)
                assertEquals(before, f.budget.used(SelectionQueryResource.WORK_UNITS))
                assertEquals(0L, f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
            }
        }
    }

    @Test fun FabricatedRegionIsRejectedBeforeArithmetic() {
        production(square).use { f ->
            val fake = Filled.Borrowed(f.owner,f.parity,f.region.oddFaceIds)
            val before = f.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(f.owner,fake,oval(1,1,2,1,0.25),identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.UNAUTHENTICATED_REGION),result)
            assertEquals(before,f.budget.used(SelectionQueryResource.WORK_UNITS))
        }
    }

    @Test fun ForeignOwnerDoesNotChargeOrCancelForeignArena() {
        production(square).use { foreign ->
            var callbacks = 0
            val b = SelectionQueryBudget(cancellationCheckpoint = { callbacks++ })
            val primary = known(SelectionLassoSplitEvents.prepare(emptyList(),b))
            val before = callbacks
            val foreignUsed = foreign.budget.used(SelectionQueryResource.WORK_UNITS)
            val result = Intersection.intersect(primary,foreign.region,oval(1,1,2,1,0.25),identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.OWNER_MISMATCH),result)
            assertEquals(before,callbacks)
            assertEquals(foreignUsed,foreign.budget.used(SelectionQueryResource.WORK_UNITS))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun MutationOfCertifiedOccurrenceFailsClosed() {
        production(square,mutableGroups=true).use { f ->
            val groups=f.groups!!; val original=groups[0]
            groups[0]=original.copy(occurrences=original.occurrences+original.occurrences.first())
            val result=Intersection.intersect(f.owner,f.region,oval(1,1,2,1,0.25),identity)
            assertEquals(Intersection.Result.InvalidInput(Intersection.InvalidReason.GROUP_ASSOCIATION),result)
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun StickyWorkExhaustionIsTypedAndReleasesArena() {
        production(square).use { f ->
            val resource = SelectionQueryResource.WORK_UNITS
            val remaining=f.budget.limits.query.getValue(resource)-f.budget.used(resource)
            assertNull(f.budget.charge(resource,remaining))
            val result=Intersection.intersect(f.owner,f.region,oval(1,1,2,1,0.25),identity)
            assertTrue(result is Intersection.Result.Unresolved)
            assertEquals(resource,((result as Intersection.Result.Unresolved).cause as
                SelectionPredicateResult.WorkloadExceeded).failure.resource)
            assertEquals(0L,f.budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun CancellationClosesOwnerAndPropagates() {
        var armed=false
        val b=SelectionQueryBudget(cancellationCheckpoint={
            if(armed)throw CancellationException("noncircular ellipse cancelled")
        })
        production(square,b).use { f ->
            armed=true
            try {
                Intersection.intersect(f.owner,f.region,oval(1,1,2,1,0.25),identity)
                fail("Expected cancellation")
            } catch (_:CancellationException) { /* expected */ }
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun CompletedBorrowerEnforcesOwnerLifetime() {
        val f=production(square)
        val result=assertRelation(f,oval(1,1,2,1,0.25),inside)
        f.close()
        assertTrue(result.isAuthenticatedFor(f.owner))
        try { result.relation; fail("Closed borrower getter succeeded") }
        catch (_:IllegalStateException) { /* expected */ }
    }
}
