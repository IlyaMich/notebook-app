package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserPartialStrokeReplacementPlanner
import com.notebook.app.ui.editor.viewport.DuPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One completed pointer sweep. This is never an acknowledgement of pointer movement. */
internal sealed interface PartialStrokeEraserGestureResult {
    data object NoHit : PartialStrokeEraserGestureResult
    data class Blocked(val reason: String) : PartialStrokeEraserGestureResult
    data class Rejected(val reason: String) : PartialStrokeEraserGestureResult
    data class Committed(val revision: Long) : PartialStrokeEraserGestureResult
    /** A write may have committed; never automatically replay an uncertain edit. */
    data class Unconfirmed(val reason: String) : PartialStrokeEraserGestureResult
}

/**
 * EDT-16 1E-6. The only bridge from a COMPLETED Compose eraser sweep to a
 * partial-stroke replacement. All geometry/planning happens off the UI thread;
 * the coordinator and Room own guarded commit admission and exact session undo.
 *
 * This class does not change the selected tool or expose erasing in the toolbar.
 * It never writes during pointer movement, and never uses a newer document
 * revision than the caller's rendered snapshot.
 */
internal class NoteEditorPartialStrokeEraser(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val coordinator: NoteAutosaveCoordinator,
) {
    init { require(noteId.isNotBlank()) }

    suspend fun submitViewportSweep(
        renderedSnapshot: StoredNoteContent,
        pageId: String,
        pointsPageDu: List<DuPoint>,
        radiusPageDu: Double,
    ): PartialStrokeEraserGestureResult {
        // Capture stable values before the first suspension, never read a
        // mutable pointer list after the event has finished.
        if (pageId.isBlank() || pointsPageDu.isEmpty() || pointsPageDu.size > 8192 ||
            !radiusPageDu.isFinite() || radiusPageDu < 0.0 ||
            pointsPageDu.any { !it.x.isFinite() || !it.y.isFinite() }
        ) return PartialStrokeEraserGestureResult.Blocked("Invalid completed partial-eraser sweep")
        val stablePoints = pointsPageDu.map { Point(it.x, it.y) }
        val visiblePage = renderedSnapshot.pages.singleOrNull { it.id == pageId }
        if (renderedSnapshot.noteId != noteId || visiblePage == null ||
            renderedSnapshot.revision < 0L
        ) return PartialStrokeEraserGestureResult.Rejected("Rendered eraser page is unavailable")
        val state = coordinator.state.value
        if (state.phase != SavePhase.SAVED || state.pendingCommandCount != 0 ||
            state.committedRevision != renderedSnapshot.revision
        ) return PartialStrokeEraserGestureResult.Rejected(
            "Visible document is not at its confirmed saved revision"
        )

        currentCoroutineContext().ensureActive()
        val content = try {
            repository.load(noteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PartialStrokeEraserGestureResult.Blocked("Cannot load a consistent eraser snapshot")
        }
        if (content.noteId != noteId) {
            return PartialStrokeEraserGestureResult.Blocked("Repository returned a different note")
        }
        if (content.revision != renderedSnapshot.revision ||
            content.pages.singleOrNull { it.id == pageId }?.revision != visiblePage.revision
        ) return PartialStrokeEraserGestureResult.Rejected(
            "Visible document changed before the partial eraser commit"
        )

        val plan = try {
            withContext(Dispatchers.Default) {
                EditorEraserPartialStrokeReplacementPlanner.plan(
                    content = content,
                    pageId = pageId,
                    sweepPointsPageDu = stablePoints,
                    sweepRadiusPageDu = radiusPageDu,
                    scanNoteRevision = content.revision,
                    scanPageRevision = visiblePage.revision,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return PartialStrokeEraserGestureResult.Blocked(
                "Cannot authorize partial erasing from this snapshot"
            )
        } ?: return PartialStrokeEraserGestureResult.NoHit

        // Cancellation before admission forbids a write. Once admitted, the
        // worker owns the transaction even if this awaiting UI job disappears.
        currentCoroutineContext().ensureActive()
        val acknowledgement = try {
            coordinator.submitPartialStrokeReplacement(plan)
        } catch (_: IllegalArgumentException) {
            return PartialStrokeEraserGestureResult.Blocked("Invalid partial-eraser command")
        }
        return when (val result = acknowledgement.await()) {
            is EraserSessionCommitResult.Committed ->
                PartialStrokeEraserGestureResult.Committed(result.revision)
            is EraserSessionCommitResult.Rejected ->
                PartialStrokeEraserGestureResult.Rejected(result.reason)
            is EraserSessionCommitResult.Unconfirmed ->
                PartialStrokeEraserGestureResult.Unconfirmed(result.reason)
        }
    }
}
