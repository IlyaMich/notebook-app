package com.notebook.app.data.content.model

import com.notebook.app.data.library.model.InitialPageSpec

/** One editor command == one Room transaction and (if changed) one note revision. */
sealed interface DocumentEdit {
    data class InsertPage(val id: String, val spec: InitialPageSpec, val at: Int? = null) : DocumentEdit
    data class RemovePage(val id: String) : DocumentEdit
    data class ReorderPages(val idsInOrder: List<String>) : DocumentEdit
    data class ChangeBackground(val pageId: String, val backgroundJson: String) : DocumentEdit
    data class InsertElements(val pageId: String, val envelopes: List<String>, val at: Int? = null) : DocumentEdit
    data class ReplaceElements(val pageId: String, val envelopes: List<String>) : DocumentEdit
    data class RemoveElements(val pageId: String, val ids: List<String>) : DocumentEdit
    data class ReorderElements(val pageId: String, val idsInOrder: List<String>) : DocumentEdit
    /** Cross-page transfer within one note preserves element IDs and their relative order. */
    data class TransferElements(
        val fromPageId: String, val toPageId: String, val ids: List<String>, val at: Int? = null,
    ) : DocumentEdit
}

data class DocumentCommit(val noteId: String, val revision: Long, val updatedAtMillis: Long)

data class StoredElement(
    val id: String, val orderIndex: Long, val kind: String, val envelopeJson: String, val revision: Long,
)

data class StoredPage(
    val id: String, val orderIndex: Long, val width: Double, val height: Double,
    val backgroundJson: String, val revision: Long, val elements: List<StoredElement>,
)

data class StoredNoteContent(val noteId: String, val revision: Long, val pages: List<StoredPage>)
