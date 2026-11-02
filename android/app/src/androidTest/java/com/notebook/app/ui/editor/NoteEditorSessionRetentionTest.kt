
package com.notebook.app.ui.editor

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.runner.RunWith
import org.junit.Test

@RunWith(AndroidJUnit4::class)
class NoteEditorSessionRetentionTest {

    private class BlockingRepository : DocumentRepository {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var revision = 7L

        @Volatile
        var commitCalls = 0

        override suspend fun load(
            noteId: String
        ): StoredNoteContent {
            return StoredNoteContent(
                noteId = noteId,
                revision = revision,
                pages = emptyList()
            )
        }

        override suspend fun commit(
            noteId: String,
            edit: DocumentEdit
        ): DocumentCommit {
            entered.complete(Unit)

            // Simulate an unfinished database write.
            release.await()

            commitCalls++
            revision = 8L

            return DocumentCommit(
                noteId = noteId,
                revision = revision,
                updatedAtMillis = 200L
            )
        }
    }

    @Test
    fun retainedViewModelStoreKeepsInFlightCoordinator() =
        runBlocking {
            val store = ViewModelStore()
            val repository = BlockingRepository()
            val noteId = "retained-test-note"

            try {
                // First screen owner requests the session.
                val originalViewModel = ViewModelProvider(
                    store,
                    ViewModelProvider.NewInstanceFactory()
                )[NoteEditorSessionViewModel::class.java]

                val originalCoordinator =
                    originalViewModel.getOrCreateCoordinator(
                        noteId = noteId,
                        repository = repository,
                        initialRevision = 7L
                    )

                originalCoordinator.enqueue(
                    DocumentEdit.ChangeBackground(
                        pageId = "test-page",
                        backgroundJson =
                            """{"type":"blank","color":"#FFF9E9"}"""
                    )
                )

                // The save has started but cannot finish.
                withTimeout(5_000) {
                    repository.entered.await()
                }

                assertEquals(
                    SavePhase.SAVING,
                    originalCoordinator.state.value.phase
                )

                // Simulate a new UI caller using the retained
                // ViewModelStore after recreation.
                val recreatedViewModel = ViewModelProvider(
                    store,
                    ViewModelProvider.NewInstanceFactory()
                )[NoteEditorSessionViewModel::class.java]

                assertSame(
                    originalViewModel,
                    recreatedViewModel
                )

                val retainedCoordinator =
                    recreatedViewModel.getOrCreateCoordinator(
                        noteId = noteId,
                        repository = repository,
                        initialRevision = 7L
                    )

                assertSame(
                    originalCoordinator,
                    retainedCoordinator
                )

                assertEquals(
                    SavePhase.SAVING,
                    retainedCoordinator.state.value.phase
                )

                // Finish the original in-flight save.
                repository.release.complete(Unit)

                val saved = withTimeout(5_000) {
                    retainedCoordinator.state.first {
                        it.phase == SavePhase.SAVED &&
                            it.committedRevision == 8L
                    }
                }

                assertEquals(0, saved.pendingCommandCount)
                assertEquals(8L, repository.revision)
                assertEquals(1, repository.commitCalls)
            } finally {
                repository.release.complete(Unit)
                store.clear()
            }
        }
}
