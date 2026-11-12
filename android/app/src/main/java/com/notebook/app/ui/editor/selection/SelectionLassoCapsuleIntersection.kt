package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import com.notebook.app.ui.editor.viewport.DuPoint

/**
 * Exact, zero-tolerance open-interior intersection of an authenticated even-odd lasso with one
 * painted local capsule (including a circular disk when its centerline has zero length).
 *
 * Each active fill edge is inverse-transformed with exact rational affine coefficients. Its
 * exact minimum squared distance from the LOCAL capsule centerline is compared against the
 * exact squared local radius. A strict inequality proves that an active fill edge enters the
 * capsule's open interior, hence some open odd-side region overlaps the capsule. Otherwise the
 * connected open capsule has constant parity, determined at its exact centerline start point.
 * Non-strict equality distinguishes pure geometric boundary contact from disjointness.
 *
 * A transformed round brush need NOT remain circular in page DU. No page-space distance,
 * rounded affine corner, tolerance, paint-availability claim, page clip or selectable HIT is made.
 */
internal object SelectionLassoCapsuleIntersection {
    enum class Relation { INTERIOR_OVERLAP, BOUNDARY_ONLY_CONTACT, DISJOINT }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_REGION, SOURCE_ASSOCIATION,
        INVALID_CAPSULE, INVALID_AFFINE, FACE_MAPPING, GROUP_ASSOCIATION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: Filled.Borrowed,
        private val primitive: SelectionPaintGeometry.Primitive.Capsule,
        private val transform: CanonicalElementAffine,
        private val evidence: Relation,
    ) {
        val region: Filled.Borrowed get() { owner.segments; return source }
        val capsule: SelectionPaintGeometry.Primitive.Capsule get() { owner.segments; return primitive }
        val affine: CanonicalElementAffine get() { owner.segments; return transform }
        val relation: Relation get() { owner.segments; return evidence }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean =
            owner === prepared && this is Completed
    }
    private class Completed(
        owner: Prepared, source: Filled.Borrowed,
        primitive: SelectionPaintGeometry.Primitive.Capsule,
        transform: CanonicalElementAffine, evidence: Relation,
    ) : Borrowed(owner, source, primitive, transform, evidence)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(valid: Boolean, reason: InvalidReason) {
        if (!valid) throw Invalid(reason)
    }
    private fun <T> known(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_CAPSULE)
    }

    fun intersect(
        prepared: Prepared, region: Filled.Borrowed,
        capsule: SelectionPaintGeometry.Primitive.Capsule, affine: CanonicalElementAffine,
    ): Result {
        var success = false
        try {
            val parity: Parity.Borrowed
            val faces: Faces.Borrowed
            val approach: SelectionLassoContactApproachEvidence.Borrowed
            val components: SelectionLassoGraphComponents.Borrowed
            val cycles: SelectionLassoSuccessorCycles.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; region.oddFaceIds
                parity = region.parity
                parity.faceParities; parity.edgeGroupToggles; parity.oddFaceIds
                faces = parity.faces
                faces.faces; faces.counts; faces.walkToFace
                approach = faces.approach
                components = approach.components
                cycles = components.cycles
                adjacency = cycles.adjacency
                adjacency.edgeGroups; adjacency.vertices; adjacency.halfEdges
                cycles.halfEdgeToWalk
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val rays = approach.contacts
            val admission = components.admission
            verify(region.isOwnedBy(prepared) && parity.isOwnedBy(prepared) &&
                faces.isOwnedBy(prepared) && approach.isOwnedBy(prepared) &&
                rays.isOwnedBy(prepared) && rays.exterior.isOwnedBy(prepared) &&
                components.isOwnedBy(prepared) && admission.isOwnedBy(prepared) &&
                cycles.isOwnedBy(prepared) && cycles.permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(region.isAuthenticatedFor(prepared) && parity.isAuthenticatedFor(prepared) &&
                faces.isAuthenticatedFor(prepared) && approach.isAuthenticatedFor(prepared) &&
                rays.isAuthenticatedFor(prepared) && admission.isAuthenticatedFor(prepared),
                InvalidReason.UNAUTHENTICATED_REGION)
            verify(region.parity === parity && parity.faces === faces &&
                approach.components === components && rays.components === components &&
                admission.cycles === cycles && cycles.adjacency === adjacency,
                InvalidReason.SOURCE_ASSOCIATION)
            verify(capsule.start.x.isFinite() && capsule.start.y.isFinite() &&
                capsule.end.x.isFinite() && capsule.end.y.isFinite() &&
                capsule.radius.isFinite() && capsule.radius > 0.0,
                InvalidReason.INVALID_CAPSULE)
            val coefficients = affine.coefficients()
            verify(coefficients.size == 6 && coefficients.all(Double::isFinite),
                InvalidReason.INVALID_AFFINE)
            val result = known(prepared.exact {
                construct(prepared, region, parity, faces, cycles, adjacency,
                    capsule, affine, coefficients)
            })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) {
            return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally {
            if (!success) prepared.close()
        }
    }

    private fun SelectionExactArithmetic.construct(
        owner: Prepared, region: Filled.Borrowed, parity: Parity.Borrowed,
        faces: Faces.Borrowed, cycles: SelectionLassoSuccessorCycles.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
        capsule: SelectionPaintGeometry.Primitive.Capsule,
        affine: CanonicalElementAffine, c: List<Double>,
    ): Borrowed {
        chargeIncidentLassoWork()
        val groups = adjacency.edgeGroups
        val vertices = adjacency.vertices
        val halfEdges = adjacency.halfEdges
        val toWalk = cycles.halfEdgeToWalk
        val toFace = faces.walkToFace
        val faceRecords = faces.faces
        val faceParity = parity.faceParities
        val toggles = parity.edgeGroupToggles
        val counts = faces.counts
        verify(groups.size == counts.edgeGroupCount && vertices.size == counts.vertexCount &&
            faces.faceCount == faceRecords.size && faces.unboundedFaceId == 0 &&
            faceRecords.size == faceParity.size && toFace.size == counts.walkCount &&
            toWalk.size.toLong() == groups.size.toLong() * 2L && halfEdges.size == toWalk.size &&
            parity.verifiedConstraintCount == groups.size && toggles.size == groups.size &&
            region.oddFaceIds.isNotEmpty() && region.oddFaceIds == parity.oddFaceIds,
            InvalidReason.FACE_MAPPING)
        var oddAt = 0
        for (i in faceParity.indices) {
            chargeIncidentLassoWork(2)
            val f = faceRecords[i]; val p = faceParity[i]
            verify(f.id == i && f.isUnbounded == (i == 0) && p.faceId == i &&
                (i != 0 || !p.isOdd), InvalidReason.FACE_MAPPING)
            if (p.isOdd) {
                verify(oddAt < region.oddFaceIds.size && region.oddFaceIds[oddAt++] == i,
                    InvalidReason.FACE_MAPPING)
            }
        }
        verify(oddAt == region.oddFaceIds.size, InvalidReason.FACE_MAPPING)

        // The six binary Double coefficients are interpreted as exact rationals.
        retainRecord(1024)
        val a = fromDouble(c[0]); val b = fromDouble(c[1]); val d = fromDouble(c[3])
        val shear = fromDouble(c[2]); val tx = fromDouble(c[4]); val ty = fromDouble(c[5])
        val det = subtract(multiply(a, d), multiply(b, shear))
        verify(sign(det) != Sign.ZERO, InvalidReason.INVALID_AFFINE)
        val localStart = point(capsule.start)
        val localEnd = point(capsule.end)
        val radius = fromDouble(capsule.radius)
        val radiusSquared = multiply(radius, radius)
        val zero = rational(0); val one = rational(1)
        fun page(localPoint: Point): Point {
            chargeIncidentLassoWork(3)
            return point(add(add(multiply(a, localPoint.x), multiply(shear, localPoint.y)), tx),
                add(add(multiply(b, localPoint.x), multiply(d, localPoint.y)), ty))
        }
        fun local(pagePoint: Point): Point {
            chargeIncidentLassoWork(5)
            val x = subtract(pagePoint.x, tx); val y = subtract(pagePoint.y, ty)
            return point(divide(subtract(multiply(d, x), multiply(shear, y)), det),
                divide(subtract(multiply(a, y), multiply(b, x)), det))
        }
        val witness = page(localStart)
        var boundaryEntersOpenCapsule = false
        var boundaryTouchesClosedCapsule = false
        var witnessOdd = false
        var observed = 0L
        var previousStart = -1; var previousEnd = -1
        for (i in groups.indices) {
            chargeIncidentLassoWork(9)
            val group = groups[i]
            verify(group.startVertexId in vertices.indices && group.endVertexId in vertices.indices &&
                group.startVertexId < group.endVertexId && group.occurrences.isNotEmpty() &&
                (group.startVertexId > previousStart ||
                    group.startVertexId == previousStart && group.endVertexId > previousEnd),
                InvalidReason.GROUP_ASSOCIATION)
            previousStart = group.startVertexId; previousEnd = group.endVertexId
            val va = vertices[group.startVertexId]; val vb = vertices[group.endVertexId]
            verify(va.id == group.startVertexId && vb.id == group.endVertexId &&
                va.point.x.context === this && va.point.y.context === this &&
                vb.point.x.context === this && vb.point.y.context === this &&
                group.start.x.context === this && group.start.y.context === this &&
                group.end.x.context === this && group.end.y.context === this &&
                compare(group.start, va.point) == Sign.ZERO &&
                compare(group.end, vb.point) == Sign.ZERO &&
                compare(va.point, vb.point) == Sign.NEGATIVE &&
                toggles[i] == (group.occurrences.size % 2 == 1),
                InvalidReason.GROUP_ASSOCIATION)
            observed = Math.addExact(observed, group.occurrences.size.toLong())
            val forward = halfEdges[2 * i]; val reverse = halfEdges[2 * i + 1]
            verify(forward.id == 2 * i && reverse.id == 2 * i + 1 &&
                forward.edgeGroupIndex == i && reverse.edgeGroupIndex == i &&
                forward.twin == 2 * i + 1 && reverse.twin == 2 * i &&
                forward.origin == group.startVertexId && forward.destination == group.endVertexId &&
                reverse.origin == group.endVertexId && reverse.destination == group.startVertexId &&
                forward.canonicalForward && !reverse.canonicalForward,
                InvalidReason.GROUP_ASSOCIATION)
            val walkA = toWalk[2 * i]; val walkB = toWalk[2 * i + 1]
            verify(walkA in toFace.indices && walkB in toFace.indices, InvalidReason.FACE_MAPPING)
            val faceA = toFace[walkA]; val faceB = toFace[walkB]
            verify(faceA in faceParity.indices && faceB in faceParity.indices &&
                (faceParity[faceA].isOdd xor faceParity[faceB].isOdd) == toggles[i],
                InvalidReason.FACE_MAPPING)
            if (!toggles[i]) continue // Even retraces are not fill boundaries.
            val distance = squaredSegmentDistance(local(group.start), local(group.end),
                localStart, localEnd, zero, one)
            when (compare(distance, radiusSquared)) {
                Sign.NEGATIVE -> { boundaryEntersOpenCapsule = true; boundaryTouchesClosedCapsule = true }
                Sign.ZERO -> boundaryTouchesClosedCapsule = true
                Sign.POSITIVE -> Unit
            }
            // Exact half-open horizontal ray from the centerline witness.
            val startY = compare(group.start.y, witness.y)
            val endY = compare(group.end.y, witness.y)
            val straddles = (startY != Sign.POSITIVE && endY == Sign.POSITIVE) ||
                (endY != Sign.POSITIVE && startY == Sign.POSITIVE)
            if (straddles) {
                val side = sign(cross(vector(group.end, group.start), vector(witness, group.start)))
                val upward = compare(group.start.y, group.end.y) == Sign.NEGATIVE
                if ((upward && side == Sign.POSITIVE) || (!upward && side == Sign.NEGATIVE))
                    witnessOdd = !witnessOdd
            }
        }
        verify(observed == parity.verifiedOccurrenceCount, InvalidReason.SOURCE_ASSOCIATION)
        val relation = when {
            boundaryEntersOpenCapsule || witnessOdd -> Relation.INTERIOR_OVERLAP
            boundaryTouchesClosedCapsule -> Relation.BOUNDARY_ONLY_CONTACT
            else -> Relation.DISJOINT
        }
        retainRecord(256)
        chargeIncidentLassoWork() // final cancellation and resource checkpoint
        return Completed(owner, region, capsule, affine, relation)
    }

    private fun SelectionExactArithmetic.squared(a: Point): Rational =
        add(multiply(a.x, a.x), multiply(a.y, a.y))

    private fun SelectionExactArithmetic.dot(a: Point, b: Point): Rational =
        add(multiply(a.x, b.x), multiply(a.y, b.y))

    /** Squared distance from p to the closed local segment ab, including zero-length ab. */
    private fun SelectionExactArithmetic.pointSegmentSquared(
        p: Point, a: Point, b: Point, zero: Rational,
    ): Rational {
        chargeIncidentLassoWork(4)
        val ab = vector(b, a)
        val ap = vector(p, a)
        val lengthSquared = squared(ab)
        if (sign(lengthSquared) == Sign.ZERO) return squared(ap)
        val projection = dot(ap, ab)
        if (compare(projection, zero) != Sign.POSITIVE) return squared(ap)
        if (compare(projection, lengthSquared) != Sign.NEGATIVE) return squared(vector(p, b))
        val area = cross(ab, ap)
        return divide(multiply(area, area), lengthSquared)
    }

    /** Exact distance between two CLOSED line segments. Crossing gives zero without rounding. */
    private fun SelectionExactArithmetic.squaredSegmentDistance(
        a: Point, b: Point, c: Point, d: Point, zero: Rational, one: Rational,
    ): Rational {
        chargeIncidentLassoWork(5)
        val ab = vector(b, a)
        val cd = vector(d, c)
        val ac = vector(c, a)
        val denom = cross(ab, cd)
        if (sign(denom) != Sign.ZERO) {
            val t = divide(cross(ac, cd), denom)
            val u = divide(cross(ac, ab), denom)
            if (compare(t, zero) != Sign.NEGATIVE && compare(t, one) != Sign.POSITIVE &&
                compare(u, zero) != Sign.NEGATIVE && compare(u, one) != Sign.POSITIVE)
                return zero
        }
        var best = pointSegmentSquared(a, c, d, zero)
        for (candidate in listOf(pointSegmentSquared(b, c, d, zero),
            pointSegmentSquared(c, a, b, zero), pointSegmentSquared(d, a, b, zero))) {
            chargeIncidentLassoWork()
            if (compare(candidate, best) == Sign.NEGATIVE) best = candidate
        }
        return best
    }
}
