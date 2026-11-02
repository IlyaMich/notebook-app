
package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.repository.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

enum class SaveRetryResult {
    STARTED,
    NOT_NEEDED,
    RECONCILIATION_REQUIRED,
}

/**
 * Serializes completed document edits for one active note.
 *
 * The caller owns the CoroutineScope. Pending commands are
 * kept in memory, not persisted in a separate journal.
 *
 * Create one coordinator per active note editing session.
 */
class NoteAutosaveCoordinator(
    private val noteId: String,
    private val repository: DocumentRepository,
    private val scope: CoroutineScope,
    initialRevision: Long,
) {
    private val lock = Any()
    private val retryMutex = Mutex()
    private val tracker = NoteSaveStateTracker(initialRevision)
    private val pending = ArrayDeque<DocumentEdit>()

    private val mutableState = MutableStateFlow(tracker.state)
    val state: StateFlow<NoteSaveState> = mutableState.asStateFlow()

    private var workerRunning = false
    private var needsReconciliation = false

    /**
     * Enqueue a completed editor command.
     *
     * Returns after the command is queued in memory,
     * not after SQLite persistence.
     */
    fun enqueue(edit: DocumentEdit) {
        val startWorker = synchronized(lock) {
            pending.addLast(edit)
            publish(tracker.onCommandQueued())

            if (!workerRunning && !needsReconciliation) {
                workerRunning = true
                true
            } else {
                false
            }
        }

        if (startWorker) {
            scope.launch { drain() }
        }
    }

    /**
     * Retry after checking whether the previous command
     * might already have committed.
     *
     * A changed revision is ambiguous without persisted
     * command IDs. Never blindly replay in that case.
     */
    suspend fun retry(): SaveRetryResult {
        retryMutex.lock()

        try {
            val expectedRevision = synchronized(lock) {
                if (!needsReconciliation ||
                    workerRunning ||
                    pending.isEmpty()
                ) {
                    return SaveRetryResult.NOT_NEEDED
                }

                tracker.state.committedRevision
            }

            val actualRevision = try {
                repository.load(noteId).revision
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                return SaveRetryResult.RECONCILIATION_REQUIRED
            }

            val startWorker = synchronized(lock) {
                if (workerRunning || !needsReconciliation) {
                    false
                } else if (
                    actualRevision != expectedRevision ||
                    tracker.state.committedRevision != expectedRevision
                ) {
                    false
                } else {
                    needsReconciliation = false
                    workerRunning = true
                    true
                }
            }

            if (!startWorker) {
                return SaveRetryResult.RECONCILIATION_REQUIRED
            }

            scope.launch { drain() }
            return SaveRetryResult.STARTED
        } finally {
            retryMutex.unlock()
        }
    }

    private suspend fun drain() {
        while (true) {
            val edit = synchronized(lock) {
                if (pending.isEmpty()) {
                    workerRunning = false
                    null
                } else {
                    publish(tracker.onSaveStarted())
                    pending.first()
                }
            } ?: return

            try {
                val result = repository.commit(noteId, edit)

                synchronized(lock) {
                    check(result.noteId == noteId) {
                        "Commit returned the wrong note"
                    }

                    check(
                        result.revision >= tracker.state.committedRevision
                    ) {
                        "Commit revision moved backwards"
                    }

                    publish(
                        tracker.onCommitSucceeded(result.revision)
                    )
                    pending.removeFirst()
                }
            } catch (exception: Exception) {
                synchronized(lock) {
                    publish(
                        tracker.onSaveFailed(
                            exception.message
                                ?: "Unable to confirm save"
                        )
                    )
                    needsReconciliation = true
                    workerRunning = false
                }

                if (exception is CancellationException) {
                    throw exception
                }

                return
            }
        }
    }

    private fun publish(newState: NoteSaveState) {
        mutableState.value = newState
    }
}
