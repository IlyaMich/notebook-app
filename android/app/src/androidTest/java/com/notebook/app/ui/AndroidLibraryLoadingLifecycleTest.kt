
package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.NotebookContentsScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLibraryLoadingLifecycleTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase
    private lateinit var repositories: LibraryRepositoryFactory

    @Before
    fun setUp() {
        context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        databaseName =
            "lib06-loading-${UUID.randomUUID()}.sqlite"

        database = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )
        database.openHelper.writableDatabase

        repositories = LibraryRepositoryFactory(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun refreshingNotebookHidesStaleNotesUntilReadCompletes() {
        val notebook = runBlocking {
            repositories.notebooks.create(
                name = "Loading Test",
            )
        }

        val note = runBlocking {
            repositories.noteLifecycle().createNote(
                notebookId = notebook.id,
                title = "Existing note",
            )
        }

        val delayNextRead = AtomicBoolean(false)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        val controlledRepository =
            object : NoteRepository by repositories.notes {

                override suspend fun listByNotebook(
                    notebookId: String,
                ): List<Note> {
                    if (delayNextRead.compareAndSet(true, false)) {
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }

                    return repositories.notes.listByNotebook(
                        notebookId
                    )
                }
            }

        val contentVersion = mutableIntStateOf(0)

        compose.setContent {
            MaterialTheme {
                NotebookContentsScreen(
                    notebookId = notebook.id,
                    notebookRepository = repositories.notebooks,
                    noteRepository = controlledRepository,
                    noteLifecycleRepository =
                        repositories.noteLifecycle(),
                    onBack = {},
                    onOpenNote = {},
                    contentVersion = contentVersion.intValue,
                )
            }
        }

        val noteTag = "notebook-note-${note.id}"

        // Initial data is loaded and visible.
        awaitTag(noteTag)


        // Trigger a new repository read through Compose state.
        compose.runOnIdle {
            delayNextRead.set(true)
            contentVersion.intValue++
        }

        try {
            // Use Compose's test synchronization so pending
            // recomposition and LaunchedEffect work can advance.
            compose.waitUntil(10_000L) {
                readStarted.isCompleted
            }

            // The second repository read is now suspended.
            // Previously displayed notes must no longer exist.
            compose.onNodeWithTag(
                noteTag
            ).assertDoesNotExist()

        } finally {
            // Release the suspended read even when the test fails.
            releaseRead.complete(Unit)
        }

        // Once the repository read finishes, the note should
        // become visible again.
        awaitTag(noteTag)
    }
}
