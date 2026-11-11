
package com.notebook.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryHierarchySidebar
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIB-06 C4-C2.
 *
 * Verifies genuine repository failures and recovery through Retry.
 * Uses isolated Room storage and delegates all successful operations
 * to the real repositories.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryErrorRetryTest {

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
            "lib06-error-retry-${UUID.randomUUID()}.sqlite"

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
    fun folderRefreshFailureHidesRowsAndRetryPreservesParent() {
        val parent = runBlocking {
            repositories.folders.create("Projects")
        }

        val child = runBlocking {
            repositories.folders.create(
                "Research",
                parent.id,
            )
        }

        val failNextRead = AtomicBoolean(false)
        val openedFolder = AtomicReference<String?>(null)

        val controlledFolders =
            object : FolderRepository by repositories.folders {

                override suspend fun listByParent(
                    parentFolderId: String?,
                ): List<Folder> {
                    if (
                        parentFolderId == parent.id &&
                        failNextRead.compareAndSet(true, false)
                    ) {
                        throw IllegalStateException(
                            "Injected folder listing failure"
                        )
                    }

                    return repositories.folders.listByParent(
                        parentFolderId
                    )
                }
            }

        val version = mutableIntStateOf(0)

        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(600.dp)
                        .height(650.dp),
                ) {
                    LibraryScreen(
                        folderRepository = controlledFolders,
                        notebookRepository = repositories.notebooks,
                        parentFolderId = parent.id,
                        onBack = {},
                        onOpenFolder = {
                            openedFolder.set(it)
                        },
                        onOpenNotebook = {},
                        contentVersion = version.intValue,
                    )
                }
            }
        }

        val childTag = "library-folder-${child.id}"

        // The initial repository read succeeds.
        awaitTag(childTag)

        // Force a genuine failure on the next refresh.
        compose.runOnIdle {
            failNextRead.set(true)
            version.intValue++
        }

        awaitText("Injected folder listing failure")

        // Previously loaded rows must not remain interactive.
        compose.onNodeWithTag(
            childTag
        ).assertDoesNotExist()

        // Retry should load the same parent folder again.
        compose.onNodeWithText("Retry").performClick()

        awaitTag(childTag)

        compose.onNodeWithTag(
            childTag
        ).performClick()

        // Verify navigation still uses the original child ID.
        assertEquals(child.id, openedFolder.get())
    }

    @Test
    fun notebookReadFailureHidesNotesAndRetryRestoresThem() {
        val notebook = runBlocking {
            repositories.notebooks.create("Physics")
        }

        val note = runBlocking {
            repositories.noteLifecycle().createNote(
                notebookId = notebook.id,
                title = "Lecture 1",
            )
        }

        val failNextRead = AtomicBoolean(false)

        val controlledNotes =
            object : NoteRepository by repositories.notes {

                override suspend fun listByNotebook(
                    notebookId: String,
                ): List<Note> {
                    if (
                        notebookId == notebook.id &&
                        failNextRead.compareAndSet(true, false)
                    ) {
                        throw IllegalStateException(
                            "Injected notebook notes failure"
                        )
                    }

                    return repositories.notes.listByNotebook(
                        notebookId
                    )
                }
            }

        val version = mutableIntStateOf(0)

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
                    contentVersion = version.intValue,
                )
            }
        }

        val noteTag = "notebook-note-${note.id}"

        // Initial load succeeds.
        awaitTag(noteTag)
        awaitTag("notebook-new-note")

        // Fail the next note-list request.
        compose.runOnIdle {
            failNextRead.set(true)
            version.intValue++
        }

        awaitText("Injected notebook notes failure")

        // Error state must not expose stale content.
        compose.onNodeWithTag(
            noteTag
        ).assertDoesNotExist()

        // Note creation is unavailable during the error.
        compose.onNodeWithTag(
            "notebook-new-note"
        ).assertDoesNotExist()

        // Retry reads the same notebook again.
        compose.onNodeWithText("Retry").performClick()

        awaitTag(noteTag)
        awaitTag("notebook-new-note")

        // The previous error must disappear after recovery.
        compose.onNodeWithText(
            "Injected notebook notes failure"
        ).assertDoesNotExist()
    }

    @Test
    fun sidebarChildLoadFailureRetriesWithoutCollapsingFolder() {
        val parent = runBlocking {
            repositories.folders.create("Projects")
        }

        val child = runBlocking {
            repositories.folders.create(
                "Research",
                parent.id,
            )
        }

        val failNextRead = AtomicBoolean(true)

        val controlledFolders =
            object : FolderRepository by repositories.folders {

                override suspend fun listByParent(
                    parentFolderId: String?,
                ): List<Folder> {
                    if (
                        parentFolderId == parent.id &&
                        failNextRead.compareAndSet(true, false)
                    ) {
                        throw IllegalStateException(
                            "Injected sidebar child failure"
                        )
                    }

                    return repositories.folders.listByParent(
                        parentFolderId
                    )
                }
            }

        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(960.dp)
                        .height(650.dp),
                ) {
                    LibraryHierarchySidebar(
                        folderRepository = controlledFolders,
                        notebookRepository = repositories.notebooks,
                        activeFolderId = null,
                        onOpenFolder = {},
                        onOpenNotebook = {},
                    )
                }
            }
        }

        val expandTag = "sidebar-expand-${parent.id}"
        val childTag = "sidebar-expand-${child.id}"

        awaitTag(expandTag)

        // Expanding the folder triggers the injected failure.
        compose.onNodeWithTag(
            expandTag
        ).performClick()

        awaitText("Injected sidebar child failure")

        compose.onNodeWithTag(
            childTag
        ).assertDoesNotExist()

        // The folder itself must remain expanded.
        compose.onNodeWithTag(
            expandTag
        ).assertContentDescriptionEquals(
            "Collapse folder Projects"
        )

        compose.onNodeWithTag(
            "sidebar-retry"
        ).performClick()

        // Children return without requiring another expand action.
        awaitTag(childTag)

        compose.onNodeWithTag(
            expandTag
        ).assertContentDescriptionEquals(
            "Collapse folder Projects"
        )

        compose.onNodeWithText(
            "Injected sidebar child failure"
        ).assertDoesNotExist()
    }
}
