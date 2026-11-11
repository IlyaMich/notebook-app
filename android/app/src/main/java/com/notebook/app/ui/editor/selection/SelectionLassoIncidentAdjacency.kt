package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.Vertex
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Exact incident ordering only. No successor links, traversal, parity or region construction. */
internal object SelectionLassoIncidentAdjacency {
    data class HalfEdge(
        val id: Int,
        val edgeGroupIndex: Int,
        val origin: Int,
        val destination: Int,
        val twin: Int,
        val canonicalForward: Boolean,
    )
    data class Incident(val vertexId: Int, val outgoing: List<Int>)
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, VERTEX_ORDER, ENDPOINT_ID, ZERO_LENGTH,
        ENDPOINT_ASSOCIATION, EDGE_ORDER, ID_RANGE, SAME_RAY, TWIN_ASSOCIATION, INCIDENT_ASSOCIATION,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    /** All exact references and records borrow the sole Prepared lifetime. */
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val grouped: SelectionLassoCoincidentGeometry.Borrowed,
        private val directed: List<HalfEdge>,
        private val adjacency: List<Incident>,
    ) {
        val halfEdges: List<HalfEdge> get() { owner.segments; return directed }
        val incidents: List<Incident> get() { owner.segments; return adjacency }
        val edgeGroups: List<EdgeGroup> get() { owner.segments; return grouped.edges }
        val vertices: List<Vertex> get() { owner.segments; return grouped.vertices }
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
        SelectionPredicateResult.InvalidCoordinates -> throw Unresolved(SelectionPredicateResult.InvalidCoordinates)
    }
    private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    private data class Ray(val halfEdge: Int, val direction: Point, val halfPlane: Int)

    fun build(prepared: Prepared, grouped: SelectionLassoCoincidentGeometry.Borrowed): Result {
        var success = false
        try {
            // Getter checks do not invoke cancellation callbacks, unlike arithmetic attempts.
            try { prepared.segments; grouped.vertices; grouped.edges }
            catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            verify(grouped.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val result = value(prepared.exact { construct(prepared, grouped) })
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

    private fun SelectionExactArithmetic.construct(
        prepared: Prepared, grouped: SelectionLassoCoincidentGeometry.Borrowed,
    ): Borrowed {
        chargeIncidentLassoWork()
        val vertices = grouped.vertices
        val groups = grouped.edges
        val count = groups.size.toLong() * 2
        verify(count <= Int.MAX_VALUE, InvalidReason.ID_RANGE)
        // Containers, vertex buckets, validation flags and immutable output wrappers/slots.
        retainRecord(256L + vertices.size.toLong() * 160 + count * 16)
        fun owns(p: Point) = p.x.context === this && p.y.context === this
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            verify(vertices[i].id == i, InvalidReason.VERTEX_ORDER)
            verify(owns(vertices[i].point), InvalidReason.OWNER_MISMATCH)
            if (i > 0) verify(compare(vertices[i - 1].point, vertices[i].point) == Sign.NEGATIVE,
                InvalidReason.VERTEX_ORDER)
        }
        val directed = ArrayList<HalfEdge>()
        val outgoing = List(vertices.size) { ArrayList<Ray>() }
        fun append(group: Int, origin: Int, destination: Int, forward: Boolean) {
            chargeIncidentLassoHalfEdge()
            val id = directed.size
            val twin = if (forward) id + 1 else id - 1 // admitted count proved both fit
            directed.add(HalfEdge(id, group, origin, destination, twin, forward))
            chargeIncidentLassoWork() // direction construction; exact arithmetic charges additionally
            val vector = vector(vertices[destination].point, vertices[origin].point)
            val y = sign(vector.y); val x = sign(vector.x)
            verify(x != Sign.ZERO || y != Sign.ZERO, InvalidReason.ZERO_LENGTH)
            val half = if (y == Sign.POSITIVE || y == Sign.ZERO && x == Sign.POSITIVE) 0 else 1
            chargeIncidentLassoWork() // outgoing association, before allocation/insertion
            outgoing[origin].add(Ray(id, vector, half))
        }
        for (index in groups.indices) {
            chargeIncidentLassoWork() // each attempted geometric-edge visit
            val edge = groups[index]
            verify(edge.startVertexId in vertices.indices && edge.endVertexId in vertices.indices,
                InvalidReason.ENDPOINT_ID)
            verify(owns(edge.start) && owns(edge.end), InvalidReason.OWNER_MISMATCH)
            verify(edge.startVertexId != edge.endVertexId && compare(edge.start, edge.end) != Sign.ZERO,
                InvalidReason.ZERO_LENGTH)
            verify(edge.startVertexId < edge.endVertexId, InvalidReason.ENDPOINT_ID)
            verify(compare(edge.start, vertices[edge.startVertexId].point) == Sign.ZERO &&
                compare(edge.end, vertices[edge.endVertexId].point) == Sign.ZERO,
                InvalidReason.ENDPOINT_ASSOCIATION)
            if (index > 0) {
                val before = groups[index - 1]
                verify(before.startVertexId < edge.startVertexId ||
                    before.startVertexId == edge.startVertexId && before.endVertexId < edge.endVertexId,
                    InvalidReason.EDGE_ORDER)
            }
            append(index, edge.startVertexId, edge.endVertexId, true)
            append(index, edge.endVertexId, edge.startVertexId, false)
        }
        verify(directed.size.toLong() == count, InvalidReason.TWIN_ASSOCIATION)
        for (i in directed.indices) {
            chargeIncidentLassoWork()
            val edge = directed[i]
            verify(edge.id == i && edge.twin in directed.indices, InvalidReason.TWIN_ASSOCIATION)
            val twin = directed[edge.twin]
            val group = groups[edge.edgeGroupIndex]
            verify(twin.twin == edge.id && twin.origin == edge.destination && twin.destination == edge.origin &&
                twin.edgeGroupIndex == edge.edgeGroupIndex && twin.canonicalForward != edge.canonicalForward &&
                edge.id / 2 == edge.edgeGroupIndex &&
                edge.origin == (if (edge.canonicalForward) group.startVertexId else group.endVertexId) &&
                edge.destination == (if (edge.canonicalForward) group.endVertexId else group.startVertexId),
                InvalidReason.TWIN_ASSOCIATION)
        }
        val seen = BooleanArray(directed.size)
        val incidents = ArrayList<Incident>()
        for (vertex in vertices.indices) {
            chargeIncidentLassoWork()
            val ordered = angularOrder(outgoing[vertex])
            val ids = ArrayList<Int>()
            for (i in ordered.indices) {
                chargeIncidentLassoWork()
                val ray = ordered[i]
                verify(ray.halfEdge in directed.indices && directed[ray.halfEdge].origin == vertex &&
                    !seen[ray.halfEdge], InvalidReason.INCIDENT_ASSOCIATION)
                if (i > 0) verify(angleCompare(ordered[i - 1], ray) < 0, InvalidReason.INCIDENT_ASSOCIATION)
                seen[ray.halfEdge] = true
                ids.add(ray.halfEdge)
            }
            incidents.add(Incident(vertex, immutable(ids)))
        }
        for (included in seen) {
            chargeIncidentLassoWork()
            verify(included, InvalidReason.INCIDENT_ASSOCIATION)
        }
        chargeIncidentLassoWork()
        return Borrowed(prepared, grouped, immutable(directed), immutable(incidents))
    }

    /** Algebraic CCW from +X: +X, +Y, -X, -Y. No viewport-axis conversion or angle rounding. */
    private fun SelectionExactArithmetic.angleCompare(a: Ray, b: Ray): Int {
        chargeIncidentLassoWork() // every attempted angular comparison
        verify(a.halfEdge != b.halfEdge, InvalidReason.INCIDENT_ASSOCIATION)
        if (a.halfPlane != b.halfPlane) return a.halfPlane.compareTo(b.halfPlane)
        return when (sign(cross(a.direction, b.direction))) {
            Sign.POSITIVE -> -1
            Sign.NEGATIVE -> 1
            Sign.ZERO -> throw Invalid(InvalidReason.SAME_RAY)
        }
    }

    private fun SelectionExactArithmetic.angularOrder(items: List<Ray>): List<Ray> {
        retainRecord(128L + items.size.toLong() * 32)
        chargeIncidentLassoWork(items.size.toLong())
        var source = ArrayList(items); var target = ArrayList(items)
        var width = 1
        while (width < source.size) {
            var begin = 0
            while (begin < source.size) {
                val middle = minOf(begin.toLong() + width, source.size.toLong()).toInt()
                val end = minOf(middle.toLong() + width, source.size.toLong()).toInt()
                var left = begin; var right = middle
                for (slot in begin until end) {
                    chargeIncidentLassoWork()
                    target[slot] = if (left < middle && (right >= end ||
                        angleCompare(source[left], source[right]) < 0)) source[left++] else source[right++]
                }
                begin = end
            }
            val swap = source; source = target; target = swap
            width = minOf(width.toLong() * 2, source.size.toLong()).toInt()
        }
        return source
    }
}
