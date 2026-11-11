package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionLassoSuccessorPermutation.Link
import java.util.Collections

/** Candidate closed walks of a permutation, without geometric face or region interpretation. */
internal object SelectionLassoSuccessorCycles {
    data class Walk(val id: Int, val startHalfEdgeId: Int, val halfEdgeIds: List<Int>) {
        val length: Int get() = halfEdgeIds.size
    }
    enum class InvalidReason { OWNER_CLOSED, OWNER_MISMATCH, INVALID_PERMUTATION, INCONSISTENT_TRAVERSAL }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    /** Exact references and immutable indices borrow the original Prepared lifetime. */
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoSuccessorPermutation.Borrowed,
        private val candidates: List<Walk>,
        private val membership: List<Int>,
    ) {
        val walks: List<Walk> get() { owner.segments; return candidates }
        val halfEdgeToWalk: List<Int> get() { owner.segments; return membership }
        val permutation: SelectionLassoSuccessorPermutation.Borrowed get() { owner.segments; return source }
        val adjacency: SelectionLassoIncidentAdjacency.Borrowed get() { owner.segments; return source.adjacency }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared

        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> = owner.exact(action)
    }
    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(condition: Boolean, reason: InvalidReason) { if (!condition) throw Invalid(reason) }
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Unresolved(SelectionPredicateResult.InvalidCoordinates)
    }
    private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))

    fun build(prepared: Prepared, permutation: SelectionLassoSuccessorPermutation.Borrowed): Result {
        var success = false
        try {
            val adjacency = try {
                prepared.segments; permutation.links
                permutation.adjacency.also { it.halfEdges; it.incidents; it.vertices; it.edgeGroups }
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            verify(permutation.isOwnedBy(prepared) && adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val result = value(prepared.exact { extract(prepared, permutation, adjacency) })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } finally {
            if (!success) prepared.close()
        }
    }

    /** Validate all integer domains before invoking the existing validator's indexed lookups. */
    private fun SelectionExactArithmetic.validateMapping(
        adjacency: SelectionLassoIncidentAdjacency.Borrowed, links: List<Link>,
    ) {
        val edges = adjacency.halfEdges
        val vertices = adjacency.vertices
        val groups = adjacency.edgeGroups
        verify(links.size == edges.size && edges.size.toLong() == groups.size.toLong() * 2,
            InvalidReason.INVALID_PERMUTATION)
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            verify(vertices[i].id == i, InvalidReason.INVALID_PERMUTATION)
        }
        for (i in edges.indices) {
            chargeIncidentLassoWork(2) // attempted half-edge and link validation
            val h = edges[i]; val link = links[i]
            verify(h.id == i && h.edgeGroupIndex == i / 2 && h.canonicalForward == (i % 2 == 0),
                InvalidReason.INVALID_PERMUTATION)
            verify(h.origin in vertices.indices && h.destination in vertices.indices &&
                h.origin != h.destination && h.twin in edges.indices && h.twin != i,
                InvalidReason.INVALID_PERMUTATION)
            val twin = edges[h.twin]; val g = groups[h.edgeGroupIndex]
            verify(twin.twin == i && twin.origin == h.destination && twin.destination == h.origin &&
                twin.edgeGroupIndex == h.edgeGroupIndex && twin.canonicalForward != h.canonicalForward &&
                h.origin == (if (h.canonicalForward) g.startVertexId else g.endVertexId) &&
                h.destination == (if (h.canonicalForward) g.endVertexId else g.startVertexId),
                InvalidReason.INVALID_PERMUTATION)
            verify(link.halfEdgeId == i && link.next in edges.indices && link.previous in edges.indices,
                InvalidReason.INVALID_PERMUTATION)
        }
        val invalid = with(SelectionLassoSuccessorPermutation) { validateLinks(adjacency, links) }
        verify(invalid == null, InvalidReason.INVALID_PERMUTATION)
    }

    private fun SelectionExactArithmetic.extract(
        prepared: Prepared, permutation: SelectionLassoSuccessorPermutation.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
    ): Borrowed {
        chargeIncidentLassoWork()
        val links = permutation.links
        validateMapping(adjacency, links)
        // Visitation/index arrays, boxed final membership, containers and immutable wrappers.
        retainRecord(192L + links.size.toLong() * 48)
        chargeIncidentLassoWork(links.size.toLong())
        val assigned = IntArray(links.size) { -1 }
        val walks = ArrayList<Walk>()
        var total = 0
        for (start in links.indices) {
            chargeIncidentLassoWork() // ascending candidate-start visit
            if (assigned[start] != -1) continue
            chargeIncidentLassoWork() // attempted candidate creation
            retainRecord(128)
            val id = walks.size
            val sequence = ArrayList<Int>()
            var current = start
            while (true) {
                chargeIncidentLassoWork() // attempted traversal visit, including closure check
                verify(current in links.indices, InvalidReason.INCONSISTENT_TRAVERSAL)
                if (assigned[current] != -1) {
                    verify(current == start && assigned[current] == id && sequence.isNotEmpty(),
                        InvalidReason.INCONSISTENT_TRAVERSAL)
                    break
                }
                verify(sequence.size < links.size && current >= start, InvalidReason.INCONSISTENT_TRAVERSAL)
                chargeIncidentLassoWork(2) // visitation update and half-edge-to-walk association
                assigned[current] = id
                chargeIncidentLassoWork() // attempted append before sequence growth
                retainRecord(64)
                sequence.add(current)
                total++ // assigned-once check bounds total by admitted half-edge count
                chargeIncidentLassoWork() // successor lookup
                current = links[current].next
            }
            chargeIncidentLassoWork() // completed immutable walk record
            walks.add(Walk(id, start, immutable(sequence)))
        }
        verify(total == links.size && walks.size <= links.size, InvalidReason.INCONSISTENT_TRAVERSAL)
        validateWalks(walks, assigned, links)
        val membership = ArrayList<Int>()
        for (walkId in assigned) {
            chargeIncidentLassoWork(2) // final index lookup/copy
            membership.add(walkId)
        }
        chargeIncidentLassoWork() // cancellation/admission before publishing anything
        return Borrowed(prepared, permutation, immutable(walks), immutable(membership))
    }

    /** Independent linear coverage/closure check; never derives geometric properties. */
    private fun SelectionExactArithmetic.validateWalks(walks: List<Walk>, assigned: IntArray, links: List<Link>) {
        retainRecord(64L + links.size.toLong())
        chargeIncidentLassoWork(links.size.toLong())
        val seen = BooleanArray(links.size)
        var covered = 0
        var lastStart = -1
        for (i in walks.indices) {
            chargeIncidentLassoWork()
            val walk = walks[i]
            verify(walk.id == i && walk.length > 0 && walk.length <= links.size &&
                walk.startHalfEdgeId == walk.halfEdgeIds.first() && walk.startHalfEdgeId > lastStart,
                InvalidReason.INCONSISTENT_TRAVERSAL)
            lastStart = walk.startHalfEdgeId
            for (position in walk.halfEdgeIds.indices) {
                chargeIncidentLassoWork(3) // sequence entry, membership and successor verification
                val h = walk.halfEdgeIds[position]
                verify(h in links.indices && !seen[h] && assigned[h] == i && h >= walk.startHalfEdgeId,
                    InvalidReason.INCONSISTENT_TRAVERSAL)
                val next = walk.halfEdgeIds[if (position == walk.length - 1) 0 else position + 1]
                verify(links[h].next == next, InvalidReason.INCONSISTENT_TRAVERSAL)
                seen[h] = true
                covered++
            }
        }
        verify(covered == links.size, InvalidReason.INCONSISTENT_TRAVERSAL)
        for (h in links.indices) {
            chargeIncidentLassoWork()
            verify(seen[h] && assigned[h] in walks.indices, InvalidReason.INCONSISTENT_TRAVERSAL)
        }
    }
}
