package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionExactSegmentIntersection.Intersection
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/**
 * Exact split preparation only: even an empty, collinear or fully retraced walk can be prepared.
 * No region validity, parity, clipping or visible-paint claim is made.
 */
internal object SelectionLassoSplitEvents {
    data class Vertex(val suppliedIndex: Int, val point: Point)
    data class Event(val point: Point, val parameter: Rational)
    /** sourceIndex is the starting normalized vertex index, not a renumbered graph edge. */
    data class Segment(
        val sourceIndex: Int,
        val start: Vertex,
        val end: Vertex,
        val events: List<Event>,
    )

    /**
     * Sole owner of the arena. Lists are immutable; exact values are borrowed until close.
     * Callers must not retain/use borrowed values past close. Getters and exact operations reject
     * access after disposal. A failed exact operation disposes the entire preparation.
     */
    class Prepared internal constructor(
        private val arena: SelectionExactArithmetic,
        private val input: List<DuPoint>,
        private val vertices: List<Vertex>,
        private val directedSegments: List<Segment>,
    ) : AutoCloseable {
        private var open = true
        val suppliedVertices: List<DuPoint> get() { check(open); return input }
        val normalizedVertices: List<Vertex> get() { check(open); return vertices }
        val segments: List<Segment> get() { check(open); return directedSegments }

        /** Budget-aware access for later event ordering; never nests an arithmetic attempt. */
        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> {
            check(open)
            return try {
                arena.attempt(action).also { if (it !is SelectionPredicateResult.Known) close() }
            } catch (failure: Throwable) { close(); throw failure }
        }

        /**
         * Sequential owner-confined intersection; the kernel starts its own attempt.
         * Calling during exact() rejects nesting and disposes this owner.
         */
        internal fun intersectExistingExactSegments(
            a: Point, b: Point, c: Point, d: Point,
        ): SelectionPredicateResult<Intersection> {
            check(open)
            return try {
                SelectionExactSegmentIntersection.intersect(a, b, c, d, arena)
                    .also { if (it !is SelectionPredicateResult.Known) close() }
            } catch (failure: Throwable) { close(); throw failure }
        }

        override fun close() { if (open) { open = false; arena.close() } }
    }

    private class Aborted(val result: SelectionPredicateResult<Nothing>) : RuntimeException()
    private fun <T> value(result: SelectionPredicateResult<T>): T = when (result) {
        is SelectionPredicateResult.Known -> result.value
        is SelectionPredicateResult.WorkloadExceeded -> throw Aborted(result)
        is SelectionPredicateResult.Indeterminate -> throw Aborted(result)
        SelectionPredicateResult.InvalidCoordinates -> throw Aborted(SelectionPredicateResult.InvalidCoordinates)
    }
    private fun <T> immutable(list: List<T>): List<T> =
        Collections.unmodifiableList(ArrayList(list))

    private class Pending(val index: Int, val start: Vertex, val end: Vertex) {
        val events = ArrayList<Event>()
    }

