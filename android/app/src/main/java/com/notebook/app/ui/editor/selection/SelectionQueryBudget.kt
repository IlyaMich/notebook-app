package com.notebook.app.ui.editor.selection

import java.util.Collections
import java.util.concurrent.CancellationException

/**
 * Counters measure caller-charged items; declaring a counter does not implement its producer.
 * Vertices = supplied vertex visits (before future normalization); events = candidate arrangement
 * events processed; edges/faces = records reserved; elements = attempted element evaluations;
 * samples/primitives = decoded/derived records reserved; ellipse nodes = refinement nodes visited.
 * ENVELOPE_BYTES is a conservative representation charge (four bytes per UTF-16 code unit per
 * parsing pass), not an asset-byte count. Depth/token length are separate maximum checks.
 * Producers must charge before doing work and reuse a scoped handle for the same element/ellipse.
 */
internal enum class SelectionQueryResource(val queryCeiling: Long, val elementCeiling: Long? = null) {
    LASSO_VERTICES(1_024),
    ARRANGEMENT_EVENTS(8_192),
    ARRANGEMENT_EDGES(16_384),
    ARRANGEMENT_FACES(8_192),
    TESTED_ELEMENTS(4_096),
    STROKE_SAMPLES(131_072, 16_384),
    DERIVED_PRIMITIVES(131_072, 16_384),
    // Representation charges, NOT measured JVM heap or encoded asset bytes.
    ENVELOPE_BYTES(16L * 1_024 * 1_024, 1L * 1_024 * 1_024),
    ELLIPSE_NODES(65_536),
    // Sum of charged attempted arithmetic/refinement costs; no wall-clock semantics.
    WORK_UNITS(2_000_000),
    // Live reservations, released explicitly; NOT actual heap usage.
    TEMPORARY_BYTES(32L * 1_024 * 1_024),
}

internal enum class SelectionBudgetScope { QUERY, ELEMENT, ELLIPSE, OPERAND }
internal enum class SelectionBudgetConstraint { QUOTA, JSON_DEPTH, NUMERIC_TOKEN_LENGTH, EXACT_BITS }
internal data class SelectionWorkloadFailure(
    val scope: SelectionBudgetScope,
    val constraint: SelectionBudgetConstraint,
    val resource: SelectionQueryResource?,
    val used: Long,
    val requested: Long,
    val limit: Long,
)

/** Provisional operational limits only. Copies maps so callers cannot change a running budget. */
internal class SelectionQueryLimits(
    query: Map<SelectionQueryResource, Long> = SelectionQueryResource.entries.associateWith { it.queryCeiling },
    element: Map<SelectionQueryResource, Long> = SelectionQueryResource.entries.mapNotNull {
        r -> r.elementCeiling?.let { r to it }
    }.toMap(),
    val jsonDepth: Int = 32,
    val numericTokenLength: Int = 128,
    val exactBits: Int = 4_096,
    val ellipseNodes: Long = 4_096,
) {
    val query: Map<SelectionQueryResource, Long> = Collections.unmodifiableMap(HashMap(query))
    val element: Map<SelectionQueryResource, Long> = Collections.unmodifiableMap(HashMap(element))
    init {
        require(this.query.keys == SelectionQueryResource.entries.toSet())
        require(this.element.keys == SelectionQueryResource.entries.filter { it.elementCeiling != null }.toSet())
        require(this.query.values.all { it >= 0 } && this.element.values.all { it >= 0 })
        require(jsonDepth >= 0 && numericTokenLength >= 0 && exactBits > 0 && ellipseNodes >= 0)
    }
}

/**
 * Single-query, thread-confined accounting. Charges precede work; denied attempts are described
 * in failure and do not overflow/increment accepted reservations. Exhaustion is sticky.
 * Scoped + aggregate charges are atomic. Previous successful work remains charged on failure.
 * Cancellation callback must throw to cancel; callers can inject coroutine ensureActive().
 */
