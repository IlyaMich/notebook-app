package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserGestureBuffer
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

class NoteEditorWholeStrokeEraserTest {
    private val noteId = "note-1"
    private val pageId = "page-1"
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val locked = "00000000-0000-4000-8000-000000000003"
    private val distant = "00000000-0000-4000-8000-000000000004"

    private fun stroke(
        id: String, order: Long, y: Int, locked: Boolean = false,
        transform: String = "[1,0,0,1,0,0]",
    ): StoredElement {
        val envelope = """ {"id":"$id", "type":"stroke","transform":$transform,"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":4.0,"color":"#112233","opacity":1.0},"points":[{"x":40,"y":$y,"tMs":0},{"x":160,"y":$y,"tMs":20}]}} """
        return StoredElement(id, order, "stroke", envelope, 1L)
    }

    private fun original() = listOf(
        stroke(a, 0L, 100),
        stroke(locked, 1L, 150, locked = true),
        stroke(b, 2L, 200),
        stroke(distant, 3L, 350),
    )

    private class FakeRepository(
        val noteId: String,
        val pageId: String,
        elements: List<StoredElement>,
    ) : DocumentRepository {
        @Volatile var revision = 7L
        @Volatile var pageRevision = 3L
        @Volatile var loadCalls = 0
        @Volatile var regularCommits = 0
        @Volatile var guardedCommits = 0
        @Volatile var forcedConflict = false
        @Volatile var returnWrongNote = false
        @Volatile var hidePage = false
        @Volatile var readGate: CompletableDeferred<Unit>? = null
        @Volatile var readEntered: CompletableDeferred<Unit>? = null
        @Volatile var regularGate: CompletableDeferred<Unit>? = null
        @Volatile var regularEntered: CompletableDeferred<Unit>? = null
        val rows = elements.toMutableList()

        override suspend fun load(noteId: String): StoredNoteContent {
            loadCalls++
            readEntered?.complete(Unit)
            readGate?.await()
            return StoredNoteContent(
                if (returnWrongNote) "another-note" else this.noteId, revision,
                if (hidePage) emptyList() else listOf(
                    StoredPage(pageId, 0L, 600.0, 800.0,
                        """{"type":"blank","color":"#FFFFFF"}""", pageRevision, rows.toList())
                ),
            )
        }

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            regularCommits++
            regularEntered?.complete(Unit)
            regularGate?.await()
            check(edit is DocumentEdit.ChangeBackground)
            revision++
            pageRevision++
            return DocumentCommit(noteId, revision, 100L)
        }

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit {
            guardedCommits++
            if (forcedConflict) {
                forcedConflict = false
                revision++ // Represents a different session committing first.
            }
            check(this.revision == expectedRevision) { "Stale guarded eraser revision" }
            when (edit) {
                is DocumentEdit.RemoveElements -> {
                    check(edit.ids.isNotEmpty())
                    check(edit.ids.all { id -> rows.any { it.id == id } })
                    rows.removeAll { it.id in edit.ids }
                    renumber()
                }
                is DocumentEdit.RestoreRemovedStrokes -> {
                    val restoring = edit.envelopes.map { json ->
                        val id = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"").find(json)
                            ?.groupValues?.get(1) ?: error("Missing restoration ID")
                        StoredElement(id, 0L, "stroke", json, pageRevision + 1L)
                    }
                    val survivors = edit.idsInOrder.filter { id -> restoring.none { it.id == id } }
                    check(survivors == rows.map { it.id })
                    val all = (rows + restoring).associateBy { it.id }
                    rows.clear()
                    rows.addAll(edit.idsInOrder.mapIndexed { index, id ->
                        all.getValue(id).copy(orderIndex = index.toLong())
                    })
                }
                else -> error("Unexpected guarded edit: $edit")
            }
            revision++
            pageRevision++
            return DocumentCommit(noteId, revision, 100L)
        }

        private fun renumber() {
            rows.sortBy { it.orderIndex }
            rows.indices.forEach { index ->
                rows[index] = rows[index].copy(orderIndex = index.toLong())
            }
        }
    }

    private fun newBuffer(): EditorEraserGestureBuffer = EditorEraserGestureBuffer()
    private fun crossStrokeSweep(buffer: EditorEraserGestureBuffer) {
        buffer.begin(pageId, Point(100.0, 85.0), 6.0)
        buffer.append(pageId, Point(100.0, 215.0))
    }
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun completedSweepRemovesAllHitUnlockedStrokesInOneCommitAndCanUndoExactly() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val before = repo.rows.map { it.envelopeJson }
        val scope = newScope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val buffer = newBuffer()
            crossStrokeSweep(buffer)
            assertEquals(
                WholeStrokeEraserGestureResult.Committed(8L),
                withTimeout(5_000) { handler.finishAndSubmit(buffer, pageId) },
            )
            assertEquals(1, repo.loadCalls)
            assertEquals(1, repo.guardedCommits)
            assertEquals(0, repo.regularCommits)
            assertEquals(listOf(locked, distant), repo.rows.map { it.id })
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(9L),
                withTimeout(5_000) { coordinator.undoWholeStroke().await() })
            assertEquals(listOf(a, locked, b, distant), repo.rows.map { it.id })
            assertEquals(before, repo.rows.map { it.envelopeJson })
            assertEquals(listOf(0L, 1L, 2L, 3L), repo.rows.map { it.orderIndex })
        } finally { scope.cancel() }
    }

    @Test fun tapIsAValidCompletedHitWithOneGuardedTransaction() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            buffer.begin(pageId, Point(100.0, 100.0), 7.0)
            assertEquals(WholeStrokeEraserGestureResult.Committed(8L),
                withTimeout(5_000) { handler.finishAndSubmit(buffer, pageId) })
            assertEquals(listOf(locked, b, distant), repo.rows.map { it.id })
            assertEquals(1, repo.guardedCommits)
        } finally { scope.cancel() }
    }

    @Test fun oneGestureCannotBeSubmittedTwice() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            buffer.begin(pageId, Point(100.0, 100.0), 7.0)
            assertEquals(WholeStrokeEraserGestureResult.Committed(8L),
                withTimeout(5_000) { handler.finishAndSubmit(buffer, pageId) })
            assertEquals(WholeStrokeEraserGestureResult.Cancelled,
                handler.finishAndSubmit(buffer, pageId))
            assertEquals(1, repo.guardedCommits)
        } finally { scope.cancel() }
    }

    @Test fun noVisibleHitDoesNotEnqueueOrAdvanceRevision() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            buffer.begin(pageId, Point(300.0, 450.0), 4.0)
            assertEquals(WholeStrokeEraserGestureResult.NoHit,
                handler.finishAndSubmit(buffer, pageId))
            assertEquals(7L, repo.revision)
            assertEquals(0, repo.guardedCommits)
        } finally { scope.cancel() }
    }

    @Test fun cancellationDoesNotEvenReadDocument() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            crossStrokeSweep(buffer)
            buffer.cancel()
            assertEquals(WholeStrokeEraserGestureResult.Cancelled,
                handler.finishAndSubmit(buffer, pageId))
            assertEquals(0, repo.loadCalls)
            assertEquals(0, repo.guardedCommits)
        } finally { scope.cancel() }
    }

    @Test fun wrongPageCompletionNeverReachesRepository() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            crossStrokeSweep(buffer)
            assertEquals(WholeStrokeEraserGestureResult.Cancelled,
                handler.finishAndSubmit(buffer, "different-page"))
            assertEquals(0, repo.loadCalls)
        } finally { scope.cancel() }
    }

    @Test fun malformedGeometryCannotProduceDestructiveCommit() = runBlocking {
        val bad = stroke(a, 0L, 100, transform = "[0,0,0,0,0,0]")
        val repo = FakeRepository(noteId, pageId, listOf(bad))
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            val buffer = newBuffer()
            buffer.begin(pageId, Point(100.0, 100.0), 5.0)
            assertTrue(handler.finishAndSubmit(buffer, pageId) is WholeStrokeEraserGestureResult.Blocked)
            assertEquals(0, repo.guardedCommits)
            assertEquals(listOf(bad), repo.rows)
        } finally { scope.cancel() }
    }

    @Test fun missingPageOrWrongNoteSnapshotFailsClosed() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        try {
            val handler = NoteEditorWholeStrokeEraser(
                noteId, repo, NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            )
            repo.hidePage = true
            val first = newBuffer().also(::crossStrokeSweep)
            assertTrue(handler.finishAndSubmit(first, pageId) is WholeStrokeEraserGestureResult.Blocked)
            repo.hidePage = false
            repo.returnWrongNote = true
            val second = newBuffer().also(::crossStrokeSweep)
            assertTrue(handler.finishAndSubmit(second, pageId) is WholeStrokeEraserGestureResult.Blocked)
            assertEquals(0, repo.guardedCommits)
        } finally { scope.cancel() }
    }

    @Test fun staleRevisionAtRoomBoundaryNeverDeletesAndNeverRegistersUndo() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        repo.forcedConflict = true
        val scope = newScope()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val buffer = newBuffer().also(::crossStrokeSweep)
            assertTrue(withTimeout(5_000) { handler.finishAndSubmit(buffer, pageId) }
                is WholeStrokeEraserGestureResult.Unconfirmed)
            assertEquals(original(), repo.rows)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
        } finally { scope.cancel() }
    }

    @Test fun ongoingOrdinaryAutosavePreventsEraserFromOvertakingIt() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        val gate = CompletableDeferred<Unit>()
        repo.regularGate = gate
        repo.regularEntered = CompletableDeferred()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            coordinator.enqueue(DocumentEdit.ChangeBackground(pageId, "{}"))
            withTimeout(5_000) { repo.regularEntered!!.await() }
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val buffer = newBuffer().also(::crossStrokeSweep)
            assertTrue(withTimeout(5_000) { handler.finishAndSubmit(buffer, pageId) }
                is WholeStrokeEraserGestureResult.Rejected)
            assertEquals(0, repo.guardedCommits)
        } finally {
            gate.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun cancelledAwaiterDuringSnapshotLoadNeverSubmitsEraser() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        repo.readGate = CompletableDeferred()
        repo.readEntered = CompletableDeferred()
        try {
            val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
            val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
            val buffer = newBuffer().also(::crossStrokeSweep)
            val caller = launch(Dispatchers.Default) { handler.finishAndSubmit(buffer, pageId) }
            withTimeout(5_000) { repo.readEntered!!.await() }
            caller.cancel()
            repo.readGate!!.complete(Unit)
            withTimeout(5_000) { caller.join() }
            assertEquals(0, repo.guardedCommits)
            assertEquals(original(), repo.rows)
        } finally { scope.cancel() }
    }

    @Test fun closedEditorSessionRejectsCompletedSweepWithoutEditing() = runBlocking {
        val repo = FakeRepository(noteId, pageId, original())
        val scope = newScope()
        val coordinator = NoteAutosaveCoordinator(noteId, repo, scope, repo.revision)
        scope.cancel()
        val handler = NoteEditorWholeStrokeEraser(noteId, repo, coordinator)
        val buffer = newBuffer().also(::crossStrokeSweep)
        assertTrue(handler.finishAndSubmit(buffer, pageId) is WholeStrokeEraserGestureResult.Rejected)
        assertEquals(0, repo.guardedCommits)
    }
}
