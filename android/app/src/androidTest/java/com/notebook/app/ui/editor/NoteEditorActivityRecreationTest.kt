
package com.notebook.app.ui.editor

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.MainActivity
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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteEditorActivityRecreationTest {

    private class BlockingRepository : DocumentRepository {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        @Volatile
        var revision = 7L

        @Volatile
        var commitCalls = 0

        override suspend fun load(
            noteId: String,
        ): StoredNoteContent = StoredNoteContent(
            noteId = noteId,
            revision = revision,
            pages = emptyList(),
        )

        override suspend fun commit(
            noteId: String,
            edit: DocumentEdit,
        ): DocumentCommit {
            entered.complete(Unit)

            // Hold the save until the test releases it.
            release.await()

            commitCalls++
            revision++

            return DocumentCommit(
                noteId = noteId,
                revision = revision,
                updatedAtMillis = 200L,
            )
        }
    }

    @Test
    fun blockedSaveSurvivesActualActivityRecreation() =
        runBlocking {
            val repository = BlockingRepository()
            val noteId = "activity-recreation-note"

            val scenario = ActivityScenario.launch(
                MainActivity::class.java
            )

            lateinit var originalViewModel:
                NoteEditorSessionViewModel

            lateinit var originalCoordinator:
                NoteAutosaveCoordinator

            try {
                // Acquire an Activity-scoped ViewModel.
                scenario.onActivity { activity ->
                    originalViewModel = ViewModelProvider(
                        activity
                    )[NoteEditorSessionViewModel::class.java]

                    originalCoordinator =
                        originalViewModel.getOrCreateCoordinator(
                            noteId = noteId,
                            repository = repository,
                            initialRevision = 7L,
                        )

                    originalCoordinator.enqueue(
                        DocumentEdit.ChangeBackground(
                            pageId = "test-page",
                            backgroundJson =
                                """{"type":"blank","color":"#FFF9E9"}""",
                        )
                    )
                }

                // The fake commit has started but cannot finish.
                withTimeout(5_000) {
                    repository.entered.await()
                }

                assertEquals(
                    SavePhase.SAVING,
                    originalCoordinator.state.value.phase,
                )
                assertEquals(7L, repository.revision)

                // This genuinely recreates MainActivity.
                scenario.recreate()

                scenario.onActivity { recreatedActivity ->
                    val retainedViewModel =
                        ViewModelProvider(
                            recreatedActivity
                        )[NoteEditorSessionViewModel::class.java]

                    assertSame(
                        originalViewModel,
                        retainedViewModel,
                    )

                    val retainedCoordinator =
                        retainedViewModel.existingCoordinator(noteId)

                    assertSame(
                        originalCoordinator,
                        retainedCoordinator,
                    )

                    assertEquals(
                        SavePhase.SAVING,
                        retainedCoordinator!!.state.value.phase,
                    )
                }

                // Complete the original operation.
                repository.release.complete(Unit)

                val saved = withTimeout(5_000) {
                    originalCoordinator.state.first {
                        it.phase == SavePhase.SAVED &&
                            it.committedRevision == 8L
                    }
                }

                assertEquals(0, saved.pendingCommandCount)
                assertEquals(8L, repository.revision)
                assertEquals(1, repository.commitCalls)
            } finally {
                repository.release.complete(Unit)
                scenario.close()
            }
        }
}
