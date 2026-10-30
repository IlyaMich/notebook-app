
package com.notebook.app.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteSaveStateTrackerTest {

    @Test
    fun initialStateReflectsLoadedRevision() {
        val tracker = NoteSaveStateTracker(7)

        assertEquals(SavePhase.SAVED, tracker.state.phase)
        assertEquals(7L, tracker.state.committedRevision)
        assertEquals(0, tracker.state.pendingCommandCount)
    }

    @Test
    fun queuedEditIsNotReportedAsSaved() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()

        assertEquals(SavePhase.PENDING, tracker.state.phase)
        assertEquals(7L, tracker.state.committedRevision)
        assertEquals(1, tracker.state.pendingCommandCount)

        tracker.onSaveStarted()

        assertEquals(SavePhase.SAVING, tracker.state.phase)
        assertEquals(7L, tracker.state.committedRevision)
    }

    @Test
    fun successfulCommitAcknowledgesRevision() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()
        tracker.onSaveStarted()
        tracker.onCommitSucceeded(8)

        assertEquals(SavePhase.SAVED, tracker.state.phase)
        assertEquals(8L, tracker.state.committedRevision)
        assertEquals(0, tracker.state.pendingCommandCount)
    }

    @Test
    fun newerPendingEditPreventsFalseSavedState() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()
        tracker.onSaveStarted()

        // Another edit completes while the first is saving.
        tracker.onCommandQueued()

        tracker.onCommitSucceeded(8)

        assertEquals(SavePhase.PENDING, tracker.state.phase)
        assertEquals(8L, tracker.state.committedRevision)
        assertEquals(1, tracker.state.pendingCommandCount)

        tracker.onSaveStarted()
        tracker.onCommitSucceeded(9)

        assertEquals(SavePhase.SAVED, tracker.state.phase)
        assertEquals(9L, tracker.state.committedRevision)
    }

    @Test
    fun failedCommitKeepsPendingEditAndPreviousRevision() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()
        tracker.onSaveStarted()
        tracker.onSaveFailed("Unable to save note")

        assertEquals(SavePhase.FAILED, tracker.state.phase)
        assertEquals(7L, tracker.state.committedRevision)
        assertEquals(1, tracker.state.pendingCommandCount)
        assertEquals(
            "Unable to save note",
            tracker.state.failureMessage,
        )

        // A new edit must not conceal the previous failure.
        tracker.onCommandQueued()

        assertEquals(SavePhase.FAILED, tracker.state.phase)
        assertEquals(2, tracker.state.pendingCommandCount)
    }

    @Test
    fun retryCanRecoverFromFailure() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()
        tracker.onSaveStarted()
        tracker.onSaveFailed("Storage unavailable")

        tracker.onSaveStarted()
        tracker.onCommitSucceeded(8)

        assertEquals(SavePhase.SAVED, tracker.state.phase)
        assertEquals(8L, tracker.state.committedRevision)
        assertEquals(0, tracker.state.pendingCommandCount)
        assertNull(tracker.state.failureMessage)
    }

    @Test
    fun successfulNoOpDoesNotInventRevision() {
        val tracker = NoteSaveStateTracker(7)

        tracker.onCommandQueued()
        tracker.onSaveStarted()
        tracker.onCommitSucceeded(7)

        assertEquals(SavePhase.SAVED, tracker.state.phase)
        assertEquals(7L, tracker.state.committedRevision)
        assertEquals(0, tracker.state.pendingCommandCount)
    }
}
