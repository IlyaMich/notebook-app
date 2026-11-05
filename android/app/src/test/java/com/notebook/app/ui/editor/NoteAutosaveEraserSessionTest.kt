package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserWholeStrokeRemovalPlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAutosaveEraserSessionTest {
    private val noteId = "test-note"
    private val pageId = "test-page"
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val c = "00000000-0000-4000-8000-000000000003"

    private fun stroke(id: String, index: Long): StoredElement = StoredElement(
        id = id,
        orderIndex = index,
        kind = "stroke",
        envelopeJson = """ {"id":"$id", "type":"stroke", "locked":false,"payload":{}} """,
        revision = 7L,
    )

    private fun plan(): EditorEraserWholeStrokeRemovalPlanner.Plan {
        val first = stroke(a, 0L)
        val third = stroke(c, 2L)
        return EditorEraserWholeStrokeRemovalPlanner.Plan(
            noteId = noteId,
            pageId = pageId,
            expectedNoteRevision = 7L,
            expectedPageRevision = 2L,
            originalElementIdsInOrder = listOf(a, b, c),
            removed = listOf(
                EditorEraserWholeStrokeRemovalPlanner.RemovedSnapshot(0, first),
                EditorEraserWholeStrokeRemovalPlanner.RemovedSnapshot(2, third),
            ),
            forwardEdit = DocumentEdit.RemoveElements(pageId, listOf(a, c)),
            inverseEdit = DocumentEdit.RestoreRemovedStrokes(
                pageId, listOf(first.envelopeJson, third.envelopeJson), listOf(a, b, c)
            ),
        )
    }

    private class FakeRepository(
        val noteId: String,
        val pageId: String,
        original: List<StoredElement>,
    ) : DocumentRepository {
        @Volatile var revision = 7L
        @Volatile var guardedCalls = 0
        @Volatile var regularCalls = 0
        @Volatile var failAfterGuardedCommit = false
        @Volatile var failBeforeGuardedCommit = false
        @Volatile var commitGate: CompletableDeferred<Unit>? = null
        @Volatile var commitEntered: CompletableDeferred<Unit>? = null
        val rows = original.toMutableList()

        override suspend fun load(noteId: String) = StoredNoteContent(
            noteId = noteId,
            revision = revision,
            pages = listOf(StoredPage(
                id = pageId, orderIndex = 0, width = 600.0, height = 800.0,
                backgroundJson = "{}", revision = 2L, elements = rows.toList(),
            )),
        )

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            regularCalls++
            commitEntered?.complete(Unit)
            commitGate?.await()
            check(edit is DocumentEdit.ChangeBackground)
            revision++
            return DocumentCommit(noteId, revision, 100L)
        }

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit {
            guardedCalls++
            commitEntered?.complete(Unit)
            commitGate?.await()
            check(revision == expectedRevision) { "Stale revision" }
            if (failBeforeGuardedCommit) {
                failBeforeGuardedCommit = false
                throw IllegalStateException("Storage unavailable")
            }
            when (edit) {
                is DocumentEdit.RemoveElements -> {
                    check(edit.ids.all { id -> rows.any { it.id == id } })
                    rows.removeAll { it.id in edit.ids }
                    rows.forEachIndexed { index, element -> rows[index] = element.copy(orderIndex = index.toLong()) }
                }
                is DocumentEdit.RestoreRemovedStrokes -> {
                    val restoring = edit.envelopes.map { json ->
                        val id = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
                            ?: error("Test envelope missing ID")
                        StoredElement(id, 0L, "stroke", json, revision + 1L)
                    }
                    check(rows.map { it.id } == edit.idsInOrder.filter { id -> restoring.none { it.id == id } })
                    val byId = (rows + restoring).associateBy { it.id }
                    rows.clear()
                    rows.addAll(edit.idsInOrder.mapIndexed { index, id ->
                        byId.getValue(id).copy(orderIndex = index.toLong())
                    })
                }
                else -> error("Unexpected guarded command")
            }
            revision++
            if (failAfterGuardedCommit) {
                failAfterGuardedCommit = false
                throw IllegalStateException("Commit acknowledgement lost")
            }
            return DocumentCommit(noteId, revision, 100L)
        }
    }

    private fun newRepository() = FakeRepository(
        noteId, pageId, listOf(stroke(a, 0), stroke(b, 1), stroke(c, 2))
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun awaitSaved(coordinator: NoteAutosaveCoordinator, revision: Long) {
        withTimeout(5_000) {
            coordinator.state.first { it.phase == SavePhase.SAVED && it.committedRevision == revision }
        }
    }

    private suspend fun awaitResult(result: kotlinx.coroutines.Deferred<EraserSessionCommitResult>) =
        withTimeout(5_000) { result.await() }

    @Test
    fun removalUndoAndRedoAreCommitConfirmedAndPreserveExactOrderAndJson() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            val before = repository.rows.map { it.envelopeJson }
            assertEquals(EraserSessionCommitResult.Committed(8L), awaitResult(coordinator.submitWholeStrokeRemoval(plan())))
            awaitSaved(coordinator, 8L)
            assertEquals(listOf(b), repository.rows.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(1, coordinator.eraserHistory.value.undoDepth)
            assertEquals(EraserSessionCommitResult.Committed(9L), awaitResult(coordinator.undoWholeStroke()))
            awaitSaved(coordinator, 9L)
            assertEquals(listOf(a, b, c), repository.rows.map { it.id })
            assertEquals(before, repository.rows.map { it.envelopeJson })
            assertTrue(coordinator.eraserHistory.value.canRedo)
            assertEquals(EraserSessionCommitResult.Committed(10L), awaitResult(coordinator.redoWholeStroke()))
            awaitSaved(coordinator, 10L)
            assertEquals(listOf(b), repository.rows.map { it.id })
            assertEquals(3, repository.guardedCalls)
            assertEquals(0, repository.regularCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun eraserIsRejectedWhilePenSaveIsPending() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val gate = CompletableDeferred<Unit>()
            repository.commitGate = gate
            repository.commitEntered = CompletableDeferred()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            coordinator.enqueue(DocumentEdit.ChangeBackground(pageId, "{}"))
            withTimeout(5_000) { repository.commitEntered!!.await() }
            val result = awaitResult(coordinator.submitWholeStrokeRemoval(plan()))
            assertTrue(result is EraserSessionCommitResult.Rejected)
            assertEquals(listOf(a, b, c), repository.rows.map { it.id })
            assertEquals(0, repository.guardedCalls)
            gate.complete(Unit)
            awaitSaved(coordinator, 8L)
        } finally { scope.cancel() }
    }

    @Test
    fun stalePlanIsRejectedWithoutEnqueueOrHistory() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 8L)
            val result = awaitResult(coordinator.submitWholeStrokeRemoval(plan()))
            assertTrue(result is EraserSessionCommitResult.Rejected)
            assertEquals(0, repository.guardedCalls)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(0, coordinator.state.value.pendingCommandCount)
        } finally { scope.cancel() }
    }

    @Test
    fun externalRevisionChangeRejectsStaleDeleteAndBlocksBlindRetry() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            repository.revision = 8L // Another writer committed after scan.
            val result = awaitResult(coordinator.submitWholeStrokeRemoval(plan()))
            assertTrue(result is EraserSessionCommitResult.Unconfirmed)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertEquals(listOf(a, b, c), repository.rows.map { it.id })
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, repository.guardedCalls)
            assertFalse(coordinator.eraserHistory.value.canUndo)
        } finally { scope.cancel() }
    }

    @Test
    fun lostAcknowledgementDoesNotMakeUndoAvailableOrReplayDelete() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            repository.failAfterGuardedCommit = true
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            assertTrue(awaitResult(coordinator.submitWholeStrokeRemoval(plan())) is EraserSessionCommitResult.Unconfirmed)
            assertEquals(listOf(b), repository.rows.map { it.id })
            assertEquals(7L, coordinator.state.value.committedRevision)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, repository.guardedCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun unrelatedEditInvalidatesEraserHistoryEvenBeforeItCommits() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            assertEquals(EraserSessionCommitResult.Committed(8L), awaitResult(coordinator.submitWholeStrokeRemoval(plan())))
            awaitSaved(coordinator, 8L)
            assertTrue(coordinator.eraserHistory.value.canUndo)
            coordinator.enqueue(DocumentEdit.ChangeBackground(pageId, "{}"))
            assertFalse(coordinator.eraserHistory.value.canUndo)
            awaitSaved(coordinator, 9L)
            assertTrue(awaitResult(coordinator.undoWholeStroke()) is EraserSessionCommitResult.Rejected)
        } finally { scope.cancel() }
    }

    @Test
    fun penEnqueuedDuringEraserCommitCannotLeaveStaleUndoHistory() = runBlocking {
        val scope = newScope()
        try {
            val repository = newRepository()
            val gate = CompletableDeferred<Unit>()
            repository.commitGate = gate
            repository.commitEntered = CompletableDeferred()
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
            val request = coordinator.submitWholeStrokeRemoval(plan())
            withTimeout(5_000) { repository.commitEntered!!.await() }
            coordinator.enqueue(DocumentEdit.ChangeBackground(pageId, "{}"))
            gate.complete(Unit)
            assertEquals(EraserSessionCommitResult.Committed(8L), awaitResult(request))
            awaitSaved(coordinator, 9L)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(0, coordinator.eraserHistory.value.undoDepth)
            assertEquals(1, repository.regularCalls)
        } finally { scope.cancel() }
    }

    @Test
    fun previouslyCancelledSessionNeverHangsOrCommitsEraser() = runBlocking {
        val scope = newScope()
        scope.cancel()
        val repository = newRepository()
        val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
        val outcome = awaitResult(coordinator.submitWholeStrokeRemoval(plan()))
        assertTrue(outcome is EraserSessionCommitResult.Unconfirmed)
        assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
        assertEquals(0, repository.guardedCalls)
        assertFalse(coordinator.eraserHistory.value.canUndo)
    }

    @Test
    fun unguardedRemoveAndRestoreAreRejectedAtCoordinatorBoundary() {
        val scope = newScope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, newRepository(), scope, 7L)
            try {
                coordinator.enqueue(DocumentEdit.RemoveElements(pageId, listOf(a)))
                throw AssertionError("Expected fail-closed rejection")
            } catch (_: IllegalArgumentException) { }
            try {
                coordinator.enqueue(DocumentEdit.RestoreRemovedStrokes(pageId, listOf("{}"), listOf(a)))
                throw AssertionError("Expected fail-closed rejection")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, coordinator.state.value.pendingCommandCount)
        } finally { scope.cancel() }
    }
}
