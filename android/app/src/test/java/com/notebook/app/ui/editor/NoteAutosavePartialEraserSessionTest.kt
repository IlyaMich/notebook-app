package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserPartialStrokeReplacementPlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Session-only eraser undo/redo tests; SQLite semantics have separate Room tests. */
class NoteAutosavePartialEraserSessionTest {
    private val note = "test-note"
    private val page = "test-page"
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val f1 = "00000000-0000-4000-8000-000000000011"
    private val f2 = "00000000-0000-4000-8000-000000000012"

    private fun envelope(id: String) = """{"id":"$id","type":"stroke","source":"fixed"}"""
    private fun row(id: String, order: Long) = StoredElement(id, order, "stroke", envelope(id), 7L)

    private fun plan(): EditorEraserPartialStrokeReplacementPlanner.Plan {
        val original = row(a, 0L)
        val fragments = listOf(envelope(f1), envelope(f2))
        val replacement = EditorEraserPartialStrokeReplacementPlanner.Replacement(
            originalPosition = 0,
            original = original,
            fragmentEnvelopes = fragments,
            fragmentIds = listOf(f1, f2),
        )
        val commands = listOf(DocumentEdit.PartialStrokeReplacement(original.envelopeJson, fragments))
        return EditorEraserPartialStrokeReplacementPlanner.Plan(
            noteId = note, pageId = page, expectedNoteRevision = 7L, expectedPageRevision = 2L,
            originalElementIdsInOrder = listOf(a, b),
            resultingElementIdsInOrder = listOf(f1, f2, b),
            replacements = listOf(replacement),
            forwardEdit = DocumentEdit.ApplyPartialStrokeReplacements(page, listOf(a, b), listOf(f1, f2, b), commands),
            inverseEdit = DocumentEdit.RestorePartialStrokeReplacements(page, listOf(a, b), listOf(f1, f2, b), commands),
        )
    }

    private class FakeRepository(val note: String, val page: String, original: List<StoredElement>) : DocumentRepository {
        @Volatile var revision = 7L
        @Volatile var guardedCalls = 0
        @Volatile var ordinaryCalls = 0
        @Volatile var failAfterWrite = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered: CompletableDeferred<Unit>? = null
        val rows = original.toMutableList()

        override suspend fun load(noteId: String) = StoredNoteContent(note, revision,
            listOf(StoredPage(page, 0L, 600.0, 800.0, "{}", 2L, rows.toList())))

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            ordinaryCalls++
            entered?.complete(Unit)
            gate?.await()
            check(edit is DocumentEdit.ChangeBackground)
            revision++
            return DocumentCommit(note, revision, 100L)
        }

