package com.notebook.app.ui.editor.selection

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionQueryBudgetTest {
    private fun query(resource: SelectionQueryResource, amount: Long) =
        SelectionQueryResource.entries.associateWith { if (it == resource) amount else it.queryCeiling }
    @Test fun atBelowAndAboveQuotaAreAtomicAndSticky() {
        val r = SelectionQueryResource.LASSO_VERTICES
        val b = SelectionQueryBudget(SelectionQueryLimits(query = query(r, 2)))
        assertNull(b.charge(r)); assertEquals(1L, b.used(r))
        assertNull(b.charge(r)); assertEquals(2L, b.used(r))
        val failure = b.charge(r)!!
        assertEquals(2L, failure.used); assertEquals(1L, failure.requested)
        assertEquals(SelectionBudgetScope.QUERY, failure.scope)
        assertEquals(2L, b.used(r)); assertSame(failure, b.charge(SelectionQueryResource.WORK_UNITS))
    }
    @Test fun scopedAndAggregateQuotasCannotBeBypassed() {
        val r = SelectionQueryResource.STROKE_SAMPLES
        val limits = SelectionQueryLimits(query = query(r, 3),
            element = SelectionQueryResource.entries.mapNotNull { x -> x.elementCeiling?.let { x to if (x == r) 2L else it } }.toMap())
        val b = SelectionQueryBudget(limits)
        val a = b.beginElement()!!; val c = b.beginElement()!!
        assertNull(b.charge(r, 2, a)); assertNull(b.charge(r, 1, c))
        assertEquals(SelectionBudgetScope.QUERY, b.charge(r, 1, c)!!.scope)
        val other = SelectionQueryBudget(limits); val e = other.beginElement()!!
        assertEquals(SelectionBudgetScope.ELEMENT, other.charge(r, 3, e)!!.scope)
        assertEquals(0L, other.used(r))
        assertThrows(IllegalArgumentException::class.java) { other.charge(r) }
        assertThrows(IllegalArgumentException::class.java) { b.charge(r, 0, e) }
    }
    @Test fun longCountersCannotOverflow() {
        val r = SelectionQueryResource.WORK_UNITS
        val b = SelectionQueryBudget(SelectionQueryLimits(query = query(r, Long.MAX_VALUE)))
        assertNull(b.charge(r, Long.MAX_VALUE))
        assertNotNull(b.charge(r, 1))
        assertEquals(Long.MAX_VALUE, b.used(r))
    }
    @Test fun invalidLimitsAndChargesReject() {
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(exactBits = 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(jsonDepth = -1) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(numericTokenLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(ellipseNodes = -1) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(query = emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryLimits(query = query(SelectionQueryResource.WORK_UNITS, -1)) }
        assertThrows(IllegalArgumentException::class.java) { SelectionQueryBudget().charge(SelectionQueryResource.WORK_UNITS, -1) }
    }
    @Test fun maximumChecksHaveTypedFailures() {
        assertNull(SelectionQueryBudget().checkDepth(32))
        assertEquals(SelectionBudgetConstraint.JSON_DEPTH, SelectionQueryBudget().checkDepth(33)!!.constraint)
        assertNull(SelectionQueryBudget().checkNumericTokenLength(128))
        assertEquals(SelectionBudgetConstraint.NUMERIC_TOKEN_LENGTH, SelectionQueryBudget().checkNumericTokenLength(129)!!.constraint)
        assertNull(SelectionQueryBudget().checkExactBits(4096))
        assertEquals(SelectionBudgetConstraint.EXACT_BITS, SelectionQueryBudget().checkExactBits(4097)!!.constraint)
    }
    @Test fun ellipseScopesAndTemporaryReservations() {
        val b = SelectionQueryBudget()
        val e = b.beginEllipse()!!
        assertNull(b.charge(SelectionQueryResource.ELLIPSE_NODES, 4096, ellipse = e))
        assertEquals(SelectionBudgetScope.ELLIPSE, b.charge(SelectionQueryResource.ELLIPSE_NODES, ellipse = e)!!.scope)
        assertNull(b.beginEllipse())
        val t = SelectionQueryBudget()
        assertNull(t.charge(SelectionQueryResource.TEMPORARY_BYTES, 32))
        t.releaseTemporary(32); assertEquals(0L, t.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalArgumentException::class.java) { t.releaseTemporary(1) }
    }
    @Test fun limitsCopyCallerMaps() {
        val source = query(SelectionQueryResource.WORK_UNITS, 2).toMutableMap()
        val b = SelectionQueryBudget(SelectionQueryLimits(query = source))
        source[SelectionQueryResource.WORK_UNITS] = 0
        assertNull(b.charge(SelectionQueryResource.WORK_UNITS, 2))
    }
    @Test fun cancellationPropagatesBeforeCharging() {
        val b = SelectionQueryBudget(cancellationCheckpoint = { throw CancellationException() })
        assertThrows(CancellationException::class.java) { b.charge(SelectionQueryResource.WORK_UNITS) }
        assertEquals(0L, b.used(SelectionQueryResource.WORK_UNITS))
        assertNull(b.failure)
    }
}