internal class SelectionQueryBudget(
    val limits: SelectionQueryLimits = SelectionQueryLimits(),
    private val cancellationCheckpoint: () -> Unit = {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Selection cancelled")
    },
) {
    private val used = SelectionQueryResource.entries.associateWith { 0L }.toMutableMap()
    var failure: SelectionWorkloadFailure? = null
        private set

    inner class Element internal constructor() {
        internal val owner = this@SelectionQueryBudget
        internal val used = limits.element.keys.associateWith { 0L }.toMutableMap()
    }
    inner class Ellipse internal constructor() {
        internal val owner = this@SelectionQueryBudget
        internal var nodes: Long = 0
    }

    fun checkpoint() = cancellationCheckpoint()
    fun used(resource: SelectionQueryResource): Long = used.getValue(resource)

    /** Opening an element is itself attempted work, charged before creating the handle. */
    fun beginElement(): Element? =
        if (charge(SelectionQueryResource.TESTED_ELEMENTS) == null) Element() else null
    fun beginEllipse(): Ellipse? { checkpoint(); return if (failure == null) Ellipse() else null }

    fun charge(
        resource: SelectionQueryResource, amount: Long = 1,
        element: Element? = null, ellipse: Ellipse? = null,
    ): SelectionWorkloadFailure? {
        require(amount >= 0)
        require((resource in limits.element) == (element != null))
        require(element == null || element.owner === this)
        require((resource == SelectionQueryResource.ELLIPSE_NODES) == (ellipse != null))
        require(ellipse == null || ellipse.owner === this)
        checkpoint()
        failure?.let { return it }
        fun exceeded(scope: SelectionBudgetScope, current: Long, limit: Long) =
            if (amount > limit - current)
                SelectionWorkloadFailure(scope, SelectionBudgetConstraint.QUOTA, resource, current, amount, limit)
            else null
        val rejected = exceeded(SelectionBudgetScope.QUERY, used.getValue(resource), limits.query.getValue(resource))
            ?: element?.let { exceeded(SelectionBudgetScope.ELEMENT, it.used.getValue(resource), limits.element.getValue(resource)) }
            ?: ellipse?.let { exceeded(SelectionBudgetScope.ELLIPSE, it.nodes, limits.ellipseNodes) }
        if (rejected != null) { failure = rejected; return rejected }
        used[resource] = used.getValue(resource) + amount
        element?.let { it.used[resource] = it.used.getValue(resource) + amount }
        ellipse?.let { it.nodes += amount }
        return null
    }

    /** Check maxima before parsing/arithmetic; no parser or allocation is performed here. */
    fun checkDepth(depth: Int) = checkMaximum(depth.toLong(), limits.jsonDepth.toLong(),
        SelectionBudgetConstraint.JSON_DEPTH, SelectionBudgetScope.QUERY)
    fun checkNumericTokenLength(length: Int) = checkMaximum(length.toLong(), limits.numericTokenLength.toLong(),
        SelectionBudgetConstraint.NUMERIC_TOKEN_LENGTH, SelectionBudgetScope.QUERY)
    fun checkExactBits(bits: Long) = checkMaximum(bits, limits.exactBits.toLong(),
        SelectionBudgetConstraint.EXACT_BITS, SelectionBudgetScope.OPERAND)

    private fun checkMaximum(value: Long, maximum: Long, constraint: SelectionBudgetConstraint,
        scope: SelectionBudgetScope): SelectionWorkloadFailure? {
        require(value >= 0)
        checkpoint()
        failure?.let { return it }
        if (value > maximum) failure = SelectionWorkloadFailure(scope, constraint, null, 0, value, maximum)
        return failure
    }

    /** Only for a successfully acquired temporary reservation. Does not clear exhaustion. */
    internal fun releaseTemporary(bytes: Long) {
        require(bytes >= 0 && bytes <= used.getValue(SelectionQueryResource.TEMPORARY_BYTES))
        used[SelectionQueryResource.TEMPORARY_BYTES] = used.getValue(SelectionQueryResource.TEMPORARY_BYTES) - bytes
    }
}
