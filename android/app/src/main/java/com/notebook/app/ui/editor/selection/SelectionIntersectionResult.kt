package com.notebook.app.ui.editor.selection

internal data class SelectionIntersectionIdentity(val pageId: String, val elementId: String? = null)
internal enum class SelectionNumericalReason { UNCERTIFIED_COMPARISON, UNCERTIFIED_MAPPING }
internal enum class SelectionInvalidLassoReason { NONFINITE_COORDINATE, DEGENERATE, WRONG_PAGE }
internal enum class SelectionUnavailableReason { UNSUPPORTED_KIND, INVALID_SOURCE, UNVERIFIED_AVAILABILITY }

/** Future page-clipped intersection contract. No Boolean conversion; cancellation is not a result. */
internal sealed interface SelectionIntersectionResult {
    val identity: SelectionIntersectionIdentity
    data class Hit(override val identity: SelectionIntersectionIdentity) : SelectionIntersectionResult
    data class Miss(override val identity: SelectionIntersectionIdentity) : SelectionIntersectionResult
    data class Indeterminate(override val identity: SelectionIntersectionIdentity,
        val reason: SelectionNumericalReason) : SelectionIntersectionResult
    data class InvalidLasso(override val identity: SelectionIntersectionIdentity,
        val reason: SelectionInvalidLassoReason) : SelectionIntersectionResult
    data class Unavailable(override val identity: SelectionIntersectionIdentity,
        val reason: SelectionUnavailableReason) : SelectionIntersectionResult
    data class WorkloadExceeded(override val identity: SelectionIntersectionIdentity,
        val failure: SelectionWorkloadFailure) : SelectionIntersectionResult
}

/** Predicates classify topology, not page-clipped paint. A predicate value is never a visible HIT. */
internal sealed interface SelectionPredicateResult<out T> {
    data class Known<T>(val value: T) : SelectionPredicateResult<T>
    data object InvalidCoordinates : SelectionPredicateResult<Nothing>
    data class Indeterminate(val reason: SelectionNumericalReason) : SelectionPredicateResult<Nothing>
    data class WorkloadExceeded(val failure: SelectionWorkloadFailure) : SelectionPredicateResult<Nothing>
}
