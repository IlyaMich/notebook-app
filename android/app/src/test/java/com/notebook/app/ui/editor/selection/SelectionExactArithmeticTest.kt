package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.selection.SelectionRobustPredicates.Sign
import java.math.BigInteger
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionExactArithmeticTest {
    private fun <T> known(r: SelectionPredicateResult<T>) = (r as SelectionPredicateResult.Known<T>).value
    @Test fun conversionPreservesBinaryValueRatherThanDecimalSpelling() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val value = known(a.attempt { fromDouble(0.1) })
            assertEquals(BigInteger("3602879701896397"), value.numerator)
            assertEquals(BigInteger("36028797018963968"), value.denominator)
        }
    }
    @Test fun minimumSubnormalAndLargestFiniteConvertExactly() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val values = known(a.attempt { fromDouble(Double.MIN_VALUE) to fromDouble(Double.MAX_VALUE) })
            assertEquals(BigInteger.ONE, values.first.numerator)
            assertEquals(BigInteger.ONE.shiftLeft(1074), values.first.denominator)
            assertEquals(BigInteger.ONE, values.second.denominator)
            assertEquals(BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE).shiftLeft(971), values.second.numerator)
        }
    }
    @Test fun signedZerosAreCanonicalAndEqual() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val z = known(a.attempt { fromDouble(-0.0) to fromDouble(0.0) })
            assertEquals(z.first, z.second)
            assertEquals(BigInteger.ZERO, z.first.numerator)
            assertEquals(BigInteger.ONE, z.first.denominator)
        }
    }
    @Test fun fractionsNormalizeSignAndCommonFactors() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            val r = known(a.attempt { rational(2, -6) to rational(-1, 3) })
            assertEquals(r.first, r.second)
            assertEquals(BigInteger.valueOf(-1), r.first.numerator)
            assertEquals(BigInteger.valueOf(3), r.first.denominator)
            assertEquals(Sign.ZERO, known(a.attempt { compare(r.first, r.second) }))
        }
    }
    @Test fun rationalArithmeticAndOrderingAreExact() {
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            known(a.attempt {
                val third = rational(1, 3); val half = rational(1, 2)
                assertEquals(rational(5, 6), add(third, half))
                assertEquals(rational(-1, 6), subtract(third, half))
                assertEquals(rational(1, 6), multiply(third, half))
                assertEquals(rational(2, 3), divide(third, half))
                assertEquals(Sign.NEGATIVE, compare(third, half))
                assertEquals(Sign.POSITIVE, sign(half))
                assertEquals(Sign.NEGATIVE, compare(point(third, half), point(half, third)))
            })
        }
    }
    @Test fun invalidInputsAndZeroDenominatorsReject() {
        for (v in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
                assertEquals(SelectionPredicateResult.InvalidCoordinates, a.attempt { fromDouble(v) })
            }
        }
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            assertEquals(SelectionPredicateResult.InvalidCoordinates, a.attempt { rational(1, 0) })
        }
        SelectionExactArithmetic(SelectionQueryBudget()).use { a ->
            assertEquals(SelectionPredicateResult.InvalidCoordinates, a.attempt { divide(rational(1), rational(0)) })
        }
    }
    @Test fun retainedValuesRemainChargedUntilClose() {
        val budget = SelectionQueryBudget()
        val a = SelectionExactArithmetic(budget)
        known(a.attempt { rational(1, 3) })
        assertTrue(budget.used(SelectionQueryResource.TEMPORARY_BYTES) > 0)
        a.close(); a.close()
        assertEquals(0L, budget.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { a.attempt { rational(1) } }
    }
    @Test fun operandBoundAtBelowAndAboveBoundary() {
        for ((bits, succeeds) in listOf(53 to true, 54 to true, 52 to false)) {
            val b = SelectionQueryBudget(SelectionQueryLimits(exactBits = bits))
            SelectionExactArithmetic(b).use { a ->
                val result = a.attempt { dyadic(0.1) }
                assertEquals(succeeds, result is SelectionPredicateResult.Known)
                if (!succeeds) assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
            }
            assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }
    @Test fun divisionAndGcdChargeBeforeExhaustion() {
        val b = SelectionQueryBudget()
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { rational(832040, 514229) })
            assertTrue(b.used(SelectionQueryResource.WORK_UNITS) > 40)
        }
        val limits = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.WORK_UNITS) 12L else it.queryCeiling
        }
        val exhausted = SelectionQueryBudget(SelectionQueryLimits(query = limits))
        SelectionExactArithmetic(exhausted).use { a ->
            assertTrue(a.attempt { rational(832040, 514229) } is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(0L, exhausted.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun failedDivisionReleasesRetainedOperandsWithoutBudgetReset() {
        val b = SelectionQueryBudget()
        SelectionExactArithmetic(b).use { a ->
            val r = known(a.attempt { rational(7, 13) })
            val remaining = b.limits.query.getValue(SelectionQueryResource.WORK_UNITS) -
                b.used(SelectionQueryResource.WORK_UNITS)
            assertNull(b.charge(SelectionQueryResource.WORK_UNITS, remaining))
            assertTrue(a.attempt { divide(r, r) } is SelectionPredicateResult.WorkloadExceeded)
            assertThrows(IllegalStateException::class.java) { a.attempt { sign(r) } }
        }
        assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertNotNull(b.failure)
    }
    @Test fun divisionExhaustionAndCancellationReleaseEarlierResults() {
        var cancel = false
        val b = SelectionQueryBudget(cancellationCheckpoint = { if (cancel) throw CancellationException() })
        SelectionExactArithmetic(b).use { a ->
            val r = known(a.attempt { rational(1, 3) })
            cancel = true
            assertThrows(CancellationException::class.java) { a.attempt { divide(r, r) } }
        }
        assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertNull(b.failure)
    }
    @Test fun cancellationDuringEuclideanLoopReleasesScratch() {
        var calls = 0
        val b = SelectionQueryBudget(cancellationCheckpoint = { if (++calls == 45) throw CancellationException() })
        SelectionExactArithmetic(b).use { a ->
            assertThrows(CancellationException::class.java) { a.attempt { rational(832040, 514229) } }
        }
        assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun memoryLimitPreventsArithmeticAllocation() {
        val limits = SelectionQueryResource.entries.associateWith {
            if (it == SelectionQueryResource.TEMPORARY_BYTES) 0L else it.queryCeiling
        }
        val b = SelectionQueryBudget(SelectionQueryLimits(query = limits))
        SelectionExactArithmetic(b).use { a ->
            assertTrue(a.attempt { fromDouble(1.0) } is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(0L, b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
    @Test fun repeatedResultsHaveDeterministicNormalizedValuesAndWork() {
        val work = mutableListOf<Long>()
        repeat(3) {
            val b = SelectionQueryBudget()
            SelectionExactArithmetic(b).use { a ->
                val r = known(a.attempt { divide(rational(7, 9), rational(-14, 15)) })
                assertEquals(BigInteger.valueOf(-5), r.numerator)
                assertEquals(BigInteger.valueOf(6), r.denominator)
                work.add(b.used(SelectionQueryResource.WORK_UNITS))
            }
        }
        assertEquals(1, work.distinct().size)
    }

    @Test fun atomicEdgeAdmissionRequiresAttemptAndChargesOriginalBudget() {
        val b=SelectionQueryBudget()
        val a=SelectionExactArithmetic(b)
        assertThrows(IllegalStateException::class.java) { a.chargeAtomicLassoEdgeRecord() }
        assertEquals(0L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        known(a.attempt { chargeAtomicLassoEdgeRecord(); chargeAtomicLassoEdgeRecord() })
        assertEquals(2L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(2L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(320L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        a.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java) { a.chargeAtomicLassoEdgeRecord() }
    }

    @Test fun atomicEdgeQuotaRejectsBeforeRecordAllocationAndStaysSticky() {
        val limits=SelectionQueryResource.entries.associateWith {
            if(it==SelectionQueryResource.ARRANGEMENT_EDGES) 1L else it.queryCeiling
        }
        val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { chargeAtomicLassoEdgeRecord() })
            val result=a.attempt { chargeAtomicLassoEdgeRecord() }
            assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
            assertEquals(SelectionQueryResource.ARRANGEMENT_EDGES,
                (result as SelectionPredicateResult.WorkloadExceeded).failure.resource)
        }
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(1L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        SelectionExactArithmetic(b).use { a ->
            assertTrue(a.attempt { chargeAtomicLassoEdgeRecord() } is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
    }

    @Test fun atomicEdgeWorkAndRepresentationDenialReleaseReservations() {
        for((resource,maximum) in listOf(SelectionQueryResource.WORK_UNITS to 0L,
            SelectionQueryResource.TEMPORARY_BYTES to 159L)) {
            val limits=SelectionQueryResource.entries.associateWith {
                if(it==resource) maximum else it.queryCeiling
            }
            val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
            SelectionExactArithmetic(b).use { a ->
                val result=a.attempt { chargeAtomicLassoEdgeRecord() }
                assertTrue(result is SelectionPredicateResult.WorkloadExceeded)
                assertEquals(resource,(result as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            }
            assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        }
    }

    @Test fun incidentAdmissionRequiresActiveAttemptAndPreservesOriginalCounters() {
        val b=SelectionQueryBudget()
        val a=SelectionExactArithmetic(b)
        assertThrows(IllegalStateException::class.java){a.chargeIncidentLassoWork()}
        assertThrows(IllegalStateException::class.java){a.chargeIncidentLassoHalfEdge()}
        known(a.attempt { chargeIncidentLassoWork(3); chargeIncidentLassoHalfEdge(); chargeIncidentLassoHalfEdge() })
        assertEquals(5L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(2L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(320L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        a.close(); assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.chargeIncidentLassoHalfEdge()}
    }

    @Test fun incidentQuotaWorkAndRepresentationFailuresAreStickyAndReleaseArena() {
        for((resource,limit) in listOf(SelectionQueryResource.ARRANGEMENT_EDGES to 0L,
            SelectionQueryResource.WORK_UNITS to 0L,SelectionQueryResource.TEMPORARY_BYTES to 159L)) {
            val limits=SelectionQueryResource.entries.associateWith { if(it==resource)limit else it.queryCeiling }
            val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
            SelectionExactArithmetic(b).use { a ->
                val r=a.attempt { chargeIncidentLassoHalfEdge() }
                assertTrue(r is SelectionPredicateResult.WorkloadExceeded)
                assertEquals(resource,(r as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            }
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertNotNull(b.failure)
            SelectionExactArithmetic(b).use { a ->
                assertTrue(a.attempt { chargeIncidentLassoWork() } is SelectionPredicateResult.WorkloadExceeded)
            }
        }
    }

    @Test fun incidentWorkAccountingDoesNotOverflowOrReset() {
        val limits=SelectionQueryResource.entries.associateWith {
            if(it==SelectionQueryResource.WORK_UNITS)Long.MAX_VALUE else it.queryCeiling
        }
        val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { chargeIncidentLassoWork(Long.MAX_VALUE) })
            assertTrue(a.attempt { chargeIncidentLassoWork() } is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(Long.MAX_VALUE,b.used(SelectionQueryResource.WORK_UNITS))
    }

    @Test fun incidentCancellationReleasesEarlierReservationsAndRemainsSeparate() {
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==1L)throw CancellationException()
        })
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { retainRecord(32) })
            assertThrows(CancellationException::class.java){a.attempt { chargeIncidentLassoHalfEdge() }}
        }
        assertNull(b.failure)
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun coincidentHelpersRequireAttemptAndUseOriginalBudget() {
        val b=SelectionQueryBudget()
        val a=SelectionExactArithmetic(b)
        assertThrows(IllegalStateException::class.java){a.chargeCoincidentLassoWork()}
        assertThrows(IllegalStateException::class.java){a.chargeCoincidentLassoEdgeGroup()}
        known(a.attempt { chargeCoincidentLassoWork(3); chargeCoincidentLassoEdgeGroup() })
        assertEquals(4L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(160L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        a.close()
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertThrows(IllegalStateException::class.java){a.chargeCoincidentLassoWork()}
    }

    @Test fun coincidentGroupQuotaWorkAndMemoryDenialStayTypedAndReleaseArena() {
        for((resource,maximum) in listOf(SelectionQueryResource.ARRANGEMENT_EDGES to 0L,
            SelectionQueryResource.WORK_UNITS to 0L,SelectionQueryResource.TEMPORARY_BYTES to 159L)) {
            val limits=SelectionQueryResource.entries.associateWith { if(it==resource)maximum else it.queryCeiling }
            val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
            SelectionExactArithmetic(b).use { a ->
                val r=a.attempt { chargeCoincidentLassoEdgeGroup() }
                assertTrue(r is SelectionPredicateResult.WorkloadExceeded)
                assertEquals(resource,(r as SelectionPredicateResult.WorkloadExceeded).failure.resource)
            }
            assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
            assertNotNull(b.failure)
            SelectionExactArithmetic(b).use { a ->
                assertTrue(a.attempt { chargeCoincidentLassoWork() } is SelectionPredicateResult.WorkloadExceeded)
            }
        }
    }

    @Test fun coincidentWorkCannotOverflowOrResetPreviouslyConsumedUnits() {
        val limits=SelectionQueryResource.entries.associateWith {
            if(it==SelectionQueryResource.WORK_UNITS)Long.MAX_VALUE else it.queryCeiling
        }
        val b=SelectionQueryBudget(SelectionQueryLimits(query=limits))
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { chargeCoincidentLassoWork(Long.MAX_VALUE) })
            assertTrue(a.attempt { chargeCoincidentLassoWork() } is SelectionPredicateResult.WorkloadExceeded)
        }
        assertEquals(Long.MAX_VALUE,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }

    @Test fun coincidentGroupCancellationReleasesPreviouslyRetainedValues() {
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==1L)throw CancellationException()
        })
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { retainRecord(32) })
            assertThrows(CancellationException::class.java){a.attempt { chargeCoincidentLassoEdgeGroup() }}
        }
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertNull(b.failure)
    }

    @Test fun atomicEdgeCancellationPropagatesBeforeAllocation() {
        lateinit var b: SelectionQueryBudget
        b=SelectionQueryBudget(cancellationCheckpoint={
            if(b.used(SelectionQueryResource.ARRANGEMENT_EDGES)==1L) throw CancellationException()
        })
        SelectionExactArithmetic(b).use { a ->
            known(a.attempt { retainRecord(32) })
            assertThrows(CancellationException::class.java) { a.attempt { chargeAtomicLassoEdgeRecord() } }
        }
        assertNull(b.failure)
        assertEquals(1L,b.used(SelectionQueryResource.ARRANGEMENT_EDGES))
        assertEquals(0L,b.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(0L,b.used(SelectionQueryResource.TEMPORARY_BYTES))
    }
}
