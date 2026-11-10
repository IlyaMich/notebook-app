package com.notebook.app.ui.editor

/**
 * Fail-closed UI eligibility for destructive eraser actions.
 *
 * This is presentation-only: the coordinator and Room revision guard remain
 * the final authorities. A pending/failed save, stale rendered preview, or
 * closed/missing coordinator state must never offer an enabled destructive UI.
 */
internal object NoteEditorEraserToolbarPolicy {
    fun canErase(
        save: NoteSaveState?,
        renderedNoteRevision: Long?,
    ): Boolean =
        save != null &&
            save.phase == SavePhase.SAVED &&
            save.pendingCommandCount == 0 &&
            renderedNoteRevision != null &&
            renderedNoteRevision == save.committedRevision

    fun canUndo(
        save: NoteSaveState?,
        renderedNoteRevision: Long?,
        history: EraserSessionHistoryState?,
    ): Boolean =
        canErase(save, renderedNoteRevision) && history?.canUndo == true

    fun canRedo(
        save: NoteSaveState?,
        renderedNoteRevision: Long?,
        history: EraserSessionHistoryState?,
    ): Boolean =
        canErase(save, renderedNoteRevision) && history?.canRedo == true
}
