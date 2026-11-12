package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionLassoIntercomponentRayContacts as Rays
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Local target-walk evidence only: no global faces, containment, parity or filled-region claim. */
internal object SelectionLassoContactApproachEvidence {
    sealed interface Evidence { val original: Rays.Evidence }
    data class IsolatedSource(override val original: Rays.IsolatedVertex) : Evidence
    data class NoForeignContact(override val original: Rays.NoOtherComponentContact) : Evidence
    sealed interface ContactApproach : Evidence {
        override val original: Rays.FirstContact
        val targetComponentId: Int
        val targetWalkId: Int
    }
    data class EdgeInteriorApproach(
        override val original: Rays.FirstContact, override val targetComponentId: Int,
        override val targetWalkId: Int, val selectedHalfEdgeId: Int,
    ) : ContactApproach
    data class Sector(
        val targetVertexId: Int, val degree: Int, val previousOutgoing: Int,
        val nextOutgoing: Int, val incomingTwin: Int, val targetWalkId: Int,
    )
    data class VertexSectorApproach(
        override val original: Rays.FirstContact, override val targetComponentId: Int,
        val sector: Sector,
    ) : ContactApproach { override val targetWalkId: Int get() = sector.targetWalkId }
    data class OverlapStartApproach(
        override val original: Rays.FirstContact, override val targetComponentId: Int,
        val sector: Sector,
    ) : ContactApproach { override val targetWalkId: Int get() = sector.targetWalkId }
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_SOURCE, SOURCE,
        CONTACT, TIES, TARGET, INCIDENT_ORDER, OCCUPIED_EAST, WALK,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared, private val source: Rays.Borrowed, private val inventory: List<Evidence>,
    ) {
        val contacts: Rays.Borrowed get() { owner.segments; return source }
        val components: SelectionLassoGraphComponents.Borrowed get() { owner.segments; return source.components }
        val records: List<Evidence> get() { owner.segments; return inventory }
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
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.CONTACT)
    }

    fun build(prepared: Prepared, contacts: Rays.Borrowed): Result {
        var success = false
        try {
            val ext: SelectionLassoExteriorSectorEvidence.Borrowed
            try {
                prepared.segments; contacts.records
                ext = contacts.exterior
                ext.records; ext.components.components; ext.components.admission.record
                ext.components.cycles.walks; ext.components.cycles.permutation.links
                ext.components.cycles.adjacency.vertices; ext.components.cycles.adjacency.incidents
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val components = ext.components; val cycles = components.cycles
            verify(contacts.isOwnedBy(prepared) && ext.isOwnedBy(prepared) &&
                components.isOwnedBy(prepared) && components.admission.isOwnedBy(prepared) &&
                cycles.isOwnedBy(prepared) && cycles.permutation.isOwnedBy(prepared) &&
                cycles.adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(contacts.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_SOURCE)
            // Revalidate only linear graph/component/exterior associations, never replay rays.
            val checked = when (val r = SelectionLassoExteriorSectorEvidence.build(prepared, components)) {
                is SelectionLassoExteriorSectorEvidence.Result.Ready -> r.value
                is SelectionLassoExteriorSectorEvidence.Result.InvalidInput -> throw Invalid(InvalidReason.SOURCE)
                is SelectionLassoExteriorSectorEvidence.Result.Unresolved -> throw Unresolved(r.cause)
            }
            val result = value(prepared.exact {
                verify(ext.records.size == checked.records.size, InvalidReason.SOURCE)
                for (i in checked.records.indices) {
                    chargeIncidentLassoWork(8) // records contain integer IDs only
                    verify(ext.records[i] == checked.records[i], InvalidReason.SOURCE)
                }
                construct(prepared, contacts)
            })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) { return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) { return Result.Unresolved(unresolved.outcome)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.unit(t: Rational) {
        verify(t.context === this, InvalidReason.OWNER_MISMATCH)
        verify(compare(t, rational(0)) != Sign.NEGATIVE && compare(t, rational(1)) != Sign.POSITIVE,
            InvalidReason.CONTACT)
    }
    private fun SelectionExactArithmetic.same(a: Point, b: Point): Boolean {
        verify(a.x.context === this && a.y.context === this &&
            b.x.context === this && b.y.context === this, InvalidReason.OWNER_MISMATCH)
        return compare(a, b) == Sign.ZERO
    }
    private fun SelectionExactArithmetic.projection(a: Point, b: Point, t: Rational, p: Point) {
        unit(t)
        verify(same(point(add(a.x, multiply(t, subtract(b.x, a.x))),
            add(a.y, multiply(t, subtract(b.y, a.y)))), p), InvalidReason.CONTACT)
    }

    private fun SelectionExactArithmetic.construct(owner: Prepared, source: Rays.Borrowed): Borrowed {
        val components = source.components; val ext = source.exterior
        verify(source.records.size == components.componentCount, InvalidReason.SOURCE)
        retainRecord(512L + source.records.size.toLong() * 320)
        val output = ArrayList<Evidence>(source.records.size)
        for ((i, original) in source.records.withIndex()) {
            chargeIncidentLassoWork(4)
            val component = components.components[i]
            val exterior = ext.records[i]
            verify(original.sourceComponentId == i && original.minimumVertexId == component.minimumVertexId,
                InvalidReason.SOURCE)
            val evidence = when (original) {
                is Rays.IsolatedVertex -> {
                    verify(exterior is SelectionLassoExteriorSectorEvidence.IsolatedVertex &&
                        component.edgeGroupCount == 0 && original.examinedOtherEdges == 0L, InvalidReason.SOURCE)
                    IsolatedSource(original)
                }
                is Rays.NoOtherComponentContact -> {
                    verify(exterior is SelectionLassoExteriorSectorEvidence.OutwardSector, InvalidReason.SOURCE)
                    exterior as SelectionLassoExteriorSectorEvidence.OutwardSector
                    validateProbe(original.probeStart, original.probeEnd, original.sourceWalkId,
                        original.examinedOtherEdges, exterior, components)
                    NoForeignContact(original)
                }
                is Rays.FirstContact -> {
                    verify(exterior is SelectionLassoExteriorSectorEvidence.OutwardSector, InvalidReason.SOURCE)
                    exterior as SelectionLassoExteriorSectorEvidence.OutwardSector
                    validateProbe(original.probeStart, original.probeEnd, original.sourceWalkId,
                        original.examinedOtherEdges, exterior, components)
                    resolve(original, components)
                }
            }
            chargeIncidentLassoWork()
            output.add(evidence)
        }
        return publish(owner, source, output)
    }

    private fun SelectionExactArithmetic.validateProbe(
        start: Point, end: Point, walk: Int, count: Long,
        exterior: SelectionLassoExteriorSectorEvidence.OutwardSector,
        components: SelectionLassoGraphComponents.Borrowed,
    ) {
        chargeIncidentLassoWork(4)
        val a = components.cycles.adjacency
        verify(a.vertices.isNotEmpty() && exterior.minimumVertexId in a.vertices.indices &&
            same(start, a.vertices[exterior.minimumVertexId].point), InvalidReason.SOURCE)
        verify(end.x.context === this && end.y.context === this, InvalidReason.OWNER_MISMATCH)
        verify(compare(end.x, a.vertices[0].point.x) == Sign.NEGATIVE &&
            compare(end.y, start.y) == Sign.ZERO && walk == exterior.candidateWalkId &&
            count == a.edgeGroups.size.toLong() - components.components[exterior.componentId].edgeGroupCount,
            InvalidReason.SOURCE)
    }

    private fun SelectionExactArithmetic.resolve(
        original: Rays.FirstContact, components: SelectionLassoGraphComponents.Borrowed,
    ): ContactApproach {
        val a = components.cycles.adjacency
        verify(original.contacts.isNotEmpty(), InvalidReason.TIES)
        unit(original.probeParameter)
        verify(sign(original.probeParameter) == Sign.POSITIVE, InvalidReason.CONTACT)
        projection(original.probeStart, original.probeEnd, original.probeParameter, original.point)
        val target = original.contacts[0].otherComponentId
        verify(target in components.components.indices && target != original.sourceComponentId,
            InvalidReason.TARGET)
        var lastGroup = -1
        var vertex = -1
        var interior = -1
        var overlaps = 0
        for (contact in original.contacts) {
            chargeIncidentLassoWork(8)
            val id = contact.edgeGroupId
            verify(id in a.edgeGroups.indices && id > lastGroup, InvalidReason.TIES)
            lastGroup = id
            verify(contact.sourceComponentId == original.sourceComponentId &&
                contact.minimumVertexId == original.minimumVertexId &&
                contact.sourceWalkId == original.sourceWalkId && contact.otherComponentId == target &&
                components.edgeGroupToComponent[id] == target, InvalidReason.TIES)
            verify(contact.probeParameter.context === this &&
                compare(contact.probeParameter, original.probeParameter) == Sign.ZERO &&
                same(contact.point, original.point), InvalidReason.TIES)
            val g = a.edgeGroups[id]
            projection(g.start, g.end, contact.edgeParameter, contact.point)
            val candidate = when (contact.kind) {
                Rays.ContactKind.EDGE_INTERIOR -> {
                    verify(contact.overlap == null && sign(contact.edgeParameter) == Sign.POSITIVE &&
                        compare(contact.edgeParameter, rational(1)) == Sign.NEGATIVE &&
                        original.contacts.size == 1, InvalidReason.CONTACT)
                    interior = id
                    -1
                }
                Rays.ContactKind.EDGE_ENDPOINT -> {
                    verify(contact.overlap == null, InvalidReason.CONTACT)
                    when {
                        compare(contact.edgeParameter, rational(0)) == Sign.ZERO -> g.startVertexId
                        compare(contact.edgeParameter, rational(1)) == Sign.ZERO -> g.endVertexId
                        else -> throw Invalid(InvalidReason.CONTACT)
                    }
                }
                Rays.ContactKind.COLLINEAR_OVERLAP -> {
                    overlaps++
                    verify(overlaps == 1, InvalidReason.CONTACT)
                    val interval = contact.overlap ?: throw Invalid(InvalidReason.CONTACT)
                    projection(original.probeStart, original.probeEnd, interval.firstProbeParameter, interval.first)
                    projection(original.probeStart, original.probeEnd, interval.lastProbeParameter, interval.last)
                    verify(same(interval.first, g.start) && same(interval.last, g.end) &&
                        compare(g.start.y, g.end.y) == Sign.ZERO &&
                        compare(g.start.x, g.end.x) == Sign.NEGATIVE &&
                        sign(interval.lastProbeParameter) == Sign.POSITIVE &&
                        compare(interval.firstProbeParameter, interval.lastProbeParameter) == Sign.POSITIVE &&
                        compare(original.probeParameter, interval.lastProbeParameter) == Sign.ZERO &&
                        compare(contact.edgeParameter, rational(1)) == Sign.ZERO &&
                        same(original.point, g.end), InvalidReason.CONTACT)
                    g.endVertexId // rightmost endpoint; never a transverse crossing
                }
            }
            if (candidate != -1) {
                verify(candidate in a.vertices.indices && same(a.vertices[candidate].point, original.point) &&
                    components.vertexToComponent[candidate] == target, InvalidReason.TARGET)
                verify(vertex == -1 || vertex == candidate, InvalidReason.TIES)
                vertex = candidate
            }
        }
        if (interior != -1) {
            val h = canonicalHalf(interior, components, target)
            val dy = subtract(a.vertices[h.destination].point.y, a.vertices[h.origin].point.y)
            val signY = sign(dy)
            verify(signY != Sign.ZERO && vertex == -1, InvalidReason.CONTACT)
            // cross(d,east)=-d.y: choose the directed edge with east on its algebraic left.
            val selected = if (signY == Sign.NEGATIVE) h.id else h.twin
            return EdgeInteriorApproach(original, target, walkFor(selected, components, target), selected)
        }
        verify(vertex != -1, InvalidReason.TARGET)
        val sector = sector(original, vertex, components, target)
        return if (overlaps == 0) VertexSectorApproach(original, target, sector)
            else OverlapStartApproach(original, target, sector)
    }

    private fun SelectionExactArithmetic.canonicalHalf(
        group: Int, components: SelectionLassoGraphComponents.Borrowed, target: Int,
    ): SelectionLassoIncidentAdjacency.HalfEdge {
        chargeIncidentLassoWork(6)
        val a = components.cycles.adjacency
        verify(group in a.edgeGroups.indices, InvalidReason.TARGET)
        val index = group.toLong() * 2
        verify(index + 1 < a.halfEdges.size, InvalidReason.TARGET)
        val h = a.halfEdges[index.toInt()]; val g = a.edgeGroups[group]
        verify(h.id == index.toInt() && h.edgeGroupIndex == group && h.canonicalForward &&
            h.origin == g.startVertexId && h.destination == g.endVertexId &&
            h.twin == (h.id xor 1) && h.twin in a.halfEdges.indices, InvalidReason.TARGET)
        val twin = a.halfEdges[h.twin]
        verify(twin.id == h.twin && twin.twin == h.id && !twin.canonicalForward &&
            twin.edgeGroupIndex == group && twin.origin == h.destination && twin.destination == h.origin &&
            components.edgeGroupToComponent[group] == target &&
            components.halfEdgeToComponent[h.id] == target &&
            components.halfEdgeToComponent[h.twin] == target, InvalidReason.TARGET)
        return h
    }

    private fun SelectionExactArithmetic.walkFor(
        h: Int, components: SelectionLassoGraphComponents.Borrowed, target: Int,
    ): Int {
        chargeIncidentLassoWork(4)
        val c = components.cycles
        verify(h in c.halfEdgeToWalk.indices && components.halfEdgeToComponent[h] == target,
            InvalidReason.WALK)
        val walk = c.halfEdgeToWalk[h]
        verify(walk in c.walks.indices && walk in components.walkToComponent.indices &&
            c.walks[walk].id == walk && components.walkToComponent[walk] == target, InvalidReason.WALK)
        return walk
    }

    private fun SelectionExactArithmetic.sector(
        original: Rays.FirstContact, vertex: Int, components: SelectionLassoGraphComponents.Borrowed, target: Int,
    ): Sector {
        val c = components.cycles; val a = c.adjacency
        verify(vertex in a.incidents.indices, InvalidReason.TARGET)
        val incident = a.incidents[vertex]; val rays = incident.outgoing
        verify(incident.vertexId == vertex && rays.isNotEmpty() && rays.size == original.contacts.size,
            InvalidReason.TIES)
        var last: Point? = null
        var lastHalf = -1
        for (id in rays) {
            chargeIncidentLassoWork(4)
            verify(id in a.halfEdges.indices, InvalidReason.TARGET)
            val h = a.halfEdges[id]
            val canonical = canonicalHalf(h.edgeGroupIndex, components, target)
            verify((id == canonical.id || id == canonical.twin) && h.origin == vertex &&
                h.destination in a.vertices.indices, InvalidReason.TARGET)
            // Ties are strictly group-ID ordered. Integer binary search is metered, not hashing.
            var lo = 0; var hi = original.contacts.lastIndex; var found = false
            while (lo <= hi) {
                chargeIncidentLassoWork()
                val mid = lo + (hi - lo) / 2
                val group = original.contacts[mid].edgeGroupId
                when {
                    group == h.edgeGroupIndex -> { found = true; break }
                    group < h.edgeGroupIndex -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            verify(found, InvalidReason.TIES)
            val direction = vector(a.vertices[h.destination].point, a.vertices[vertex].point)
            val x = sign(direction.x); val y = sign(direction.y)
            verify(x != Sign.ZERO || y != Sign.ZERO, InvalidReason.INCIDENT_ORDER)
            // A positive-X edge would occupy the authenticated clear approach segment.
            verify(!(x == Sign.POSITIVE && y == Sign.ZERO), InvalidReason.OCCUPIED_EAST)
            val half = if (y == Sign.POSITIVE || y == Sign.ZERO && x == Sign.POSITIVE) 0 else 1
            last?.let { verify(lastHalf < half ||
                lastHalf == half && sign(cross(it, direction)) == Sign.POSITIVE, InvalidReason.INCIDENT_ORDER) }
            last = direction; lastHalf = half
        }
        val previous = rays.last(); val next = rays.first()
        val incoming = a.halfEdges[next].twin
        val links = c.permutation.links
        verify(incoming in links.indices && previous in links.indices &&
            links[incoming].next == previous && links[previous].previous == incoming, InvalidReason.WALK)
        val walk = walkFor(incoming, components, target)
        verify(walkFor(previous, components, target) == walk, InvalidReason.WALK)
        retainRecord(96)
        return Sector(vertex, rays.size, previous, next, incoming, walk)
    }

    private fun SelectionExactArithmetic.publish(
        owner: Prepared, source: Rays.Borrowed, output: ArrayList<Evidence>,
    ): Borrowed {
        chargeIncidentLassoWork(output.size.toLong() + 1)
        return Borrowed(owner, source, Collections.unmodifiableList(output))
    }
}
