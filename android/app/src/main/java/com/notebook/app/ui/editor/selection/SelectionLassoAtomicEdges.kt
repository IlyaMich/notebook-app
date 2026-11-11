package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Point
import com.notebook.app.ui.editor.selection.SelectionExactArithmetic.Rational
import com.notebook.app.ui.editor.selection.SelectionLassoSplitEvents.Prepared
import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.util.Collections

/** Directed source occurrences only. No merging, graph topology, parity or region validity. */
internal object SelectionLassoAtomicEdges {
    data class Fragment(
        val sourceIndex: Int,
        val suppliedStartIndex: Int,
        val suppliedEndIndex: Int,
        val ordinal: Int,
        val start: Point,
        val end: Point,
        val startParameter: Rational,
        val endParameter: Rational,
    )

    enum class InvalidReason {
        SOURCE_ASSOCIATION, SOURCE_ORDER, INCOMPLETE_EVENTS, SOURCE_ENDPOINTS,
        PARAMETER_ORDER, COINCIDENT_ENDPOINTS, EVENT_POSITION,
    }

    sealed interface Result {
        data class Ready(val value: Borrowed) : Result
        data class InvalidPrepared(val reason: InvalidReason, val sourceIndex: Int, val ordinal: Int?) : Result
        /** Existing typed arithmetic failures; never a successful empty fragment collection. */
        data class Unresolved(val cause: SelectionPredicateResult<Nothing>) : Result
    }

    /**
     * Borrows the sole Prepared owner; intentionally has no independent close or arena.
     * Exact values/list contents must not be used after the owner closes. Access through this
     * wrapper and exact operations check owner lifetime. Retained lists are defensively immutable.
     */
    class Borrowed internal constructor(
        private val owner: Prepared,
        private val records: List<Fragment>,
    ) {
        val fragments: List<Fragment> get() {
            owner.segments // enforce the owner's lifetime before exposing borrowed records
            return records
        }
        internal fun isOwnedBy(prepared: Prepared): Boolean = owner === prepared

        fun <T> exact(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> =
            owner.exact(action)
    }

    private class Invalid(val rejection: Result.InvalidPrepared) : RuntimeException()

    /**
     * Uses only the owner's original arena and budget, in one non-nested attempt. Invalid prepared
     * data, workload failure or cancellation disposes that owner, invalidating all its borrowers.
     */
    fun build(prepared: Prepared): Result {
        val outcome = try {
            prepared.exact {
                retainRecord(128) // result, borrower and immutable output containers
                val output = ArrayList<Fragment>()
                val vertices = prepared.normalizedVertices
                val supplied = prepared.suppliedVertices
                val zero = rational(0); val one = rational(1)
                var previousSource = -1
                fun verify(valid: Boolean, reason: InvalidReason, source: Int, ordinal: Int? = null) {
                    if (!valid) throw Invalid(Result.InvalidPrepared(reason, source, ordinal))
                }
                val segments = prepared.segments
                verify(segments.size == if (vertices.size > 1) vertices.size else 0,
                    InvalidReason.SOURCE_ASSOCIATION, -1)
                for (segment in segments) {
                    val source = segment.sourceIndex
                    verify(source > previousSource, InvalidReason.SOURCE_ORDER, source)
                    verify(source in vertices.indices, InvalidReason.SOURCE_ASSOCIATION, source)
                    previousSource = source
                    val startVertex = vertices[source]
                    val endVertex = vertices[(source + 1) % vertices.size]
                    verify(segment.start.suppliedIndex == startVertex.suppliedIndex &&
                        segment.end.suppliedIndex == endVertex.suppliedIndex &&
                        segment.start.suppliedIndex in supplied.indices &&
                        segment.end.suppliedIndex in supplied.indices,
                        InvalidReason.SOURCE_ASSOCIATION, source)
                    verify(compare(segment.start.point, startVertex.point) == Sign.ZERO &&
                        compare(segment.end.point, endVertex.point) == Sign.ZERO &&
                        compare(segment.start.point, segment.end.point) != Sign.ZERO,
                        InvalidReason.SOURCE_ENDPOINTS, source)
                    val events = segment.events
                    verify(events.size >= 2, InvalidReason.INCOMPLETE_EVENTS, source)
                    verify(compare(events.first().parameter, zero) == Sign.ZERO &&
                        compare(events.last().parameter, one) == Sign.ZERO &&
                        compare(events.first().point, segment.start.point) == Sign.ZERO &&
                        compare(events.last().point, segment.end.point) == Sign.ZERO,
                        InvalidReason.SOURCE_ENDPOINTS, source)
                    val delta = vector(segment.end.point, segment.start.point)
                    for (ordinal in 0 until events.lastIndex) {
                        // Charge every attempt, including a fragment subsequently found invalid.
                        chargeAtomicLassoEdgeRecord()
                        val a = events[ordinal]; val b = events[ordinal + 1]
                        verify(compare(a.parameter, b.parameter) == Sign.NEGATIVE,
                            InvalidReason.PARAMETER_ORDER, source, ordinal)
                        verify(compare(a.point, b.point) != Sign.ZERO,
                            InvalidReason.COINCIDENT_ENDPOINTS, source, ordinal)
                        // The first event was checked above; each following event is checked once.
                        val expected = if (ordinal + 1 == events.lastIndex) segment.end.point else
                            point(add(segment.start.point.x, multiply(b.parameter, delta.x)),
                                add(segment.start.point.y, multiply(b.parameter, delta.y)))
                        verify(compare(b.point, expected) == Sign.ZERO,
                            InvalidReason.EVENT_POSITION, source, ordinal)
                        output.add(Fragment(source, segment.start.suppliedIndex, segment.end.suppliedIndex,
                            ordinal, a.point, b.point, a.parameter, b.parameter))
                    }
                }
                Borrowed(prepared, Collections.unmodifiableList(ArrayList(output)))
            }
        } catch (invalid: Invalid) {
            // Prepared.exact already disposed the arena. No partial records escape this attempt.
            return invalid.rejection
        }
        return when (outcome) {
            is SelectionPredicateResult.Known -> Result.Ready(outcome.value)
            is SelectionPredicateResult.WorkloadExceeded -> Result.Unresolved(outcome)
            is SelectionPredicateResult.Indeterminate -> Result.Unresolved(outcome)
            SelectionPredicateResult.InvalidCoordinates -> Result.Unresolved(SelectionPredicateResult.InvalidCoordinates)
        }
    }
}
