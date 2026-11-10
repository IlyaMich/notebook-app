package com.notebook.app.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorEraserToolbarPolicyTest {
    private fun save(
        phase: SavePhase = SavePhase.SAVED,
        revision: Long = 12L,
        pending: Int = 0,
    ) = NoteSaveState(phase, revision, pending)

    @Test fun confirmedCurrentPreviewAllowsPartialErase() {
        assertTrue(NoteEditorEraserToolbarPolicy.canErase(save(), 12L))
    }

    @Test fun pendingSavingAndFailureDoNotAllowErase() {
        for (phase in listOf(SavePhase.PENDING, SavePhase.SAVING, SavePhase.FAILED)) {
            assertFalse(NoteEditorEraserToolbarPolicy.canErase(save(phase), 12L))
        }
        assertFalse(NoteEditorEraserToolbarPolicy.canErase(save(pending = 1), 12L))
    }

    @Test fun staleOrMissingRenderedPreviewIsNotEligible() {
        assertFalse(NoteEditorEraserToolbarPolicy.canErase(save(), 11L))
        assertFalse(NoteEditorEraserToolbarPolicy.canErase(save(), 13L))
        assertFalse(NoteEditorEraserToolbarPolicy.canErase(save(), null))
        assertFalse(NoteEditorEraserToolbarPolicy.canErase(null, 12L))
    }

    @Test fun undoAndRedoRequireBothConfirmedPreviewAndHistory() {
        val undo = EraserSessionHistoryState(canUndo = true, undoDepth = 1)
        val redo = EraserSessionHistoryState(canRedo = true, redoDepth = 1)
        assertTrue(NoteEditorEraserToolbarPolicy.canUndo(save(), 12L, undo))
        assertFalse(NoteEditorEraserToolbarPolicy.canRedo(save(), 12L, undo))
        assertTrue(NoteEditorEraserToolbarPolicy.canRedo(save(), 12L, redo))
        assertFalse(NoteEditorEraserToolbarPolicy.canUndo(save(), 12L, redo))
        assertFalse(NoteEditorEraserToolbarPolicy.canUndo(save(), 12L, null))
        assertFalse(NoteEditorEraserToolbarPolicy.canRedo(save(), 12L, null))
    }

    @Test fun historyAvailabilityDoesNotOverrideStaleOrFailedState() {
        val both = EraserSessionHistoryState(canUndo = true, canRedo = true, undoDepth = 2, redoDepth = 1)
        assertFalse(NoteEditorEraserToolbarPolicy.canUndo(save(), 11L, both))
        assertFalse(NoteEditorEraserToolbarPolicy.canRedo(save(), 11L, both))
        assertFalse(NoteEditorEraserToolbarPolicy.canUndo(save(SavePhase.FAILED), 12L, both))
        assertFalse(NoteEditorEraserToolbarPolicy.canRedo(save(SavePhase.SAVING), 12L, both))
    }
}
