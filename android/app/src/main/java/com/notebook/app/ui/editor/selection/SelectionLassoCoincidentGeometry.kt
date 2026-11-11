package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionLassoAtomicEdges.Fragment
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Geometric identity and traversal grouping only; no connectivity, parity or filled region. */
internal object SelectionLassoCoincidentGeometry {
    data class Vertex(val id: Int, val point: Point)
    /** The original directed fragment retains every source index, ordinal and parameter. */
    data class Occurrence(val fragment: Fragment, val followsCanonical: Boolean)
    data class EdgeGroup(
        val startVertexId: Int, val endVertexId: Int,
        val start: Point, val end: Point,
        val occurrences: List<Occurrence>,
    )
    enum class InvalidReason {
        OWNER_CLOSED, OWNER_MISMATCH, SOURCE_ASSOCIATION, ZERO_LENGTH,
        PARAMETER_INTERVAL, ENDPOINT_ASSOCIATION,
    }
    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidInput(val reason: InvalidReason) : Result
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    /** Sole lifetime owner remains Prepared. Returned references are borrowed until it closes. */
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val points: List<Vertex>,
        private val groups: List<EdgeGroup>,
    ) {
        val vertices: List<Vertex> get() { owner.segments; return points }
        val edges: List<EdgeGroup> get() { owner.segments; return groups }
        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> =
            owner.exact(action)
    }

    private class Invalid(val reason: InvalidReason) : RuntimeException()
    private class Unresolved(val outcome: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Unresolved(result)
        is SelectionPredicateResult.Indeterminate -> throw Unresolved(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Unresolved(SelectionPredicateResult.InvalidCoordinates)
    }
    private fun verify(condition: Boolean, reason: InvalidReason) { if (!condition) throw Invalid(reason) }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private data class Endpoint(val point: Point, val fragmentIndex: Int, val start: Boolean)
    private data class Candidate(val low: Int, val high: Int, val fragmentIndex: Int)

    fun build(prepared: Prepared, atomic: SelectionLassoAtomicEdges.Borrowed): Result {
        var success = false
        try {
            // Only getters are caught here: cancellation callbacks are never mistaken for closure.
            val fragments = try {
                prepared.segments
                atomic.fragments
            } catch (_: IllegalStateException) { throw Invalid(InvalidReason.OWNER_CLOSED) }
            val ownerArena = value(prepared.exact { this })
            val atomicArena = value(atomic.exact { this })
            verify(ownerArena === atomicArena, InvalidReason.OWNER_MISMATCH)
            val grouped = value(prepared.exact { construct(prepared, fragments) })
            success = true
            return Result.Ready(grouped)
        } catch (invalid: Invalid) {
            return Result.InvalidInput(invalid.reason)
        } catch (unresolved: Unresolved) {
            return Result.Unresolved(unresolved.outcome)
        } finally {
            // A mismatched unrelated owner is not disposed; the supplied query owner always is.
            if (!success) prepared.close()
        }
    }

    private fun SelectionExactArithmetic.construct(prepared: Prepared, fragments: List<Fragment>): Borrowed {
        chargeCoincidentLassoWork()
        retainRecord(256)
        val sources = prepared.segments
        retainRecord(64L + sources.size.toLong() * 8)
        val offsets = IntArray(sources.size)
        var expected = 0L
        for (i in sources.indices) {
            chargeCoincidentLassoWork()
            val source = sources[i]
            verify(source.sourceIndex == i && source.events.size >= 2, InvalidReason.SOURCE_ASSOCIATION)
            offsets[i] = expected.toInt()
            expected += source.events.size.toLong() - 1
            verify(expected <= Int.MAX_VALUE, InvalidReason.SOURCE_ASSOCIATION)
        }
        verify(expected == fragments.size.toLong(), InvalidReason.SOURCE_ASSOCIATION)
        // Seen source occurrences, endpoint-ID maps, candidates and endpoint working records.
        retainRecord(192L + fragments.size.toLong() * 320)
        val seen = BooleanArray(fragments.size)
        val starts = IntArray(fragments.size) { -1 }
        val ends = IntArray(fragments.size) { -1 }
        val endpoints = ArrayList<Endpoint>()
        val zero = rational(0); val one = rational(1)
        fun owns(p: Point) = p.x.context === this && p.y.context === this
        for (i in fragments.indices) {
            chargeCoincidentLassoWork()
            val f = fragments[i]
            verify(f.sourceIndex in sources.indices, InvalidReason.SOURCE_ASSOCIATION)
            val s = sources[f.sourceIndex]
            verify(f.ordinal >= 0 && f.ordinal < s.events.lastIndex &&
                f.suppliedStartIndex == s.start.suppliedIndex &&
                f.suppliedEndIndex == s.end.suppliedIndex, InvalidReason.SOURCE_ASSOCIATION)
            val slot = offsets[f.sourceIndex] + f.ordinal
            verify(!seen[slot], InvalidReason.SOURCE_ASSOCIATION)
            seen[slot] = true
            verify(owns(f.start) && owns(f.end) && f.startParameter.context === this &&
                f.endParameter.context === this, InvalidReason.OWNER_MISMATCH)
            verify(compare(f.start, f.end) != Sign.ZERO, InvalidReason.ZERO_LENGTH)
            verify(compare(f.startParameter, f.endParameter) == Sign.NEGATIVE &&
                compare(f.startParameter, zero) != Sign.NEGATIVE &&
                compare(f.endParameter, one) != Sign.POSITIVE, InvalidReason.PARAMETER_INTERVAL)
            val a = s.events[f.ordinal]; val b = s.events[f.ordinal + 1]
            verify(compare(f.startParameter, a.parameter) == Sign.ZERO &&
                compare(f.endParameter, b.parameter) == Sign.ZERO, InvalidReason.PARAMETER_INTERVAL)
            verify(compare(f.start, a.point) == Sign.ZERO && compare(f.end, b.point) == Sign.ZERO,
                InvalidReason.ENDPOINT_ASSOCIATION)
            chargeCoincidentLassoWork() // each attempted endpoint visit, before record allocation
            endpoints.add(Endpoint(f.start, i, true))
            chargeCoincidentLassoWork()
            endpoints.add(Endpoint(f.end, i, false))
        }
        val sorted = ordered(endpoints) { a, b -> compare(a.point, b.point).order() }
        val vertices = ArrayList<Vertex>()
        for (endpoint in sorted) {
            chargeCoincidentLassoWork()
            if (vertices.isEmpty() || compare(vertices.last().point, endpoint.point) != Sign.ZERO) {
                chargeCoincidentLassoWork() // attempted interned-vertex record
                retainRecord(80)
                vertices.add(Vertex(vertices.size, endpoint.point))
            }
            val id = vertices.lastIndex
            if (endpoint.start) starts[endpoint.fragmentIndex] = id else ends[endpoint.fragmentIndex] = id
        }
        val candidates = ArrayList<Candidate>()
        for (i in fragments.indices) {
            chargeCoincidentLassoWork()
            verify(starts[i] in vertices.indices && ends[i] in vertices.indices,
                InvalidReason.ENDPOINT_ASSOCIATION)
            verify(starts[i] != ends[i], InvalidReason.ZERO_LENGTH)
            candidates.add(Candidate(minOf(starts[i], ends[i]), maxOf(starts[i], ends[i]), i))
        }
        // Integer identity ordering only; no rational hash/equality/comparator bypass.
        val sortedCandidates = ordered(candidates) { a, b ->
            chargeCoincidentLassoWork(4)
            val af = fragments[a.fragmentIndex]; val bf = fragments[b.fragmentIndex]
            when {
                a.low != b.low -> a.low.compareTo(b.low)
                a.high != b.high -> a.high.compareTo(b.high)
                af.sourceIndex != bf.sourceIndex -> af.sourceIndex.compareTo(bf.sourceIndex)
                else -> af.ordinal.compareTo(bf.ordinal)
            }
        }
        val groups = ArrayList<EdgeGroup>()
        var begin = 0
        while (begin < sortedCandidates.size) {
            val first = sortedCandidates[begin]
            chargeCoincidentLassoEdgeGroup()
            val occurrences = ArrayList<Occurrence>()
            var cursor = begin
            while (cursor < sortedCandidates.size) {
                chargeCoincidentLassoWork()
                val candidate = sortedCandidates[cursor]
                if (candidate.low != first.low || candidate.high != first.high) break
                chargeCoincidentLassoWork() // original occurrence association/insertion
                retainRecord(96)
                occurrences.add(Occurrence(fragments[candidate.fragmentIndex],
                    starts[candidate.fragmentIndex] == first.low))
                cursor++
            }
            groups.add(EdgeGroup(first.low, first.high, vertices[first.low].point,
                vertices[first.high].point, immutable(occurrences)))
            begin = cursor
        }
        chargeCoincidentLassoWork()
        return Borrowed(prepared, immutable(vertices), immutable(groups))
    }

    private fun Sign.order() = when (this) { Sign.NEGATIVE -> -1; Sign.ZERO -> 0; Sign.POSITIVE -> 1 }

    /** Stable bounded mergesort. Every move and exact/integer comparison is explicitly metered. */
    private fun <T> SelectionExactArithmetic.ordered(items: List<T>, compare: (T, T) -> Int): List<T> {
        retainRecord(128L + items.size.toLong() * 32)
        chargeCoincidentLassoWork(items.size.toLong())
        var source = ArrayList(items)
        var target = ArrayList(items)
        var width = 1
        while (width < source.size) {
            var begin = 0
            while (begin < source.size) {
                val middle = minOf(begin.toLong() + width, source.size.toLong()).toInt()
                val end = minOf(middle.toLong() + width, source.size.toLong()).toInt()
                var left = begin; var right = middle
                for (slot in begin until end) {
                    chargeCoincidentLassoWork()
                    target[slot] = if (left < middle && (right >= end ||
                        compare(source[left], source[right]) <= 0)) source[left++] else source[right++]
                }
                begin = end
            }
            val swap = source; source = target; target = swap
            width = minOf(width.toLong() * 2, source.size.toLong()).toInt()
        }
        return source
    }
}
