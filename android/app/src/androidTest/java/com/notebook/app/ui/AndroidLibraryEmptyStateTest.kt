
package com.notebook.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIB-06 C4-C3.
 *
 * Verifies empty-library, empty-folder and empty-notebook behavior.
 * All storage is isolated from the normal application database.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryEmptyStateTest {

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
            "lib06-empty-${UUID.randomUUID()}.sqlite"

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
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun freshLibraryShowsQuickNotesAndCreationActions() {
        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(600.dp)
                        .height(650.dp),
                ) {
                    LibraryScreen(
                        folderRepository = repositories.folders,
                        notebookRepository = repositories.notebooks,
                        onOpenFolder = {},
                        onOpenNotebook = {},
                    )
                }
            }
        }

        // Initial library provisioning must create Quick Notes.
        awaitText("Quick Notes")

        val quickNotes = runBlocking {
            repositories.notebooks.listByParent(null)
        }

        assertEquals(1, quickNotes.size)
        assertTrue(quickNotes.single().isQuickNotes)

        awaitTag(
            "library-notebook-${quickNotes.single().id}"
        )

        // There are no user-created folders yet.
        compose.onNodeWithText(
            "No folders here"
        ).assertExists()

        // The library remains actionable.
        compose.onNodeWithTag(
            "library-new-folder"
        ).assertIsEnabled()

        compose.onNodeWithTag(
            "library-new-notebook"
        ).assertIsEnabled()

        // Quick Notes is not a missing or empty-library error.
        compose.onNodeWithText(
            "No notebooks here"
        ).assertDoesNotExist()
    }

    @Test
    fun emptyNestedFolderSupportsCreatingFoldersAndNotebooks() {
        val parent = runBlocking {
            repositories.folders.create("Projects")
        }

        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(600.dp)
                        .height(650.dp),
                ) {
                    LibraryScreen(
                        folderRepository = repositories.folders,
                        notebookRepository = repositories.notebooks,
                        parentFolderId = parent.id,
                        onBack = {},
                        onOpenFolder = {},
                        onOpenNotebook = {},
                    )
                }
            }
        }

        awaitText("No folders here")
        awaitText("No notebooks here")

        compose.onNodeWithTag(
            "library-new-folder"
        ).assertIsEnabled()

        compose.onNodeWithTag(
            "library-new-notebook"
        ).assertIsEnabled()

        // Create a folder through the real UI.
        compose.onNodeWithTag(
            "library-new-folder"
        ).performClick()

        compose.onNodeWithTag(
            "library-name-input"
        ).performTextInput("Research")

        compose.onNodeWithTag(
            "library-create-confirm"
        ).performClick()

        val child = runBlocking {
            // Wait for the repository write before inspecting it.
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Research")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            repositories.folders.listByParent(parent.id)
                .single { it.name == "Research" }
        }

        assertEquals(parent.id, child.parentFolderId)
        awaitTag("library-folder-${child.id}")

        compose.onNodeWithText(
            "No folders here"
        ).assertDoesNotExist()

        // Create a notebook inside the same parent.
        compose.onNodeWithTag(
            "library-new-notebook"
        ).performClick()

        compose.onNodeWithTag(
            "library-name-input"
        ).performTextInput("Planning")

        compose.onNodeWithTag(
            "library-create-confirm"
        ).performClick()

        awaitText("Planning")

        val notebook = runBlocking {
            repositories.notebooks.listByParent(parent.id)
                .single { it.name == "Planning" }
        }

        assertEquals(parent.id, notebook.parentFolderId)
        awaitTag("library-notebook-${notebook.id}")

        compose.onNodeWithText(
            "No notebooks here"
        ).assertDoesNotExist()
    }

    @Test
    fun emptyNotebookSupportsListGridAndNoteCreation() {
        val notebook = runBlocking {
            repositories.notebooks.create("Empty Physics")
        }

        val openedNote = AtomicReference<String?>(null)

        compose.setContent {
            MaterialTheme {
                NotebookContentsScreen(
                    notebookId = notebook.id,
                    notebookRepository = repositories.notebooks,
                    noteRepository = repositories.notes,
                    noteLifecycleRepository =
                        repositories.noteLifecycle(),
                    onBack = {},
                    onOpenNote = { openedNote.set(it) },
                )
            }
        }

        val emptyMessage =
            "No notes yet. Create your first note."

        // Default empty-list presentation.
        awaitText(emptyMessage)

        compose.onNodeWithTag(
            "notebook-new-note"
        ).assertExists()

        compose.onNodeWithTag(
            "notebook-view-grid"
        ).performClick()

        // Switching view mode must not hide the empty state.
        compose.onNodeWithText(
            emptyMessage
        ).assertExists()

        compose.onNodeWithTag(
            "notebook-view-list"
        ).performClick()

        compose.onNodeWithText(
            emptyMessage
        ).assertExists()

        // The primary creation action remains available.
        compose.onNodeWithTag(
            "notebook-new-note"
        ).performClick()

        compose.waitUntil(15_000L) {
            openedNote.get() != null
        }

        val noteId = openedNote.get()
        assertNotNull(noteId)

        val created = runBlocking {
            repositories.notes.findById(noteId!!)
        }

        assertNotNull(created)
        assertEquals(notebook.id, created!!.notebookId)
    }

    @Test
    fun emptyNotebookMessageWaitsUntilLoadingCompletes() {
        val notebook = runBlocking {
            repositories.notebooks.create("Delayed Notebook")
        }

        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        val controlledNotes =
            object : NoteRepository by repositories.notes {

                override suspend fun listByNotebook(
                    notebookId: String,
                ): List<Note> {
                    readStarted.complete(Unit)
                    releaseRead.await()

                    return repositories.notes.listByNotebook(
                        notebookId
                    )
                }
            }

        compose.setContent {
            MaterialTheme {
                NotebookContentsScreen(
                    notebookId = notebook.id,
                    notebookRepository = repositories.notebooks,
                    noteRepository = controlledNotes,
                    noteLifecycleRepository =
                        repositories.noteLifecycle(),
                    onBack = {},
                    onOpenNote = {},
                )
            }
        }

        val emptyMessage =
            "No notes yet. Create your first note."

        try {
            // Compose's waitUntil allows UI effects to advance.
            compose.waitUntil(15_000L) {
                readStarted.isCompleted
            }

            // The repository hasn't returned any result yet.
            // Loading must not be presented as an empty notebook.
            compose.onNodeWithText(
                emptyMessage
            ).assertDoesNotExist()

            // Creation should not be active until the notebook
            // finishes loading successfully.
            compose.onNodeWithTag(
                "notebook-new-note"
            ).assertDoesNotExist()
        } finally {
            releaseRead.complete(Unit)
        }

        // Only now should the genuine empty state appear.
        awaitText(emptyMessage)

        compose.onNodeWithTag(
            "notebook-new-note"
        ).assertExists()
    }
}
