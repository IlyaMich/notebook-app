package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign

/**
 * One algebraic-left local corridor certificate in canonical DU (Y down).
 * No embedding, face, parity, filled-region, clipping or painted-intersection claim.
 */
internal object SelectionLassoSideWitnessEvidence {
    data class Record(
        val halfEdgeId: Int,
        val edgeGroupIndex: Int,
        val midpoint: Point,
        val witness: Point,
        val offsetParameter: Rational,
        val certifiedLimit: Rational,
        val obstructionFound: Boolean,
        val examinedEdgeGroups: Int,
    )
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, HALF_EDGE, GRAPH_ASSOCIATION,
        ZERO_LENGTH, MIDPOINT_CONTACT, INVALID_PROBE,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoSuccessorCycles.Borrowed,
        private val evidence: Record,
    ) {
        val record: Record get() { owner.segments; return evidence }
        val cycles: SelectionLassoSuccessorCycles.Borrowed get() { owner.segments; return source }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> =
            owner.exact(action)
    }
    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(condition: Boolean, reason: InvalidReason) { if (!condition) throw Invalid(reason) }
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_PROBE)
    }
    private data class Probe(
        val a: Point, val b: Point, val midpoint: Point, val normal: Point, val end: Point,
        val zero: Rational, val one: Rational, val two: Rational, val half: Rational,
        val group: Int,
    )

    fun build(
        prepared: Prepared, cycles: SelectionLassoSuccessorCycles.Borrowed, directedHalfEdgeId: Int,
    ): Result {
        var success = false
        try {
            val permutation: SelectionLassoSuccessorPermutation.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; cycles.walks
                permutation = cycles.permutation
                permutation.links
                adjacency = permutation.adjacency
                adjacency.halfEdges; adjacency.vertices; adjacency.edgeGroups
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            // No arithmetic or cancellation callback runs on a foreign owner.
            verify(cycles.isOwnedBy(prepared) && permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val probe = value(prepared.exact { validateAndProbe(adjacency, directedHalfEdgeId) })
            val groups = adjacency.edgeGroups
            var limit = probe.one
            var obstruction = false
            var examined = 0
            for (index in groups.indices) {
                value(prepared.exact { chargeIncidentLassoWork(); Unit })
                if (index == probe.group) continue
                val edge = groups[index]
                examined++
                // Never inside prepared.exact: this kernel creates its own arithmetic attempt.
                val hit = value(prepared.intersectExistingExactSegments(
                    probe.midpoint, probe.end, edge.start, edge.end))
                val candidate = value(prepared.exact { obstructionParameter(hit, probe) })
                if (candidate != null) {
                    limit = value(prepared.exact {
                        chargeIncidentLassoWork()
                        if (compare(candidate, limit) == Sign.NEGATIVE) candidate else limit
                    })
                    obstruction = true // Includes actual contact at the artificial cap t=1.
                }
            }
            val record = value(prepared.exact {
                chargeIncidentLassoWork(3)
                verify(examined == groups.size - 1, InvalidReason.GRAPH_ASSOCIATION)
                val tau = divide(limit, probe.two)
                verify(compare(tau, probe.zero) == Sign.POSITIVE &&
                    compare(tau, limit) == Sign.NEGATIVE &&
                    compare(tau, probe.half) != Sign.POSITIVE, InvalidReason.INVALID_PROBE)
                val witness = point(add(probe.midpoint.x, multiply(tau, probe.normal.x)),
                    add(probe.midpoint.y, multiply(tau, probe.normal.y)))
                verify(sign(cross(vector(probe.b, probe.a), vector(witness, probe.a))) == Sign.POSITIVE,
                    InvalidReason.INVALID_PROBE)
                // Every other edge was disjoint or first met the probe at t >= limit > tau.
                // This proves the entire corridor (m,w], including w, is unobstructed.
                chargeIncidentLassoWork()
                retainRecord(256) // record, borrower, references; exact integers charged by arena
                Record(directedHalfEdgeId, probe.group, probe.midpoint, witness, tau, limit,
                    obstruction, examined)
            })
            value(prepared.exact { chargeIncidentLassoWork(); Unit }) // publication / cancellation
            val result = Borrowed(prepared, cycles, record)
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

    private fun SelectionExactArithmetic.owns(p: Point) =
        p.x.context === this && p.y.context === this

    private fun SelectionExactArithmetic.validateAndProbe(
        adjacency: SelectionLassoIncidentAdjacency.Borrowed, id: Int,
    ): Probe {
        chargeIncidentLassoWork()
        val edges = adjacency.halfEdges
        val vertices = adjacency.vertices
        val groups = adjacency.edgeGroups
        verify(id in edges.indices, InvalidReason.HALF_EDGE)
        verify(edges.size.toLong() == groups.size.toLong() * 2, InvalidReason.GRAPH_ASSOCIATION)
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            val v = vertices[i]
            verify(v.id == i, InvalidReason.GRAPH_ASSOCIATION)
            verify(owns(v.point), InvalidReason.OWNER_MISMATCH)
            if (i > 0) verify(compare(vertices[i - 1].point, v.point) == Sign.NEGATIVE,
                InvalidReason.GRAPH_ASSOCIATION)
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork(3)
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices,
                InvalidReason.GRAPH_ASSOCIATION)
            verify(owns(g.start) && owns(g.end), InvalidReason.OWNER_MISMATCH)
            verify(g.startVertexId != g.endVertexId && compare(g.start, g.end) != Sign.ZERO,
                InvalidReason.ZERO_LENGTH)
            verify(g.startVertexId < g.endVertexId &&
                compare(g.start, vertices[g.startVertexId].point) == Sign.ZERO &&
                compare(g.end, vertices[g.endVertexId].point) == Sign.ZERO, InvalidReason.GRAPH_ASSOCIATION)
            if (i > 0) {
                val before = groups[i - 1]
                verify(before.startVertexId < g.startVertexId ||
                    before.startVertexId == g.startVertexId && before.endVertexId < g.endVertexId,
                    InvalidReason.GRAPH_ASSOCIATION)
            }
        }
        val h = edges[id]
        verify(h.id == id && h.edgeGroupIndex == id / 2 && h.edgeGroupIndex in groups.indices &&
            h.canonicalForward == (id % 2 == 0), InvalidReason.HALF_EDGE)
        verify(h.twin in edges.indices && h.twin == (id xor 1), InvalidReason.HALF_EDGE)
        val twin = edges[h.twin]
        verify(twin.id == h.twin && twin.twin == id && twin.origin == h.destination &&
            twin.destination == h.origin && twin.edgeGroupIndex == h.edgeGroupIndex &&
            twin.canonicalForward != h.canonicalForward, InvalidReason.HALF_EDGE)
        verify(h.origin in vertices.indices && h.destination in vertices.indices &&
            h.origin != h.destination, InvalidReason.HALF_EDGE)
        val g = groups[h.edgeGroupIndex]
        verify(h.origin == (if (h.canonicalForward) g.startVertexId else g.endVertexId) &&
            h.destination == (if (h.canonicalForward) g.endVertexId else g.startVertexId),
            InvalidReason.GRAPH_ASSOCIATION)
        val a = vertices[h.origin].point
        val b = vertices[h.destination].point
        verify(compare(a, b) != Sign.ZERO, InvalidReason.ZERO_LENGTH)
        retainRecord(256) // probe and working references; no topology records
        val zero = rational(0); val one = rational(1); val two = rational(2)
        val half = divide(one, two)
        val midpoint = point(divide(add(a.x, b.x), two), divide(add(a.y, b.y), two))
        val direction = vector(b, a)
        val normal = point(subtract(zero, direction.y), direction.x)
        val end = point(add(midpoint.x, normal.x), add(midpoint.y, normal.y))
        verify(compare(midpoint, end) != Sign.ZERO &&
            sign(cross(direction, normal)) == Sign.POSITIVE, InvalidReason.INVALID_PROBE)
        return Probe(a, b, midpoint, normal, end, zero, one, two, half, h.edgeGroupIndex)
    }

    private fun SelectionExactArithmetic.obstructionParameter(hit: Intersection, probe: Probe): Rational? {
        chargeIncidentLassoWork()
        fun unit(t: Rational) {
            verify(t.context === this, InvalidReason.OWNER_MISMATCH)
            verify(compare(t, probe.zero) != Sign.NEGATIVE &&
                compare(t, probe.one) != Sign.POSITIVE, InvalidReason.INVALID_PROBE)
        }
        fun positive(t: Rational): Rational {
            unit(t)
            verify(compare(t, probe.zero) != Sign.ZERO, InvalidReason.MIDPOINT_CONTACT)
            return t
        }
        return when (hit) {
            Intersection.Disjoint -> null
            is Intersection.AtPoint -> {
                verify(owns(hit.point), InvalidReason.OWNER_MISMATCH)
                unit(hit.secondParameter)
                positive(hit.firstParameter)
            }
            is Intersection.Overlap -> {
                verify(owns(hit.first) && owns(hit.last), InvalidReason.OWNER_MISMATCH)
                unit(hit.secondSegmentAtFirst); unit(hit.secondSegmentAtLast)
                val first = positive(hit.firstSegmentAtFirst)
                val last = positive(hit.firstSegmentAtLast)
                // Kernel overlap endpoints are lexicographic, not directed along the probe.
                if (compare(first, last) == Sign.POSITIVE) last else first
            }
        }
    }
}
