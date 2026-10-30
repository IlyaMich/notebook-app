
package com.notebook.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notebook.app.data.content.repository.DocumentRepository

/**
 * Owns one editor's autosave coordinator for the lifetime
 * of its Navigation 3 entry.
 *
 * The coordinator survives configuration changes because
 * its scope belongs to this ViewModel, not the Composable.
 */
class NoteEditorSessionViewModel : ViewModel() {

    private var activeNoteId: String? = null
    private var coordinator: NoteAutosaveCoordinator? = null

    /**
     * Return the existing coordinator after rotation.
     *
     * initialRevision is used only when the session is
     * created for the first time.
     */
    fun getOrCreateCoordinator(
        noteId: String,
        repository: DocumentRepository,
        initialRevision: Long,
    ): NoteAutosaveCoordinator {
        require(noteId.isNotBlank())
        require(initialRevision >= 0L)

        check(activeNoteId == null || activeNoteId == noteId) {
            "Editor ViewModel cannot be reused for another note"
        }

        coordinator?.let { return it }

        val created = NoteAutosaveCoordinator(
            noteId = noteId,
            repository = repository,
            scope = viewModelScope,
            initialRevision = initialRevision,
        )

        activeNoteId = noteId
        coordinator = created

        return created
    }

    /**
     * Allows the screen to inspect an existing session during
     * recreation, before its asynchronous reload completes.
     */
    fun existingCoordinator(
        noteId: String,
    ): NoteAutosaveCoordinator? =
        if (activeNoteId == noteId) coordinator else null

    suspend fun retrySave(): SaveRetryResult {
        val active = coordinator
            ?: return SaveRetryResult.NOT_NEEDED

        return active.retry()
    }
}
