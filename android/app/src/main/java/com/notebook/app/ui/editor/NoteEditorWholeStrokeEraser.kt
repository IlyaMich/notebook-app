package com.notebook.app.ui.editor

import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserCompletedWholeStrokePlanner
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserGestureBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Result of ONE completed gesture, never of an in-progress pointer update. */
internal sealed interface WholeStrokeEraserGestureResult {
    /** A cancelled/invalidated gesture has not loaded or edited a document. */
    data object Cancelled : WholeStrokeEraserGestureResult
    /** A complete, unambiguous scan hit no editable strokes. */
    data object NoHit : WholeStrokeEraserGestureResult
    /** The document or geometry cannot be safely used for removal. No write. */
    data class Blocked(val reason: String) : WholeStrokeEraserGestureResult
    /** Forward commit was rejected by the session admission gate. No write. */
    data class Rejected(val reason: String) : WholeStrokeEraserGestureResult
    data class Committed(val revision: Long) : WholeStrokeEraserGestureResult
    /** Storage might have committed; NEVER automatically resubmit this gesture. */
    data class Unconfirmed(val reason: String) : WholeStrokeEraserGestureResult
}

/**
 * Pre-UI integration boundary. Only [finishAndSubmit] may submit a destructive
 * edit: it consumes a completed gesture, loads a consistent snapshot, performs
 * read-only canonical hit-testing/planning off the main thread, and admits at
 * most one revision-guarded command through the existing autosave coordinator.
 *
 * No per-pointer writes, UI state, or separate persistence mechanism.
 * The editor must call buffer.cancel() for navigation/lifecycle cancellation;
 * no pointer/toolbar wiring is enabled by this class.
 */
internal class NoteEditorWholeStrokeEraser(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val coordinator: NoteAutosaveCoordinator,
) {
    init { require(noteId.isNotBlank()) }

    suspend fun finishAndSubmit(
        buffer: EditorEraserGestureBuffer,
        pageId: String,
        finalPoint: Point? = null,
    ): WholeStrokeEraserGestureResult {
        // Complete synchronously before the first suspension: cancelled or
        // wrong-page gestures can never load a snapshot or reach autosave.
        val sweep = buffer.finish(pageId, finalPoint)
            ?: return WholeStrokeEraserGestureResult.Cancelled

        currentCoroutineContext().ensureActive()
        val content = try {
            repository.load(noteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return WholeStrokeEraserGestureResult.Blocked(
                "Cannot load a consistent eraser snapshot"
            )
        }
        if (content.noteId != noteId) {
            return WholeStrokeEraserGestureResult.Blocked("Repository returned a different note")
        }

        val plan = try {
            withContext(Dispatchers.Default) {
                EditorEraserCompletedWholeStrokePlanner.plan(content, sweep)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Includes a geometry uncertainty, malformed canonical content,
            // wrong/missing page, and otherwise unsafe planning inputs.
            return WholeStrokeEraserGestureResult.Blocked(
                "Cannot authorize whole-stroke removal from this snapshot"
            )
        } ?: return WholeStrokeEraserGestureResult.NoHit

        // If navigation cancelled the caller during the scan, never admit a
        // write. Once admitted, cancelling an awaiter does NOT undo a commit.
        currentCoroutineContext().ensureActive()
        val acknowledgement = try {
            coordinator.submitWholeStrokeRemoval(plan)
        } catch (failure: IllegalArgumentException) {
            return WholeStrokeEraserGestureResult.Blocked(
                "Invalid eraser command"
            )
        }
        return when (val result = acknowledgement.await()) {
            is EraserSessionCommitResult.Committed ->
                WholeStrokeEraserGestureResult.Committed(result.revision)
            is EraserSessionCommitResult.Rejected ->
                WholeStrokeEraserGestureResult.Rejected(result.reason)
            is EraserSessionCommitResult.Unconfirmed ->
                WholeStrokeEraserGestureResult.Unconfirmed(result.reason)
        }
    }
}
