package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent

/**
 * Read-only preparation of a whole-stroke removal. This is NOT an undo engine,
 * a repository operation, or permission to execute a destructive edit.
 *
 * The caller MUST supply the canonical hit-test's
 * ScanResult.confirmedHitsIfUnambiguous, never its raw hitElementIds.
 * The scan revisions must have been captured from the content used for that scan.
 * A future commit path must atomically recheck the expected note revision, and
 * install working session undo before submitting [Plan.forwardEdit].
 */
internal object EditorEraserWholeStrokeRemovalPlanner {

    data class RemovedSnapshot(
        val originalPosition: Int,
        val element: StoredElement,
    )

    data class Plan(
        val noteId: String,
        val pageId: String,
        val expectedNoteRevision: Long,
        val expectedPageRevision: Long,
        val originalElementIdsInOrder: List<String>,
        val removed: List<RemovedSnapshot>,
        val forwardEdit: DocumentEdit.RemoveElements,
    )

    /**
     * Returns null ONLY for an unambiguous, empty hit set.
     * Throws IllegalArgumentException for any unsafe or stale input.
     * The result is a snapshot/plan; this method performs no writes.
     */
    fun plan(
        content: StoredNoteContent,
        pageId: String,
        scanNoteRevision: Long,
        scanPageRevision: Long,
        confirmedHitsIfUnambiguous: List<String>?,
    ): Plan? {
        val hits = requireNotNull(confirmedHitsIfUnambiguous) {
            "Eraser scan is uncertain; removal is forbidden"
        }
        require(content.noteId.isNotBlank()) { "Missing note identity" }
        require(content.revision == scanNoteRevision) {
            "Stale eraser scan: note revision differs"
        }
        val page = requireNotNull(content.pages.singleOrNull { it.id == pageId }) {
            "Eraser page is missing or ambiguous"
        }
        require(page.revision == scanPageRevision) {
            "Stale eraser scan: page revision differs"
        }

        // A valid document has note-wide unique IDs and ordered page elements.
        // Reject an invalid snapshot rather than guess which object is targeted.
        val allIds = content.pages.flatMap { p -> p.elements.map { it.id } }
        require(allIds.size == allIds.toSet().size) {
            "Document contains duplicate element IDs"
        }
        val ordered = page.elements.sortedBy { it.orderIndex }
        require(ordered.map { it.orderIndex }.distinct().size == ordered.size) {
            "Page contains duplicate element order indices"
        }
        require(hits.size == hits.toSet().size) {
            "Eraser scan contains duplicate hit IDs"
        }
        require(hits.none { it.isBlank() }) {
            "Eraser scan contains an empty element ID"
        }
        if (hits.isEmpty()) return null

        val hitIds = hits.toSet()
        val candidates = ordered.filter { it.id in hitIds }
        require(candidates.size == hitIds.size) {
            "Eraser hit refers to a missing or different-page element"
        }
        require(candidates.all { it.kind == "stroke" }) {
            "Whole-stroke eraser cannot remove a non-stroke element"
        }

        // Use the production canonical decoder, NOT another JSON decoder.
        // This also verifies identity, stroke encoding and the locked flag.
        val canonicalById = StoredCanonicalStrokeDecoder.fromPage(page)
            .associateBy { it.elementId }
        require(candidates.all { candidate ->
            canonicalById[candidate.id]?.let { !it.locked && it.pageId == pageId } == true
        }) {
            "Whole-stroke eraser cannot remove a locked or invalid stroke"
        }

        val removed = ordered.mapIndexedNotNull { index, element ->
            if (element.id in hitIds) RemovedSnapshot(index, element) else null
        }
        return Plan(
            noteId = content.noteId,
            pageId = pageId,
            expectedNoteRevision = content.revision,
            expectedPageRevision = page.revision,
            originalElementIdsInOrder = ordered.map { it.id },
            removed = removed,
            forwardEdit = DocumentEdit.RemoveElements(
                pageId = pageId,
                ids = removed.map { it.element.id },
            ),
        )
    }
}
