package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign

/**
 * Exact zero-tolerance intersection between an authenticated lasso's OPEN odd region and the
 * OPEN interior of one filled ellipse, given by a positive-width/height local frame and a
 * non-singular element-to-page affine. This is NOT an outlined ellipse or an element HIT.
 *
 * All finite binary Double inputs are admitted as exact rationals. Local ellipse center,
 * radii and inverse-transformed lasso edges remain rational, including unrepresentable
 * frame.x + frame.width. For each active fill edge the minimum of the exact quadratic
 * ellipse equation on its closed segment is rational and compared with zero. A strict
 * negative minimum proves open interior overlap (odd-side neighborhood); equality alone
 * proves only boundary contact. If no active edge enters, the ellipse's center decides
 * the constant parity of its connected open interior. No tessellation, epsilon or roots.
 *
 * The query shares its owner's existing arithmetic arena and sticky resource budget. It does
 * not create new arrangement edges/faces, validate painted-source availability, or page-clip.
 */
internal object SelectionLassoFilledEllipseIntersection {
    enum class Relation { INTERIOR_OVERLAP, BOUNDARY_ONLY_CONTACT, DISJOINT }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_REGION, SOURCE_ASSOCIATION,
        INVALID_ELLIPSE, INVALID_AFFINE, FACE_MAPPING, GROUP_ASSOCIATION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: Filled.Borrowed,
        private val localFrame: CanonicalFrame,
        private val transform: CanonicalElementAffine,
        private val evidence: Relation,
    ) {
        val region: Filled.Borrowed get() { owner.segments; return source }
        val frame: CanonicalFrame get() { owner.segments; return localFrame }
        val affine: CanonicalElementAffine get() { owner.segments; return transform }
        val relation: Relation get() { owner.segments; return evidence }
        internal fun isOwnedBy(prepared: Prepared) = owner === prepared
        internal fun isAuthenticatedFor(prepared: Prepared) = owner === prepared && this is Completed
    }
    private class Completed(
        owner: Prepared, source: Filled.Borrowed, frame: CanonicalFrame,
        transform: CanonicalElementAffine, evidence: Relation,
    ) : Borrowed(owner, source, frame, transform, evidence)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) { if (!ok) throw Invalid(reason) }
    private fun <T> known(outcome: SelectionPredicateResult<T>): T = when (outcome) {
        is SelectionPredicateResult.Known -> outcome.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(outcome)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(outcome)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_ELLIPSE)
    }

    fun intersect(
        prepared: Prepared, region: Filled.Borrowed,
        frame: CanonicalFrame, affine: CanonicalElementAffine,
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
            verify(frame.x.isFinite() && frame.y.isFinite() && frame.width.isFinite() &&
                frame.height.isFinite() && frame.width > 0.0 && frame.height > 0.0,
                InvalidReason.INVALID_ELLIPSE)
            val coefficients = affine.coefficients()
            verify(coefficients.size == 6 && coefficients.all(Double::isFinite),
                InvalidReason.INVALID_AFFINE)
            val outcome = known(prepared.exact {
                classify(prepared, region, parity, faces, cycles, adjacency, frame, affine, coefficients)
            })
            success = true
            return Result.Ready(outcome)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) {
            return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.classify(
        owner: Prepared, region: Filled.Borrowed, parity: Parity.Borrowed,
        faces: Faces.Borrowed, cycles: SelectionLassoSuccessorCycles.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
        frame: CanonicalFrame, affine: CanonicalElementAffine, c: List<Double>,
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
        var nextOdd = 0
        for (i in faceParity.indices) {
            chargeIncidentLassoWork(2)
            val f = faceRecords[i]; val p = faceParity[i]
            verify(f.id == i && f.isUnbounded == (i == 0) && p.faceId == i &&
                (i != 0 || !p.isOdd), InvalidReason.FACE_MAPPING)
            if (p.isOdd) {
                verify(nextOdd < region.oddFaceIds.size && region.oddFaceIds[nextOdd++] == i,
                    InvalidReason.FACE_MAPPING)
            }
        }
        verify(nextOdd == region.oddFaceIds.size, InvalidReason.FACE_MAPPING)

        // Frame additions and affine inversion use exact rationals, not rounded Double corners.
        retainRecord(1024)
        val x0 = fromDouble(frame.x); val y0 = fromDouble(frame.y)
        val width = fromDouble(frame.width); val height = fromDouble(frame.height)
        val two = rational(2); val half = rational(1, 2)
        val cx = add(x0, multiply(width, half))
        val cy = add(y0, multiply(height, half))
        val rx = multiply(width, half); val ry = multiply(height, half)
        val rx2 = multiply(rx, rx); val ry2 = multiply(ry, ry)
        val rhs = multiply(rx2, ry2)
        val localCenter = point(cx, cy)
        val a = fromDouble(c[0]); val b = fromDouble(c[1])
        val shear = fromDouble(c[2]); val d = fromDouble(c[3])
        val tx = fromDouble(c[4]); val ty = fromDouble(c[5])
        val det = subtract(multiply(a, d), multiply(b, shear))
        verify(sign(det) != Sign.ZERO, InvalidReason.INVALID_AFFINE)
        val zero = rational(0); val one = rational(1)
        fun local(page: Point): Point {
            chargeIncidentLassoWork(5)
            val x = subtract(page.x, tx); val y = subtract(page.y, ty)
            return point(divide(subtract(multiply(d, x), multiply(shear, y)), det),
                divide(subtract(multiply(a, y), multiply(b, x)), det))
        }
        val witness = point(add(add(multiply(a, cx), multiply(shear, cy)), tx),
            add(add(multiply(b, cx), multiply(d, cy)), ty))
        var boundaryInside = false
        var boundaryTouches = false
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
            val forward = halfEdges[2 * i]; val backward = halfEdges[2 * i + 1]
            verify(forward.id == 2 * i && backward.id == 2 * i + 1 &&
                forward.edgeGroupIndex == i && backward.edgeGroupIndex == i &&
                forward.twin == 2 * i + 1 && backward.twin == 2 * i &&
                forward.origin == group.startVertexId && forward.destination == group.endVertexId &&
                backward.origin == group.endVertexId && backward.destination == group.startVertexId &&
                forward.canonicalForward && !backward.canonicalForward,
                InvalidReason.GROUP_ASSOCIATION)
            val wa = toWalk[2 * i]; val wb = toWalk[2 * i + 1]
            verify(wa in toFace.indices && wb in toFace.indices, InvalidReason.FACE_MAPPING)
            val fa = toFace[wa]; val fb = toFace[wb]
            verify(fa in faceParity.indices && fb in faceParity.indices &&
                (faceParity[fa].isOdd xor faceParity[fb].isOdd) == toggles[i],
                InvalidReason.FACE_MAPPING)
            if (!toggles[i]) continue // An even retrace is not a fill boundary.

            // Exact minimum of F(P(t)) for P(t) on the closed local lasso edge, t in [0,1].
            val first = local(group.start); val last = local(group.end)
            val u = vector(first, localCenter); val v = vector(last, first)
            val vx2 = multiply(v.x, v.x); val vy2 = multiply(v.y, v.y)
            val quadratic = add(multiply(ry2, vx2), multiply(rx2, vy2))
            verify(sign(quadratic) == Sign.POSITIVE, InvalidReason.GROUP_ASSOCIATION)
            val linear = multiply(two, add(multiply(ry2, multiply(u.x, v.x)),
                multiply(rx2, multiply(u.y, v.y))))
            val originF = subtract(add(multiply(ry2, multiply(u.x, u.x)),
                multiply(rx2, multiply(u.y, u.y))), rhs)
            val endpointF = add(add(quadratic, linear), originF)
            var minimum = if (compare(originF, endpointF) == Sign.NEGATIVE) originF else endpointF
            val candidate = divide(subtract(zero, linear), multiply(two, quadratic))
            if (compare(candidate, zero) == Sign.POSITIVE &&
                compare(candidate, one) == Sign.NEGATIVE) {
                val atVertex = add(add(multiply(quadratic, multiply(candidate, candidate)),
                    multiply(linear, candidate)), originF)
                if (compare(atVertex, minimum) == Sign.NEGATIVE) minimum = atVertex
            }
            when (sign(minimum)) {
                Sign.NEGATIVE -> { boundaryInside = true; boundaryTouches = true }
                Sign.ZERO -> boundaryTouches = true
                Sign.POSITIVE -> Unit
            }
            // Exact half-open horizontal ray from the ellipse center in page coordinates.
            val ay = compare(group.start.y, witness.y)
            val by = compare(group.end.y, witness.y)
            val crossing = (ay != Sign.POSITIVE && by == Sign.POSITIVE) ||
                (by != Sign.POSITIVE && ay == Sign.POSITIVE)
            if (crossing) {
                val side = sign(cross(vector(group.end, group.start),
                    vector(witness, group.start)))
                val upward = compare(group.start.y, group.end.y) == Sign.NEGATIVE
                if ((upward && side == Sign.POSITIVE) || (!upward && side == Sign.NEGATIVE))
                    witnessOdd = !witnessOdd
            }
        }
        verify(observed == parity.verifiedOccurrenceCount, InvalidReason.SOURCE_ASSOCIATION)
        val relation = when {
            boundaryInside || witnessOdd -> Relation.INTERIOR_OVERLAP
            boundaryTouches -> Relation.BOUNDARY_ONLY_CONTACT
            else -> Relation.DISJOINT
        }
        retainRecord(256)
        chargeIncidentLassoWork() // final budget and cancellation gate before completion
        return Completed(owner, region, frame, affine, relation)
    }
}
