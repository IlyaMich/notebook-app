package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoFilledRegionAdmission as Filled
import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import com.notebook.app.ui.editor.viewport.DuPoint

/**
 * Point membership in an already admitted even-odd lasso, at *exactly zero* DU tolerance.
 *
 * Only odd-multiplicity unique geometric edge groups separate filled and unfilled faces.
 * Even-multiplicity out-and-back edges are not fill boundaries. The ray uses an exact half-open
 * Y interval per active edge: one vertex is counted once, horizontal edges are handled by the
 * independent on-segment test, and no division, epsilon or approximate polygon is introduced.
 *
 * This is one owner-confined query point. It is NOT an ink/shape/text/image intersection, page
 * clip, finite boundary-tolerance policy, or approved gesture-size classifier. The completion
 * evidence is historical: test-only mutations of upstream aliases are not made impossible here.
 */
internal object SelectionLassoExactPointLocation {
    enum class Location { ODD_INTERIOR, EVEN_EXTERIOR, EXACT_FILL_BOUNDARY }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_REGION, SOURCE_ASSOCIATION,
        INVALID_POINT, INVALID_MAPPING, GROUP_ASSOCIATION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: Filled.Borrowed,
        private val query: DuPoint,
        private val classification: Location,
    ) {
        val region: Filled.Borrowed get() { owner.segments; return source }
        val point: DuPoint get() { owner.segments; return query }
        val location: Location get() { owner.segments; return classification }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        /** Historical provenance only. Every getter enforces the separate owner lifetime. */
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean = owner === prepared && this is Completed
    }
    private class Completed(owner: Prepared, source: Filled.Borrowed, query: DuPoint, result: Location) :
        Borrowed(owner, source, query, result)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(condition: Boolean, reason: InvalidReason) {
        if (!condition) throw Invalid(reason)
    }
    private fun <T> known(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_POINT)
    }

    fun locate(prepared: Prepared, region: Filled.Borrowed, point: DuPoint): Result {
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
                adjacency.vertices; adjacency.edgeGroups
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
            verify(parity.faces === faces && region.parity === parity &&
                approach.components === components && admission.cycles === cycles &&
                rays.components === components && cycles.adjacency === adjacency,
                InvalidReason.SOURCE_ASSOCIATION)
            // Authentication/owner checks precede malformed-query admission and all arithmetic.
            verify(point.x.isFinite() && point.y.isFinite(), InvalidReason.INVALID_POINT)
            val result = known(prepared.exact { classify(prepared, region, parity, faces, adjacency, point) })
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

    private fun SelectionExactArithmetic.classify(
        owner: Prepared,
        region: Filled.Borrowed,
        parity: Parity.Borrowed,
        faces: Faces.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
        query: DuPoint,
    ): Borrowed {
        chargeIncidentLassoWork()
        val groups = adjacency.edgeGroups
        val vertices = adjacency.vertices
        val toggle = parity.edgeGroupToggles
        val faceRecords = faces.faces
        val faceParities = parity.faceParities
        val oddIds = region.oddFaceIds
        val counts = faces.counts
        verify(faceRecords.size > 1 && oddIds.isNotEmpty() && counts.edgeGroupCount == groups.size &&
            counts.vertexCount == vertices.size && toggle.size == groups.size &&
            parity.verifiedConstraintCount == groups.size &&
            faceRecords.size == faceParities.size && faces.unboundedFaceId == 0 &&
            faces.faceCount == faceRecords.size && oddIds == parity.oddFaceIds,
            InvalidReason.INVALID_MAPPING)
        var oddAt = 0
        for (i in faceRecords.indices) {
            chargeIncidentLassoWork(2)
            val f = faceRecords[i]
            val p = faceParities[i]
            verify(f.id == i && f.isUnbounded == (i == 0) && p.faceId == i &&
                (i != 0 || !p.isOdd), InvalidReason.INVALID_MAPPING)
            if (p.isOdd) {
                verify(oddAt < oddIds.size && oddIds[oddAt++] == i, InvalidReason.INVALID_MAPPING)
            }
        }
        verify(oddAt == oddIds.size, InvalidReason.INVALID_MAPPING)

        // Validate current integer associations even though the prior completion is certified.
        var lastX: SelectionExactArithmetic.Point? = null
        for (i in vertices.indices) {
            chargeIncidentLassoWork(2)
            val v = vertices[i]
            verify(v.id == i && v.point.x.context === this && v.point.y.context === this,
                InvalidReason.GROUP_ASSOCIATION)
            if (lastX != null) verify(compare(lastX, v.point) == Sign.NEGATIVE,
                InvalidReason.GROUP_ASSOCIATION)
            lastX = v.point
        }
        val q = point(query) // Exact conversion of the original finite binary Double coordinates.
        var onFillBoundary = false
        var inside = false
        var observed = 0L
        var previousStart = -1
        var previousEnd = -1
        for (i in groups.indices) {
            chargeIncidentLassoWork(7)
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices &&
                g.startVertexId < g.endVertexId && g.occurrences.isNotEmpty() &&
                (g.startVertexId > previousStart ||
                    g.startVertexId == previousStart && g.endVertexId > previousEnd),
                InvalidReason.GROUP_ASSOCIATION)
            previousStart = g.startVertexId; previousEnd = g.endVertexId
            val a = vertices[g.startVertexId].point
            val b = vertices[g.endVertexId].point
            verify(g.start.x.context === this && g.start.y.context === this &&
                g.end.x.context === this && g.end.y.context === this &&
                compare(g.start, a) == Sign.ZERO && compare(g.end, b) == Sign.ZERO &&
                compare(a, b) == Sign.NEGATIVE &&
                toggle[i] == (g.occurrences.size % 2 != 0), InvalidReason.GROUP_ASSOCIATION)
            observed = Math.addExact(observed, g.occurrences.size.toLong())
            if (!toggle[i]) continue // Even retraces are not boundaries or parity crossings.

            val side = sign(cross(vector(b, a), vector(q, a)))
            val ax = compare(a.x, q.x); val bx = compare(b.x, q.x)
            val ay = compare(a.y, q.y); val by = compare(b.y, q.y)
            if (side == Sign.ZERO && (ax != Sign.POSITIVE || bx != Sign.POSITIVE) &&
                (ax != Sign.NEGATIVE || bx != Sign.NEGATIVE) &&
                (ay != Sign.POSITIVE || by != Sign.POSITIVE) &&
                (ay != Sign.NEGATIVE || by != Sign.NEGATIVE)) {
                onFillBoundary = true
            }
            // Exactly one endpoint of a half-open vertical span participates at a ray vertex.
            val straddles = (ay != Sign.POSITIVE && by == Sign.POSITIVE) ||
                (by != Sign.POSITIVE && ay == Sign.POSITIVE)
            if (straddles) {
                val upward = compare(a.y, b.y) == Sign.NEGATIVE
                val toRight = if (upward) side == Sign.POSITIVE else side == Sign.NEGATIVE
                if (toRight) inside = !inside
            }
        }
        verify(observed == parity.verifiedOccurrenceCount, InvalidReason.SOURCE_ASSOCIATION)
        // The publication charge also serves as the final cancellation checkpoint.
        retainRecord(192)
        chargeIncidentLassoWork()
        val where = when {
            onFillBoundary -> Location.EXACT_FILL_BOUNDARY
            inside -> Location.ODD_INTERIOR
            else -> Location.EVEN_EXTERIOR
        }
        return Completed(owner, region, DuPoint(query.x, query.y), where)
    }
}
