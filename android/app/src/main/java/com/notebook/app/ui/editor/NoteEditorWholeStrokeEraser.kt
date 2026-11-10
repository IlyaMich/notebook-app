package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.viewport.DuPoint
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
 * Revision-guarded commit boundary. [finishAndSubmit] consumes a buffer owned
 * by non-Compose callers; [submitViewportSweep] accepts an already completed,
 * captured Compose sweep and verifies the rendered document revision before
 * scanning. Both paths load a consistent snapshot, perform
 * read-only canonical hit-testing/planning off the main thread, and admits at
 * most one revision-guarded command through the existing autosave coordinator.
 *
 * No per-pointer writes, UI state, or separate persistence mechanism.
 * The Compose viewport owns cancellation and submits only completed sweeps;
 * the ordinary toolbar still does not expose the eraser.
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

        return submitCompleted(sweep, expectedVisibleRevision = null)
    }

    /**
     * End-to-end viewport entry point. The caller captures [renderedSnapshot]
     * synchronously at the completed pointer-release boundary. Never scan a
     * newer Room revision than the one shown to the user.
     *
     * No destructive work is reachable before normal gesture completion.
     */
    suspend fun submitViewportSweep(
        renderedSnapshot: StoredNoteContent,
        pageId: String,
        pointsPageDu: List<DuPoint>,
        radiusPageDu: Double,
    ): WholeStrokeEraserGestureResult {
        if (renderedSnapshot.noteId != noteId ||
            renderedSnapshot.pages.count { it.id == pageId } != 1
        ) {
            return WholeStrokeEraserGestureResult.Rejected("Rendered eraser page is unavailable")
        }
        val save = coordinator.state.value
        if (save.phase != SavePhase.SAVED ||
            save.pendingCommandCount != 0 ||
            save.committedRevision != renderedSnapshot.revision
        ) {
            return WholeStrokeEraserGestureResult.Rejected(
                "Visible document is not at its confirmed saved revision"
            )
        }
        return submitCompleted(
            sweep = EditorEraserGestureBuffer.CompletedSweep(
                pageId = pageId,
                pointsPageDu = pointsPageDu.map { Point(it.x, it.y) },
                radiusPageDu = radiusPageDu,
            ),
            expectedVisibleRevision = renderedSnapshot.revision,
        )
    }

    /** Can accept only a fully completed, page-local sweep. No pointer writes. */
    private suspend fun submitCompleted(
        sweep: EditorEraserGestureBuffer.CompletedSweep,
        expectedVisibleRevision: Long?,
    ): WholeStrokeEraserGestureResult {
        // Reject invalid callback input without even reading the repository.
        if (sweep.pageId.isBlank() ||
            sweep.pointsPageDu.isEmpty() || sweep.pointsPageDu.size > 8192 ||
            !sweep.radiusPageDu.isFinite() || sweep.radiusPageDu < 0.0 ||
            sweep.pointsPageDu.any { !it.x.isFinite() || !it.y.isFinite() }
        ) {
            return WholeStrokeEraserGestureResult.Blocked("Invalid completed eraser sweep")
        }
        // The input callback is allowed to own a mutable list. Copy it before
        // suspending to prevent a late pointer update altering planned geometry.
        val stableSweep = sweep.copy(pointsPageDu = sweep.pointsPageDu.toList())

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
        if (expectedVisibleRevision != null &&
            content.revision != expectedVisibleRevision
        ) {
            return WholeStrokeEraserGestureResult.Rejected(
                "Visible document changed before the eraser commit"
            )
        }

        val plan = try {
            withContext(Dispatchers.Default) {
                EditorEraserCompletedWholeStrokePlanner.plan(content, stableSweep)
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