        private fun id(raw: String): String = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"")
            .find(raw)?.groupValues?.get(1) ?: error("Missing test ID")

        override suspend fun commitIfRevision(noteId: String, expectedRevision: Long, edit: DocumentEdit): DocumentCommit {
            guardedCalls++
            entered?.complete(Unit)
            gate?.await()
            check(revision == expectedRevision) { "Stale guarded write" }
            val command = when (edit) {
                is DocumentEdit.ApplyPartialStrokeReplacements -> edit
                is DocumentEdit.RestorePartialStrokeReplacements -> edit
                else -> error("Unexpected command: $edit")
            }
            val (order, replacing, output) = when (command) {
                is DocumentEdit.ApplyPartialStrokeReplacements -> Triple(
                    command.resultingIdsInOrder, command.replacements.map { it.originalEnvelope },
                    command.replacements.flatMap { it.fragmentEnvelopes })
                is DocumentEdit.RestorePartialStrokeReplacements -> Triple(
                    command.originalIdsInOrder, command.replacements.flatMap { it.fragmentEnvelopes },
                    command.replacements.map { it.originalEnvelope })
                else -> error("Impossible")
            }
            val expected = when (command) {
                is DocumentEdit.ApplyPartialStrokeReplacements -> command.originalIdsInOrder
                is DocumentEdit.RestorePartialStrokeReplacements -> command.resultingIdsInOrder
                else -> error("Impossible")
            }
            check(rows.map { it.id } == expected)
            replacing.forEach { raw -> check(rows.any { it.id == id(raw) && it.envelopeJson == raw }) }
            val replacementIds = replacing.map(::id).toSet()
            val byId = (rows.filterNot { it.id in replacementIds } + output.map {
                StoredElement(id(it), 0L, "stroke", it, revision + 1L)
            }).associateBy { it.id }
            check(byId.keys == order.toSet())
            rows.clear()
            rows.addAll(order.mapIndexed { idx, key -> byId.getValue(key).copy(orderIndex = idx.toLong()) })
            revision++
            if (failAfterWrite) throw IllegalStateException("Lost acknowledgement")
            return DocumentCommit(note, revision, 100L)
        }
    }

    private fun newRepo() = FakeRepository(note, page, listOf(row(a, 0L), row(b, 1L)))
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private suspend fun result(deferred: kotlinx.coroutines.Deferred<EraserSessionCommitResult>) =
        withTimeout(5_000) { deferred.await() }

    @Test fun partialCommitUndoRedoPreservesFragmentIdsAndOriginalBytes() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo()
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
            val original = repo.rows.map { it.envelopeJson }
            assertEquals(EraserSessionCommitResult.Committed(8L), result(coordinator.submitPartialStrokeReplacement(plan())))
            assertEquals(listOf(f1, f2, b), repo.rows.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(9L), result(coordinator.undoEraserEdit()))
            assertEquals(original, repo.rows.map { it.envelopeJson })
            assertTrue(coordinator.eraserHistory.value.canRedo)
            assertEquals(EraserSessionCommitResult.Committed(10L), result(coordinator.redoEraserEdit()))
            assertEquals(listOf(f1, f2, b), repo.rows.map { it.id })
            assertEquals(3, repo.guardedCalls)
            assertEquals(0, repo.ordinaryCalls)
        } finally { scope.cancel() }
    }

    @Test fun partialCommandsCannotEnterUnguardedAutosave() {
        val scope = newScope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, newRepo(), scope, 7L)
            try { coordinator.enqueue(plan().forwardEdit); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            try { coordinator.enqueue(plan().inverseEdit); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, coordinator.state.value.pendingCommandCount)
        } finally { scope.cancel() }
    }

    @Test fun stalePlanIsRejectedBeforeRepositoryCall() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo()
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 8L)
            assertTrue(result(coordinator.submitPartialStrokeReplacement(plan())) is EraserSessionCommitResult.Rejected)
            assertEquals(0, repo.guardedCalls)
        } finally { scope.cancel() }
    }

    @Test fun pendingPenCommandRejectsPartialErase() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo()
            repo.gate = CompletableDeferred()
            repo.entered = CompletableDeferred()
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
            coordinator.enqueue(DocumentEdit.ChangeBackground(page, "{}"))
            withTimeout(5_000) { repo.entered!!.await() }
            assertTrue(result(coordinator.submitPartialStrokeReplacement(plan())) is EraserSessionCommitResult.Rejected)
            assertEquals(0, repo.guardedCalls)
            assertTrue(repo.gate!!.complete(Unit))
        } finally { scope.cancel() }
    }

    @Test fun lostAcknowledgementBlocksUndoAndBlindRetry() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo().apply { failAfterWrite = true }
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
            assertTrue(result(coordinator.submitPartialStrokeReplacement(plan())) is EraserSessionCommitResult.Unconfirmed)
            assertEquals(listOf(f1, f2, b), repo.rows.map { it.id })
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, repo.guardedCalls)
        } finally { scope.cancel() }
    }

    @Test fun alreadyClosedSessionRejectsPartialWrite() = runBlocking {
        val scope = newScope().apply { cancel() }
        val repo = newRepo()
        val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
        assertTrue(result(coordinator.submitPartialStrokeReplacement(plan())) is EraserSessionCommitResult.Rejected)
        assertEquals(0, repo.guardedCalls)
    }

    @Test fun malformedForwardInverseAndOrderAreRejectedWithoutWrite() {
        val scope = newScope()
        try {
            val repo = newRepo()
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
            val malformed = plan().copy(resultingElementIdsInOrder = listOf(f1, b))
            try { coordinator.submitPartialStrokeReplacement(malformed); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            val mismatched = plan().copy(inverseEdit = plan().inverseEdit.copy(resultingIdsInOrder = listOf(b)))
            try { coordinator.submitPartialStrokeReplacement(mismatched); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, repo.guardedCalls)
        } finally { scope.cancel() }
    }

    @Test fun ordinaryEditClearsPartialHistoryAfterConfirmedCommit() = runBlocking {
        val scope = newScope()
        try {
            val repo = newRepo()
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7L)
            assertEquals(EraserSessionCommitResult.Committed(8L), result(coordinator.submitPartialStrokeReplacement(plan())))
            coordinator.enqueue(DocumentEdit.ChangeBackground(page, "{}"))
            assertEquals(0, coordinator.eraserHistory.value.undoDepth)
            assertTrue(result(coordinator.undoEraserEdit()) is EraserSessionCommitResult.Rejected)
        } finally { scope.cancel() }
    }
}
