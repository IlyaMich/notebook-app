package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.ink.EditorEraserWholeStrokeRemovalPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import java.util.ArrayDeque

enum class SaveRetryResult {
    STARTED,
    NOT_NEEDED,
    RECONCILIATION_REQUIRED,
}

/** The result describes the FIRST attempt, not a later explicit retry. */
sealed interface EraserSessionCommitResult {
    data class Committed(val revision: Long) : EraserSessionCommitResult
    data class Rejected(val reason: String) : EraserSessionCommitResult
    /** The write may have committed. Inspect save state before any new action. */
    data class Unconfirmed(val reason: String) : EraserSessionCommitResult
}

/** Only eraser operations have history here; EDT-18 will provide general undo. */
data class EraserSessionHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
)

/**
 * Serializes completed document edits for one active note. The caller owns
 * [scope]; pending commands and eraser history are session-only, not durable.
 * Existing pen/page edits still use [enqueue] and the ordinary commit path.
 *
 * Whole-stroke eraser commands and their inverses share the SAME queue, but
 * use conditional repository commits. They are admitted only when the queue
 * is idle, so they cannot overtake pending pen strokes.
 */
class NoteAutosaveCoordinator(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val scope: CoroutineScope,
    initialRevision: Long,
) {
    private data class EraserHistoryEntry(
        val forward: DocumentEdit.RemoveElements,
        val inverse: DocumentEdit.RestoreRemovedStrokes,
    )

    private sealed interface HistoryTransition {
        val entry: EraserHistoryEntry
        val epoch: Long

        data class Remove(
            override val entry: EraserHistoryEntry,
            override val epoch: Long,
        ) : HistoryTransition

        data class Undo(
            override val entry: EraserHistoryEntry,
            override val epoch: Long,
        ) : HistoryTransition

        data class Redo(
            override val entry: EraserHistoryEntry,
            override val epoch: Long,
        ) : HistoryTransition
    }

    private data class PendingCommand(
        val edit: DocumentEdit,
        val expectedRevision: Long? = null,
        val transition: HistoryTransition? = null,
        val acknowledgement: CompletableDeferred<EraserSessionCommitResult>? = null,
    )

    private val lock = Any()
    private val retryMutex = Mutex()
    private val tracker = NoteSaveStateTracker(initialRevision)
    private val pending = ArrayDeque<PendingCommand>()
    private val undoStack = ArrayDeque<EraserHistoryEntry>()
    private val redoStack = ArrayDeque<EraserHistoryEntry>()
    private var historyEpoch = 0L

    private val mutableState = MutableStateFlow(tracker.state)
    val state: StateFlow<NoteSaveState> = mutableState.asStateFlow()

    private val mutableHistory = MutableStateFlow(EraserSessionHistoryState())
    val eraserHistory: StateFlow<EraserSessionHistoryState> = mutableHistory.asStateFlow()

    private var workerRunning = false
    private var needsReconciliation = false

    /**
     * Returns after queuing in memory, NOT after a durable commit.
     * Invalidate eraser-only history on any non-eraser command, even if the
     * command later fails; its inverse has not been registered by EDT-18.
     */
    fun enqueue(edit: DocumentEdit) {
        // Eraser restoration must never enter the unguarded autosave path.
        require(edit !is DocumentEdit.RestoreRemovedStrokes &&
            edit !is DocumentEdit.RemoveElements) {
            "Stroke removal/restoration requires a guarded eraser session command"
        }
        val startWorker = synchronized(lock) {
            clearEraserHistory()
            pending.addLast(PendingCommand(edit))
            publish(tracker.onCommandQueued())
            val start = !workerRunning && !needsReconciliation
            if (start) workerRunning = true
            publishHistory()
            start
        }
        if (startWorker) launchDrain()
    }

    /**
     * A complete, unambiguous gesture plan is required. This call queues ONE
     * guarded transaction only while autosave is idle. The returned Deferred
     * resolves after Room confirms the commit, rejects admission, or reports an
     * unconfirmed write; it is not a cancellation handle for a queued edit.
     * Call only after the gesture has completed, never per pointer sample.
     */
    internal fun submitWholeStrokeRemoval(
        plan: EditorEraserWholeStrokeRemovalPlanner.Plan,
    ): Deferred<EraserSessionCommitResult> {
        require(plan.noteId == noteId && plan.pageId.isNotBlank()) {
            "Eraser plan belongs to another note or an invalid page"
        }
        require(plan.removed.isNotEmpty()) { "Eraser plan has no strokes" }
        val removedIds = plan.removed.map { it.element.id }
        require(plan.forwardEdit.pageId == plan.pageId &&
            plan.forwardEdit.ids == removedIds &&
            plan.inverseEdit.pageId == plan.pageId &&
            plan.inverseEdit.envelopes == plan.removed.map { it.element.envelopeJson } &&
            plan.inverseEdit.idsInOrder == plan.originalElementIdsInOrder &&
            removedIds.size == removedIds.toSet().size
        ) { "Eraser plan has inconsistent inverse data" }
        val entry = EraserHistoryEntry(
            forward = plan.forwardEdit.copy(ids = plan.forwardEdit.ids.toList()),
            inverse = plan.inverseEdit.copy(
                envelopes = plan.inverseEdit.envelopes.toList(),
                idsInOrder = plan.inverseEdit.idsInOrder.toList(),
            ),
        )
        val acknowledgement = CompletableDeferred<EraserSessionCommitResult>()
        val accepted = synchronized(lock) {
            if (!scope.isActive) {
                // An editor that has already ended must not accept new destructive work.
                acknowledgement.complete(EraserSessionCommitResult.Rejected("Editor session is closed"))
                false
            } else if (!idleForGuardedEdit()) {
                acknowledgement.complete(EraserSessionCommitResult.Rejected("Autosave is not idle"))
                false
            } else if (tracker.state.committedRevision != plan.expectedNoteRevision) {
                acknowledgement.complete(EraserSessionCommitResult.Rejected("Eraser snapshot is stale"))
                false
            } else {
                admitGuarded(
                    edit = entry.forward,
                    expectedRevision = plan.expectedNoteRevision,
                    transition = HistoryTransition.Remove(entry, historyEpoch),
                    acknowledgement = acknowledgement,
                )
                true
            }
        }
        if (accepted) launchDrain()
        return acknowledgement
    }

    /** Only the most recent successfully committed eraser action can be undone. */
    fun undoWholeStroke(): Deferred<EraserSessionCommitResult> = submitHistoryAction(undo = true)

    /** Redo the most recently *confirmed* eraser undo. */
    fun redoWholeStroke(): Deferred<EraserSessionCommitResult> = submitHistoryAction(undo = false)

    private fun submitHistoryAction(undo: Boolean): Deferred<EraserSessionCommitResult> {
        val acknowledgement = CompletableDeferred<EraserSessionCommitResult>()
        val accepted = synchronized(lock) {
            val entry = if (undo) undoStack.lastOrNull() else redoStack.lastOrNull()
            if (!scope.isActive) {
                acknowledgement.complete(EraserSessionCommitResult.Rejected("Editor session is closed"))
                false
            } else if (!idleForGuardedEdit() || entry == null) {
                acknowledgement.complete(EraserSessionCommitResult.Rejected("No available eraser ${if (undo) "undo" else "redo"}"))
                false
            } else {
                val expected = tracker.state.committedRevision
                admitGuarded(
                    edit = if (undo) entry.inverse else entry.forward,
                    expectedRevision = expected,
                    transition = if (undo) HistoryTransition.Undo(entry, historyEpoch)
                                 else HistoryTransition.Redo(entry, historyEpoch),
                    acknowledgement = acknowledgement,
                )
                true
            }
        }
        if (accepted) launchDrain()
        return acknowledgement
    }

    /** Called with [lock] held. */
    private fun idleForGuardedEdit(): Boolean =
        scope.isActive && !workerRunning && !needsReconciliation && pending.isEmpty() &&
            tracker.state.phase == SavePhase.SAVED

    /** Called with [lock] held. */
    private fun admitGuarded(
        edit: DocumentEdit,
        expectedRevision: Long,
        transition: HistoryTransition,
        acknowledgement: CompletableDeferred<EraserSessionCommitResult>,
    ) {
        check(idleForGuardedEdit())
        check(expectedRevision >= 0L && expectedRevision < Long.MAX_VALUE)
        pending.addLast(PendingCommand(edit, expectedRevision, transition, acknowledgement))
        publish(tracker.onCommandQueued())
        workerRunning = true
        publishHistory()
    }

    /**
     * Retry only if the last *confirmed* revision still matches storage.
     * If a write succeeded but its acknowledgement was lost, a changed
     * revision is ambiguous without durable command IDs. Never replay it.
     */
    suspend fun retry(): SaveRetryResult {
        retryMutex.lock()
        try {
            val expectedRevision = synchronized(lock) {
                if (!needsReconciliation || workerRunning || pending.isEmpty()) {
                    return SaveRetryResult.NOT_NEEDED
                }
                tracker.state.committedRevision
            }
            // A cancelled ViewModel scope cannot own a retry. Require a fresh
            // editor session and a freshly loaded snapshot instead.
            if (!scope.isActive) return SaveRetryResult.RECONCILIATION_REQUIRED
            val actualRevision = try {
                repository.load(noteId).revision
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return SaveRetryResult.RECONCILIATION_REQUIRED
            }
            val startWorker = synchronized(lock) {
                if (workerRunning || !needsReconciliation || !scope.isActive) {
                    false
                } else if (actualRevision != expectedRevision ||
                    tracker.state.committedRevision != expectedRevision) {
                    false
                } else {
                    needsReconciliation = false
                    workerRunning = true
                    publishHistory()
                    true
                }
            }
            if (!startWorker) return SaveRetryResult.RECONCILIATION_REQUIRED
            launchDrain()
            return SaveRetryResult.STARTED
        } finally {
            retryMutex.unlock()
        }
    }

    private fun launchDrain() {
        // invokeOnCompletion also handles an already-cancelled scope where
        // drain() never started, so acknowledgement cannot hang indefinitely.
        scope.launch { drain() }.invokeOnCompletion { cause ->
            if (cause != null) synchronized(lock) {
                if (workerRunning && pending.isNotEmpty()) {
                    val message = cause.message ?: "Autosave worker stopped before confirmation"
                    publish(tracker.onWorkerUnavailable(message))
                    needsReconciliation = true
                    workerRunning = false
                    pending.first().acknowledgement?.complete(
                        EraserSessionCommitResult.Unconfirmed(message)
                    )
                    publishHistory()
                }
            }
        }
    }

    private suspend fun drain() {
        while (true) {
            val command = synchronized(lock) {
                if (pending.isEmpty()) {
                    workerRunning = false
                    publishHistory()
                    null
                } else {
                    publish(tracker.onSaveStarted())
                    pending.first()
                }
            } ?: return

            try {
                val result = if (command.expectedRevision == null) {
                    repository.commit(noteId, command.edit)
                } else {
                    repository.commitIfRevision(noteId, command.expectedRevision, command.edit)
                }
                val finished = synchronized(lock) {
                    check(result.noteId == noteId) { "Commit returned the wrong note" }
                    check(result.revision >= tracker.state.committedRevision) {
                        "Commit revision moved backwards"
                    }
                    // Every guarded eraser edit is a nonempty, changed command.
                    // A successful no-op must not create an undo entry.
                    if (command.expectedRevision != null) {
                        check(result.revision == command.expectedRevision + 1L) {
                            "Guarded eraser command was not confirmed as a changed revision"
                        }
                    }
                    publish(tracker.onCommitSucceeded(result.revision))
                    check(pending.removeFirst() === command)
                    applyHistoryTransition(command.transition)
                    // Publish an idle, undo-ready state BEFORE acknowledging a
                    // final queued command. This eliminates a UI race where
                    // await() returns while canUndo still reads false.
                    val queueEmpty = pending.isEmpty()
                    if (queueEmpty) workerRunning = false
                    publishHistory()
                    command.acknowledgement?.complete(
                        EraserSessionCommitResult.Committed(result.revision)
                    )
                    queueEmpty
                }
                if (finished) return
            } catch (exception: Exception) {
                synchronized(lock) {
                    val message = exception.message ?: "Unable to confirm save"
                    publish(tracker.onSaveFailed(message))
                    needsReconciliation = true
                    workerRunning = false
                    command.acknowledgement?.complete(
                        EraserSessionCommitResult.Unconfirmed(message)
                    )
                    publishHistory()
                }
                if (exception is CancellationException) throw exception
                return
            }
        }
    }

    /** Called only after durable guarded commit succeeds, with [lock] held. */
    private fun applyHistoryTransition(transition: HistoryTransition?) {
        if (transition == null || historyEpoch != transition.epoch) return
        when (transition) {
            is HistoryTransition.Remove -> {
                redoStack.clear()
                undoStack.addLast(transition.entry)
            }
            is HistoryTransition.Undo -> {
                if (undoStack.lastOrNull() == transition.entry) {
                    undoStack.removeLast()
                    redoStack.addLast(transition.entry)
                } else {
                    clearEraserHistory()
                }
            }
            is HistoryTransition.Redo -> {
                if (redoStack.lastOrNull() == transition.entry) {
                    redoStack.removeLast()
                    undoStack.addLast(transition.entry)
                } else {
                    clearEraserHistory()
                }
            }
        }
    }

    /** Called with [lock] held. */
    private fun clearEraserHistory() {
        historyEpoch++
        undoStack.clear()
        redoStack.clear()
    }

    /** Called with [lock] held. */
    private fun publishHistory() {
        val idle = idleForGuardedEdit()
        mutableHistory.value = EraserSessionHistoryState(
            canUndo = idle && undoStack.isNotEmpty(),
            canRedo = idle && redoStack.isNotEmpty(),
            undoDepth = undoStack.size,
            redoDepth = redoStack.size,
        )
    }

    private fun publish(newState: NoteSaveState) {
        mutableState.value = newState
    }
}
