package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import java.util.Collections

internal data class SelectionMember(val source: com.notebook.app.data.content.model.StoredElement, val locked: Boolean)

/** Frozen editor baseline, not database validation. No Room, asset access, or mutation. */
internal class SelectionSnapshot private constructor(
    val selection: PageSelectionState,
    val page: StoredPage,
    val members: List<SelectionMember>,
) {
    fun validate(content: StoredNoteContent): SelectionValidation {
        if (content.noteId != selection.noteId) return SelectionValidation.Invalid("Wrong note")
        if (content.revision != selection.expectedNoteRevision) return SelectionValidation.StaleRevision
        return try {
            val current = capture(content, selection)
            if (current.page == page && current.members == members) SelectionValidation.Current
            else SelectionValidation.Invalid("Page or selected source changed")
        } catch (_: RuntimeException) {
            SelectionValidation.Invalid("Invalid or missing selection source")
        }
    }

    companion object {
        fun capture(content: StoredNoteContent, selection: PageSelectionState): SelectionSnapshot {
            require(content.noteId == selection.noteId) { "Wrong note" }
            require(content.revision == selection.expectedNoteRevision) { "Stale selection revision" }
            require(content.pages.map { it.id }.distinct().size == content.pages.size)
            val ids = content.pages.flatMap { it.elements.map { row -> row.id } }
            require(ids.distinct().size == ids.size) { "Duplicate note-wide element ID" }
            val source = requireNotNull(content.pages.singleOrNull { it.id == selection.pageId }) { "Missing page" }
            require(source.width.isFinite() && source.height.isFinite() && source.width > 0 && source.height > 0)
            require(source.revision >= 0 && source.orderIndex >= 0)
            require(source.elements.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
                "Noncanonical page order"
            }
            val wanted = selection.members.map { it.elementId }
            val selected = source.elements.filter { it.id in wanted }
            require(selected.map { it.id } == wanted) { "Missing or reordered selection member" }
            val frozenPage = source.copy(elements = Collections.unmodifiableList(ArrayList(source.elements)))
            val frozenMembers = selected.map {
                val envelope = StoredFrameElementDecoder.checkedEnvelope(it)
                val locked = with(StoredFrameElementDecoder) { envelope.flag("locked") }
                SelectionMember(it, locked)
            }
            return SelectionSnapshot(selection, frozenPage,
                Collections.unmodifiableList(ArrayList(frozenMembers)))
        }
    }
}
