package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SelectionPageQueryAdmissionTest {
    private val note = "00000000-0000-4000-8000-000000000001"
    private val page = "00000000-0000-4000-8000-000000000002"
    private val otherPage = "00000000-0000-4000-8000-000000000003"
    private fun id(i: Int) = "00000000-0000-4000-8000-${i.toString().padStart(12, '0')}"
    private fun row(index: Int, kind: String = "stroke", envelope: String = "{unparsed}") =
        StoredElement(id(100 + index), index.toLong(), kind, envelope, 7)
    private fun page(rows: List<StoredElement> = listOf(row(0))) =
        StoredPage(page, 0, 100.0, 200.0, "blank", 2, rows)
    private fun content(vararg pages: StoredPage) =
        StoredNoteContent(note, 7, pages.toList())
    private fun admit(c: StoredNoteContent = content(page()),
        n: String = note, p: String = page, rev: Long = 7,
        budget: SelectionQueryBudget = SelectionQueryBudget()) =
        SelectionPageQueryAdmission.admit(c, n, p, rev, budget)
    private fun reason(result: SelectionPageQueryAdmission.Result) =
        (result as SelectionPageQueryAdmission.Result.Rejected).reason
    private fun queryLimit(resource: SelectionQueryResource, maximum: Long) =
        SelectionQueryLimits(query = SelectionQueryResource.entries.associateWith {
            if (it == resource) maximum else it.queryCeiling
        })

    @Test fun admitsOnePageInCanonicalRenderOrderWithoutParsingOrSelecting() {
        val rows = listOf(row(0, "stroke"), row(1, "shape"), row(2, "text"), row(3, "image"))
        val other = StoredPage(otherPage, 1, 400.0, 500.0, "blank", 0, listOf(row(0)))
        val budget = SelectionQueryBudget()
        val admitted = admit(content(other, page(rows)), budget = budget)
            as SelectionPageQueryAdmission.Result.Admitted
        val baseline = admitted.baseline
        assertEquals(note, baseline.noteId)
        assertEquals(page, baseline.pageId)
        assertEquals(7L, baseline.expectedNoteRevision)
        assertEquals(rows, baseline.page.elements)
        assertEquals(rows, baseline.candidates.map { it.source })
        assertEquals((0..3).toList(), baseline.candidates.map { it.ordinal })
        assertEquals(4L, budget.used(SelectionQueryResource.TESTED_ELEMENTS))
        assertEquals(6L, budget.used(SelectionQueryResource.WORK_UNITS))
        assertEquals(rows.sumOf { it.envelopeJson.length.toLong() * 4L },
            budget.used(SelectionQueryResource.ENVELOPE_BYTES))
        // Even intentionally malformed JSON and unverified images are not silently dropped.
        assertTrue(baseline.candidates.any { it.source.kind == "image" })
    }

    @Test fun baselineDefensivelyFreezesSourceRowsAndCandidateList() {
        val mutable = mutableListOf(row(0))
        val baseline = (admit(content(page(mutable))) as
            SelectionPageQueryAdmission.Result.Admitted).baseline
        mutable.clear()
        assertEquals(1, baseline.page.elements.size)
        assertEquals(1, baseline.candidates.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.page.elements as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (baseline.candidates as MutableList).clear()
        }
    }

    @Test fun wrongNoteAndStaleRevisionRejectWithoutAdmission() {
        assertEquals(SelectionPageQueryAdmission.Rejection.WRONG_NOTE,
            reason(admit(n = otherPage)))
        assertEquals(SelectionPageQueryAdmission.Rejection.STALE_REVISION,
            reason(admit(rev = 8)))
        assertEquals(SelectionPageQueryAdmission.Rejection.STALE_REVISION,
            reason(admit(rev = -1)))
    }

    @Test fun invalidIdsMissingAndDuplicatePageReject() {
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_ID,
            reason(admit(p = "not-a-uuid")))
        assertEquals(SelectionPageQueryAdmission.Rejection.MISSING_PAGE,
            reason(admit(p = otherPage)))
        assertEquals(SelectionPageQueryAdmission.Rejection.DUPLICATE_PAGE,
            reason(admit(content(page(), page()))))
    }

    @Test fun invalidPageMetricsAndElementMetadataReject() {
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_PAGE_METADATA,
            reason(admit(content(page().copy(width = Double.NaN)))))
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_PAGE_METADATA,
            reason(admit(content(page().copy(height = 0.0)))))
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_ELEMENT_METADATA,
            reason(admit(content(page(listOf(row(0).copy(orderIndex = 1)))))))
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_ELEMENT_METADATA,
            reason(admit(content(page(listOf(row(0).copy(revision = -1)))))))
        assertEquals(SelectionPageQueryAdmission.Rejection.INVALID_ELEMENT_METADATA,
            reason(admit(content(page(listOf(row(0).copy(id = "INVALID")))))))
    }

    @Test fun duplicateElementIdsRejectInsteadOfRetargeting() {
        assertEquals(SelectionPageQueryAdmission.Rejection.DUPLICATE_PAGE_ELEMENT_ID,
            reason(admit(content(page(listOf(row(0), row(1).copy(id = id(100))))))))
    }

    @Test fun zeroElementPageAdmitsEmptyCandidateList() {
        val baseline = (admit(content(page(emptyList()))) as
            SelectionPageQueryAdmission.Result.Admitted).baseline
        assertTrue(baseline.candidates.isEmpty())
        assertEquals(7L, baseline.expectedNoteRevision)
    }

    @Test fun elementQuotaExhaustionIsTypedAndSticky() {
        val budget = SelectionQueryBudget(queryLimit(SelectionQueryResource.TESTED_ELEMENTS, 1))
        val result = admit(content(page(listOf(row(0), row(1)))), budget = budget)
            as SelectionPageQueryAdmission.Result.WorkloadExceeded
        assertEquals(SelectionQueryResource.TESTED_ELEMENTS, result.failure.resource)
        assertSame(result.failure, budget.failure)
        assertEquals(1L, budget.used(SelectionQueryResource.TESTED_ELEMENTS))
    }

    @Test fun envelopeQuotaRejectsBeforeParsingAndPreservesElementScope() {
        val budget = SelectionQueryBudget(queryLimit(SelectionQueryResource.ENVELOPE_BYTES, 4))
        val result = admit(budget = budget) as SelectionPageQueryAdmission.Result.WorkloadExceeded
        assertEquals(SelectionQueryResource.ENVELOPE_BYTES, result.failure.resource)
        assertSame(result.failure, budget.failure)
        assertEquals(0L, budget.used(SelectionQueryResource.ENVELOPE_BYTES))
    }

    @Test fun pageScanningIsChargedAndBoundedEvenBeforeElementEnumeration() {
        val other = page().copy(id = otherPage)
        val budget = SelectionQueryBudget(queryLimit(SelectionQueryResource.WORK_UNITS, 1))
        val result = admit(content(other, page()), budget = budget)
            as SelectionPageQueryAdmission.Result.WorkloadExceeded
        assertEquals(SelectionQueryResource.WORK_UNITS, result.failure.resource)
        assertEquals(0L, budget.used(SelectionQueryResource.TESTED_ELEMENTS))
    }

    @Test fun cancellationPropagatesAndDoesNotReturnPartialCandidates() {
        val budget = SelectionQueryBudget(cancellationCheckpoint = { throw CancellationException("stop") })
        assertThrows(CancellationException::class.java) { admit(budget = budget) }
        assertEquals(0L, budget.used(SelectionQueryResource.TESTED_ELEMENTS))
        assertNull(budget.failure)
    }

    @Test fun unknownKindIsAttemptedNotClassifiedAsMissOrIgnored() {
        val sourcePage = page(listOf(row(0, "new-kind")))
        val baseline = (admit(content(sourcePage)) as
            SelectionPageQueryAdmission.Result.Admitted).baseline
        assertEquals("new-kind", baseline.candidates.single().source.kind)
    }
}
