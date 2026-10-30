package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.repository.DocumentRepository

/**
 * The only persistence-facing seam of the debug ink evaluation.
 * Actual transactions, revision increments, validation and failures remain owned
 * by the existing DocumentRepository implementation; never write to Room here.
 */
internal class InkDocumentRepositoryBridge(private val repository: DocumentRepository) {
    suspend fun insertCompletedStroke(
        noteId: String,
        pageId: String,
        elementId: String,
        stroke: CanonicalInkStroke,
    ): DocumentCommit {
        val envelope = CanonicalInkDocumentCodec.encode(elementId, stroke)
        return repository.commit(
            noteId,
            DocumentEdit.InsertElements(pageId = pageId, envelopes = listOf(envelope)),
        )
    }

    /**
     * Read-only projection of supported strokes from the requested page. Do NOT
     * replace the original document elements with the projected DTOs on resave.
     * An unsupported stroke fails explicitly rather than disappearing silently.
     */
    suspend fun loadSupportedStrokes(
        noteId: String,
        pageId: String,
    ): List<StoredCanonicalInkStroke> {
        val content = repository.load(noteId)
        val page = content.pages.singleOrNull { it.id == pageId }
            ?: throw IllegalArgumentException("Page does not belong to this note")
        return page.elements.filter { it.kind == "stroke" }.map { stored ->
            val decoded = CanonicalInkDocumentCodec.decode(stored.envelopeJson)
            require(decoded.elementId == stored.id) { "Element ID mismatch" }
            decoded
        }
    }
}
