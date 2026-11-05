package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredNoteContent

/**
 * Pure, read-only bridge from a FINISHED page-local sweep to the reversible
 * whole-stroke plan. One document snapshot is used for both the scan and plan;
 * the downstream coordinator must still commit with an atomic revision guard.
 */
internal object EditorEraserCompletedWholeStrokePlanner {
    fun plan(
        content: StoredNoteContent,
        sweep: EditorEraserGestureBuffer.CompletedSweep,
    ): EditorEraserWholeStrokeRemovalPlanner.Plan? {
        require(sweep.pageId.isNotBlank()) { "Missing eraser page" }
        require(sweep.pointsPageDu.isNotEmpty() && sweep.pointsPageDu.size <= 8192) {
            "Invalid completed eraser sweep size"
        }
        require(sweep.radiusPageDu.isFinite() && sweep.radiusPageDu >= 0.0) {
            "Invalid eraser radius"
        }
        require(sweep.pointsPageDu.all { it.x.isFinite() && it.y.isFinite() }) {
            "Non-finite eraser coordinate"
        }
        require(content.noteId.isNotBlank()) { "Missing note identity" }
        val page = requireNotNull(content.pages.singleOrNull { it.id == sweep.pageId }) {
            "Eraser page missing or ambiguous in document snapshot"
        }
        val scan = EditorEraserCanonicalHitTest.scan(
            page = page,
            eraserPointsPageDu = sweep.pointsPageDu,
            eraserRadiusPageDu = sweep.radiusPageDu,
        )
        // Crucially, never use scan.hitElementIds directly: null signals that
        // unsupported/uncertain painted geometry forbids destructive work.
        return EditorEraserWholeStrokeRemovalPlanner.plan(
            content = content,
            pageId = page.id,
            scanNoteRevision = content.revision,
            scanPageRevision = page.revision,
            confirmedHitsIfUnambiguous = scan.confirmedHitsIfUnambiguous,
        )
    }
}
