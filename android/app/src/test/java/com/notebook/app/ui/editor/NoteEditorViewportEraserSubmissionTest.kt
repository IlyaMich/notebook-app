package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.viewport.DuPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** EDT-16 1D-5: visible-preview revision gate and completion-only adapter. */
class NoteEditorViewportEraserSubmissionTest {
    private val noteId = "note-1"
    private val pageId = "page-1"
    private val strokeId = "00000000-0000-4000-8000-000000000001"
    private val originalEnvelope =
        """{"id":"$strokeId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":4,"color":"#2255AA","opacity":1},"points":[{"x":40,"y":100,"tMs":0},{"x":160,"y":100,"tMs":20}]}}"""

    private class Repo(
        private val noteId: String,
        private val pageId: String,
        val envelope: String,
        val strokeId: String,
    ) : DocumentRepository {
        @Volatile var revision = 1L
        @Volatile var pageRevision = 1L
        @Volatile var containsStroke = true
        @Volatile var loads = 0
        @Volatile var guarded = 0
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered: CompletableDeferred<Unit>? = null

        override suspend fun load(noteId: String): StoredNoteContent {
            loads++
            entered?.complete(Unit)
            gate?.await()
            val elements = if (containsStroke) {
                listOf(StoredElement(strokeId, 0L, "stroke", envelope, pageRevision))
            } else emptyList()
            return StoredNoteContent(this.noteId, revision, listOf(
                StoredPage(pageId, 0L, 600.0, 800.0,
                    """{"type":"blank","color":"#FFFFFF"}""", pageRevision, elements)
            ))
        }

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            error("Unguarded document edit forbidden in this test")
        }

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit {
            guarded++
            require(revision == expectedRevision)
            when (edit) {
                is DocumentEdit.RemoveElements -> {
                    require(edit.pageId == pageId && edit.ids == listOf(strokeId))
                    require(containsStroke)
                    containsStroke = false
                }
                is DocumentEdit.RestoreRemovedStrokes -> {
                    require(edit.pageId == pageId)
                    require(edit.idsInOrder == listOf(strokeId))
                    require(edit.envelopes == listOf(envelope))
                    require(!containsStroke)
                    containsStroke = true
                }
                else -> error("Unexpected edit $edit")
            }
            revision++
            pageRevision++
            return DocumentCommit(noteId, revision, 100L)
        }
    }

    private fun repo() = Repo(noteId, pageId, originalEnvelope, strokeId)
    private fun points() = listOf(DuPoint(100.0, 85.0), DuPoint(100.0, 115.0))
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun visibleSnapshotCommitsOnceAndUndoRestoresExactEnvelope() = runBlocking {
        val repo = repo()
        val before = repo.load(noteId)
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, before.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            assertEquals(
                WholeStrokeEraserGestureResult.Committed(before.revision + 1L),
                withTimeout(5_000) {
                    handler.submitViewportSweep(before, pageId, points(), 7.0)
                },
            )
            assertEquals(1, repo.guarded)
            assertFalse(repo.containsStroke)
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(
                EraserSessionCommitResult.Committed(before.revision + 2L),
                withTimeout(5_000) { coordinator.undoWholeStroke().await() },
            )
            assertTrue(repo.containsStroke)
            assertEquals(originalEnvelope, repo.load(noteId).pages.single().elements.single().envelopeJson)
        } finally { scope.cancel() }
    }

    @Test fun visiblyStaleSnapshotRejectedWithoutDatabaseRead() = runBlocking {
        val repo = repo()
        val before = repo.load(noteId)
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, before.revision + 1L)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val count = repo.loads
            assertTrue(handler.submitViewportSweep(before, pageId, points(), 7.0)
                is WholeStrokeEraserGestureResult.Rejected)
            assertEquals(count, repo.loads)
            assertEquals(0, repo.guarded)
            assertTrue(repo.containsStroke)
        } finally { scope.cancel() }
    }

    @Test fun externalRevisionChangeAfterVisibleSnapshotCannotDeleteUnseenStroke() = runBlocking {
        val repo = repo()
        val before = repo.load(noteId)
        val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, before.revision)
            repo.revision++ // Simulates a different editor transaction after rendering.
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            assertTrue(handler.submitViewportSweep(before, pageId, points(), 7.0)
                is WholeStrokeEraserGestureResult.Rejected)
            assertEquals(0, repo.guarded)
            assertTrue(repo.containsStroke)
        } finally { scope.cancel() }
    }

    @Test fun malformedCompletedPointsAreBlockedBeforeDatabaseRead() = runBlocking {
        val repo = repo()
        val before = repo.load(noteId)
        val scope = scope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, before.revision)
            )
            val count = repo.loads
            val malformed = listOf(
                emptyList<DuPoint>(),
                listOf(DuPoint(Double.NaN, 100.0)),
                List(8193) { DuPoint(100.0, 100.0) },
            )
            for (bad in malformed) {
                assertTrue(handler.submitViewportSweep(before, pageId, bad, 7.0)
                    is WholeStrokeEraserGestureResult.Blocked)
            }
            assertTrue(handler.submitViewportSweep(before, pageId, points(), Double.POSITIVE_INFINITY)
                is WholeStrokeEraserGestureResult.Blocked)
            assertEquals(count, repo.loads)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun cancellationWhileRepositoryLoadsNeverSubmitsGuardedEdit() = runBlocking {
        val repo = repo()
        val before = repo.load(noteId)
        val scope = scope()
        repo.gate = CompletableDeferred()
        repo.entered = CompletableDeferred()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, before.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val caller = launch(Dispatchers.Default) {
                handler.submitViewportSweep(before, pageId, points(), 7.0)
            }
            withTimeout(5_000) { repo.entered!!.await() }
            caller.cancel()
            repo.gate!!.complete(Unit)
            withTimeout(5_000) { caller.join() }
            assertEquals(0, repo.guarded)
            assertTrue(repo.containsStroke)
        } finally {
            repo.gate?.complete(Unit)
            scope.cancel()
        }
    }
}
