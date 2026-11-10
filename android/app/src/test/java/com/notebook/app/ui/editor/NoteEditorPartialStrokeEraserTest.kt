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

/** Completion-only checks. Actual SQLite and Compose are covered by Android tests. */
class NoteEditorPartialStrokeEraserTest {
    private val note = "test-note"
    private val page = "test-page"
    private val originalId = "00000000-0000-4000-8000-000000000001"
    private val lockedId = "00000000-0000-4000-8000-000000000002"
    private val background = """{"type":"blank","color":"#FFFFFF"}"""
    private fun stroke(id: String, y: Int = 100, locked: Boolean = false) =
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2,"color":"#2255AA","opacity":1},"points":[{"x":10,"y":$y,"tMs":0},{"x":90,"y":$y,"tMs":80}]}}"""
    private val original = stroke(originalId)
    private val locked = stroke(lockedId, 180, true)
    private val idRegex = Regex("\\\"id\\\":\\\"([^\\\"]+)\\\"")
    private fun id(json: String): String = requireNotNull(idRegex.find(json)).groupValues[1]

    private inner class Fake : DocumentRepository {
        @Volatile var revision = 1L
        @Volatile var pageRevision = 1L
        @Volatile var loads = 0
        @Volatile var guarded = 0
        @Volatile var ordinary = 0
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var started: CompletableDeferred<Unit>? = null
        var rows = listOf(original, locked)
        var loseAck = false
        var failAtRead = false

        override suspend fun load(noteId: String): StoredNoteContent {
            loads++
            started?.complete(Unit)
            gate?.await()
            if (failAtRead) throw IllegalStateException("Read failed")
            return StoredNoteContent(note, revision, listOf(
                StoredPage(page, 0, 600.0, 800.0, background, pageRevision,
                    rows.mapIndexed { n, raw -> StoredElement(id(raw), n.toLong(), "stroke", raw, pageRevision) }),
            ))
        }

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            ordinary++
            if (edit !is DocumentEdit.ChangeBackground) error("Unexpected unguarded edit")
            revision++
            return DocumentCommit(note, revision, 100)
        }

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit {
            guarded++
            require(revision == expectedRevision)
            val replacements: List<DocumentEdit.PartialStrokeReplacement>
            val order: List<String>
            when (edit) {
                is DocumentEdit.ApplyPartialStrokeReplacements -> {
                    replacements = edit.replacements
                    order = edit.resultingIdsInOrder
                }
                is DocumentEdit.RestorePartialStrokeReplacements -> {
                    replacements = edit.replacements
                    order = edit.originalIdsInOrder
                }
                else -> error("Not a partial eraser command: $edit")
            }
            val old = rows.associateBy(::id).toMutableMap()
            for (replacement in replacements) {
                val sourceId = id(replacement.originalEnvelope)
                val fragmentIds = replacement.fragmentEnvelopes.map(::id)
                when (edit) {
                    is DocumentEdit.ApplyPartialStrokeReplacements -> {
                        require(old.remove(sourceId) == replacement.originalEnvelope)
                        replacement.fragmentEnvelopes.forEach { old[id(it)] = it }
                    }
                    else -> {
                        fragmentIds.forEach { require(old.remove(it) != null) }
                        old[sourceId] = replacement.originalEnvelope
                    }
                }
            }
            rows = order.map(old::getValue)
            revision++
            pageRevision++
            if (loseAck) error("Lost acknowledgement after durable write")
            return DocumentCommit(note, revision, 100)
        }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun cut() = listOf(DuPoint(50.0, 100.0))
    private fun service(repo: Fake, scope: CoroutineScope, initial: Long) =
        NoteEditorPartialStrokeEraser(note, repo, NoteAutosaveCoordinator(note, repo, scope, initial))

    @Test fun partialCutCommitsOneGuardedEditAndUndoRestoresOriginal() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, before.revision)
            val handler = NoteEditorPartialStrokeEraser(note, repo, coordinator)
            assertEquals(PartialStrokeEraserGestureResult.Committed(2L),
                withTimeout(10_000) { handler.submitViewportSweep(before, page, cut(), 4.0) })
            assertEquals(1, repo.guarded)
            assertEquals(0, repo.ordinary)
            assertEquals(3, repo.rows.size)
            assertEquals(locked, repo.rows.last())
            assertTrue(repo.rows.take(2).all { id(it) != originalId })
            assertTrue(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(3L),
                withTimeout(10_000) { coordinator.undoEraserEdit().await() })
            assertEquals(listOf(original, locked), repo.rows)
            assertEquals(EraserSessionCommitResult.Committed(4L),
                withTimeout(10_000) { coordinator.redoEraserEdit().await() })
            assertEquals(3, repo.rows.size)
        } finally { scope.cancel() }
    }

    @Test fun unchangedSweepDoesNotWriteOrRegisterHistory() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, before.revision)
            val result = NoteEditorPartialStrokeEraser(note, repo, coordinator)
                .submitViewportSweep(before, page, listOf(DuPoint(400.0, 400.0)), 1.0)
            assertEquals(PartialStrokeEraserGestureResult.NoHit, result)
            assertEquals(0, repo.guarded)
            assertFalse(coordinator.eraserHistory.value.canUndo)
        } finally { scope.cancel() }
    }

    @Test fun staleVisibleRevisionRejectedBeforeRoomRead() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val handler = service(repo, scope, before.revision + 1)
            val count = repo.loads
            assertTrue(handler.submitViewportSweep(before, page, cut(), 4.0) is
                PartialStrokeEraserGestureResult.Rejected)
            assertEquals(count, repo.loads)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun externalRevisionAndPageChangeRejectedWithoutWrite() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val handler = service(repo, scope, before.revision)
            repo.revision++
            assertTrue(handler.submitViewportSweep(before, page, cut(), 4.0) is
                PartialStrokeEraserGestureResult.Rejected)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun wrongPageRejectedWithoutLoadingRoom() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val handler = service(repo, scope, before.revision)
            val reads = repo.loads
            assertTrue(handler.submitViewportSweep(before, "another-page", cut(), 4.0) is
                PartialStrokeEraserGestureResult.Rejected)
            assertEquals(reads, repo.loads)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun pageRevisionChangeWithSameNoteRevisionCannotCommit() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val handler = service(repo, scope, before.revision)
            repo.pageRevision++
            assertTrue(handler.submitViewportSweep(before, page, cut(), 4.0) is
                PartialStrokeEraserGestureResult.Rejected)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun malformedInputNeverLoadsOrWrites() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            val handler = service(repo, scope, before.revision)
            val count = repo.loads
            for (bad in listOf(emptyList(), listOf(DuPoint(Double.NaN, 10.0)),
                    List(8193) { DuPoint(10.0, 10.0) })) {
                assertTrue(handler.submitViewportSweep(before, page, bad, 4.0) is
                    PartialStrokeEraserGestureResult.Blocked)
            }
            assertEquals(count, repo.loads)
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun cancelledDuringRoomReadCannotAdmitWrite() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            repo.started = CompletableDeferred()
            repo.gate = CompletableDeferred()
            val handler = service(repo, scope, before.revision)
            val job = scope.launch { handler.submitViewportSweep(before, page, cut(), 4.0) }
            withTimeout(10_000) { repo.started!!.await() }
            job.cancel()
            repo.gate!!.complete(Unit)
            job.join()
            assertEquals(0, repo.guarded)
        } finally { scope.cancel() }
    }

    @Test fun uncertainAcknowledgementNeverOffersUndoOrRetries() = runBlocking<Unit> {
        val repo = Fake(); val before = repo.load(note); val scope = scope()
        try {
            repo.loseAck = true
            val coordinator = NoteAutosaveCoordinator(note, repo, scope, before.revision)
            val result = NoteEditorPartialStrokeEraser(note, repo, coordinator)
                .submitViewportSweep(before, page, cut(), 4.0)
            assertTrue(result is PartialStrokeEraserGestureResult.Unconfirmed)
            assertEquals(1, repo.guarded)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertEquals(1, repo.guarded)
        } finally { scope.cancel() }
    }
}
