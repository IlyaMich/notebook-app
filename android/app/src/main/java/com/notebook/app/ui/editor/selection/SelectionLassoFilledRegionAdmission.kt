package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as Faces
import com.notebook.app.ui.editor.selection.SelectionLassoSourceParityEvidence as Parity
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import java.util.Collections

/**
 * Exact topological nondegeneracy only: a certified odd bounded planar face is a nonempty open
 * region and hence has positive geometric area. No signed-area cancellation, floating tolerance,
 * interaction/gesture threshold, boundary-contact rule, painted hit, or page clip is inferred.
 *
 * An all-even source is a valid parity classification but NOT a filled-lasso selection region.
 * A geometrically successful synthetic graph is not adequate without authenticated source parity.
 */
internal object SelectionLassoFilledRegionAdmission {
    enum class EmptyReason { NO_ODD_INTERIOR }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_PARITY, UNAUTHENTICATED_FACES,
        SOURCE_ASSOCIATION, INVALID_FACE_PARTITION, INVALID_PARITY, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class EmptyInterior(val reason: EmptyReason) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    /** All getters are borrowed from the original exact-arithmetic owner. */
    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val evidence: Parity.Borrowed,
        private val regionFaces: List<Int>,
    ) {
        val parity: Parity.Borrowed get() { owner.segments; return evidence }
        val oddFaceIds: List<Int> get() { owner.segments; return regionFaces }
        val oddFaceCount: Int get() { owner.segments; return regionFaces.size }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        /** Historical certification. It does not keep the owner open or freeze upstream aliases. */
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean = owner === prepared && this is Completed
    }
    private class Completed(owner: Prepared, evidence: Parity.Borrowed, regionFaces: List<Int>) :
        Borrowed(owner, evidence, regionFaces)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Empty : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) { if (!ok) throw Invalid(reason) }
    private fun <T> known(value: SelectionPredicateResult<T>): T = when (value) {
        is SelectionPredicateResult.Known -> value.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(value)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(value)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_PARITY)
    }

    fun build(prepared: Prepared, parity: Parity.Borrowed): Result {
        var success = false
        try {
            val faces: Faces.Borrowed
            try {
                prepared.segments
                parity.faceParities; parity.oddFaceIds; parity.edgeGroupToggles
                faces = parity.faces
                faces.faces; faces.counts; faces.walkToFace
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val approach = faces.approach
            val components = approach.components
            val rays = approach.contacts
            val admission = components.admission
            val cycles = components.cycles
            val adjacency = cycles.adjacency
            verify(parity.isOwnedBy(prepared) && faces.isOwnedBy(prepared) &&
                approach.isOwnedBy(prepared) && rays.isOwnedBy(prepared) &&
                components.isOwnedBy(prepared) && admission.isOwnedBy(prepared) &&
                cycles.isOwnedBy(prepared) && cycles.permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(parity.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_PARITY)
            verify(faces.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_FACES)
            verify(approach.isAuthenticatedFor(prepared) && rays.isAuthenticatedFor(prepared) &&
                admission.isAuthenticatedFor(prepared), InvalidReason.SOURCE_ASSOCIATION)
            val result = known(prepared.exact { admit(prepared, parity, faces) })
            success = true
            return Result.Ready(result)
        } catch (_: Empty) {
            return Result.EmptyInterior(EmptyReason.NO_ODD_INTERIOR)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) {
            return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.admit(
        owner: Prepared, parity: Parity.Borrowed, faces: Faces.Borrowed,
    ): Borrowed {
        chargeIncidentLassoWork()
        val records = parity.faceParities
        val indices = parity.oddFaceIds
        val faceRecords = faces.faces
        val faceCount = faceRecords.size
        val c = faces.counts
        val walks = faces.walkToFace
        val groups = faces.approach.components.cycles.adjacency.edgeGroups
        verify(faceCount > 0 && records.size == faceCount && c.walkCount == walks.size &&
            c.edgeGroupCount == groups.size && parity.edgeGroupToggles.size == groups.size &&
            parity.verifiedConstraintCount == groups.size && parity.verifiedOccurrenceCount >= 0L &&
            faces.unboundedFaceId == 0 && faces.faceCount == faceCount,
            InvalidReason.SOURCE_ASSOCIATION)
        // The face/odd lists are immutable output from earlier completed builders; recheck
        // their integer associations without replaying exact intersections or source scans.
        retainRecord(192L + faceCount.toLong() * 24L + walks.size.toLong() * 4L)
        val covered = BooleanArray(walks.size)
        var priorOdd = 0
        var nextOdd = 0
        for (id in 0 until faceCount) {
            chargeIncidentLassoWork(3)
            val face = faceRecords[id]
            val record = records[id]
            verify(face.id == id && face.isUnbounded == (id == 0) && record.faceId == id &&
                (id != 0 || !record.isOdd), InvalidReason.INVALID_FACE_PARTITION)
            if (record.isOdd) {
                verify(id > 0 && nextOdd < indices.size && indices[nextOdd] == id &&
                    id > priorOdd, InvalidReason.INVALID_PARITY)
                priorOdd = id
                nextOdd++
            }
            var lastWalk = -1
            for (w in face.boundaryWalkIds) {
                chargeIncidentLassoWork(2)
                verify(w in walks.indices && !covered[w] && w > lastWalk && walks[w] == id,
                    InvalidReason.INVALID_FACE_PARTITION)
                covered[w] = true
                lastWalk = w
            }
            if (id > 0) verify(face.boundaryWalkIds.isNotEmpty(), InvalidReason.INVALID_FACE_PARTITION)
        }
        verify(nextOdd == indices.size, InvalidReason.INVALID_PARITY)
        for (included in covered) {
            chargeIncidentLassoWork()
            verify(included, InvalidReason.INVALID_FACE_PARTITION)
        }
        // Every bounded open face in a certified planar straight-line embedding has positive
        // measure. Its parity determines filled membership; summing signed walk areas is wrong
        // for annuli and may cancel across independent opposite-winding lobes.
        if (indices.isEmpty()) throw Empty()
        retainRecord(192L + indices.size.toLong() * 24L)
        val accepted = ArrayList<Int>(indices.size)
        for (id in indices) { chargeIncidentLassoWork(); accepted.add(id) }
        val frozen = Collections.unmodifiableList(accepted)
        chargeIncidentLassoWork() // final budget/cancellation gate before authenticated publication
        return Completed(owner, parity, frozen)
    }
}
