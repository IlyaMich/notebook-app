
package com.notebook.app.ui.editor

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAutosaveCoordinatorTest {

    private val noteId = "test-note"
    private val edit = DocumentEdit.ChangeBackground(
        pageId = "test-page",
        backgroundJson = """{"type":"blank"}""",
    )

    private class FakeRepository(
        private val noteId: String,
    ) : DocumentRepository {

        @Volatile var revision = 7L
        @Volatile var commitCalls = 0
        @Volatile var failBeforeCommit = false
        @Volatile var failAfterCommit = false

        var commitGate: CompletableDeferred<Unit>? = null
        @Volatile
        var commitEntered: CompletableDeferred<Unit>? = null

        override suspend fun load(
            noteId: String,
        ): StoredNoteContent {
            return StoredNoteContent(
                noteId = noteId,
                revision = revision,
                pages = emptyList(),
            )
        }

        override suspend fun commit(
            noteId: String,
            edit: DocumentEdit,
        ): DocumentCommit {
            commitCalls++
            commitEntered?.complete(Unit)
            commitGate?.await()

            if (failBeforeCommit) {
                failBeforeCommit = false
                throw IllegalStateException("Storage unavailable")
            }

            revision++

            if (failAfterCommit) {
                failAfterCommit = false
                throw IllegalStateException("Commit outcome unknown")
            }

            return DocumentCommit(
                noteId = noteId,
                revision = revision,
                updatedAtMillis = 200L,
            )
        }
    }

    private suspend fun awaitPhase(
        coordinator: NoteAutosaveCoordinator,
        phase: SavePhase,
    ): NoteSaveState = withTimeout(5_000) {
        coordinator.state.first { it.phase == phase }
    }

    @Test
    fun successfulCommitPublishesSavedRevision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = FakeRepository(noteId)
            val coordinator = NoteAutosaveCoordinator(
                noteId, repository, scope, 7L,
            )

            coordinator.enqueue(edit)

            val saved = withTimeout(5_000) {
                coordinator.state.first {
                    it.phase == SavePhase.SAVED &&
                        it.committedRevision == 8L
                }
            }

            assertEquals(0, saved.pendingCommandCount)
            assertEquals(8L, repository.revision)
            assertEquals(1, repository.commitCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queuedEditPreventsPrematureSavedState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = FakeRepository(noteId)
            val gate = CompletableDeferred<Unit>()
            repository.commitGate = gate

            val coordinator = NoteAutosaveCoordinator(
                noteId, repository, scope, 7L,
            )

            coordinator.enqueue(edit)
            awaitPhase(coordinator, SavePhase.SAVING)

            coordinator.enqueue(edit)

            assertEquals(
                2,
                coordinator.state.value.pendingCommandCount,
            )
            assertEquals(
                SavePhase.SAVING,
                coordinator.state.value.phase,
            )

            gate.complete(Unit)

            val saved = withTimeout(5_000) {
                coordinator.state.first {
                    it.phase == SavePhase.SAVED &&
                        it.committedRevision == 9L
                }
            }

            assertEquals(0, saved.pendingCommandCount)
            assertEquals(2, repository.commitCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedWriteCanRetryAfterRevisionVerification() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = FakeRepository(noteId)
            repository.failBeforeCommit = true

            val coordinator = NoteAutosaveCoordinator(
                noteId, repository, scope, 7L,
            )

            coordinator.enqueue(edit)

            val failed = awaitPhase(coordinator, SavePhase.FAILED)
            assertEquals(7L, failed.committedRevision)
            assertEquals(1, failed.pendingCommandCount)

            assertEquals(
                SaveRetryResult.STARTED,
                coordinator.retry(),
            )

            val saved = withTimeout(5_000) {
                coordinator.state.first {
                    it.phase == SavePhase.SAVED &&
                        it.committedRevision == 8L
                }
            }

            assertEquals(0, saved.pendingCommandCount)
            assertEquals(2, repository.commitCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun uncertainCommitOutcomeBlocksAutomaticRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val repository = FakeRepository(noteId)
            repository.failAfterCommit = true

            val coordinator = NoteAutosaveCoordinator(
                noteId, repository, scope, 7L,
            )

            coordinator.enqueue(edit)

            val failed = awaitPhase(coordinator, SavePhase.FAILED)

            assertEquals(7L, failed.committedRevision)
            assertEquals(8L, repository.revision)

            val retryResult = coordinator.retry()

            assertEquals(
                SaveRetryResult.RECONCILIATION_REQUIRED,
                retryResult,
            )
            assertEquals(1, repository.commitCalls)
            assertTrue(
                coordinator.state.value.phase == SavePhase.FAILED
            )
        } finally {
            scope.cancel()
        }
    }


    @Test
    fun failureBlocksQueueUntilExplicitRetry() = runBlocking {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default
        )

        try {
            val repository = FakeRepository(noteId)
            repository.failBeforeCommit = true

            val coordinator = NoteAutosaveCoordinator(
                noteId, repository, scope, 7L
            )

            // First command fails before persistence.
            coordinator.enqueue(edit)

            val failed = awaitPhase(
                coordinator, SavePhase.FAILED
            )

            assertEquals(7L, failed.committedRevision)
            assertEquals(1, failed.pendingCommandCount)
            assertEquals(1, repository.commitCalls)

            // A second completed edit arrives while saving is failed.
            coordinator.enqueue(edit)

            assertEquals(
                SavePhase.FAILED,
                coordinator.state.value.phase
            )
            assertEquals(
                2,
                coordinator.state.value.pendingCommandCount
            )
            assertEquals(1, repository.commitCalls)

            // A verified retry should process both commands.
            assertEquals(
                SaveRetryResult.STARTED,
                coordinator.retry()
            )

            val saved = withTimeout(5_000) {
                coordinator.state.first {
                    it.phase == SavePhase.SAVED &&
                        it.committedRevision == 9L
                }
            }

            assertEquals(0, saved.pendingCommandCount)
            assertEquals(9L, repository.revision)

            // One failed attempt, then two successful commits.
            assertEquals(3, repository.commitCalls)
        } finally {
            scope.cancel()
        }
    }


    @Test
    fun cancellationDuringBlockedCommitNeverReportsSaved() =
        runBlocking {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )

            val repository = FakeRepository(noteId)
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()

            repository.commitEntered = entered
            repository.commitGate = gate

            try {
                val coordinator = NoteAutosaveCoordinator(
                    noteId, repository, scope, 7L
                )

                coordinator.enqueue(edit)

                // Wait deterministically until commit() starts.
                withTimeout(5_000) {
                    entered.await()
                }

                assertEquals(
                    SavePhase.SAVING,
                    coordinator.state.value.phase
                )
                assertEquals(7L, repository.revision)

                // Cancel before the fake repository commits.
                scope.cancel()

                val failed = awaitPhase(
                    coordinator, SavePhase.FAILED
                )

                assertEquals(7L, failed.committedRevision)
                assertEquals(1, failed.pendingCommandCount)
                assertEquals(7L, repository.revision)
                assertEquals(1, repository.commitCalls)
            } finally {
                scope.cancel()
                gate.complete(Unit)
            }
        }
}
