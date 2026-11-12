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
 * Zero-tolerance exact intersection of an admitted even-odd lasso's OPEN odd interior with
 * a nondegenerate affine image of a closed, filled local DU rectangle.
 *
 * INTERIOR_OVERLAP proves that the interiors of both sets meet in a nonempty open region.
 * BOUNDARY_ONLY_CONTACT proves the two sets have no common interior, but the frame meets a
 * certified odd-multiplicity fill boundary. It is NOT a selection HIT policy.
 * DISJOINT proves neither of those relations. No painted elements, rasterization, page clipping,
 * finite DU tolerance, image availability or gesture thresholds are decided here.
 *
 * Soundness: every active edge separates odd and even faces. If an active edge enters the
 * convex frame's open interior, a small open odd-side neighborhood lies inside the frame.
 * Otherwise the parity is constant throughout the frame's connected open interior. Its exact
 * diagonal midpoint decides overlap. Closed clipping of active edges then distinguishes pure
 * boundary contact from disjointness. The half-open parity ray and all clips are exact rationals.
 * No transformed corner is rounded to Double.
 */
internal object SelectionLassoFilledFrameIntersection {
    enum class Relation { INTERIOR_OVERLAP, BOUNDARY_ONLY_CONTACT, DISJOINT }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_REGION, SOURCE_ASSOCIATION,
        INVALID_FRAME, INVALID_AFFINE, FACE_MAPPING, GROUP_ASSOCIATION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val evidence: Filled.Borrowed,
        private val frameSource: CanonicalFrame,
        private val affineSource: CanonicalElementAffine,
        private val found: Relation,
    ) {
        val region: Filled.Borrowed get() { owner.segments; return evidence }
        val frame: CanonicalFrame get() { owner.segments; return frameSource }
        val affine: CanonicalElementAffine get() { owner.segments; return affineSource }
        val relation: Relation get() { owner.segments; return found }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean =
            owner === prepared && this is Completed
    }
    private class Completed(
        owner: Prepared, source: Filled.Borrowed, frame: CanonicalFrame,
        affine: CanonicalElementAffine, relation: Relation,
    ) : Borrowed(owner, source, frame, affine, relation)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) {
        if (!ok) throw Invalid(reason)
    }
    private fun <T> known(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_FRAME)
    }

    fun intersect(
        prepared: Prepared, region: Filled.Borrowed, frame: CanonicalFrame,
        affine: CanonicalElementAffine,
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
                prepared.segments
                region.oddFaceIds
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
            // Only pure source/frame validation happens before entering the one shared arena.
            verify(frame.x.isFinite() && frame.y.isFinite() && frame.width.isFinite() &&
                frame.height.isFinite() && frame.width > 0.0 && frame.height > 0.0,
                InvalidReason.INVALID_FRAME)
            val c = affine.coefficients()
            verify(c.size == 6 && c.all { it.isFinite() }, InvalidReason.INVALID_AFFINE)
            val result = known(prepared.exact {
                construct(prepared, region, parity, faces, cycles, adjacency, frame, affine, c)
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

        // Admit the fixed four-point exact frame and its four clipping constraints.
        retainRecord(1024)
        val lx = fromDouble(frame.x); val ly = fromDouble(frame.y)
        val rx = add(lx, fromDouble(frame.width)); val by = add(ly, fromDouble(frame.height))
        val coeff = c.map { fromDouble(it) }
        fun transformed(x: Rational, y: Rational): Point {
            chargeIncidentLassoWork(3)
            return point(add(add(multiply(coeff[0], x), multiply(coeff[2], y)), coeff[4]),
                add(add(multiply(coeff[1], x), multiply(coeff[3], y)), coeff[5]))
        }
        val quad = listOf(transformed(lx, ly), transformed(rx, ly),
            transformed(rx, by), transformed(lx, by))
        val winding = sign(cross(vector(quad[1], quad[0]), vector(quad[2], quad[0])))
        verify(winding != Sign.ZERO, InvalidReason.INVALID_AFFINE)
        val half = rational(1, 2)
        val midpoint = point(multiply(add(quad[0].x, quad[2].x), half),
            multiply(add(quad[0].y, quad[2].y), half))

        var previousStart = -1; var previousEnd = -1; var observed = 0L
        var boundaryInOpenFrame = false
        var boundaryTouchesClosedFrame = false
        var midpointOdd = false
        val center = midpoint
        for (i in groups.indices) {
            chargeIncidentLassoWork(9)
            val group = groups[i]
            verify(group.startVertexId in vertices.indices && group.endVertexId in vertices.indices &&
                group.startVertexId < group.endVertexId && group.occurrences.isNotEmpty() &&
                (group.startVertexId > previousStart ||
                    group.startVertexId == previousStart && group.endVertexId > previousEnd),
                InvalidReason.GROUP_ASSOCIATION)
            previousStart = group.startVertexId; previousEnd = group.endVertexId
            val va = vertices[group.startVertexId]
            val vb = vertices[group.endVertexId]
            verify(va.id == group.startVertexId && vb.id == group.endVertexId &&
                va.point.x.context === this && va.point.y.context === this &&
                vb.point.x.context === this && vb.point.y.context === this &&
                group.start.x.context === this && group.start.y.context === this &&
                group.end.x.context === this && group.end.y.context === this &&
                compare(group.start, va.point) == Sign.ZERO &&
                compare(group.end, vb.point) == Sign.ZERO &&
                compare(va.point, vb.point) == Sign.NEGATIVE &&
                toggles[i] == (group.occurrences.size % 2 == 1), InvalidReason.GROUP_ASSOCIATION)
            observed = Math.addExact(observed, group.occurrences.size.toLong())
            val forward = halfEdges[2 * i]; val backward = halfEdges[2 * i + 1]
            verify(forward.id == 2 * i && backward.id == 2 * i + 1 &&
                forward.edgeGroupIndex == i && backward.edgeGroupIndex == i &&
                forward.twin == 2 * i + 1 && backward.twin == 2 * i &&
                forward.origin == group.startVertexId && forward.destination == group.endVertexId &&
                backward.origin == group.endVertexId && backward.destination == group.startVertexId &&
                forward.canonicalForward && !backward.canonicalForward,
                InvalidReason.GROUP_ASSOCIATION)
            val aWalk = toWalk[2 * i]; val bWalk = toWalk[2 * i + 1]
            verify(aWalk in toFace.indices && bWalk in toFace.indices, InvalidReason.FACE_MAPPING)
            val faceA = toFace[aWalk]; val faceB = toFace[bWalk]
            verify(faceA in faceParity.indices && faceB in faceParity.indices &&
                (faceParity[faceA].isOdd xor faceParity[faceB].isOdd) == toggles[i],
                InvalidReason.FACE_MAPPING)
            if (!toggles[i]) continue // An even retrace is not a fill boundary.
            val a = group.start; val b = group.end
            if (clips(a, b, quad, winding, open = true)) boundaryInOpenFrame = true
            if (clips(a, b, quad, winding, open = false)) boundaryTouchesClosedFrame = true
            val ay = compare(a.y, center.y); val byCmp = compare(b.y, center.y)
            val crossing = (ay != Sign.POSITIVE && byCmp == Sign.POSITIVE) ||
                (byCmp != Sign.POSITIVE && ay == Sign.POSITIVE)
            if (crossing) {
                val side = sign(cross(vector(b, a), vector(center, a)))
                val upward = compare(a.y, b.y) == Sign.NEGATIVE
                if ((upward && side == Sign.POSITIVE) || (!upward && side == Sign.NEGATIVE))
                    midpointOdd = !midpointOdd
            }
        }
        verify(observed == parity.verifiedOccurrenceCount, InvalidReason.SOURCE_ASSOCIATION)
        // If no active edge enters the open frame, the exact center cannot be on such an edge.
        // Its parity therefore represents the entire connected open convex frame interior.
        val relation = when {
            boundaryInOpenFrame || midpointOdd -> Relation.INTERIOR_OVERLAP
            boundaryTouchesClosedFrame -> Relation.BOUNDARY_ONLY_CONTACT
            else -> Relation.DISJOINT
        }
        retainRecord(256)
        chargeIncidentLassoWork() // final cancellation/admission before completed publication
        return Completed(owner, region, frame, affine, relation)
    }

    /** Does segment ab meet the quad's open interior, or its closure? Exact linear clipping. */
    private fun SelectionExactArithmetic.clips(
        a: Point, b: Point, quad: List<Point>, orientation: Sign, open: Boolean,
    ): Boolean {
        var lo = rational(0); var hi = rational(1)
        val zero = rational(0)
        for (i in 0 until 4) {
            chargeIncidentLassoWork(5)
            val base = quad[i]
            val edge = vector(quad[(i + 1) % 4], base)
            val va = vector(a, base); val vb = vector(b, base)
            var sa = cross(edge, va); var sb = cross(edge, vb)
            if (orientation == Sign.NEGATIVE) {
                sa = subtract(zero, sa); sb = subtract(zero, sb)
            }
            val slope = subtract(sb, sa)
            when (sign(slope)) {
                Sign.ZERO -> {
                    val s = sign(sa)
                    if (s == Sign.NEGATIVE || (open && s == Sign.ZERO)) return false
                }
                Sign.POSITIVE -> {
                    val bound = divide(subtract(zero, sa), slope)
                    if (compare(bound, lo) == Sign.POSITIVE) lo = bound
                }
                Sign.NEGATIVE -> {
                    val bound = divide(subtract(zero, sa), slope)
                    if (compare(bound, hi) == Sign.NEGATIVE) hi = bound
                }
            }
            if (compare(lo, hi) == Sign.POSITIVE) return false
        }
        return when (compare(lo, hi)) {
            Sign.NEGATIVE -> true
            Sign.ZERO -> !open
            Sign.POSITIVE -> false
        }
    }
}
