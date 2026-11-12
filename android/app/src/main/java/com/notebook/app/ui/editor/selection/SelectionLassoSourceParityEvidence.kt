package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionLassoGlobalFaceAssociation as GlobalFaces
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/**
 * Even-odd evidence for *every* exact geometric face. This is not a filled-lasso admission,
 * a gesture-area test, a boundary-tolerance policy, or a painted-element intersection test.
 *
 * Each occurrence must correspond to exactly one interval of an original, implicitly closed
 * source segment. The edge toggle is the occurrence multiplicity modulo two; the unbounded
 * face is even. Bridges and other same-face edges must therefore have even multiplicity.
 * No geometric intersection scans, new graph, or second arithmetic arena are performed.
 */
internal object SelectionLassoSourceParityEvidence {
    data class FaceParity(val faceId: Int, val isOdd: Boolean)

    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, UNAUTHENTICATED_FACES,
        FACE_MAPPING, SOURCE_COVERAGE, SOURCE_INTERVAL, SOURCE_ENDPOINT,
        OCCURRENCE, GROUP_ASSOCIATION, ODD_VERTEX_INCIDENCE,
        ODD_SAME_FACE_EDGE, CONTRADICTORY_PARITY, UNREACHABLE_FACE, COUNT_OVERFLOW,
    }

    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    open class Borrowed internal constructor(
        private val owner: Prepared,
        private val source: GlobalFaces.Borrowed,
        private val parities: List<FaceParity>,
        private val toggles: List<Boolean>,
        private val oddIndices: List<Int>,
        private val sourceOccurrences: Long,
        private val constraints: Int,
    ) {
        val faces: GlobalFaces.Borrowed get() { owner.segments; return source }
        val faceParities: List<FaceParity> get() { owner.segments; return parities }
        val edgeGroupToggles: List<Boolean> get() { owner.segments; return toggles }
        val verifiedOccurrenceCount: Long get() { owner.segments; return sourceOccurrences }
        val verifiedConstraintCount: Int get() { owner.segments; return constraints }
        val oddFaceIds: List<Int> get() { owner.segments; return oddIndices }
        internal fun isOwnedBy(prepared: Prepared) = owner === prepared
        internal fun isAuthenticatedFor(prepared: Prepared): Boolean =
            owner === prepared && this is Completed
    }

    private class Completed(
        owner: Prepared, source: GlobalFaces.Borrowed, parities: List<FaceParity>,
        toggles: List<Boolean>, oddIndices: List<Int>, sourceOccurrences: Long, constraints: Int,
    ) : Borrowed(owner, source, parities, toggles, oddIndices, sourceOccurrences, constraints)

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun verify(condition: Boolean, reason: InvalidReason) {
        if (!condition) throw Invalid(reason)
    }
    private fun <T> known(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Invalid(InvalidReason.SOURCE_INTERVAL)
    }
    private fun add(a: Long, b: Long) = Math.addExact(a, b)
    private fun multiply(a: Long, b: Long) = Math.multiplyExact(a, b)
    private fun sized(n: Long): Int {
        verify(n in 0..Int.MAX_VALUE.toLong(), InvalidReason.COUNT_OVERFLOW)
        return n.toInt()
    }

    fun build(prepared: Prepared, faces: GlobalFaces.Borrowed): Result {
        var success = false
        try {
            val approach: SelectionLassoContactApproachEvidence.Borrowed
            val components: SelectionLassoGraphComponents.Borrowed
            val cycles: SelectionLassoSuccessorCycles.Borrowed
            val adjacency: SelectionLassoIncidentAdjacency.Borrowed
            try {
                prepared.segments; prepared.normalizedVertices
                faces.counts; faces.walkToFace; faces.faces
                approach = faces.approach
                components = approach.components
                cycles = components.cycles
                adjacency = cycles.adjacency
                adjacency.edgeGroups; adjacency.vertices; adjacency.halfEdges
                cycles.halfEdgeToWalk
            } catch (_: IllegalStateException) {
                throw Invalid(InvalidReason.OWNER_CLOSED)
            }
            val rays = approach.contacts
            val exterior = rays.exterior
            val admission = components.admission
            verify(faces.isOwnedBy(prepared) && approach.isOwnedBy(prepared) &&
                rays.isOwnedBy(prepared) && exterior.isOwnedBy(prepared) &&
                components.isOwnedBy(prepared) && admission.isOwnedBy(prepared) &&
                cycles.isOwnedBy(prepared) && cycles.permutation.isOwnedBy(prepared) &&
                adjacency.isOwnedBy(prepared), InvalidReason.OWNER_MISMATCH)
            verify(faces.isAuthenticatedFor(prepared), InvalidReason.UNAUTHENTICATED_FACES)
            verify(approach.isAuthenticatedFor(prepared) &&
                rays.isAuthenticatedFor(prepared) && admission.isAuthenticatedFor(prepared) &&
                approach.components === components && rays.components === components &&
                admission.cycles === cycles, InvalidReason.FACE_MAPPING)
            val completed = known(prepared.exact { construct(prepared, faces, cycles, adjacency) })
            success = true
            return Result.Ready(completed)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } catch (_: ArithmeticException) {
            return Result.InvalidInput(InvalidReason.COUNT_OVERFLOW)
        } finally {
            if (!success) prepared.close()
        }
    }

    private fun SelectionExactArithmetic.construct(
        owner: Prepared, source: GlobalFaces.Borrowed,
        cycles: SelectionLassoSuccessorCycles.Borrowed,
        adjacency: SelectionLassoIncidentAdjacency.Borrowed,
    ): Borrowed {
        val groups = adjacency.edgeGroups
        val vertices = adjacency.vertices
        val faceRecords = source.faces
        val faceCount = faceRecords.size
        val walks = cycles.walks
        val map = source.walkToFace
        val c = source.counts
        verify(faceCount > 0 && source.unboundedFaceId == 0 && map.size == walks.size &&
            c.vertexCount == vertices.size && c.edgeGroupCount == groups.size &&
            c.walkCount == walks.size && faceCount.toLong() == add(add(groups.size.toLong() - vertices.size.toLong(), c.componentCount.toLong()), 1) &&
            faceCount.toLong() == walks.size.toLong() + 1L - c.nonIsolatedComponentCount.toLong() &&
            cycles.halfEdgeToWalk.size.toLong() == multiply(groups.size.toLong(), 2L) &&
            adjacency.halfEdges.size == cycles.halfEdgeToWalk.size, InvalidReason.FACE_MAPPING)
        // Validate the output partition independently of its numerical Euler identities.
        retainRecord(add(256L, add(multiply(walks.size.toLong(), 8L),
            multiply(faceCount.toLong(), 8L))))
        val walkSeen = BooleanArray(walks.size)
        for (i in faceRecords.indices) {
            chargeIncidentLassoWork()
            val f = faceRecords[i]
            verify(f.id == i && f.isUnbounded == (i == 0), InvalidReason.FACE_MAPPING)
            var prior = -1
            for (walk in f.boundaryWalkIds) {
                chargeIncidentLassoWork(2)
                verify(walk in map.indices && !walkSeen[walk] && walk > prior && map[walk] == i,
                    InvalidReason.FACE_MAPPING)
                walkSeen[walk] = true
                prior = walk
            }
        }
        for (present in walkSeen) {
            chargeIncidentLassoWork()
            verify(present, InvalidReason.FACE_MAPPING)
        }
        // Preserve the earlier completed face builder's half-edge/walk association proof
        // against mutable, test-only source aliases. No pairwise geometry is replayed.
        val halfEdges = adjacency.halfEdges
        retainRecord(add(128L, halfEdges.size.toLong()))
        val halfSeen = BooleanArray(halfEdges.size)
        var coveredHalfEdges = 0L
        for (walkId in walks.indices) {
            chargeIncidentLassoWork(2)
            val walk = walks[walkId]
            verify(walk.id == walkId && walk.halfEdgeIds.isNotEmpty() &&
                walk.startHalfEdgeId == walk.halfEdgeIds.first(), InvalidReason.FACE_MAPPING)
            for (half in walk.halfEdgeIds) {
                chargeIncidentLassoWork(3)
                verify(half in halfEdges.indices && !halfSeen[half] &&
                    cycles.halfEdgeToWalk[half] == walkId, InvalidReason.FACE_MAPPING)
                halfSeen[half] = true
                coveredHalfEdges = add(coveredHalfEdges, 1L)
            }
        }
        verify(coveredHalfEdges == halfEdges.size.toLong(), InvalidReason.FACE_MAPPING)
        // Flattened source event intervals form the authoritative occurrence index.
        val segments = owner.segments
        val normalized = owner.normalizedVertices
        verify(segments.size == if (normalized.size > 1) normalized.size else 0,
            InvalidReason.SOURCE_COVERAGE)
        retainRecord(add(256L, multiply(segments.size.toLong(), 32L)))
        val offsets = IntArray(segments.size)
        var totalIntervals = 0L
        val zero = rational(0); val one = rational(1)
        for (i in segments.indices) {
            chargeIncidentLassoWork(4)
            val s = segments[i]
            verify(s.sourceIndex == i && s.start.suppliedIndex == normalized[i].suppliedIndex &&
                s.end.suppliedIndex == normalized[(i + 1) % normalized.size].suppliedIndex &&
                s.events.size >= 2, InvalidReason.SOURCE_COVERAGE)
            val nextVertex = normalized[(i + 1) % normalized.size].point
            val originalVertex = normalized[i].point
            verify(s.start.point.x.context === this && s.start.point.y.context === this &&
                s.end.point.x.context === this && s.end.point.y.context === this &&
                originalVertex.x.context === this && originalVertex.y.context === this &&
                nextVertex.x.context === this && nextVertex.y.context === this,
                InvalidReason.OWNER_MISMATCH)
            verify(compare(s.start.point, normalized[i].point) == Sign.ZERO &&
                compare(s.end.point, normalized[(i + 1) % normalized.size].point) == Sign.ZERO &&
                compare(s.start.point, s.end.point) != Sign.ZERO, InvalidReason.SOURCE_ENDPOINT)
            offsets[i] = sized(totalIntervals)
            totalIntervals = add(totalIntervals, s.events.size.toLong() - 1L)
            sized(totalIntervals)
            val deltaX = subtract(s.end.point.x, s.start.point.x)
            val deltaY = subtract(s.end.point.y, s.start.point.y)
            for (j in s.events.indices) {
                chargeIncidentLassoWork(4)
                val event = s.events[j]
                verify(event.parameter.context === this && event.point.x.context === this &&
                    event.point.y.context === this, InvalidReason.OWNER_MISMATCH)
                verify(compare(event.parameter, zero) != Sign.NEGATIVE &&
                    compare(event.parameter, one) != Sign.POSITIVE, InvalidReason.SOURCE_INTERVAL)
                if (j > 0) verify(compare(s.events[j - 1].parameter, event.parameter) == Sign.NEGATIVE,
                    InvalidReason.SOURCE_INTERVAL)
                verify(compare(add(s.start.point.x, multiply(event.parameter, deltaX)),
                    event.point.x) == Sign.ZERO &&
                    compare(add(s.start.point.y, multiply(event.parameter, deltaY)),
                        event.point.y) == Sign.ZERO, InvalidReason.SOURCE_INTERVAL)
            }
            verify(compare(s.events.first().parameter, zero) == Sign.ZERO &&
                compare(s.events.last().parameter, one) == Sign.ZERO &&
                compare(s.events.first().point, s.start.point) == Sign.ZERO &&
                compare(s.events.last().point, s.end.point) == Sign.ZERO, InvalidReason.SOURCE_ENDPOINT)
        }
        // Each retained graph group must contain only genuine, nonduplicated source intervals.
        // Reject nonempty synthetic geometry with no source, not merely an all-even result.
        retainRecord(add(512L, add(multiply(totalIntervals, 2L),
            add(multiply(groups.size.toLong(), 16L), multiply(vertices.size.toLong(), 4L)))))
        chargeIncidentLassoWork(add(totalIntervals, vertices.size.toLong()))
        val intervalSeen = BooleanArray(sized(totalIntervals))
        val touched = BooleanArray(vertices.size)
        val incidentOdd = BooleanArray(vertices.size)
        val toggle = BooleanArray(groups.size)
        var observed = 0L
        for (i in groups.indices) {
            chargeIncidentLassoWork(4)
            val g = groups[i]
            verify(g.startVertexId in vertices.indices && g.endVertexId in vertices.indices &&
                g.startVertexId < g.endVertexId && g.occurrences.isNotEmpty(),
                InvalidReason.GROUP_ASSOCIATION)
            val aPoint = vertices[g.startVertexId].point
            val bPoint = vertices[g.endVertexId].point
            verify(g.start.x.context === this && g.start.y.context === this &&
                g.end.x.context === this && g.end.y.context === this &&
                aPoint.x.context === this && aPoint.y.context === this &&
                bPoint.x.context === this && bPoint.y.context === this,
                InvalidReason.OWNER_MISMATCH)
            verify(compare(g.start, aPoint) == Sign.ZERO &&
                compare(g.end, bPoint) == Sign.ZERO,
                InvalidReason.GROUP_ASSOCIATION)
            var previousSource = -1
            var previousOrdinal = -1
            for (occurrence in g.occurrences) {
                chargeIncidentLassoWork(9)
                val f = occurrence.fragment
                verify(f.sourceIndex in segments.indices, InvalidReason.OCCURRENCE)
                val s = segments[f.sourceIndex]
                verify(f.ordinal in 0 until s.events.lastIndex &&
                    (f.sourceIndex > previousSource ||
                        f.sourceIndex == previousSource && f.ordinal > previousOrdinal),
                    InvalidReason.OCCURRENCE)
                previousSource = f.sourceIndex; previousOrdinal = f.ordinal
                verify(f.suppliedStartIndex == s.start.suppliedIndex &&
                    f.suppliedEndIndex == s.end.suppliedIndex, InvalidReason.OCCURRENCE)
                val slot = sized(add(offsets[f.sourceIndex].toLong(), f.ordinal.toLong()))
                verify(slot in intervalSeen.indices && !intervalSeen[slot], InvalidReason.SOURCE_COVERAGE)
                intervalSeen[slot] = true
                val a = s.events[f.ordinal]; val b = s.events[f.ordinal + 1]
                verify(f.startParameter.context === this && f.endParameter.context === this &&
                    f.start.x.context === this && f.start.y.context === this &&
                    f.end.x.context === this && f.end.y.context === this,
                    InvalidReason.OWNER_MISMATCH)
                verify(compare(f.startParameter, a.parameter) == Sign.ZERO &&
                    compare(f.endParameter, b.parameter) == Sign.ZERO &&
                    compare(f.start, a.point) == Sign.ZERO && compare(f.end, b.point) == Sign.ZERO,
                    InvalidReason.SOURCE_INTERVAL)
                val forward = compare(f.start, g.start) == Sign.ZERO &&
                    compare(f.end, g.end) == Sign.ZERO
                val backward = compare(f.start, g.end) == Sign.ZERO &&
                    compare(f.end, g.start) == Sign.ZERO
                verify((forward || backward) && occurrence.followsCanonical == forward,
                    InvalidReason.GROUP_ASSOCIATION)
                toggle[i] = !toggle[i]
                observed = add(observed, 1)
                incidentOdd[g.startVertexId] = !incidentOdd[g.startVertexId]
                incidentOdd[g.endVertexId] = !incidentOdd[g.endVertexId]
                touched[g.startVertexId] = true
                touched[g.endVertexId] = true
            }
        }
        verify(observed == totalIntervals, InvalidReason.SOURCE_COVERAGE)
        for (i in intervalSeen.indices) {
            chargeIncidentLassoWork()
            verify(intervalSeen[i], InvalidReason.SOURCE_COVERAGE)
        }
        // A genuine empty/single-point source cannot be replaced by synthetic isolated graph vertices.
        for (i in vertices.indices) {
            chargeIncidentLassoWork()
            verify(touched[i], InvalidReason.SOURCE_COVERAGE)
            verify(!incidentOdd[i], InvalidReason.ODD_VERTEX_INCIDENCE)
        }
        // Walk-to-face sides of each canonical undirected group form the complete dual constraints.
        val arcs = sized(multiply(groups.size.toLong(), 2L))
        retainRecord(add(512L, add(multiply(faceCount.toLong(), 24L), multiply(arcs.toLong(), 20L))))
        chargeIncidentLassoWork(add(arcs.toLong(), faceCount.toLong()))
        val head = IntArray(faceCount) { -1 }
        val to = IntArray(arcs)
        val next = IntArray(arcs)
        val arcToggle = BooleanArray(arcs)
        val parity = IntArray(faceCount) { -1 }
        val queue = IntArray(faceCount)
        var arcCount = 0
        fun addArc(from: Int, target: Int, changes: Boolean) {
            chargeIncidentLassoWork(3)
            verify(from in 0 until faceCount && target in 0 until faceCount && arcCount < arcs,
                InvalidReason.FACE_MAPPING)
            to[arcCount] = target
            arcToggle[arcCount] = changes
            next[arcCount] = head[from]
            head[from] = arcCount++
        }
        for (i in groups.indices) {
            chargeIncidentLassoWork(5)
            val a = i * 2; val b = a + 1
            val forward = halfEdges[a]; val backward = halfEdges[b]; val group = groups[i]
            verify(forward.id == a && backward.id == b && forward.edgeGroupIndex == i &&
                backward.edgeGroupIndex == i && forward.twin == b && backward.twin == a &&
                forward.canonicalForward && !backward.canonicalForward &&
                forward.origin == group.startVertexId && forward.destination == group.endVertexId &&
                backward.origin == group.endVertexId && backward.destination == group.startVertexId,
                InvalidReason.FACE_MAPPING)
            val leftWalk = cycles.halfEdgeToWalk[a]
            val rightWalk = cycles.halfEdgeToWalk[b]
            verify(leftWalk in map.indices && rightWalk in map.indices, InvalidReason.FACE_MAPPING)
            val left = map[leftWalk]; val right = map[rightWalk]
            verify(left in 0 until faceCount && right in 0 until faceCount,
                InvalidReason.FACE_MAPPING)
            if (left == right) verify(!toggle[i], InvalidReason.ODD_SAME_FACE_EDGE)
            addArc(left, right, toggle[i]); addArc(right, left, toggle[i])
        }
        verify(arcCount == arcs, InvalidReason.FACE_MAPPING)
        // Iterative, bounded BFS. Duplicate constraints are intentionally *not* deduplicated.
        var first = 0; var last = 1
        queue[0] = 0
        parity[0] = 0
        while (first < last) {
            chargeIncidentLassoWork()
            val face = queue[first++]
            var arc = head[face]
            while (arc != -1) {
                chargeIncidentLassoWork(3)
                val other = to[arc]
                val expected = parity[face] xor (if (arcToggle[arc]) 1 else 0)
                if (parity[other] == -1) {
                    verify(last < queue.size, InvalidReason.FACE_MAPPING)
                    parity[other] = expected
                    queue[last++] = other
                } else verify(parity[other] == expected, InvalidReason.CONTRADICTORY_PARITY)
                arc = next[arc]
            }
        }
        verify(last == faceCount, InvalidReason.UNREACHABLE_FACE)
        // Every retained output reservation and final cancellation checkpoint precede publication.
        retainRecord(add(384L, add(multiply(faceCount.toLong(), 128L), multiply(groups.size.toLong(), 24L))))
        val faceOutput = ArrayList<FaceParity>(faceCount)
        val oddOutput = ArrayList<Int>()
        for (i in 0 until faceCount) {
            chargeIncidentLassoWork(2)
            faceOutput.add(FaceParity(i, parity[i] == 1))
            if (parity[i] == 1) { chargeIncidentLassoWork(); oddOutput.add(i) }
        }
        val groupOutput = ArrayList<Boolean>(groups.size)
        for (v in toggle) {
            chargeIncidentLassoWork()
            groupOutput.add(v)
        }
        val frozenFaces = Collections.unmodifiableList(faceOutput)
        val frozenToggles = Collections.unmodifiableList(groupOutput)
        val frozenOddIds = Collections.unmodifiableList(oddOutput)
        chargeIncidentLassoWork() // final publication/cancellation gate
        return Completed(owner, source, frozenFaces, frozenToggles, frozenOddIds, observed, groups.size)
    }
}
