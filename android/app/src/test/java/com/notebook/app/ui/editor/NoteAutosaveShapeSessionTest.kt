package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.*
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserWholeStrokeRemovalPlanner
import com.notebook.app.ui.editor.shapes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteAutosaveShapeSessionTest {
    private val note = "10000000-0000-4000-8000-000000000001"
    private val page = "10000000-0000-4000-8000-000000000002"
    private val shapeId = "10000000-0000-4000-8000-000000000003"
    private val strokeId = "10000000-0000-4000-8000-000000000004"

    private fun shape(kind: CanonicalShapeKind = CanonicalShapeKind.RECTANGLE): String =
        CanonicalShapeAdapter.create(shapeId, kind,
            if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
                CanonicalShapeGeometry.Endpoints(10.0, 20.0, 60.0, 50.0)
            else CanonicalShapeGeometry.Frame(10.0, 20.0, 50.0, 30.0),
            CanonicalShapeStroke("#aBcDeF", 3.0, 0.75), CanonicalShapeFill("#FEDCBA", 0.5),
            if (kind == CanonicalShapeKind.ARROW) CanonicalShapeArrowHead(12.0, -390.0) else null)

    private fun row(raw: String, order: Long = 0L) =
        StoredElement(Json.parseToJsonElement(raw).jsonObject.getValue("id").jsonPrimitive.content,
            order, "shape", raw, 7)
    private fun stroke() = StoredElement(strokeId, 0, "stroke",
        """{"id":"$strokeId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","color":"#2255AA","width":2,"opacity":1},"points":[{"x":10,"y":50,"tMs":0},{"x":90,"y":50,"tMs":20}]}}""", 7)
    private fun changed(raw: String) = raw.replace("\"width\":3.0", "\"width\":4.125")
    private fun background() = DocumentEdit.ChangeBackground(page, """{"type":"blank","color":"#FFF9E9"}""")

    /** Queue/failure model only; real validation/transactions are covered by the Room suite. */
    private class Repo(val note: String, val page: String, initial: List<StoredElement>) : DocumentRepository {
        @Volatile var revision = 7L
        @Volatile var guarded = 0
        @Volatile var ordinary = 0
        @Volatile var loads = 0
        @Volatile var failBefore = false
        @Volatile var failAfter = false
        @Volatile var failLoad = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var entered: CompletableDeferred<Unit>? = null
        @Volatile var loadGate: CompletableDeferred<Unit>? = null
        @Volatile var loadEntered: CompletableDeferred<Unit>? = null
        val rows = initial.toMutableList()
        var extraPages: List<StoredPage> = emptyList()

        override suspend fun load(noteId: String): StoredNoteContent {
            loads++
            loadEntered?.complete(Unit)
            loadGate?.await()
            if (failLoad) error("Read failed")
            return StoredNoteContent(note, revision, listOf(StoredPage(page, 0, 100.0, 100.0,
                """{"type":"blank","color":"#FFFFFF"}""", 0, rows.toList())) + extraPages)
        }
        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            ordinary++
            entered?.complete(Unit)
            gate?.await()
            check(edit is DocumentEdit.ChangeBackground)
            revision++
            return DocumentCommit(note, revision, 100)
        }
        private fun decode(raw: String, kind: String = "shape") = StoredElement(
            Json.parseToJsonElement(raw).jsonObject.getValue("id").jsonPrimitive.content, 0, kind, raw, revision + 1)
        override suspend fun commitIfRevision(noteId: String, expectedRevision: Long, edit: DocumentEdit): DocumentCommit {
            guarded++
            entered?.complete(Unit)
            gate?.await()
            check(revision == expectedRevision) { "Competing revision" }
            if (failBefore) { failBefore = false; error("Write failed") }
            when (edit) {
                is DocumentEdit.InsertElements -> rows.addAll(edit.at ?: rows.size, edit.envelopes.map { decode(it) })
                is DocumentEdit.ReplaceElements -> edit.envelopes.forEach { raw ->
                    val candidate = decode(raw)
                    val index = rows.indexOfFirst { it.id == candidate.id }
                    check(index >= 0)
                    rows[index] = candidate.copy(orderIndex = rows[index].orderIndex)
                }
                is DocumentEdit.RemoveElements -> rows.removeAll { it.id in edit.ids }
                is DocumentEdit.RestoreRemovedStrokes -> {
                    val all = (rows + edit.envelopes.map { decode(it, "stroke") }).associateBy { it.id }
                    rows.clear()
                    rows.addAll(edit.idsInOrder.map { all.getValue(it) })
                }
                else -> error("Unexpected guarded command")
            }
            rows.forEachIndexed { index, element -> rows[index] = element.copy(orderIndex = index.toLong()) }
            revision++
            if (failAfter) { failAfter = false; error("Acknowledgement lost") }
            return DocumentCommit(note, revision, 100)
        }
    }
    private inner class Session(rows: List<StoredElement>) {
        val repo = Repo(note, page, rows)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NoteAutosaveCoordinator(note, repo, scope, 7)
        val commands = NoteEditorShapeCommands(note, repo, coordinator)
        suspend fun snapshot() = repo.load(note)
        suspend fun saved(revision: Long) {
            withTimeout(5_000) { coordinator.state.first { it.phase == SavePhase.SAVED && it.committedRevision == revision } }
        }
    }
    private suspend fun session(rows: List<StoredElement> = emptyList(), block: suspend Session.() -> Unit) {
        val s = Session(rows)
        try { withTimeout(10_000) { s.block() } } finally { s.scope.cancel() }
    }
    private suspend fun result(value: Deferred<EraserSessionCommitResult>) = withTimeout(5_000) { value.await() }

    @Test fun fourKindsInsertUndoRedoUseOneGuardedRevisionEachAndSameId() = runBlocking {
        for (kind in CanonicalShapeKind.entries) session {
            val raw = shape(kind)
            assertEquals(ShapeCommandResult.Committed(8), commands.insert(snapshot(), page, raw))
            assertEquals(listOf(raw), repo.rows.map { it.envelopeJson })
            assertTrue(coordinator.shapeHistory.value.canUndo)
            assertFalse(coordinator.eraserHistory.value.canUndo)
            assertEquals(EraserSessionCommitResult.Committed(9), result(coordinator.undoShapeEdit()))
            assertTrue(repo.rows.isEmpty())
            assertEquals(EraserSessionCommitResult.Committed(10), result(coordinator.redoShapeEdit()))
            assertEquals(listOf(shapeId), repo.rows.map { it.id })
            assertEquals(raw, repo.rows.single().envelopeJson)
            assertEquals(3, repo.guarded)
            assertEquals(0, repo.ordinary)
        }
    }

    @Test fun replacementUndoRestoresExactBytesAndOrderingWithUnchangedNeighbors() = runBlocking {
        val raw = " \n" + shape().replace("[1,0,0,1,0,0]", "[2,0,0.5,3,4,5]") + "\n"
        session(listOf(stroke(), row(raw, 1))) {
            val before = snapshot()
            val after = changed(raw)
            assertNotEquals(raw, after)
            assertEquals(ShapeCommandResult.Committed(8), commands.replace(before, page, after))
            assertEquals(EraserSessionCommitResult.Committed(9), result(coordinator.undoShapeEdit()))
            assertEquals(before.pages.single().elements.map { it.envelopeJson }, repo.rows.map { it.envelopeJson })
            assertEquals(EraserSessionCommitResult.Committed(10), result(coordinator.redoShapeEdit()))
            assertEquals(listOf(strokeId, shapeId), repo.rows.map { it.id })
            assertEquals(after, repo.rows.last().envelopeJson)
            assertEquals(before.pages.single().elements.first(), repo.rows.first())
        }
    }

    @Test fun noOpIncludingNumericSpellingHasNoWriteReadOrHistory() = runBlocking {
        session(listOf(row(shape()))) {
            val before = snapshot()
            val loads = repo.loads
            assertEquals(ShapeCommandResult.NoChange,
                commands.replace(before, page, shape().replace("3.0", "3e0")))
            assertEquals(0, repo.guarded)
            assertEquals(loads, repo.loads)
            assertEquals(0, coordinator.shapeHistory.value.undoDepth)
            assertEquals(7L, coordinator.state.value.committedRevision)
        }
    }

    @Test fun invalidWrongOwnerCollisionLockedAndRetaggedActionsRejectBeforeReadOrWrite() = runBlocking {
        session(listOf(row(shape()))) {
            val before = snapshot()
            val loads = repo.loads
            val invalid = listOf("{}", shape().replace("rectangle", "triangle"),
                shape().replace("\"width\":50.0", "\"width\":0"),
                shape().replace("\"locked\":false", "\"locked\":true"),
                shape(CanonicalShapeKind.ELLIPSE))
            for (raw in invalid) assertTrue(commands.replace(before, page, raw) is ShapeCommandResult.Rejected)
            assertTrue(commands.insert(before, page, shape()) is ShapeCommandResult.Rejected)
            assertTrue(commands.insert(before, "missing", shape()) is ShapeCommandResult.Rejected)
            assertTrue(commands.insert(before.copy(noteId = "other"), page, shape()) is ShapeCommandResult.Rejected)
            assertEquals(loads, repo.loads)
            assertEquals(0, repo.guarded)
            val locked = before.copy(pages = listOf(before.pages.single().copy(elements =
                listOf(row(shape().replace("\"locked\":false", "\"locked\":true"))))))
            assertTrue(commands.replace(locked, page, changed(shape())) is ShapeCommandResult.Rejected)
        }
    }

    @Test fun planRejectsNoteWideCollisionNoncontiguousOrderAndNonidentityInsertion() = runBlocking {
        session {
            val before = snapshot()
            val other = before.pages.single().copy(id = "10000000-0000-4000-8000-000000000099",
                elements = listOf(row(shape())))
            assertThrows(IllegalArgumentException::class.java) {
                ShapeEditPlan.insert(before.copy(pages = before.pages + other), page, shape())
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShapeEditPlan.replace(before.copy(pages = listOf(before.pages.single().copy(
                    elements = listOf(row(shape(), 2))))), page, changed(shape()))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShapeEditPlan.insert(before, page, shape().replace("[1,0,0,1,0,0]", "[2,0,0,2,0,0]"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ShapeEditPlan.insert(before.copy(revision = Long.MAX_VALUE), page, shape())
            }
        }
    }

    @Test fun busyQueueAndClosedSessionRejectWithoutGuardedWrite() = runBlocking {
        session {
            val before = snapshot()
            repo.gate = CompletableDeferred()
            repo.entered = CompletableDeferred()
            coordinator.enqueue(background())
            repo.entered!!.await()
            assertTrue(commands.insert(before, page, shape()) is ShapeCommandResult.Rejected)
            assertTrue(result(coordinator.submitShapeEdit(ShapeEditPlan.insert(before, page, shape()))) is EraserSessionCommitResult.Rejected)
            repo.gate!!.complete(Unit)
            saved(8)
            scope.cancel()
            assertTrue(commands.insert(snapshot(), page, shape()) is ShapeCommandResult.Rejected)
            assertEquals(0, repo.guarded)
        }
    }

    @Test fun staleOrChangedDisplayedPageAndReadFailureNeverReachCommit() = runBlocking {
        session {
            val before = snapshot()
            repo.revision = 8
            assertTrue(commands.insert(before, page, shape()) is ShapeCommandResult.Rejected)
            repo.revision = 7
            repo.rows += row(shape())
            assertTrue(commands.insert(before, page, shape()) is ShapeCommandResult.Rejected)
            repo.rows.clear()
            repo.failLoad = true
            assertTrue(commands.insert(before, page, shape()) is ShapeCommandResult.Rejected)
            assertEquals(0, repo.guarded)
            assertFalse(coordinator.shapeHistory.value.canUndo)
        }
    }

    @Test fun competingRevisionInsideGuardedCommitIsUnconfirmedAndCannotReplay() = runBlocking {
        session {
            val before = snapshot()
            val plan = ShapeEditPlan.insert(before, page, shape())
            repo.gate = CompletableDeferred(); repo.entered = CompletableDeferred()
            val pending = coordinator.submitShapeEdit(plan)
            repo.entered!!.await()
            repo.revision = 8
            repo.gate!!.complete(Unit)
            assertTrue(result(pending) is EraserSessionCommitResult.Unconfirmed)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            assertTrue(repo.rows.isEmpty())
            assertEquals(1, repo.guarded)
        }
    }

    @Test fun failureBeforeCommitCanRetryExplicitlyWithoutPrematureHistory() = runBlocking {
        session {
            repo.failBefore = true
            assertTrue(commands.insert(snapshot(), page, shape()) is ShapeCommandResult.Unconfirmed)
            assertEquals(7L, repo.revision)
            assertFalse(coordinator.shapeHistory.value.canUndo)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            assertEquals(SaveRetryResult.STARTED, coordinator.retry())
            saved(8)
            assertTrue(coordinator.shapeHistory.value.canUndo)
            assertEquals(2, repo.guarded)
            assertEquals(listOf(shapeId), repo.rows.map { it.id })
        }
    }

    @Test fun lostAcknowledgementRequiresReloadAndFreshSessionHasNoHistory() = runBlocking {
        session {
            repo.failAfter = true
            assertTrue(commands.insert(snapshot(), page, shape()) is ShapeCommandResult.Unconfirmed)
            assertEquals(8L, repo.revision)
            assertEquals(7L, coordinator.state.value.committedRevision)
            assertFalse(coordinator.shapeHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
            val fresh = NoteAutosaveCoordinator(note, repo, scope, 8)
            assertFalse(fresh.shapeHistory.value.canUndo)
            assertEquals(1, repo.guarded)
            assertEquals(shape(), repo.rows.single().envelopeJson)
        }
    }

    @Test fun cancellationBeforeAdmissionDoesNotWriteAndFrozenPlanSurvivesCallerListChanges() = runBlocking {
        session(listOf(row(shape()))) {
            val original = snapshot()
            repo.loadGate = CompletableDeferred(); repo.loadEntered = CompletableDeferred()
            val job = scope.async { commands.replace(original, page, changed(shape())) }
            repo.loadEntered!!.await()
            job.cancelAndJoin()
            repo.loadGate!!.complete(Unit)
            assertEquals(0, repo.guarded)
            assertEquals(shape(), repo.rows.single().envelopeJson)

            val mutableRows = original.pages.single().elements.toMutableList()
            val visible = original.copy(pages = listOf(original.pages.single().copy(elements = mutableRows)))
            repo.loadGate = CompletableDeferred(); repo.loadEntered = CompletableDeferred()
            val accepted = scope.async { commands.replace(visible, page, changed(shape())) }
            repo.loadEntered!!.await()
            mutableRows.clear()
            repo.loadGate!!.complete(Unit)
            assertEquals(ShapeCommandResult.Committed(8), accepted.await())
            assertEquals(EraserSessionCommitResult.Committed(9), result(coordinator.undoShapeEdit()))
            assertEquals(shape(), repo.rows.single().envelopeJson)
        }
    }

    @Test fun cancellingAnAwaiterDoesNotUndoAlreadyAdmittedWork() = runBlocking {
        session {
            val plan = ShapeEditPlan.insert(snapshot(), page, shape())
            repo.gate = CompletableDeferred(); repo.entered = CompletableDeferred()
            val pending = coordinator.submitShapeEdit(plan)
            repo.entered!!.await()
            pending.cancel()
            repo.gate!!.complete(Unit)
            saved(8)
            assertEquals(listOf(shapeId), repo.rows.map { it.id })
            assertTrue(coordinator.shapeHistory.value.canUndo)
        }
    }

    @Test fun cancelledCoordinatorDuringCommitNeverClaimsSuccessOrAllowsRetry() = runBlocking {
        session {
            val plan = ShapeEditPlan.insert(snapshot(), page, shape())
            repo.gate = CompletableDeferred(); repo.entered = CompletableDeferred()
            val pending = coordinator.submitShapeEdit(plan)
            repo.entered!!.await()
            scope.cancel()
            assertTrue(result(pending) is EraserSessionCommitResult.Unconfirmed)
            assertEquals(SavePhase.FAILED, coordinator.state.value.phase)
            repo.gate!!.complete(Unit)
            assertEquals(7L, repo.revision)
            assertFalse(coordinator.shapeHistory.value.canUndo)
            assertEquals(SaveRetryResult.RECONCILIATION_REQUIRED, coordinator.retry())
        }
    }

    @Test fun shapeAndEraserActionsShareChronologyAndTypedActionsCannotSkip() = runBlocking {
        session(listOf(stroke())) {
            val before = snapshot()
            val eraser = requireNotNull(EditorEraserWholeStrokeRemovalPlanner.plan(
                before, page, before.revision, before.pages.single().revision, listOf(strokeId)))
            assertEquals(EraserSessionCommitResult.Committed(8), result(coordinator.submitWholeStrokeRemoval(eraser)))
            assertEquals(ShapeCommandResult.Committed(9), commands.insert(snapshot(), page, shape()))
            assertTrue(result(coordinator.undoEraserEdit()) is EraserSessionCommitResult.Rejected)
            assertEquals(EraserSessionCommitResult.Committed(10), result(coordinator.undoShapeEdit()))
            assertEquals(EraserSessionCommitResult.Committed(11), result(coordinator.undoEraserEdit()))
            assertTrue(result(coordinator.redoShapeEdit()) is EraserSessionCommitResult.Rejected)
            assertEquals(EraserSessionCommitResult.Committed(12), result(coordinator.redoEraserEdit()))
            assertEquals(EraserSessionCommitResult.Committed(13), result(coordinator.redoShapeEdit()))
            assertEquals(listOf(shapeId), repo.rows.map { it.id })
            assertEquals(1, coordinator.eraserHistory.value.undoDepth)
            assertEquals(1, coordinator.shapeHistory.value.undoDepth)
        }
    }

    @Test fun unsupportedEditInvalidatesAllHistoryIncludingAnInflightShapeTransition() = runBlocking {
        session {
            repo.gate = CompletableDeferred(); repo.entered = CompletableDeferred()
            val pending = coordinator.submitShapeEdit(ShapeEditPlan.insert(snapshot(), page, shape()))
            repo.entered!!.await()
            coordinator.enqueue(background())
            repo.gate!!.complete(Unit)
            assertEquals(EraserSessionCommitResult.Committed(8), result(pending))
            saved(9)
            assertEquals(0, coordinator.shapeHistory.value.undoDepth)
            assertTrue(result(coordinator.undoShapeEdit()) is EraserSessionCommitResult.Rejected)
        }
    }

    @Test fun exhaustedRevisionDisablesHistoryAndRejectsUndoWithoutOverflow() = runBlocking {
        session {
            repo.revision = Long.MAX_VALUE - 1
            val exhausted = NoteAutosaveCoordinator(note, repo, scope, repo.revision)
            val boundary = NoteEditorShapeCommands(note, repo, exhausted)
            assertEquals(ShapeCommandResult.Committed(Long.MAX_VALUE),
                boundary.insert(snapshot(), page, shape()))
            assertFalse(exhausted.shapeHistory.value.canUndo)
            assertTrue(result(exhausted.undoShapeEdit()) is EraserSessionCommitResult.Rejected)
            assertEquals(Long.MAX_VALUE, repo.revision)
            assertEquals(1, repo.guarded)
        }
    }

    @Test fun newConfirmedShapeActionAfterUndoClearsRedo() = runBlocking {
        session {
            assertEquals(ShapeCommandResult.Committed(8), commands.insert(snapshot(), page, shape()))
            result(coordinator.undoShapeEdit())
            assertTrue(coordinator.shapeHistory.value.canRedo)
            assertEquals(ShapeCommandResult.Committed(10), commands.insert(snapshot(), page, shape(CanonicalShapeKind.LINE)))
            assertFalse(coordinator.shapeHistory.value.canRedo)
            assertTrue(result(coordinator.redoShapeEdit()) is EraserSessionCommitResult.Rejected)
        }
    }
}
