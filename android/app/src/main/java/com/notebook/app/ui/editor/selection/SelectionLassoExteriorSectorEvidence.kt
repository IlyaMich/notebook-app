package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Component-relative symbolic west sectors only; other components may obstruct that ray. */
internal object SelectionLassoExteriorSectorEvidence {
    sealed interface Evidence {
        val componentId: Int
        val minimumVertexId: Int
    }
    data class IsolatedVertex(override val componentId: Int, override val minimumVertexId: Int) : Evidence
    /**
     * Algebraic CCW is +X,+Y,-X,-Y, independent of screen rotation terminology.
     * West is inserted before outgoing[insertionPosition] (degree denotes the wrap position).
     * Its sector is after previousOutgoing and before nextOutgoing. The directed boundary is
     * incomingTwin = twin(nextOutgoing), followed by previousOutgoing, in candidateWalkId.
     * Both directed transition edges, NOT both outgoing rays, must belong to that walk.
     * Every component vertex is lexicographically >= the minimum, so all component edges lie
     * in x >= minimum.x; the open symbolic ray minimum + t*(-1,0), t>0, misses this component.
     */
    data class OutwardSector(
        override val componentId: Int,
        override val minimumVertexId: Int,
        val insertionPosition: Int,
        val degree: Int,
        val previousOutgoing: Int,
        val nextOutgoing: Int,
        val incomingTwin: Int,
        val candidateWalkId: Int,
    ) : Evidence
    enum class InvalidReason { OWNER_CLOSED, OWNER_MISMATCH, COMPONENT, INCIDENT, SECTOR, TRANSITION }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: SelectionLassoGraphComponents.Borrowed,
        private val values: List<Evidence>,
    ) {
        val components: SelectionLassoGraphComponents.Borrowed get() { owner.segments; return source }
        val records: List<Evidence> get() { owner.segments; return values }
        internal fun isOwnedBy(prepared: Prepared) = owner === prepared
        fun <T> exact(action: SelectionExactArithmetic.() -> T) = owner.exact(action)
    }
    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) { if (!ok) throw Invalid(reason) }
    private fun <T> value(r: SelectionPredicateResult<T>): T = when (r) {
        is SelectionPredicateResult.Known -> r.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(r)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(r)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.INCIDENT)
    }

    fun build(prepared: Prepared, components: SelectionLassoGraphComponents.Borrowed): Result {
        var success = false
        try {
            try {
                prepared.segments; components.components; components.vertexToComponent
                components.edgeGroupToComponent; components.halfEdgeToComponent; components.walkToComponent
                components.admission.record; components.cycles.walks
                components.cycles.permutation.links
                components.cycles.adjacency.incidents; components.cycles.adjacency.vertices
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val c = components.cycles
            verify(components.isOwnedBy(prepared) && components.admission.isOwnedBy(prepared) &&
                c.isOwnedBy(prepared) && c.permutation.isOwnedBy(prepared) &&
                c.adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            // Reuse the bounded linear association validator instead of duplicating it.
            // This temporary index borrows the same owner; no graph or intersection is rebuilt.
            val checked = when (val r = SelectionLassoGraphComponents.build(prepared, components.admission)) {
                is SelectionLassoGraphComponents.Result.Ready -> r.value
                is SelectionLassoGraphComponents.Result.Unresolved -> throw Unresolved(r.cause)
                is SelectionLassoGraphComponents.Result.InvalidInput -> throw Invalid(InvalidReason.COMPONENT)
            }
            val result = value(prepared.exact {
                compareIndices(components, checked)
                construct(prepared, components)
            })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) { return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) { return Result.Unresolved(unresolved.outcome)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.compareIndices(
        supplied: SelectionLassoGraphComponents.Borrowed, checked: SelectionLassoGraphComponents.Borrowed,
    ) {
        chargeIncidentLassoWork()
        verify(supplied.componentCount == checked.componentCount, InvalidReason.COMPONENT)
        for (i in checked.components.indices) {
            chargeIncidentLassoWork(6)
            verify(supplied.components[i] == checked.components[i], InvalidReason.COMPONENT)
        }
        fun index(a: List<Int>, b: List<Int>) {
            verify(a.size == b.size, InvalidReason.COMPONENT)
            for (i in b.indices) { chargeIncidentLassoWork(); verify(a[i] == b[i], InvalidReason.COMPONENT) }
        }
        index(supplied.vertexToComponent, checked.vertexToComponent)
        index(supplied.edgeGroupToComponent, checked.edgeGroupToComponent)
        index(supplied.halfEdgeToComponent, checked.halfEdgeToComponent)
        index(supplied.walkToComponent, checked.walkToComponent)
    }

    private fun SelectionExactArithmetic.construct(
        prepared: Prepared, source: SelectionLassoGraphComponents.Borrowed,
    ): Borrowed {
        val c = source.cycles; val a = c.adjacency
        retainRecord(256L + source.componentCount.toLong() * 192)
        val output = ArrayList<Evidence>(source.componentCount)
        // A single linear pass certifies each component's exact minimum premise.
        for (v in a.vertices) {
            chargeIncidentLassoWork(3)
            val id = source.vertexToComponent[v.id]
            verify(id in source.components.indices, InvalidReason.COMPONENT)
            val minimum = source.components[id].minimumVertexId
            verify(minimum in a.vertices.indices && source.vertexToComponent[minimum] == id &&
                v.id >= minimum, InvalidReason.COMPONENT)
            val p = a.vertices[minimum].point
            verify(compare(v.point.x, p.x) != Sign.NEGATIVE, InvalidReason.COMPONENT)
            if (compare(v.point.x, p.x) == Sign.ZERO)
                verify(compare(v.point.y, p.y) != Sign.NEGATIVE, InvalidReason.COMPONENT)
        }
        for (component in source.components) {
            chargeIncidentLassoWork()
            val minimum = component.minimumVertexId
            val incident = a.incidents[minimum]
            verify(incident.vertexId == minimum, InvalidReason.INCIDENT)
            val rays = incident.outgoing
            if (component.edgeGroupCount == 0) {
                verify(component.vertexCount == 1 && component.halfEdgeCount == 0 &&
                    component.walkCount == 0 && rays.isEmpty(), InvalidReason.COMPONENT)
                output.add(IsolatedVertex(component.id, minimum))
                continue
            }
            verify(rays.isNotEmpty(), InvalidReason.INCIDENT)
            var insertion = 0
            var last: Point? = null
            var lastHalf = -1
            for ((position, id) in rays.withIndex()) {
                chargeIncidentLassoWork(4)
                verify(id in a.halfEdges.indices, InvalidReason.INCIDENT)
                val h = a.halfEdges[id]
                verify(h.origin == minimum && h.destination in a.vertices.indices &&
                    source.halfEdgeToComponent[id] == component.id &&
                    source.edgeGroupToComponent[h.edgeGroupIndex] == component.id, InvalidReason.INCIDENT)
                val d = vector(a.vertices[h.destination].point, a.vertices[minimum].point)
                val x = sign(d.x); val y = sign(d.y)
                verify(x != Sign.ZERO || y != Sign.ZERO, InvalidReason.INCIDENT)
                verify(x != Sign.NEGATIVE, InvalidReason.SECTOR)
                val half = if (y == Sign.POSITIVE || y == Sign.ZERO && x == Sign.POSITIVE) 0 else 1
                last?.let { verify(lastHalf < half || lastHalf == half &&
                    sign(cross(it, d)) == Sign.POSITIVE, InvalidReason.INCIDENT) }
                if (half == 0) insertion = position + 1
                last = d; lastHalf = half
            }
            val previous = rays[if (insertion == 0) rays.lastIndex else insertion - 1]
            val next = rays[if (insertion == rays.size) 0 else insertion]
            val incoming = a.halfEdges[next].twin
            verify(incoming in a.halfEdges.indices, InvalidReason.TRANSITION)
            chargeIncidentLassoWork(6)
            val links = c.permutation.links
            verify(links[incoming].next == previous && links[previous].previous == incoming,
                InvalidReason.TRANSITION)
            val walk = c.halfEdgeToWalk[incoming]
            verify(walk in c.walks.indices && c.halfEdgeToWalk[previous] == walk &&
                source.walkToComponent[walk] == component.id, InvalidReason.TRANSITION)
            output.add(OutwardSector(component.id, minimum, insertion, rays.size, previous, next, incoming, walk))
        }
        return publish(prepared, source, output)
    }

    private fun SelectionExactArithmetic.publish(
        prepared: Prepared, source: SelectionLassoGraphComponents.Borrowed, output: List<Evidence>,
    ): Borrowed {
        chargeIncidentLassoWork(output.size.toLong() + 1)
        return Borrowed(prepared, source, Collections.unmodifiableList(output))
    }
}
