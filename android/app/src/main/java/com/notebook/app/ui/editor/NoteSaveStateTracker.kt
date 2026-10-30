
package com.notebook.app.ui.editor

/**
 * User-visible state of the current note's local persistence.
 */
enum class SavePhase {
    PENDING,
    SAVING,
    SAVED,
    FAILED,
}

/**
 * A snapshot of save progress.
 *
 * committedRevision is the last confirmed database revision,
 * not a prediction of the next revision.
 */
data class NoteSaveState(
    val phase: SavePhase,
    val committedRevision: Long,
    val pendingCommandCount: Int,
    val failureMessage: String? = null,
)

/**
 * Tracks save acknowledgements for one editing session.
 *
 * This is a synchronous state machine, NOT a persistence
 * queue. The future coordinator owns serialization and
 * calls these methods after repository operations.
 *
 * Create it only after the note has loaded successfully.
 */
class NoteSaveStateTracker(initialRevision: Long) {

    init {
        require(initialRevision >= 0L)
    }

    var state = NoteSaveState(
        phase = SavePhase.SAVED,
        committedRevision = initialRevision,
        pendingCommandCount = 0,
    )
        private set

    /** A completed edit has entered the pending queue. */
    fun onCommandQueued(): NoteSaveState {
        check(state.pendingCommandCount < Int.MAX_VALUE)

        val phase = when (state.phase) {
            SavePhase.FAILED -> SavePhase.FAILED
            SavePhase.SAVING -> SavePhase.SAVING
            else -> SavePhase.PENDING
        }

        state = state.copy(
            phase = phase,
            pendingCommandCount = state.pendingCommandCount + 1,
        )
        return state
    }

    /** The coordinator started writing one pending command. */
    fun onSaveStarted(): NoteSaveState {
        check(state.pendingCommandCount > 0)
        check(state.phase != SavePhase.SAVING)

        state = state.copy(
            phase = SavePhase.SAVING,
            failureMessage = null,
        )
        return state
    }

    /**
     * Called only after commit() returns successfully.
     *
     * A successful no-op may retain the existing revision.
     * Newer queued commands must remain pending.
     */
    fun onCommitSucceeded(revision: Long): NoteSaveState {
        check(state.phase == SavePhase.SAVING)
        require(revision >= state.committedRevision)

        val remaining = state.pendingCommandCount - 1

        state = NoteSaveState(
            phase = if (remaining == 0) {
                SavePhase.SAVED
            } else {
                SavePhase.PENDING
            },
            committedRevision = revision,
            pendingCommandCount = remaining,
        )
        return state
    }

    /** Failed command remains pending for recovery or retry. */
    fun onSaveFailed(message: String): NoteSaveState {
        check(state.phase == SavePhase.SAVING)
        require(message.isNotBlank())

        state = state.copy(
            phase = SavePhase.FAILED,
            failureMessage = message,
        )
        return state
    }
}
