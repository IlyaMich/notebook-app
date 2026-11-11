package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.shapes.ShapeEditPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ShapeCommandResult {
    data object NoChange : ShapeCommandResult
    data class Rejected(val reason: String) : ShapeCommandResult
    data class Committed(val revision: Long) : ShapeCommandResult
    /** Storage might have committed. Never automatically replay this action. */
    data class Unconfirmed(val reason: String) : ShapeCommandResult
}

/** Completed-action boundary only. The existing coordinator remains the sole writer/history owner. */
internal class NoteEditorShapeCommands(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val coordinator: NoteAutosaveCoordinator,
) {
    suspend fun insert(renderedSnapshot: StoredNoteContent, pageId: String, envelope: String): ShapeCommandResult =
        submit(renderedSnapshot) { frozen -> ShapeEditPlan.insert(frozen, pageId, envelope) }

    suspend fun replace(renderedSnapshot: StoredNoteContent, pageId: String, envelope: String): ShapeCommandResult =
        submit(renderedSnapshot) { frozen -> ShapeEditPlan.replace(frozen, pageId, envelope) }

    private suspend fun submit(
        rendered: StoredNoteContent,
        build: (StoredNoteContent) -> ShapeEditPlan?,
    ): ShapeCommandResult {
        currentCoroutineContext().ensureActive()
        val save = coordinator.state.value
        if (rendered.noteId != noteId || save.phase != SavePhase.SAVED ||
            save.pendingCommandCount != 0 || save.committedRevision != rendered.revision) {
            return ShapeCommandResult.Rejected("Visible shape snapshot is not current and saved")
        }
        val plan = try {
            // Freeze list ownership before suspension; rows and envelope strings are immutable.
            val frozen = rendered.copy(pages = rendered.pages.map { it.copy(elements = it.elements.toList()) })
            // JSON decoding and note-wide validation must not run on the input/main thread.
            withContext(Dispatchers.Default) { build(frozen) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return ShapeCommandResult.Rejected("Invalid completed shape action")
        } ?: return ShapeCommandResult.NoChange
        val fresh = try {
            repository.load(noteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ShapeCommandResult.Rejected("Cannot load a consistent shape snapshot")
        }
        if (!withContext(Dispatchers.Default) { plan.matchesSnapshot(fresh) }) {
            return ShapeCommandResult.Rejected("Visible shape page changed before admission")
        }
        currentCoroutineContext().ensureActive()
        // Admission is synchronized; Room compares the revision inside the write transaction.
        return when (val result = coordinator.submitShapeEdit(plan).await()) {
            is EraserSessionCommitResult.Committed -> ShapeCommandResult.Committed(result.revision)
            is EraserSessionCommitResult.Rejected -> ShapeCommandResult.Rejected(result.reason)
            is EraserSessionCommitResult.Unconfirmed -> ShapeCommandResult.Unconfirmed(result.reason)
        }
    }
}
