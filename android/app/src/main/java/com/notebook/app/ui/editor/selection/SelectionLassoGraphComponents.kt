package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Connectivity inventory of an admitted embedding; no exterior, face, area or parity claims. */
internal object SelectionLassoGraphComponents {
    data class Component(
        val id: Int,
        val minimumVertexId: Int,
        val vertexCount: Int,
        val edgeGroupCount: Int,
        val halfEdgeCount: Int,
        val walkCount: Int,
    )
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, INVALID_ADMISSION, INVALID_GRAPH,
        INVALID_COMPONENT, INVALID_WALK,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoEmbeddingAdmission.Borrowed,
        private val records: List<Component>,
        private val vertices: List<Int>,
        private val groups: List<Int>,
        private val directed: List<Int>,
        private val walks: List<Int>,
    ) {
        val admission: SelectionLassoEmbeddingAdmission.Borrowed get() { owner.segments; return source }
        val cycles: SelectionLassoSuccessorCycles.Borrowed get() { owner.segments; return source.cycles }
        val components: List<Component> get() { owner.segments; return records }
        val componentCount: Int get() { owner.segments; return records.size }
        val vertexToComponent: List<Int> get() { owner.segments; return vertices }
        val edgeGroupToComponent: List<Int> get() { owner.segments; return groups }
        val halfEdgeToComponent: List<Int> get() { owner.segments; return directed }
        val walkToComponent: List<Int> get() { owner.segments; return walks }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> =
            owner.exact(action)
    }
    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(condition: Boolean, reason: InvalidReason) { if (!condition) throw Invalid(reason) }
    private fun <T> value(r: SelectionPredicateResult<T>): T = when (r) {
        is SelectionPredicateResult.Known -> r.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(r)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(r)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INVALID_GRAPH)
    }
    private fun increment(n: Int): Int {
        verify(n < Int.MAX_VALUE, InvalidReason.INVALID_COMPONENT)
        return n + 1
    }

    fun build(prepared: Prepared, admission: SelectionLassoEmbeddingAdmission.Borrowed): Result {
        var success = false
        try {
            val cycles: SelectionLassoSuccessorCycles.Borrowed
            val permutation: SelectionLassoSuccessorPermutation.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; admission.record
                cycles = admission.cycles
                cycles.walks; cycles.halfEdgeToWalk
                permutation = cycles.permutation
                permutation.links
                adjacency = permutation.adjacency
                adjacency.vertices; adjacency.edgeGroups; adjacency.halfEdges; adjacency.incidents
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            verify(admission.isOwnedBy(prepared) && cycles.isOwnedBy(prepared) &&
                permutation.isOwnedBy(prepared) && adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            val result = value(prepared.exact { construct(prepared, admission) })
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

    private fun SelectionExactArithmetic.validateSource(admission: SelectionLassoEmbeddingAdmission.Borrowed) {
        chargeIncidentLassoWork()
        val c = admission.cycles; val a = c.adjacency
        val vertices = a.vertices; val groups = a.edgeGroups; val edges = a.halfEdges
        val links = c.permutation.links; val r = admission.record
        val n = vertices.size.toLong(); val e = groups.size.toLong()
        verify(r.vertexCount == vertices.size && r.edgeGroupCount == groups.size &&
            r.examinedEdgePairs == e * (e - 1) / 2 &&
            r.examinedNonincidentVertexEdgeRelations == e * (n - 2),
            InvalidReason.INVALID_ADMISSION)
        verify(edges.size.toLong() == e * 2 && links.size == edges.size &&
            c.halfEdgeToWalk.size == edges.size && c.walks.size <= edges.size,
            InvalidReason.INVALID_GRAPH)
        fun owns(p: SelectionExactArithmetic.Point) = p.x.context === this && p.y.context === this
        for (i in vertices.indices) {
            chargeIncidentLassoWork(2)
            verify(vertices[i].id == i, InvalidReason.INVALID_GRAPH)
            verify(owns(vertices[i].point), InvalidReason.OWNER_MISMATCH)
            if (i > 0) verify(compare(vertices[i - 1].point, vertices[i].point) == Sign.NEGATIVE,
                InvalidReason.INVALID_GRAPH)
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork(3)
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices &&
                g.startVertexId < g.endVertexId, InvalidReason.INVALID_GRAPH)
            verify(owns(g.start) && owns(g.end), InvalidReason.OWNER_MISMATCH)
            verify(compare(g.start, vertices[g.startVertexId].point) == Sign.ZERO &&
                compare(g.end, vertices[g.endVertexId].point) == Sign.ZERO, InvalidReason.INVALID_GRAPH)
            if (i > 0) {
                val before = groups[i - 1]
                verify(before.startVertexId < g.startVertexId ||
                    before.startVertexId == g.startVertexId && before.endVertexId < g.endVertexId,
                    InvalidReason.INVALID_GRAPH)
            }
        }
        for (i in edges.indices) {
            chargeIncidentLassoWork(4)
            val h = edges[i]; val link = links[i]
            verify(h.id == i && h.edgeGroupIndex == i / 2 && h.canonicalForward == (i % 2 == 0) &&
                h.origin in vertices.indices && h.destination in vertices.indices &&
                h.twin in edges.indices && h.twin == (i xor 1), InvalidReason.INVALID_GRAPH)
            val g = groups[h.edgeGroupIndex]; val twin = edges[h.twin]
            verify(twin.id == h.twin && twin.twin == i && twin.edgeGroupIndex == h.edgeGroupIndex &&
                twin.canonicalForward != h.canonicalForward && twin.origin == h.destination &&
                twin.destination == h.origin &&
                h.origin == (if (h.canonicalForward) g.startVertexId else g.endVertexId) &&
                h.destination == (if (h.canonicalForward) g.endVertexId else g.startVertexId),
                InvalidReason.INVALID_GRAPH)
            verify(link.halfEdgeId == i && link.next in edges.indices && link.previous in edges.indices,
                InvalidReason.INVALID_GRAPH)
        }
        // Reuses bounded integer incidence/link validation. The admitted exact embedding and
        // angular ordering are not reconstructed, and no segment intersections are rerun.
        verify(with(SelectionLassoSuccessorPermutation) { validateLinks(a, links) } == null,
            InvalidReason.INVALID_GRAPH)
    }

    private fun SelectionExactArithmetic.find(parent: IntArray, start: Int): Int {
        var current = start
        var hops = 0
        while (true) {
            chargeIncidentLassoWork(2)
            verify(current in parent.indices && hops < parent.size, InvalidReason.INVALID_COMPONENT)
            val next = parent[current]
            verify(next in parent.indices, InvalidReason.INVALID_COMPONENT)
            if (next == current) return current
            val grandparent = parent[next]
            verify(grandparent in parent.indices, InvalidReason.INVALID_COMPONENT)
            chargeIncidentLassoWork() // path-compression update
            parent[current] = grandparent
            current = next
            hops++
        }
    }

    private fun SelectionExactArithmetic.union(parent: IntArray, weights: IntArray, a: Int, b: Int) {
        chargeIncidentLassoWork() // every attempted union, including repeated connectivity
        val first = find(parent, a); val second = find(parent, b)
        if (first == second) return
        chargeIncidentLassoWork(3)
        val winner = if (weights[first] > weights[second] ||
            weights[first] == weights[second] && first < second) first else second
        val loser = if (winner == first) second else first
        val combined = weights[winner].toLong() + weights[loser]
        verify(combined <= parent.size, InvalidReason.INVALID_COMPONENT)
        parent[loser] = winner
        weights[winner] = combined.toInt()
    }

    private fun SelectionExactArithmetic.construct(
        prepared: Prepared, admission: SelectionLassoEmbeddingAdmission.Borrowed,
    ): Borrowed {
        validateSource(admission)
        val c = admission.cycles; val a = c.adjacency
        val n = a.vertices.size; val e = a.edgeGroups.size; val h = a.halfEdges.size; val w = c.walks.size
        // Nine vertex-sized Int arrays (parents/weights/root IDs/vertex IDs/minima/four counts),
        // three remaining index arrays, coverage flags, boxed immutable output slots/records,
        // list growth and wrappers. Conservative representation estimate, not measured heap.
        retainRecord(512L + n.toLong() * 320 + (e.toLong() + h + w) * 64 + h)
        chargeIncidentLassoWork(n.toLong() * 9 + e + h + w)
        val parent = IntArray(n) { it }; val weights = IntArray(n) { 1 }
        val rootIds = IntArray(n) { -1 }; val vertexIds = IntArray(n)
        val minima = IntArray(n)
        val vertexCounts = IntArray(n); val edgeCounts = IntArray(n)
        val halfCounts = IntArray(n); val walkCounts = IntArray(n)
        val edgeIds = IntArray(e); val halfIds = IntArray(h); val walkIds = IntArray(w)
        for (g in a.edgeGroups) union(parent, weights, g.startVertexId, g.endVertexId)
        var count = 0
        for (i in 0 until n) {
            chargeIncidentLassoWork(4)
            val root = find(parent, i)
            if (rootIds[root] == -1) {
                minima[count] = i
                rootIds[root] = count
                count = increment(count)
            }
            val id = rootIds[root]
            verify(id in 0 until count, InvalidReason.INVALID_COMPONENT)
            vertexIds[i] = id
            vertexCounts[id] = increment(vertexCounts[id])
        }
        for (i in a.edgeGroups.indices) {
            chargeIncidentLassoWork(4)
            val g = a.edgeGroups[i]
            val id = vertexIds[g.startVertexId]
            verify(id in 0 until count && vertexIds[g.endVertexId] == id, InvalidReason.INVALID_COMPONENT)
            edgeIds[i] = id
            edgeCounts[id] = increment(edgeCounts[id])
        }
        for (i in a.halfEdges.indices) {
            chargeIncidentLassoWork(4)
            val directed = a.halfEdges[i]
            val id = edgeIds[directed.edgeGroupIndex]
            verify(id in 0 until count && vertexIds[directed.origin] == id &&
                vertexIds[directed.destination] == id, InvalidReason.INVALID_COMPONENT)
            halfIds[i] = id
            halfCounts[id] = increment(halfCounts[id])
        }
        mapWalks(c, halfIds, walkIds, walkCounts, count)
        val records = ArrayList<Component>(count)
        var vertices = 0L; var groups = 0L; var directed = 0L; var walks = 0L
        for (id in 0 until count) {
            chargeIncidentLassoWork(6)
            verify(minima[id] in vertexIds.indices && vertexIds[minima[id]] == id &&
                vertexCounts[id] > 0 && (id == 0 || minima[id - 1] < minima[id]) &&
                halfCounts[id].toLong() == edgeCounts[id].toLong() * 2, InvalidReason.INVALID_COMPONENT)
            vertices += vertexCounts[id]; groups += edgeCounts[id]
            directed += halfCounts[id]; walks += walkCounts[id]
            records.add(Component(id, minima[id], vertexCounts[id], edgeCounts[id], halfCounts[id], walkCounts[id]))
        }
        verify(vertices == n.toLong() && groups == e.toLong() && directed == h.toLong() &&
            walks == w.toLong(), InvalidReason.INVALID_COMPONENT)
        val result = Borrowed(prepared, admission, Collections.unmodifiableList(records),
            immutableIndex(vertexIds), immutableIndex(edgeIds), immutableIndex(halfIds), immutableIndex(walkIds))
        chargeIncidentLassoWork() // final admission/cancellation before publication
        return result
    }

    private fun SelectionExactArithmetic.mapWalks(
        c: SelectionLassoSuccessorCycles.Borrowed, halfIds: IntArray, walkIds: IntArray,
        counts: IntArray, componentCount: Int,
    ) {
        val edges = c.adjacency.halfEdges; val links = c.permutation.links
        chargeIncidentLassoWork(edges.size.toLong()) // coverage-array initialization
        val seen = BooleanArray(edges.size) // covered by construct's prior reservation
        var lastStart = -1
        var total = 0L
        for (i in c.walks.indices) {
            chargeIncidentLassoWork()
            val walk = c.walks[i]
            verify(walk.id == i && walk.length > 0 && walk.length <= edges.size &&
                walk.startHalfEdgeId == walk.halfEdgeIds.first() && walk.startHalfEdgeId > lastStart,
                InvalidReason.INVALID_WALK)
            lastStart = walk.startHalfEdgeId
            var component = -1
            for (position in walk.halfEdgeIds.indices) {
                chargeIncidentLassoWork(5)
                val id = walk.halfEdgeIds[position]
                verify(id in edges.indices && !seen[id] && id >= walk.startHalfEdgeId &&
                    c.halfEdgeToWalk[id] == i, InvalidReason.INVALID_WALK)
                val next = walk.halfEdgeIds[if (position == walk.length - 1) 0 else position + 1]
                verify(next in edges.indices && links[id].next == next && links[next].previous == id &&
                    edges[id].destination == edges[next].origin, InvalidReason.INVALID_WALK)
                if (component == -1) component = halfIds[id]
                verify(halfIds[id] == component && component in 0 until componentCount,
                    InvalidReason.INVALID_WALK)
                seen[id] = true
                total++
            }
            walkIds[i] = component
            counts[component] = increment(counts[component])
        }
        verify(total == edges.size.toLong(), InvalidReason.INVALID_WALK)
        for (i in edges.indices) {
            chargeIncidentLassoWork()
            verify(seen[i] && c.halfEdgeToWalk[i] in c.walks.indices, InvalidReason.INVALID_WALK)
        }
    }

    private fun SelectionExactArithmetic.immutableIndex(values: IntArray): List<Int> {
        val result = ArrayList<Int>(values.size) // output representation admitted by construct
        for (id in values) { chargeIncidentLassoWork(); result.add(id) }
        return Collections.unmodifiableList(result)
    }
}
