package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/**
 * Exact algebraic shoelace evidence in canonical page DU (Y down).
 * Signs carry no visual rotation, simplicity, face, containment or filled-region assertion.
 * Both zero and nonzero sums are retained; neither establishes lasso validity.
 */
internal object SelectionLassoCycleAreaEvidence {
    data class Record(
        val walkId: Int,
        val startHalfEdgeId: Int,
        val twiceSignedArea: Rational,
        val sign: Sign,
    )
    enum class InvalidReason { OWNER_CLOSED, OWNER_MISMATCH, INVALID_WALK, INVALID_GRAPH, INVALID_MAPPING }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    /** No second owner: exact area values and source walks live only until Prepared closes. */
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoSuccessorCycles.Borrowed,
        private val values: List<Record>,
    ) {
        val records: List<Record> get() { owner.segments; return values }
        val cycles: SelectionLassoSuccessorCycles.Borrowed get() { owner.segments; return source }
        val walks: List<SelectionLassoSuccessorCycles.Walk> get() { owner.segments; return source.walks }
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

    fun build(prepared: Prepared, cycles: SelectionLassoSuccessorCycles.Borrowed): Result {
        var success = false
        try {
            val permutation: SelectionLassoSuccessorPermutation.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; cycles.walks; cycles.halfEdgeToWalk
                permutation = cycles.permutation
                permutation.links
                adjacency = permutation.adjacency
                adjacency.halfEdges; adjacency.vertices; adjacency.incidents; adjacency.edgeGroups
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            verify(cycles.isOwnedBy(prepared) && permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val result = value(prepared.exact { accumulate(prepared, cycles) })
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

    /** Complete source validation precedes every area term; no angular order is recomputed. */
    private fun SelectionExactArithmetic.validateSource(source: SelectionLassoSuccessorCycles.Borrowed) {
        val permutation = source.permutation
        val a = permutation.adjacency
        val links = permutation.links; val edges = a.halfEdges
        val vertices = a.vertices; val groups = a.edgeGroups
        val walks = source.walks; val membership = source.halfEdgeToWalk
        verify(links.size == edges.size && membership.size == edges.size &&
            edges.size.toLong() == groups.size.toLong() * 2, InvalidReason.INVALID_MAPPING)
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            val v = vertices[i]
            verify(v.id == i, InvalidReason.INVALID_GRAPH)
            verify(v.point.x.context === this && v.point.y.context === this, InvalidReason.OWNER_MISMATCH)
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork()
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices &&
                g.startVertexId < g.endVertexId, InvalidReason.INVALID_GRAPH)
            verify(g.start.x.context === this && g.start.y.context === this &&
                g.end.x.context === this && g.end.y.context === this, InvalidReason.OWNER_MISMATCH)
            verify(compare(g.start, vertices[g.startVertexId].point) == Sign.ZERO &&
                compare(g.end, vertices[g.endVertexId].point) == Sign.ZERO, InvalidReason.INVALID_GRAPH)
        }
        for (i in edges.indices) {
            chargeIncidentLassoWork(2)
            val h = edges[i]; val link = links[i]
            verify(h.id == i && h.edgeGroupIndex == i / 2 && h.canonicalForward == (i % 2 == 0),
                InvalidReason.INVALID_GRAPH)
            verify(h.origin in vertices.indices && h.destination in vertices.indices &&
                h.origin != h.destination && h.twin in edges.indices && h.twin != i,
                InvalidReason.INVALID_GRAPH)
            val twin = edges[h.twin]; val g = groups[h.edgeGroupIndex]
            verify(twin.twin == i && twin.origin == h.destination && twin.destination == h.origin &&
                twin.edgeGroupIndex == h.edgeGroupIndex && twin.canonicalForward != h.canonicalForward &&
                h.origin == (if (h.canonicalForward) g.startVertexId else g.endVertexId) &&
                h.destination == (if (h.canonicalForward) g.endVertexId else g.startVertexId),
                InvalidReason.INVALID_GRAPH)
            verify(link.halfEdgeId == i && link.next in edges.indices && link.previous in edges.indices,
                InvalidReason.INVALID_MAPPING)
        }
        val issue = with(SelectionLassoSuccessorPermutation) { validateLinks(a, links) }
        verify(issue == null, InvalidReason.INVALID_MAPPING)
        retainRecord(64L + edges.size.toLong())
        chargeIncidentLassoWork(edges.size.toLong())
        val seen = BooleanArray(edges.size)
        var lastStart = -1
        for (i in walks.indices) {
            chargeIncidentLassoWork()
            val w = walks[i]
            verify(w.id == i && w.length > 0 && w.length <= edges.size &&
                w.startHalfEdgeId == w.halfEdgeIds.first() && w.startHalfEdgeId > lastStart,
                InvalidReason.INVALID_WALK)
            lastStart = w.startHalfEdgeId
            for (position in w.halfEdgeIds.indices) {
                chargeIncidentLassoWork(4)
                val h = w.halfEdgeIds[position]
                verify(h in edges.indices && !seen[h] && h >= w.startHalfEdgeId &&
                    membership[h] == i, InvalidReason.INVALID_WALK)
                val next = w.halfEdgeIds[if (position == w.length - 1) 0 else position + 1]
                verify(next in edges.indices, InvalidReason.INVALID_WALK)
                verify(links[h].next == next && links[next].previous == h &&
                    edges[h].destination == edges[next].origin, InvalidReason.INVALID_MAPPING)
                seen[h] = true
            }
        }
        for (h in edges.indices) {
            chargeIncidentLassoWork()
            verify(seen[h] && membership[h] in walks.indices, InvalidReason.INVALID_WALK)
        }
    }

    private fun SelectionExactArithmetic.accumulate(
        prepared: Prepared, source: SelectionLassoSuccessorCycles.Borrowed,
    ): Borrowed {
        chargeIncidentLassoWork()
        retainRecord(192)
        validateSource(source)
        val vertices = source.adjacency.vertices
        val edges = source.adjacency.halfEdges
        val output = ArrayList<Record>()
        for (walk in source.walks) {
            chargeIncidentLassoWork() // attempted walk visit
            var sum = rational(0)
            for (id in walk.halfEdgeIds) {
                chargeIncidentLassoWork(3) // half-edge plus two vertex lookups
                val h = edges[id]
                val origin = vertices[h.origin].point
                val destination = vertices[h.destination].point
                chargeIncidentLassoWork() // attempted term; integer arithmetic charges additionally
                val term = subtract(multiply(origin.x, destination.y), multiply(destination.x, origin.y))
                sum = add(sum, term) // retain exact walk order, including cancelling/backtracking terms
            }
            val orientation = sign(sum)
            chargeIncidentLassoWork() // attempted immutable evidence record
            retainRecord(128) // record and mutable/immutable output slots; area integers are arena-accounted
            output.add(Record(walk.id, walk.startHalfEdgeId, sum, orientation))
        }
        chargeIncidentLassoWork(output.size.toLong() + 1) // output copy and final cancellation checkpoint
        return Borrowed(prepared, source, Collections.unmodifiableList(ArrayList(output)))
    }
}
