package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoContactApproachEvidence as Approach
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts as Rays
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/**
 * Geometric faces only; no source parity, nesting, lasso validity or page clipping.
 *
 * Every admitted connected non-isolated component has E-V+2 successor walks. Its certified
 * west-sector relation joins two sides of one open face. Targets have strictly smaller minimum
 * X, so the N relations are independent. Sound unions leave K+1-N = E-V+C+1 classes; hence
 * no face is missed. Euler is a consistency check, not a substitute for certified relations.
 * Finite isolated points do not separate an open planar region or create boundary walks.
 *
 * Certification is historical. Production source collections are immutable; structural
 * revalidation detects contradictory test aliases but does not replay global intersection scans.
 */
internal object SelectionLassoGlobalFaceAssociation {
    data class Counts(
        val vertexCount: Int, val edgeGroupCount: Int, val componentCount: Int,
        val walkCount: Int, val nonIsolatedComponentCount: Int,
    )
    data class Face(val id: Int, val boundaryWalkIds: List<Int>, val isUnbounded: Boolean) {
        /** Components contributing walks; isolated-point location is deliberately not inferred. */
        val componentParticipationCount: Int get() = boundaryWalkIds.size
    }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_APPROACH, UNAUTHENTICATED_RAYS,
        UNAUTHENTICATED_ADMISSION, SOURCE_ASSOCIATION, COMPONENT_ASSOCIATION, WALK_MAPPING,
        ISOLATED_EVIDENCE, MINIMUM_X_ORDER, REDUNDANT_ASSOCIATION, DSU_CONTRADICTION,
        SAME_COMPONENT_WALKS, EULER_COUNT, COUNT_OVERFLOW,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    open class Borrowed internal constructor(
        private val owner: Prepared, private val source: Approach.Borrowed,
        private val counters: Counts, private val mapping: List<Int>, private val inventory: List<Face>,
    ) {
        val approach: Approach.Borrowed get() { owner.segments; return source }
        val counts: Counts get() { owner.segments; return counters }
        val walkToFace: List<Int> get() { owner.segments; return mapping }
        val faces: List<Face> get() { owner.segments; return inventory }
        val faceCount: Int get() { owner.segments; return inventory.size }
        val unboundedFaceId: Int get() { owner.segments; return 0 }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared
        /** Historical completion only; normal getters independently enforce owner liveness. */
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean = owner === prepared && this is Completed
    }
    /** Only successful private publication can mint completion. Face records are immutable;
     * upstream test aliases are not frozen by this certificate. Consumers must still validate
     * relevant sources/associations; no source-occurrence coverage or parity is certified.
     */
    private class Completed(owner: Prepared, source: Approach.Borrowed, counters: Counts,
        mapping: List<Int>, inventory: List<Face>) : Borrowed(owner, source, counters, mapping, inventory)
    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(ok: Boolean, reason: InvalidReason) { if (!ok) throw Invalid(reason) }
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.SOURCE_ASSOCIATION)
    }
    private fun plus(a: Long, b: Long) = Math.addExact(a, b)
    private fun minus(a: Long, b: Long) = Math.subtractExact(a, b)
    private fun times(a: Long, b: Long) = Math.multiplyExact(a, b)
    private fun indexCount(n: Long): Int {
        verify(n in 0..Int.MAX_VALUE.toLong(), InvalidReason.COUNT_OVERFLOW)
        return n.toInt()
    }

    fun build(prepared: Prepared, approach: Approach.Borrowed): Result {
        var success = false
        try {
            val rays: Rays.Borrowed
            val components: SelectionLassoGraphComponents.Borrowed
            try {
                prepared.segments; approach.records
                rays = approach.contacts
                rays.records; rays.exterior.records
                components = approach.components
                components.components; components.admission.record
                components.cycles.walks; components.cycles.permutation.links
                components.cycles.adjacency.vertices; components.cycles.adjacency.incidents
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val exterior = rays.exterior; val admission = components.admission
            val cycles = components.cycles; val permutation = cycles.permutation; val a = cycles.adjacency
            verify(approach.isOwnedBy(prepared) && rays.isOwnedBy(prepared) &&
                exterior.isOwnedBy(prepared) && components.isOwnedBy(prepared) &&
                admission.isOwnedBy(prepared) && cycles.isOwnedBy(prepared) &&
                permutation.isOwnedBy(prepared) && a.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(approach.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_APPROACH)
            verify(rays.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_RAYS)
            verify(admission.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_ADMISSION)
            verify(rays.components === components && exterior.components === components &&
                admission.cycles === cycles && permutation.adjacency === a, InvalidReason.SOURCE_ASSOCIATION)
            // Sequential owned build: local/structural checks only, with no new ray/edge scan.
            val checked = when (val r = Approach.build(prepared, rays)) {
                is Approach.Result.Ready -> r.value
                is Approach.Result.InvalidInput -> throw Invalid(InvalidReason.SOURCE_ASSOCIATION)
                is Approach.Result.Unresolved -> throw Unresolved(r.cause)
            }
            val result = value(prepared.exact { construct(prepared, approach, checked) })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) { return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) { return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) { return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally { if (!success) prepared.close() }
    }

    /** Compare only integer fields and source identity; never data-class equality on rationals. */
    private fun sameEvidence(a: Approach.Evidence, b: Approach.Evidence): Boolean {
        if (a.original !== b.original) return false
        return when (a) {
            is Approach.IsolatedSource -> b is Approach.IsolatedSource
            is Approach.NoForeignContact -> b is Approach.NoForeignContact
            is Approach.EdgeInteriorApproach -> b is Approach.EdgeInteriorApproach &&
                a.targetComponentId == b.targetComponentId && a.targetWalkId == b.targetWalkId &&
                a.selectedHalfEdgeId == b.selectedHalfEdgeId
            is Approach.VertexSectorApproach -> b is Approach.VertexSectorApproach &&
                a.targetComponentId == b.targetComponentId && a.sector == b.sector
            is Approach.OverlapStartApproach -> b is Approach.OverlapStartApproach &&
                a.targetComponentId == b.targetComponentId && a.sector == b.sector
        }
    }

    private fun SelectionExactArithmetic.construct(
        owner: Prepared, source: Approach.Borrowed, checked: Approach.Borrowed,
    ): Borrowed {
        val comp = source.components; val c = comp.cycles; val a = c.adjacency
        val v = a.vertices.size; val e = a.edgeGroups.size; val h = a.halfEdges.size
        val k = c.walks.size; val count = comp.componentCount
        verify(source.records.size == count && checked.records.size == count &&
            source.contacts.records.size == count && source.contacts.exterior.records.size == count,
            InvalidReason.COMPONENT_ASSOCIATION)
        val nodes = indexCount(plus(k.toLong(), 1))
        // Working integer arrays, coverage flags, boxed indices, wrappers and output slots.
        retainRecord(plus(512, plus(times(nodes.toLong(), 160),
            plus(times(count.toLong(), 64), times(h.toLong(), 2)))))
        chargeIncidentLassoWork(plus(nodes.toLong(), count.toLong()))
        val parent = IntArray(nodes) { it }; val weight = IntArray(nodes) { 1 }
        val rootFace = IntArray(nodes) { -1 }; val walkFace = IntArray(k) { -1 }
        val lastFaceForComponent = IntArray(count) { -1 }
        val seen = BooleanArray(h)
        verify(c.halfEdgeToWalk.size == h && c.permutation.links.size == h &&
            comp.walkToComponent.size == k && comp.halfEdgeToComponent.size == h,
            InvalidReason.WALK_MAPPING)
        var totalV = 0L; var totalE = 0L; var totalH = 0L; var totalK = 0L; var nonIsolated = 0L
        for (id in 0 until count) {
            chargeIncidentLassoWork(10)
            val record = comp.components[id]
            verify(record.id == id && record.minimumVertexId in a.vertices.indices &&
                record.minimumVertexId in comp.vertexToComponent.indices &&
                comp.vertexToComponent[record.minimumVertexId] == id, InvalidReason.COMPONENT_ASSOCIATION)
            verify(record.vertexCount > 0 && record.edgeGroupCount >= 0 &&
                record.halfEdgeCount >= 0 && record.walkCount >= 0, InvalidReason.COMPONENT_ASSOCIATION)
            if (record.edgeGroupCount == 0) {
                verify(record.vertexCount == 1 && record.walkCount == 0 && record.halfEdgeCount == 0 &&
                    source.records[id] is Approach.IsolatedSource, InvalidReason.ISOLATED_EVIDENCE)
            } else {
                nonIsolated = plus(nonIsolated, 1)
                verify(record.walkCount.toLong() == plus(minus(record.edgeGroupCount.toLong(),
                    record.vertexCount.toLong()), 2), InvalidReason.EULER_COUNT)
            }
            totalV = plus(totalV, record.vertexCount.toLong())
            totalE = plus(totalE, record.edgeGroupCount.toLong())
            totalH = plus(totalH, record.halfEdgeCount.toLong())
            totalK = plus(totalK, record.walkCount.toLong())
            verify(sameEvidence(source.records[id], checked.records[id]) &&
                source.records[id].original === source.contacts.records[id], InvalidReason.SOURCE_ASSOCIATION)
        }
        verify(totalV == v.toLong() && totalE == e.toLong() && totalH == h.toLong() &&
            totalK == k.toLong() && h.toLong() == times(e.toLong(), 2), InvalidReason.COMPONENT_ASSOCIATION)
        var covered = 0L; var lastStart = -1
        for (id in c.walks.indices) {
            chargeIncidentLassoWork(4)
            val walk = c.walks[id]
            verify(walk.id == id && walk.halfEdgeIds.isNotEmpty() &&
                walk.startHalfEdgeId == walk.halfEdgeIds.first() &&
                walk.startHalfEdgeId > lastStart && comp.walkToComponent[id] in 0 until count,
                InvalidReason.WALK_MAPPING)
            lastStart = walk.startHalfEdgeId
            for (position in walk.halfEdgeIds.indices) {
                chargeIncidentLassoWork(6)
                val edge = walk.halfEdgeIds[position]
                val next = walk.halfEdgeIds[if (position == walk.halfEdgeIds.lastIndex) 0 else position + 1]
                verify(edge in 0 until h && next in 0 until h && !seen[edge] &&
                    edge >= walk.startHalfEdgeId && c.halfEdgeToWalk[edge] == id &&
                    comp.halfEdgeToComponent[edge] == comp.walkToComponent[id], InvalidReason.WALK_MAPPING)
                val link = c.permutation.links[edge]
                verify(link.halfEdgeId == edge && link.next == next &&
                    c.permutation.links[next].previous == edge &&
                    a.halfEdges[edge].destination == a.halfEdges[next].origin, InvalidReason.WALK_MAPPING)
                seen[edge] = true
                covered = plus(covered, 1)
            }
        }
        verify(covered == h.toLong(), InvalidReason.WALK_MAPPING)
        val counters = Counts(v, e, count, k, indexCount(nonIsolated))
        val unions = associate(source, parent, weight)
        verify(unions == nonIsolated, InvalidReason.DSU_CONTRADICTION)
        val unboundedRoot = find(parent, k)
        rootFace[unboundedRoot] = 0
        var faces = 1
        for (walk in 0 until k) {
            chargeIncidentLassoWork(3)
            val root = find(parent, walk)
            if (rootFace[root] == -1) {
                rootFace[root] = faces
                faces = indexCount(plus(faces.toLong(), 1))
            }
            walkFace[walk] = rootFace[root]
        }
        val euler = plus(plus(minus(e.toLong(), v.toLong()), count.toLong()), 1)
        val independent = minus(plus(k.toLong(), 1), nonIsolated)
        verify(faces.toLong() == euler && euler == independent && euler >= 1, InvalidReason.EULER_COUNT)
        val inventory = createFaces(source, walkFace, lastFaceForComponent, faces)
        return publish(owner, source, counters, walkFace, inventory)
    }

    private fun SelectionExactArithmetic.associate(
        source: Approach.Borrowed, parent: IntArray, weight: IntArray,
    ): Long {
        val comp = source.components; val a = comp.cycles.adjacency
        var unions = 0L
        for ((id, evidence) in source.records.withIndex()) {
            chargeIncidentLassoWork(8)
            val original = evidence.original
            val component = comp.components[id]
            val exterior = source.contacts.exterior.records[id]
            verify(original.sourceComponentId == id && original.minimumVertexId == component.minimumVertexId &&
                exterior.componentId == id && exterior.minimumVertexId == component.minimumVertexId,
                InvalidReason.COMPONENT_ASSOCIATION)
            if (evidence is Approach.IsolatedSource) {
                verify(component.edgeGroupCount == 0 &&
                    exterior is SelectionLassoExteriorSectorEvidence.IsolatedVertex, InvalidReason.ISOLATED_EVIDENCE)
                continue
            }
            verify(component.edgeGroupCount > 0 &&
                exterior is SelectionLassoExteriorSectorEvidence.OutwardSector, InvalidReason.COMPONENT_ASSOCIATION)
            exterior as SelectionLassoExteriorSectorEvidence.OutwardSector
            val walk = exterior.candidateWalkId
            verify(walk in comp.walkToComponent.indices && comp.walkToComponent[walk] == id,
                InvalidReason.WALK_MAPPING)
            val target = when (evidence) {
                is Approach.NoForeignContact -> {
                    verify(evidence.original.sourceWalkId == walk, InvalidReason.SOURCE_ASSOCIATION)
                    parent.lastIndex
                }
                is Approach.ContactApproach -> {
                    val other = evidence.targetComponentId; val targetWalk = evidence.targetWalkId
                    verify(other in comp.components.indices && other != id &&
                        targetWalk in comp.walkToComponent.indices &&
                        comp.walkToComponent[targetWalk] == other && evidence.original.sourceWalkId == walk,
                        InvalidReason.COMPONENT_ASSOCIATION)
                    val minimum = comp.components[other].minimumVertexId
                    verify(minimum in a.vertices.indices && minimum in comp.vertexToComponent.indices &&
                        comp.vertexToComponent[minimum] == other, InvalidReason.COMPONENT_ASSOCIATION)
                    val x = a.vertices[component.minimumVertexId].point.x
                    val targetX = a.vertices[minimum].point.x
                    val contactX = evidence.original.point.x
                    verify(x.context === this && targetX.context === this && contactX.context === this,
                        InvalidReason.OWNER_MISMATCH)
                    verify(compare(targetX, x) == Sign.NEGATIVE &&
                        compare(targetX, contactX) != Sign.POSITIVE && compare(contactX, x) == Sign.NEGATIVE,
                        InvalidReason.MINIMUM_X_ORDER)
                    targetWalk
                }
                is Approach.IsolatedSource -> throw Invalid(InvalidReason.ISOLATED_EVIDENCE)
            }
            union(parent, weight, walk, target)
            unions = plus(unions, 1)
        }
        return unions
    }

    private fun SelectionExactArithmetic.find(parent: IntArray, start: Int): Int {
        var current = start; var hops = 0
        while (true) {
            chargeIncidentLassoWork(2)
            verify(current in parent.indices && hops < parent.size, InvalidReason.DSU_CONTRADICTION)
            val next = parent[current]
            verify(next in parent.indices, InvalidReason.DSU_CONTRADICTION)
            if (next == current) return current
            val grandparent = parent[next]
            verify(grandparent in parent.indices, InvalidReason.DSU_CONTRADICTION)
            chargeIncidentLassoWork()
            parent[current] = grandparent
            current = next
            hops++
        }
    }

    private fun SelectionExactArithmetic.union(parent: IntArray, weight: IntArray, a: Int, b: Int) {
        chargeIncidentLassoWork()
        val first = find(parent, a); val second = find(parent, b)
        verify(first != second, InvalidReason.REDUNDANT_ASSOCIATION)
        val winner = if (weight[first] > weight[second] ||
            weight[first] == weight[second] && first < second) first else second
        val loser = if (winner == first) second else first
        chargeIncidentLassoWork(3)
        val combined = plus(weight[winner].toLong(), weight[loser].toLong())
        verify(combined <= parent.size, InvalidReason.DSU_CONTRADICTION)
        parent[loser] = winner
        weight[winner] = indexCount(combined)
    }

    private fun SelectionExactArithmetic.createFaces(
        source: Approach.Borrowed, mapping: IntArray, lastFace: IntArray, count: Int,
    ): List<Face> {
        retainRecord(plus(128, plus(times(count.toLong(), 96), times(mapping.size.toLong(), 64))))
        chargeIncidentLassoWork(plus(count.toLong(), mapping.size.toLong()))
        val members = List(count) { ArrayList<Int>() }
        for (walk in mapping.indices) {
            chargeIncidentLassoWork(2)
            val face = mapping[walk]
            verify(face in members.indices, InvalidReason.DSU_CONTRADICTION)
            members[face].add(walk)
        }
        val output = ArrayList<Face>(count)
        var lastMinimum = -1; var total = 0L
        for (id in members.indices) {
            chargeIncidentLassoWork()
            val walks = members[id]
            if (id != 0) {
                verify(walks.isNotEmpty() && walks.first() > lastMinimum, InvalidReason.DSU_CONTRADICTION)
                lastMinimum = walks.first()
            }
            for (walk in walks) {
                chargeIncidentLassoWork(3)
                val component = source.components.walkToComponent[walk]
                verify(component in lastFace.indices && lastFace[component] != id, InvalidReason.SAME_COMPONENT_WALKS)
                lastFace[component] = id
                verify(mapping[walk] == id, InvalidReason.DSU_CONTRADICTION)
                total = plus(total, 1)
            }
            chargeGlobalLassoFaceRecord() // fixed record admission, including unbounded face
            chargeIncidentLassoWork(walks.size.toLong())
            output.add(Face(id, Collections.unmodifiableList(ArrayList(walks)), id == 0))
        }
        verify(total == mapping.size.toLong(), InvalidReason.WALK_MAPPING)
        return Collections.unmodifiableList(output)
    }

    private fun SelectionExactArithmetic.publish(
        owner: Prepared, source: Approach.Borrowed, counts: Counts, mapping: IntArray, faces: List<Face>,
    ): Borrowed {
        chargeIncidentLassoWork(plus(mapping.size.toLong(), 1))
        val index = ArrayList<Int>(mapping.size)
        for (id in mapping) { chargeIncidentLassoWork(); index.add(id) }
        val immutableIndex = Collections.unmodifiableList(index)
        chargeIncidentLassoWork() // final cancellation checkpoint before atomic publication
        return Completed(owner, source, counts, immutableIndex, faces)
    }
}
