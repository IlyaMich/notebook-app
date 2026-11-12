package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign

/**
 * Certifies only the represented planar straight-line embedding and its supplied graph links.
 * Empty, disconnected and backtracking graphs are allowed. No source parity, face association,
 * filled-lasso validity, page clipping or painted-element intersection is established.
 */
internal object SelectionLassoEmbeddingAdmission {
    enum class Scope { REPRESENTED_PLANAR_STRAIGHT_LINE_EMBEDDING }
    data class Record(
        val vertexCount: Int,
        val edgeGroupCount: Int,
        val examinedEdgePairs: Long,
        val examinedNonincidentVertexEdgeRelations: Long,
        val scope: Scope = Scope.REPRESENTED_PLANAR_STRAIGHT_LINE_EMBEDDING,
    )
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, VERTEX_ID, VERTEX_ORDER, EDGE_ID, EDGE_ORDER,
        DUPLICATE_EDGE, ZERO_LENGTH, ENDPOINT_ASSOCIATION, INVALID_ADJACENCY,
        INVALID_SUCCESSOR, INVALID_CYCLES, UNSPLIT_CROSSING, UNSPLIT_ENDPOINT_CONTACT,
        POSITIVE_LENGTH_OVERLAP, UNSPLIT_VERTEX, INVALID_INTERSECTION, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(
            val reason: InvalidReason, val firstEdge: Int? = null,
            val secondEdge: Int? = null, val vertex: Int? = null,
        ) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoSuccessorCycles.Borrowed,
        private val evidence: Record,
    ) {
        val cycles: SelectionLassoSuccessorCycles.Borrowed get() { owner.segments; return source }
        val record: Record get() { owner.segments; return evidence }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        /** Pure historical completion identity; normal getters still enforce owner liveness. */
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean = owner === prepared && this is Completed
        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> =
            owner.exact(action)
    }
    /** Private final subtype: copied records and ordinary internal subclasses cannot mint it. */
    private class Completed(owner: Prepared, source: SelectionLassoSuccessorCycles.Borrowed,
        evidence: Record) : Borrowed(owner, source, evidence)

    private class Invalid(val result: Result.InvalidInput) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(
        condition: Boolean, reason: InvalidReason, first: Int? = null,
        second: Int? = null, vertex: Int? = null,
    ) { if (!condition) throw Invalid(Result.InvalidInput(reason, first, second, vertex)) }
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(Result.InvalidInput(InvalidReason.INVALID_INTERSECTION))
    }
    private fun increment(count: Long): Long {
        verify(count < Long.MAX_VALUE, InvalidReason.COUNT_OVERFLOW)
        return count + 1
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
                adjacency.vertices; adjacency.edgeGroups; adjacency.halfEdges; adjacency.incidents
            } catch (_: IllegalStateException) { throw Invalid(Result.InvalidInput(InvalidReason.OWNER_CLOSED)) }
            verify(cycles.isOwnedBy(prepared) && permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            value(prepared.exact {
                validateGeometry(adjacency)
                validateTopology(cycles)
            })
            val pairs = scanPairs(prepared, adjacency)
            val relations = value(prepared.exact { checkVertices(adjacency) })
            val record = value(prepared.exact {
                chargeIncidentLassoWork(3)
                retainRecord(256) // evidence, borrower and working/output references
                Record(adjacency.vertices.size, adjacency.edgeGroups.size, pairs, relations)
            })
            val result = value(prepared.exact { publish(prepared, cycles, record) })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) {
            return invalid.result
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } finally {
            if (!success) prepared.close()
        }
    }

    /** All geometry scans and evidence admission precede this final publication checkpoint. */
    private fun SelectionExactArithmetic.publish(
        owner: Prepared, cycles: SelectionLassoSuccessorCycles.Borrowed, record: Record,
    ): Borrowed {
        chargeIncidentLassoWork()
        return Completed(owner, cycles, record)
    }

    private fun SelectionExactArithmetic.owns(p: Point) =
        p.x.context === this && p.y.context === this

    private fun SelectionExactArithmetic.validateGeometry(a: SelectionLassoIncidentAdjacency.Borrowed) {
        chargeIncidentLassoWork()
        val vertices = a.vertices; val groups = a.edgeGroups; val halfEdges = a.halfEdges
        verify(halfEdges.size.toLong() == groups.size.toLong() * 2, InvalidReason.EDGE_ID)
        for (i in vertices.indices) {
            chargeIncidentLassoWork(2)
            verify(vertices[i].id == i, InvalidReason.VERTEX_ID, vertex = i)
            verify(owns(vertices[i].point), InvalidReason.OWNER_MISMATCH, vertex = i)
            if (i > 0) verify(compare(vertices[i - 1].point, vertices[i].point) == Sign.NEGATIVE,
                InvalidReason.VERTEX_ORDER, vertex = i)
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork(4)
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices,
                InvalidReason.EDGE_ID, i)
            verify(owns(g.start) && owns(g.end), InvalidReason.OWNER_MISMATCH, i)
            verify(g.startVertexId != g.endVertexId && compare(g.start, g.end) != Sign.ZERO,
                InvalidReason.ZERO_LENGTH, i)
            verify(g.startVertexId < g.endVertexId, InvalidReason.EDGE_ORDER, i)
            verify(compare(g.start, vertices[g.startVertexId].point) == Sign.ZERO &&
                compare(g.end, vertices[g.endVertexId].point) == Sign.ZERO, InvalidReason.ENDPOINT_ASSOCIATION, i)
            if (i > 0) {
                val before = groups[i - 1]
                verify(before.startVertexId != g.startVertexId || before.endVertexId != g.endVertexId,
                    InvalidReason.DUPLICATE_EDGE, i - 1, i)
                verify(before.startVertexId < g.startVertexId ||
                    before.startVertexId == g.startVertexId && before.endVertexId < g.endVertexId,
                    InvalidReason.EDGE_ORDER, i)
            }
        }
        for (i in halfEdges.indices) {
            chargeIncidentLassoWork(4)
            val h = halfEdges[i]
            verify(h.id == i && h.edgeGroupIndex == i / 2 && h.canonicalForward == (i % 2 == 0),
                InvalidReason.EDGE_ID)
            verify(h.origin in vertices.indices && h.destination in vertices.indices &&
                h.origin != h.destination, InvalidReason.ENDPOINT_ASSOCIATION, h.edgeGroupIndex)
            verify(h.twin in halfEdges.indices && h.twin == (i xor 1), InvalidReason.INVALID_ADJACENCY)
            val twin = halfEdges[h.twin]
            val g = groups[h.edgeGroupIndex]
            verify(twin.id == h.twin && twin.twin == i && twin.origin == h.destination &&
                twin.destination == h.origin && twin.edgeGroupIndex == h.edgeGroupIndex &&
                twin.canonicalForward != h.canonicalForward, InvalidReason.INVALID_ADJACENCY)
            verify(h.origin == (if (h.canonicalForward) g.startVertexId else g.endVertexId) &&
                h.destination == (if (h.canonicalForward) g.endVertexId else g.startVertexId),
                InvalidReason.ENDPOINT_ASSOCIATION, h.edgeGroupIndex)
        }
    }

    private fun SelectionExactArithmetic.validateTopology(c: SelectionLassoSuccessorCycles.Borrowed) {
        val a = c.adjacency; val edges = a.halfEdges; val links = c.permutation.links
        verify(a.incidents.size == a.vertices.size, InvalidReason.INVALID_ADJACENCY)
        verify(links.size == edges.size && c.halfEdgeToWalk.size == edges.size, InvalidReason.INVALID_SUCCESSOR)
        retainRecord(128L + edges.size.toLong() * 2) // two coverage flags, admitted before allocation
        val incidentSeen = BooleanArray(edges.size)
        for (i in a.incidents.indices) {
            chargeIncidentLassoWork()
            val incident = a.incidents[i]
            verify(incident.vertexId == i, InvalidReason.INVALID_ADJACENCY)
            var previous: Point? = null
            var previousHalf = -1
            for (id in incident.outgoing) {
                chargeIncidentLassoWork(3)
                verify(id in edges.indices && edges[id].origin == i && !incidentSeen[id],
                    InvalidReason.INVALID_ADJACENCY)
                incidentSeen[id] = true
                val h = edges[id]
                val direction = vector(a.vertices[h.destination].point, a.vertices[i].point)
                val x = sign(direction.x); val y = sign(direction.y)
                verify(x != Sign.ZERO || y != Sign.ZERO, InvalidReason.ZERO_LENGTH, h.edgeGroupIndex)
                val half = if (y == Sign.POSITIVE || y == Sign.ZERO && x == Sign.POSITIVE) 0 else 1
                // Validate the supplied order only; do not sort or reconstruct adjacency.
                previous?.let {
                    verify(previousHalf < half || previousHalf == half && sign(cross(it, direction)) == Sign.POSITIVE,
                        InvalidReason.INVALID_ADJACENCY)
                }
                previous = direction; previousHalf = half
            }
        }
        for (i in edges.indices) {
            chargeIncidentLassoWork(2)
            verify(incidentSeen[i], InvalidReason.INVALID_ADJACENCY)
            val link = links[i]
            verify(link.halfEdgeId == i && link.next in edges.indices && link.previous in edges.indices,
                InvalidReason.INVALID_SUCCESSOR)
        }
        verify(with(SelectionLassoSuccessorPermutation) { validateLinks(a, links) } == null,
            InvalidReason.INVALID_SUCCESSOR)
        val seen = BooleanArray(edges.size)
        var lastStart = -1
        var total = 0L
        for (i in c.walks.indices) {
            chargeIncidentLassoWork()
            val w = c.walks[i]
            verify(w.id == i && w.length > 0 && w.length <= edges.size &&
                w.startHalfEdgeId == w.halfEdgeIds.first() && w.startHalfEdgeId > lastStart,
                InvalidReason.INVALID_CYCLES)
            lastStart = w.startHalfEdgeId
            for (position in w.halfEdgeIds.indices) {
                chargeIncidentLassoWork(4)
                val h = w.halfEdgeIds[position]
                verify(h in edges.indices && !seen[h] && h >= w.startHalfEdgeId &&
                    c.halfEdgeToWalk[h] == i, InvalidReason.INVALID_CYCLES)
                val next = w.halfEdgeIds[if (position == w.length - 1) 0 else position + 1]
                verify(next in edges.indices && links[h].next == next && links[next].previous == h &&
                    edges[h].destination == edges[next].origin, InvalidReason.INVALID_CYCLES)
                seen[h] = true
                total = increment(total)
            }
        }
        verify(total == edges.size.toLong(), InvalidReason.INVALID_CYCLES)
        for (h in edges.indices) {
            chargeIncidentLassoWork()
            verify(seen[h] && c.halfEdgeToWalk[h] in c.walks.indices, InvalidReason.INVALID_CYCLES)
        }
    }

    private fun scanPairs(prepared: Prepared, a: SelectionLassoIncidentAdjacency.Borrowed): Long {
        val groups = a.edgeGroups
        var examined = 0L
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            value(prepared.exact { chargeIncidentLassoWork(); examined = increment(examined) })
            val first = groups[i]; val second = groups[j]
            // The helper starts its own attempt; never call it inside prepared.exact.
            val hit = value(prepared.intersectExistingExactSegments(first.start, first.end, second.start, second.end))
            value(prepared.exact { classifyPair(first, second, hit, i, j) })
        }
        return examined
    }

    private fun SelectionExactArithmetic.classifyPair(
        a: EdgeGroup, b: EdgeGroup, hit: Intersection, i: Int, j: Int,
    ) {
        chargeIncidentLassoWork()
        fun unit(t: Rational) {
            verify(t.context === this, InvalidReason.OWNER_MISMATCH, i, j)
            verify(compare(t, rational(0)) != Sign.NEGATIVE && compare(t, rational(1)) != Sign.POSITIVE,
                InvalidReason.INVALID_INTERSECTION, i, j)
        }
        fun endpoint(g: EdgeGroup, p: Point, t: Rational): Int {
            verify(owns(p), InvalidReason.OWNER_MISMATCH, i, j)
            unit(t)
            val start = compare(p, g.start) == Sign.ZERO
            val end = compare(p, g.end) == Sign.ZERO
            if (start) {
                verify(compare(t, rational(0)) == Sign.ZERO, InvalidReason.INVALID_INTERSECTION, i, j)
                return g.startVertexId
            }
            if (end) {
                verify(compare(t, rational(1)) == Sign.ZERO, InvalidReason.INVALID_INTERSECTION, i, j)
                return g.endVertexId
            }
            verify(compare(t, rational(0)) == Sign.POSITIVE && compare(t, rational(1)) == Sign.NEGATIVE,
                InvalidReason.INVALID_INTERSECTION, i, j)
            return -1
        }
        when (hit) {
            Intersection.Disjoint -> verify(a.startVertexId != b.startVertexId &&
                a.startVertexId != b.endVertexId && a.endVertexId != b.startVertexId &&
                a.endVertexId != b.endVertexId, InvalidReason.INVALID_INTERSECTION, i, j)
            is Intersection.AtPoint -> {
                val first = endpoint(a, hit.point, hit.firstParameter)
                val second = endpoint(b, hit.point, hit.secondParameter)
                verify(first >= 0 || second >= 0, InvalidReason.UNSPLIT_CROSSING, i, j)
                verify(first >= 0 && second >= 0, InvalidReason.UNSPLIT_ENDPOINT_CONTACT, i, j)
                verify(first == second, InvalidReason.ENDPOINT_ASSOCIATION, i, j)
            }
            is Intersection.Overlap -> {
                verify(owns(hit.first) && owns(hit.last), InvalidReason.OWNER_MISMATCH, i, j)
                unit(hit.firstSegmentAtFirst); unit(hit.firstSegmentAtLast)
                unit(hit.secondSegmentAtFirst); unit(hit.secondSegmentAtLast)
                verify(compare(hit.first, hit.last) == Sign.NEGATIVE, InvalidReason.INVALID_INTERSECTION, i, j)
                throw Invalid(Result.InvalidInput(InvalidReason.POSITIVE_LENGTH_OVERLAP, i, j))
            }
        }
    }

    private fun SelectionExactArithmetic.checkVertices(a: SelectionLassoIncidentAdjacency.Borrowed): Long {
        var examined = 0L
        for (v in a.vertices) for ((i, g) in a.edgeGroups.withIndex()) {
            chargeIncidentLassoWork() // endpoint exclusion is also attempted work
            if (v.id == g.startVertexId || v.id == g.endVertexId) continue
            chargeIncidentLassoWork(2)
            examined = increment(examined)
            val collinear = sign(cross(vector(g.end, g.start), vector(v.point, g.start))) == Sign.ZERO
            if (collinear) verify(!(compare(g.start, v.point) == Sign.NEGATIVE &&
                compare(v.point, g.end) == Sign.NEGATIVE), InvalidReason.UNSPLIT_VERTEX, i, vertex = v.id)
        }
        return examined
    }
}
