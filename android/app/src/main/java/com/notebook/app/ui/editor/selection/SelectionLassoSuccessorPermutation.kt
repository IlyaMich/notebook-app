package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.HalfEdge
import com.notebook.app.ui.editor.selection.SelectionLassoIncidentAdjacency.Incident
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Integer permutation only. Does not follow links, enumerate components or infer regions. */
internal object SelectionLassoSuccessorPermutation {
    data class Link(val halfEdgeId: Int, val next: Int, val previous: Int)
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, VERTEX_ID, EDGE_ID, ENDPOINT, TWIN,
        INCIDENT_VERTEX, INCIDENT_MEMBERSHIP, ANGULAR_ORDER, PERMUTATION,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoIncidentAdjacency.Borrowed,
        private val mapping: List<Link>,
    ) {
        val links: List<Link> get() { owner.segments; return mapping }
        val adjacency: SelectionLassoIncidentAdjacency.Borrowed get() { owner.segments; return source }
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

    fun build(prepared: Prepared, adjacency: SelectionLassoIncidentAdjacency.Borrowed): Result {
        var success = false
        try {
            try { prepared.segments; adjacency.halfEdges; adjacency.incidents; adjacency.vertices; adjacency.edgeGroups }
            catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            verify(adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val output = value(prepared.exact { constructLinks(prepared, adjacency) })
            success = true
            return Result.Ready(output)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } finally {
            if (!success) prepared.close()
        }
    }

    private fun SelectionExactArithmetic.constructLinks(
        prepared: Prepared, adjacency: SelectionLassoIncidentAdjacency.Borrowed,
    ): Borrowed {
        chargeIncidentLassoWork()
        val edges = adjacency.halfEdges
        retainRecord(192L + edges.size.toLong() * 8)
        validateAdjacency(adjacency)
        val positions = indexIncidents(edges, adjacency.incidents, adjacency.vertices.size, true, adjacency)
        chargeIncidentLassoWork(edges.size.toLong() * 2)
        val successors = IntArray(edges.size) { -1 }
        val predecessors = IntArray(edges.size) { -1 }
        for (h in edges) {
            chargeIncidentLassoWork() // attempted source half-edge visit
            chargeIncidentLassoWork(3) // destination/twin-position/ordered-list lookups
            val outgoing = adjacency.incidents[h.destination].outgoing
            val position = positions[h.twin]
            verify(position in outgoing.indices && outgoing[position] == h.twin, InvalidReason.INCIDENT_MEMBERSHIP)
            val next = outgoing[if (position == 0) outgoing.lastIndex else position - 1]
            verify(next in edges.indices && edges[next].origin == h.destination, InvalidReason.PERMUTATION)
            verify(successors[h.id] == -1 && predecessors[next] == -1, InvalidReason.PERMUTATION)
            chargeIncidentLassoWork(2) // attempted successor and inverse-predecessor associations
            successors[h.id] = next
            predecessors[next] = h.id
        }
        val links = ArrayList<Link>()
        for (id in edges.indices) {
            chargeIncidentLassoWork() // attempted immutable link record
            retainRecord(64) // record, list growth and immutable-copy slots
            links.add(Link(id, successors[id], predecessors[id]))
        }
        validateLinks(adjacency, links)?.let { throw Invalid(it) }
        chargeIncidentLassoWork() // final cancellation/admission checkpoint before publication
        return Borrowed(prepared, adjacency, Collections.unmodifiableList(ArrayList(links)))
    }

    private fun SelectionExactArithmetic.validateAdjacency(a: SelectionLassoIncidentAdjacency.Borrowed) {
        val vertices = a.vertices; val groups = a.edgeGroups; val edges = a.halfEdges
        verify(edges.size.toLong() == groups.size.toLong() * 2, InvalidReason.EDGE_ID)
        fun owns(p: Point) = p.x.context === this && p.y.context === this
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            verify(vertices[i].id == i, InvalidReason.VERTEX_ID)
            verify(owns(vertices[i].point), InvalidReason.OWNER_MISMATCH)
            if (i > 0) verify(compare(vertices[i - 1].point, vertices[i].point) == Sign.NEGATIVE,
                InvalidReason.VERTEX_ID)
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork()
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices &&
                g.startVertexId < g.endVertexId, InvalidReason.ENDPOINT)
            verify(owns(g.start) && owns(g.end), InvalidReason.OWNER_MISMATCH)
            verify(compare(g.start, vertices[g.startVertexId].point) == Sign.ZERO &&
                compare(g.end, vertices[g.endVertexId].point) == Sign.ZERO, InvalidReason.ENDPOINT)
            if (i > 0) {
                val before = groups[i - 1]
                verify(before.startVertexId < g.startVertexId ||
                    before.startVertexId == g.startVertexId && before.endVertexId < g.endVertexId,
                    InvalidReason.EDGE_ID)
            }
        }
        for (i in edges.indices) {
            chargeIncidentLassoWork()
            val h = edges[i]
            verify(h.id == i && h.edgeGroupIndex == i / 2 && h.canonicalForward == (i % 2 == 0),
                InvalidReason.EDGE_ID)
            verify(h.origin in vertices.indices && h.destination in vertices.indices &&
                h.origin != h.destination, InvalidReason.ENDPOINT)
            verify(h.twin in edges.indices && h.twin != i, InvalidReason.TWIN)
            val twin = edges[h.twin]
            verify(twin.twin == i && twin.origin == h.destination && twin.destination == h.origin &&
                twin.edgeGroupIndex == h.edgeGroupIndex && twin.canonicalForward != h.canonicalForward,
                InvalidReason.TWIN)
            val group = groups[h.edgeGroupIndex]
            verify(h.origin == (if (h.canonicalForward) group.startVertexId else group.endVertexId) &&
                h.destination == (if (h.canonicalForward) group.endVertexId else group.startVertexId),
                InvalidReason.ENDPOINT)
        }
    }

    private data class Ray(val vector: Point, val half: Int)
    private fun SelectionExactArithmetic.ray(
        a: SelectionLassoIncidentAdjacency.Borrowed, h: HalfEdge,
    ): Ray {
        chargeIncidentLassoWork()
        retainRecord(32)
        val v = vector(a.vertices[h.destination].point, a.vertices[h.origin].point)
        val x = sign(v.x); val y = sign(v.y)
        verify(x != Sign.ZERO || y != Sign.ZERO, InvalidReason.ENDPOINT)
        return Ray(v, if (y == Sign.POSITIVE || y == Sign.ZERO && x == Sign.POSITIVE) 0 else 1)
    }

    /** Checks the supplied linear order only. Never sorts, rotates or replaces incident entries. */
    private fun SelectionExactArithmetic.increasing(a: Ray, b: Ray): Boolean {
        chargeIncidentLassoWork()
        return if (a.half != b.half) a.half < b.half else sign(cross(a.vector, b.vector)) == Sign.POSITIVE
    }

    private fun SelectionExactArithmetic.indexIncidents(
        edges: List<HalfEdge>, incidents: List<Incident>, vertices: Int,
        checkAngles: Boolean, adjacency: SelectionLassoIncidentAdjacency.Borrowed,
    ): IntArray {
        verify(incidents.size == vertices, InvalidReason.INCIDENT_VERTEX)
        retainRecord(64L + edges.size.toLong() * 4)
        chargeIncidentLassoWork(edges.size.toLong())
        val positions = IntArray(edges.size) { -1 }
        for (vertex in incidents.indices) {
            chargeIncidentLassoWork()
            val incident = incidents[vertex]
            verify(incident.vertexId == vertex, InvalidReason.INCIDENT_VERTEX)
            var last: Ray? = null
            for (position in incident.outgoing.indices) {
                chargeIncidentLassoWork(2) // attempted entry lookup and origin association
                val id = incident.outgoing[position]
                verify(id in edges.indices && edges[id].origin == vertex &&
                    positions[id] == -1, InvalidReason.INCIDENT_MEMBERSHIP)
                chargeIncidentLassoWork() // position-index insertion
                positions[id] = position
                if (checkAngles) {
                    val current = ray(adjacency, edges[id])
                    last?.let { verify(increasing(it, current), InvalidReason.ANGULAR_ORDER) }
                    last = current
                }
            }
        }
        for (position in positions) {
            chargeIncidentLassoWork()
            verify(position >= 0, InvalidReason.INCIDENT_MEMBERSHIP)
        }
        return positions
    }

    /**
     * Metered validator for candidate integer links against an already validated adjacency.
     * Kept separately testable for malformed link fixtures. No walking or component enumeration.
     * Caller supplies the owner's active exact attempt; arithmetic failure propagates normally.
     */
    internal fun SelectionExactArithmetic.validateLinks(
        adjacency: SelectionLassoIncidentAdjacency.Borrowed, links: List<Link>,
    ): InvalidReason? = try {
        val edges = adjacency.halfEdges
        verify(links.size == edges.size, InvalidReason.PERMUTATION)
        val positions = indexIncidents(edges, adjacency.incidents, adjacency.vertices.size, false, adjacency)
        for (i in edges.indices) {
            chargeIncidentLassoWork()
            val h = edges[i]; val link = links[i]
            verify(link.halfEdgeId == i && link.next in edges.indices && link.previous in edges.indices,
                InvalidReason.PERMUTATION)
            verify(h.destination in adjacency.incidents.indices && h.twin in edges.indices,
                InvalidReason.PERMUTATION)
            chargeIncidentLassoWork(4) // destination, twin index and two inverse-link lookups
            val outgoing = adjacency.incidents[h.destination].outgoing
            val position = positions[h.twin]
            verify(position in outgoing.indices && outgoing[position] == h.twin, InvalidReason.PERMUTATION)
            val required = outgoing[if (position == 0) outgoing.lastIndex else position - 1]
            verify(link.next == required && edges[link.next].origin == h.destination &&
                edges[link.previous].destination == h.origin &&
                links[link.next].previous == i && links[link.previous].next == i,
                InvalidReason.PERMUTATION)
        }
        null
    } catch (invalid: Invalid) { invalid.reason }
}