    /**
     * Thread-confined query. Charge supplied size before copying/expansion; signed zeros compare
     * equal for topology but original input bits (including redundant vertices) are retained.
     * All unordered segment pairs are attempted in ascending (firstIndex, secondIndex) order.
     */
    fun prepare(supplied: List<DuPoint>, budget: SelectionQueryBudget): SelectionPredicateResult<Prepared> {
        val arena = SelectionExactArithmetic(budget)
        var transferred = false
        try {
            fun <T> step(action: SelectionExactArithmetic.() -> T) = value(arena.attempt(action))
            val input = step {
                consume(budget.charge(SelectionQueryResource.LASSO_VERTICES, supplied.size.toLong()))
                // Copies, normalization indices, exact vertex slots, containers and final owner.
                retainRecord(256L + supplied.size.toLong() * 160)
                val copy = ArrayList<DuPoint>(supplied.size)
                for (p in supplied) {
                    consume(budget.charge(SelectionQueryResource.WORK_UNITS, 4))
                    if (!p.x.isFinite() || !p.y.isFinite())
                        throw Aborted(SelectionPredicateResult.InvalidCoordinates)
                    copy.add(DuPoint(p.x, p.y))
                }
                immutable(copy)
            }
            val vertices = step {
                val normalized = ArrayList<Vertex>()
                for (i in input.indices) {
                    consume(budget.charge(SelectionQueryResource.WORK_UNITS, 4))
                    val p = input[i]
                    val previous = normalized.lastOrNull()?.suppliedIndex?.let { input[it] }
                    if (previous == null || p.x != previous.x || p.y != previous.y)
                        normalized.add(Vertex(i, point(p)))
                }
                if (normalized.size > 1) {
                    val first = input[normalized.first().suppliedIndex]
                    val last = input[normalized.last().suppliedIndex]
                    consume(budget.charge(SelectionQueryResource.WORK_UNITS, 4))
                    if (first.x == last.x && first.y == last.y) normalized.removeAt(normalized.lastIndex)
                }
                immutable(normalized)
            }
            val pending = step {
                retainRecord(64L + vertices.size.toLong() * 128)
                val segments = ArrayList<Pending>()
                for (i in vertices.indices) {
                    consume(budget.charge(SelectionQueryResource.WORK_UNITS))
                    val next = vertices[(i + 1) % vertices.size]
                    if (compare(vertices[i].point, next.point) != Sign.ZERO)
                        segments.add(Pending(i, vertices[i], next))
                }
                segments
            }
            fun insert(segment: Pending, point: Point, parameter: Rational) = step {
                // Includes endpoints and every duplicate candidate, before record allocation.
                consume(budget.charge(SelectionQueryResource.ARRANGEMENT_EVENTS))
                consume(budget.charge(SelectionQueryResource.WORK_UNITS))
                // Event, list slot/growth allowance and immutable output slot.
                retainRecord(96)
                segment.events.add(Event(point, parameter))
                Unit
            }
            val parameters = step { rational(0) to rational(1) }
            for (segment in pending) {
                insert(segment, segment.start.point, parameters.first)
                insert(segment, segment.end.point, parameters.second)
            }
            for (i in pending.indices) for (j in i + 1 until pending.size) {
                // Pair attempts consume work, not event quota, including disjoint pairs.
                step { consume(budget.charge(SelectionQueryResource.WORK_UNITS)); Unit }
                val a = pending[i]; val b = pending[j]
                when (val hit = value(SelectionExactSegmentIntersection.intersect(
                    a.start.point, a.end.point, b.start.point, b.end.point, arena))) {
                    Intersection.Disjoint -> Unit
                    is Intersection.AtPoint -> {
                        insert(a, hit.point, hit.firstParameter)
                        insert(b, hit.point, hit.secondParameter)
                    }
                    is Intersection.Overlap -> {
                        insert(a, hit.first, hit.firstSegmentAtFirst)
                        insert(a, hit.last, hit.firstSegmentAtLast)
                        insert(b, hit.first, hit.secondSegmentAtFirst)
                        insert(b, hit.last, hit.secondSegmentAtLast)
                    }
                }
            }
            val segments = step {
                retainRecord(64L + pending.size.toLong() * 96)
                val output = ArrayList<Segment>(pending.size)
                for (segment in pending) {
                    val ordered = sortEvents(segment.events, budget)
                    val unique = ArrayList<Event>()
                    for (event in ordered) {
                        consume(budget.charge(SelectionQueryResource.WORK_UNITS))
                        if (unique.isEmpty() || compare(unique.last().parameter, event.parameter) != Sign.ZERO)
                            unique.add(event)
                    }
                    output.add(Segment(segment.index, segment.start, segment.end, immutable(unique)))
                }
                immutable(output)
            }
            // Charge final handoff/cancellation before publishing any partial structure.
            step { consume(budget.charge(SelectionQueryResource.WORK_UNITS)); Unit }
            val result = Prepared(arena, input, vertices, segments)
            transferred = true
            return SelectionPredicateResult.Known(result)
        } catch (aborted: Aborted) {
            return aborted.result
        } finally {
            if (!transferred) arena.close()
        }
    }

    /** Stable bottom-up mergesort; each comparison is exact and each slot move is charged. */
    private fun SelectionExactArithmetic.sortEvents(
        events: List<Event>, budget: SelectionQueryBudget,
    ): List<Event> {
        // Two arrays and output slots. Conservatively retained in the arena until disposal.
        retainRecord(128L + events.size.toLong() * 32)
        consume(budget.charge(SelectionQueryResource.WORK_UNITS, events.size.toLong()))
        var source = events.toTypedArray()
        var target = source.copyOf()
        var width = 1
        while (width < source.size) {
            var begin = 0
            while (begin < source.size) {
                val middle = minOf(begin.toLong() + width, source.size.toLong()).toInt()
                val end = minOf(middle.toLong() + width, source.size.toLong()).toInt()
                var left = begin; var right = middle
                for (slot in begin until end) {
                    consume(budget.charge(SelectionQueryResource.WORK_UNITS))
                    target[slot] = if (left < middle && (right >= end ||
                        compare(source[left].parameter, source[right].parameter) != Sign.POSITIVE))
                        source[left++] else source[right++]
                }
                begin = end
            }
            val swap = source; source = target; target = swap
            width = minOf(width.toLong() * 2, source.size.toLong()).toInt()
        }
        return source.asList()
    }
}
