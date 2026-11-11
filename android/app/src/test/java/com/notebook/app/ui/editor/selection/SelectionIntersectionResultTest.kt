package com.notebook.app.ui.editor.selection

import org.junit.Assert.*
import org.junit.Test

class SelectionIntersectionResultTest {
    private val id = SelectionIntersectionIdentity("page", "element")
    @Test fun outcomesPreserveIdentityAndTypedReasons() {
        val budget = SelectionQueryBudget()
        val failure = budget.charge(SelectionQueryResource.WORK_UNITS, Long.MAX_VALUE)!!
        val results = listOf(
            SelectionIntersectionResult.Hit(id), SelectionIntersectionResult.Miss(id),
            SelectionIntersectionResult.Indeterminate(id, SelectionNumericalReason.UNCERTIFIED_COMPARISON),
            SelectionIntersectionResult.InvalidLasso(id, SelectionInvalidLassoReason.DEGENERATE),
            SelectionIntersectionResult.Unavailable(id, SelectionUnavailableReason.UNVERIFIED_AVAILABILITY),
            SelectionIntersectionResult.WorkloadExceeded(id, failure),
        )
        assertEquals(6, results.map { it.javaClass }.distinct().size)
        assertTrue(results.all { it.identity == id })
        assertEquals(SelectionUnavailableReason.UNVERIFIED_AVAILABILITY,
            (results[4] as SelectionIntersectionResult.Unavailable).reason)
        assertSame(failure, (results[5] as SelectionIntersectionResult.WorkloadExceeded).failure)
    }
    @Test fun predicateUnresolvedIsNotAValueOrVisibleMiss() {
        val unresolved: SelectionPredicateResult<SelectionRobustPredicates.Sign> =
            SelectionPredicateResult.Indeterminate(SelectionNumericalReason.UNCERTIFIED_MAPPING)
        assertFalse(unresolved is SelectionPredicateResult.Known)
        assertEquals(SelectionPredicateResult.InvalidCoordinates, SelectionPredicateResult.InvalidCoordinates)
        assertNull(SelectionIntersectionIdentity("page").elementId)
    }
}
