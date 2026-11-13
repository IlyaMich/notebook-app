package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import java.util.Collections

/**
 * 3E-1A: read-only admission for ONE page of ONE already-loaded, confirmed note revision.
 *
 * Admission is NOT a lasso result, paint certificate, availability check, or selectable set.
 * No candidate is classified here: especially, an unknown kind, locked member, malformed
 * payload or unverified image MUST be resolved by later query stages, never dropped as MISS.
 *
 * Every attempted page scan and element enumeration is budgeted before work. The envelope
 * representation is charged before any future parser is allowed to consume it. Later stages
 * must reuse the same query budget and each candidate's scoped element handle; they must not
 * charge TESTED_ELEMENTS again for the same attempt. No Room, viewport, or edit path exists.
 */
internal object SelectionPageQueryAdmission {
    enum class Rejection {
        INVALID_ID,
        WRONG_NOTE,
        STALE_REVISION,
        MISSING_PAGE,
        DUPLICATE_PAGE,
        INVALID_PAGE_METADATA,
        INVALID_ELEMENT_METADATA,
        DUPLICATE_PAGE_ELEMENT_ID,
    }

    /** This is an attempted row, not a confirmed visual/eligible element. */
    class Candidate internal constructor(
        val source: StoredElement,
        val ordinal: Int,
        val budgetElement: SelectionQueryBudget.Element,
    )

    /** Page and ordered row list are defensively frozen; individual StoredElement values are immutable. */
    class Baseline internal constructor(
        val noteId: String,
        val pageId: String,
        val expectedNoteRevision: Long,
        page: StoredPage,
        candidates: List<Candidate>,
    ) {
        val page: StoredPage = page
        val candidates: List<Candidate> = Collections.unmodifiableList(ArrayList(candidates))
    }

    sealed interface Result {
        data class Admitted(val baseline: Baseline) : Result
        data class Rejected(val reason: Rejection) : Result
        data class WorkloadExceeded(val failure: SelectionWorkloadFailure) : Result
    }

    fun admit(
        content: StoredNoteContent,
        noteId: String,
        pageId: String,
        expectedNoteRevision: Long,
        budget: SelectionQueryBudget,
    ): Result {
        // Cancellation is exceptional; never convert it to a rejected or partial selection.
        budget.checkpoint()
        budget.failure?.let { return Result.WorkloadExceeded(it) }
        if (!canonical(noteId) || !canonical(pageId)) return Result.Rejected(Rejection.INVALID_ID)
        if (content.noteId != noteId) return Result.Rejected(Rejection.WRONG_NOTE)
        if (expectedNoteRevision < 0 || content.revision != expectedNoteRevision) {
            return Result.Rejected(Rejection.STALE_REVISION)
        }

        var target: StoredPage? = null
        for (page in content.pages) {
            budget.charge(SelectionQueryResource.WORK_UNITS)?.let {
                return Result.WorkloadExceeded(it)
            }
            if (page.id == pageId) {
                if (target != null) return Result.Rejected(Rejection.DUPLICATE_PAGE)
                target = page
            }
        }
        val source = target ?: return Result.Rejected(Rejection.MISSING_PAGE)
        if (!source.width.isFinite() || !source.height.isFinite() ||
            source.width <= 0 || source.height <= 0 ||
            source.orderIndex < 0 || source.revision < 0
        ) return Result.Rejected(Rejection.INVALID_PAGE_METADATA)

        val seenIds = HashSet<String>()
        val frozen = ArrayList<StoredElement>()
        val candidates = ArrayList<Candidate>()
        for ((index, row) in source.elements.withIndex()) {
            // beginElement charges TESTED_ELEMENTS before even inspecting the attempted row.
            val element = budget.beginElement()
                ?: return Result.WorkloadExceeded(requireNotNull(budget.failure))
            budget.charge(SelectionQueryResource.WORK_UNITS)?.let {
                return Result.WorkloadExceeded(it)
            }
            // Conservative representation charge, not a measurement of actual JVM heap.
            // 4 bytes per UTF-16 code unit for one future parsing pass; no JSON is parsed here.
            budget.charge(SelectionQueryResource.ENVELOPE_BYTES,
                row.envelopeJson.length.toLong() * 4L, element = element)?.let {
                return Result.WorkloadExceeded(it)
            }
            if (row.orderIndex != index.toLong() || row.revision < 0 || !canonical(row.id)) {
                return Result.Rejected(Rejection.INVALID_ELEMENT_METADATA)
            }
            if (!seenIds.add(row.id)) return Result.Rejected(Rejection.DUPLICATE_PAGE_ELEMENT_ID)
            frozen.add(row)
            candidates.add(Candidate(row, index, element))
        }
        val frozenRows: List<StoredElement> = Collections.unmodifiableList(frozen)
        return Result.Admitted(Baseline(noteId, pageId, expectedNoteRevision,
            source.copy(elements = frozenRows), candidates))
    }

    private fun canonical(id: String): Boolean = try {
        LibraryValidation.canonicalId(id)
        true
    } catch (_: LibraryOperationException) {
        false
    }
}
