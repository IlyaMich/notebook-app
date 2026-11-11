package com.notebook.app.ui.editor.selection

import com.notebook.app.data.library.LibraryValidation
import java.util.Collections

internal data class PageElementRef(val pageId: String, val elementId: String)

/** Ephemeral ordered references. Invalid construction rejects; revision checks require a snapshot. */
internal class PageSelectionState(
    val noteId: String,
    val pageId: String,
    val expectedNoteRevision: Long,
    members: List<PageElementRef>,
) {
    val members: List<PageElementRef> = Collections.unmodifiableList(ArrayList(members))

    init {
        LibraryValidation.canonicalId(noteId)
        LibraryValidation.canonicalId(pageId)
        require(expectedNoteRevision >= 0) { "Negative selection revision" }
        require(this.members.all { it.pageId == pageId }) { "Cross-page selection" }
        this.members.forEach { LibraryValidation.canonicalId(it.elementId) }
        require(this.members.map { it.elementId }.distinct().size == this.members.size) {
            "Duplicate selection member"
        }
    }
}

internal sealed interface SelectionValidation {
    data object Current : SelectionValidation
    data object StaleRevision : SelectionValidation
    data class Invalid(val reason: String) : SelectionValidation
}
