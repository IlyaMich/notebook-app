package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import com.notebook.app.ui.editor.selection.SelectionLassoCoincidentGeometry.EdgeGroup
import com.notebook.app.ui.editor.selection.SelectionLassoExteriorSectorEvidence.OutwardSector
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Exact ray contacts only: neither global face association nor source parity is established. */
internal object SelectionLassoIntercomponentRayContacts {
    enum class ContactKind { EDGE_ENDPOINT, EDGE_INTERIOR, COLLINEAR_OVERLAP }
    data class OverlapInterval(
        val first: Point, val last: Point,
        val firstProbeParameter: Rational, val lastProbeParameter: Rational,
    )
    data class Contact(
        val sourceComponentId: Int,
        val minimumVertexId: Int,
        val sourceWalkId: Int,
        val otherComponentId: Int,
        val edgeGroupId: Int,
        val kind: ContactKind,
        val probeParameter: Rational,
        val point: Point,
        val edgeParameter: Rational,
        val overlap: OverlapInterval? = null,
    )
    sealed interface Evidence {
        val sourceComponentId: Int
        val minimumVertexId: Int
        val examinedOtherEdges: Long
    }
    data class IsolatedVertex(
        override val sourceComponentId: Int, override val minimumVertexId: Int,
    ) : Evidence { override val examinedOtherEdges: Long get() = 0 }
    data class NoOtherComponentContact(
        override val sourceComponentId: Int, override val minimumVertexId: Int,
        val sourceWalkId: Int, val probeStart: Point, val probeEnd: Point,
        override val examinedOtherEdges: Long,
    ) : Evidence
    data class FirstContact(
        override val sourceComponentId: Int, override val minimumVertexId: Int,
        val sourceWalkId: Int, val probeStart: Point, val probeEnd: Point,
        val probeParameter: Rational, val point: Point, val contacts: List<Contact>,
        override val examinedOtherEdges: Long,
    ) : Evidence
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, EXTERIOR_SOURCE, ASSOCIATION, PROBE, CONTACT,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }
    class Borrowed internal constructor(
        private val owner: Prepared, private val source: SelectionLassoExteriorSectorEvidence.Borrowed,
        private val inventory: List<Evidence>,
    ) {
        val exterior: SelectionLassoExteriorSectorEvidence.Borrowed get() { owner.segments; return source }
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
    private data class Probe(val start: Point, val end: Point, val zero: Rational, val one: Rational)

    fun build(prepared: Prepared, exterior: SelectionLassoExteriorSectorEvidence.Borrowed): Result {
        var success = false
        try {
            val components: SelectionLassoGraphComponents.Borrowed
            try {
                prepared.segments; exterior.records
                components = exterior.components
                components.components; components.admission.record; components.cycles.walks
                components.cycles.permutation.links; components.cycles.adjacency.vertices
                components.cycles.adjacency.edgeGroups
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val cycles = components.cycles
            verify(exterior.isOwnedBy(prepared) && components.isOwnedBy(prepared) &&
                components.admission.isOwnedBy(prepared) && cycles.isOwnedBy(prepared) &&
                cycles.permutation.isOwnedBy(prepared) && cycles.adjacency.isOwnedBy(prepared),
                InvalidReason.OWNER_MISMATCH)
            // Reuse linear source/sector validation, never quadratic pairwise admission.
            val checked = when (val r = SelectionLassoExteriorSectorEvidence.build(prepared, components)) {
                is SelectionLassoExteriorSectorEvidence.Result.Ready -> r.value
                is SelectionLassoExteriorSectorEvidence.Result.InvalidInput -> throw Invalid(InvalidReason.EXTERIOR_SOURCE)
                is SelectionLassoExteriorSectorEvidence.Result.Unresolved -> throw Unresolved(r.cause)
            }
            value(prepared.exact {
                chargeIncidentLassoWork()
                verify(exterior.records.size == checked.records.size, InvalidReason.EXTERIOR_SOURCE)
                for (i in checked.records.indices) {
                    chargeIncidentLassoWork(8) // integer-only record fields; no geometric hashing
                    verify(exterior.records[i] == checked.records[i], InvalidReason.EXTERIOR_SOURCE)
                }
            })
            val leftX = value(prepared.exact { globalCap(exterior) })
            val output = value(prepared.exact {
                retainRecord(512L + exterior.records.size.toLong() * 192)
                chargeIncidentLassoWork()
                ArrayList<Evidence>(exterior.records.size)
            })
            for (source in exterior.records) {
                value(prepared.exact { chargeIncidentLassoWork() })
                val evidence = when (source) {
                    is SelectionLassoExteriorSectorEvidence.IsolatedVertex ->
                        IsolatedVertex(source.componentId, source.minimumVertexId)
                    is OutwardSector -> {
                        verify(leftX != null, InvalidReason.PROBE)
                        val probe = value(prepared.exact {
                            chargeIncidentLassoWork(3)
                            val start = cycles.adjacency.vertices[source.minimumVertexId].point
                            verify(compare(leftX!!, start.x) == Sign.NEGATIVE, InvalidReason.PROBE)
                            retainRecord(128)
                            Probe(start, point(leftX, start.y), rational(0), rational(1))
                        })
                        scan(prepared, exterior, source, probe)
                    }
                }
                value(prepared.exact { chargeIncidentLassoWork(); output.add(evidence) })
            }
            val result = value(prepared.exact { publish(prepared, exterior, output) })
            success = true
            return Result.Ready(result)
        } catch (invalid: Invalid) { return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) { return Result.Unresolved(unresolved.outcome)
        } finally { if (!success) prepared.close() }
    }

    private fun SelectionExactArithmetic.globalCap(exterior: SelectionLassoExteriorSectorEvidence.Borrowed): Rational? {
        var needsRay = false
        for (r in exterior.records) { chargeIncidentLassoWork(); if (r is OutwardSector) needsRay = true }
        if (!needsRay) return null
        val vertices = exterior.components.cycles.adjacency.vertices
        verify(vertices.isNotEmpty(), InvalidReason.PROBE)
        var minimum = vertices[0].point.x
        for (v in vertices) {
            chargeIncidentLassoWork()
            if (compare(v.point.x, minimum) == Sign.NEGATIVE) minimum = v.point.x
        }
        // Every straight edge is between its endpoint X coordinates. Therefore all inventory
        // contacts lie at x >= minimum > leftX; this finite segment covers the entire west ray
        // as far as any represented edge can intersect it. The exact 1 is not an epsilon.
        val leftX = subtract(minimum, rational(1))
        verify(compare(leftX, minimum) == Sign.NEGATIVE, InvalidReason.PROBE)
        return leftX
    }

    private fun scan(
        prepared: Prepared, exterior: SelectionLassoExteriorSectorEvidence.Borrowed,
        source: OutwardSector, probe: Probe,
    ): Evidence {
        val components = exterior.components
        val groups = components.cycles.adjacency.edgeGroups
        val ties = value(prepared.exact { chargeIncidentLassoWork(); retainRecord(64); ArrayList<Contact>() })
        var best: Contact? = null
        var examined = 0L
        for (id in groups.indices) {
            val other = value(prepared.exact {
                chargeIncidentLassoWork(2)
                val component = components.edgeGroupToComponent[id]
                verify(component in components.components.indices, InvalidReason.ASSOCIATION)
                component
            })
            if (other == source.componentId) continue // already certified component-relative exclusion
            value(prepared.exact {
                chargeIncidentLassoWork()
                verify(examined < Long.MAX_VALUE, InvalidReason.ASSOCIATION)
                examined++
            })
            val g = groups[id]
            // This helper creates its own attempt, strictly outside prepared.exact.
            val hit = value(prepared.intersectExistingExactSegments(probe.start, probe.end, g.start, g.end))
            val candidate = value(prepared.exact { classify(probe, g, hit, source, other, id) }) ?: continue
            value(prepared.exact { collect(ties, best, candidate).also { best = it } })
        }
        return value(prepared.exact {
            chargeIncidentLassoWork()
            verify(examined == groups.size.toLong() - components.components[source.componentId].edgeGroupCount,
                InvalidReason.ASSOCIATION)
            retainRecord(192)
            val first = best
            if (first == null) {
                verify(ties.isEmpty(), InvalidReason.CONTACT)
                NoOtherComponentContact(source.componentId, source.minimumVertexId, source.candidateWalkId,
                    probe.start, probe.end, examined)
            } else {
                verify(ties.isNotEmpty(), InvalidReason.CONTACT)
                chargeIncidentLassoWork(ties.size.toLong())
                FirstContact(source.componentId, source.minimumVertexId, source.candidateWalkId,
                    probe.start, probe.end, first.probeParameter, first.point,
                    Collections.unmodifiableList(ArrayList(ties)), examined)
            }
        })
    }

    private fun SelectionExactArithmetic.collect(ties: ArrayList<Contact>, best: Contact?, candidate: Contact): Contact {
        chargeIncidentLassoWork(2)
        val comparison = if (best == null) Sign.NEGATIVE else compare(candidate.probeParameter, best.probeParameter)
        if (comparison == Sign.NEGATIVE) {
            ties.clear()
            ties.add(candidate)
            return candidate
        }
        if (comparison == Sign.ZERO) {
            verify(best != null && compare(candidate.point, best.point) == Sign.ZERO, InvalidReason.CONTACT)
            verify(ties.isNotEmpty() && ties.last().edgeGroupId < candidate.edgeGroupId, InvalidReason.CONTACT)
            ties.add(candidate)
        }
        return best!!
    }

    private fun SelectionExactArithmetic.classify(
        probe: Probe, edge: EdgeGroup, hit: Intersection, source: OutwardSector, other: Int, id: Int,
    ): Contact? {
        chargeIncidentLassoWork()
        if (hit == Intersection.Disjoint) return null
        fun unit(t: Rational) {
            verify(t.context === this, InvalidReason.OWNER_MISMATCH)
            verify(compare(t, probe.zero) != Sign.NEGATIVE && compare(t, probe.one) != Sign.POSITIVE,
                InvalidReason.CONTACT)
        }
        fun projected(a: Point, b: Point, t: Rational, p: Point) {
            verify(p.x.context === this && p.y.context === this, InvalidReason.OWNER_MISMATCH)
            unit(t)
            val x = add(a.x, multiply(t, subtract(b.x, a.x)))
            val y = add(a.y, multiply(t, subtract(b.y, a.y)))
            verify(compare(x, p.x) == Sign.ZERO && compare(y, p.y) == Sign.ZERO, InvalidReason.CONTACT)
        }
        fun checked(p: Point, t: Rational, u: Rational) {
            projected(probe.start, probe.end, t, p)
            projected(edge.start, edge.end, u, p)
            verify(compare(t, probe.zero) == Sign.POSITIVE, InvalidReason.CONTACT)
            verify(compare(p.x, probe.start.x) == Sign.NEGATIVE &&
                compare(p.y, probe.start.y) == Sign.ZERO, InvalidReason.CONTACT)
        }
        return when (hit) {
            Intersection.Disjoint -> null
            is Intersection.AtPoint -> {
                checked(hit.point, hit.firstParameter, hit.secondParameter)
                val start = compare(hit.secondParameter, probe.zero) == Sign.ZERO
                val end = compare(hit.secondParameter, probe.one) == Sign.ZERO
                verify((compare(hit.point, edge.start) == Sign.ZERO) == start &&
                    (compare(hit.point, edge.end) == Sign.ZERO) == end, InvalidReason.CONTACT)
                retainRecord(320) // contact plus tie-list growth and final copy slots
                Contact(source.componentId, source.minimumVertexId, source.candidateWalkId, other, id,
                    if (start || end) ContactKind.EDGE_ENDPOINT else ContactKind.EDGE_INTERIOR,
                    hit.firstParameter, hit.point, hit.secondParameter)
            }
            is Intersection.Overlap -> {
                checked(hit.first, hit.firstSegmentAtFirst, hit.secondSegmentAtFirst)
                checked(hit.last, hit.firstSegmentAtLast, hit.secondSegmentAtLast)
                verify(compare(hit.first, hit.last) == Sign.NEGATIVE &&
                    compare(edge.start.y, edge.end.y) == Sign.ZERO &&
                    compare(hit.firstSegmentAtFirst, hit.firstSegmentAtLast) == Sign.POSITIVE,
                    InvalidReason.CONTACT)
                val firstIsNear = compare(hit.firstSegmentAtFirst, hit.firstSegmentAtLast) == Sign.NEGATIVE
                val t = if (firstIsNear) hit.firstSegmentAtFirst else hit.firstSegmentAtLast
                val p = if (firstIsNear) hit.first else hit.last
                val u = if (firstIsNear) hit.secondSegmentAtFirst else hit.secondSegmentAtLast
                retainRecord(416)
                Contact(source.componentId, source.minimumVertexId, source.candidateWalkId, other, id,
                    ContactKind.COLLINEAR_OVERLAP, t, p, u,
                    OverlapInterval(hit.first, hit.last, hit.firstSegmentAtFirst, hit.firstSegmentAtLast))
            }
        }
    }

    private fun SelectionExactArithmetic.publish(
        owner: Prepared, source: SelectionLassoExteriorSectorEvidence.Borrowed, output: ArrayList<Evidence>,
    ): Borrowed {
        chargeIncidentLassoWork(output.size.toLong() + 1)
        return Borrowed(owner, source, Collections.unmodifiableList(output))
    }
}
