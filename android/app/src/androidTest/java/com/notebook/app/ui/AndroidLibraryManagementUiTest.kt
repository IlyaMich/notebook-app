package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI operations use the production LIB-03 repositories and a separate disposable Room database. */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryManagementUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase
    private lateinit var repositories: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "m1-manage-${UUID.randomUUID()}.sqlite"
        database = AppDatabaseProvider.openIsolated(context, databaseName)
        database.openHelper.writableDatabase
        repositories = LibraryRepositoryFactory(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun showLibrary(folderId: String? = null) {
        compose.setContent {
            MaterialTheme {
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = folderId,
                    onOpenFolder = {},
                    onOpenNotebook = {},
                )
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun reopenDatabase() {
        database.close()
        database = AppDatabaseProvider.openIsolated(context, databaseName)
        repositories = LibraryRepositoryFactory(database)
    }

    @Test fun renameFolderAndNotebookUpdatesRowsAndSurvivesReopen() {
        val folder = runBlocking { repositories.folders.create("Projects") }
        val notebook = runBlocking { repositories.notebooks.create("Drafts") }
        showLibrary()

        awaitTag("library-rename-folder-${folder.id}")
        compose.onNodeWithTag("library-rename-folder-${folder.id}").performClick()
        awaitTag("library-rename-input")
        compose.onNodeWithTag("library-rename-input").performTextReplacement("Research")
        compose.onNodeWithTag("library-rename-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.folders.findById(folder.id)?.name == "Research" }
        }
        awaitTag("library-rename-notebook-${notebook.id}")
        compose.onNodeWithTag("library-rename-notebook-${notebook.id}").performClick()
        awaitTag("library-rename-input")
        compose.onNodeWithTag("library-rename-input").performTextReplacement("Plans")
        compose.onNodeWithTag("library-rename-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.notebooks.findById(notebook.id)?.name == "Plans" }
        }
        reopenDatabase()
        assertEquals("Research", runBlocking { repositories.folders.findById(folder.id)?.name })
        assertEquals("Plans", runBlocking { repositories.notebooks.findById(notebook.id)?.name })
        assertEquals(null, runBlocking { repositories.folders.findById(folder.id)?.parentFolderId })
        assertEquals(null, runBlocking { repositories.notebooks.findById(notebook.id)?.parentFolderId })
    }

    @Test fun moveNotebookIntoNestedFolderPreservesIdAndSurvivesReopen() {
        val source = runBlocking { repositories.folders.create("Projects") }
        val destination = runBlocking { repositories.folders.create("Archive", source.id) }
        val notebook = runBlocking { repositories.notebooks.create("Meeting notes", source.id) }
        showLibrary(source.id)

        awaitTag("library-move-notebook-${notebook.id}")
        compose.onNodeWithTag("library-move-notebook-${notebook.id}").performClick()
        awaitTag("library-destination-${destination.id}")
        compose.onNodeWithTag("library-destination-${destination.id}").performClick()
        compose.onNodeWithTag("library-move-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.notebooks.findById(notebook.id)?.parentFolderId == destination.id }
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("library-notebook-${notebook.id}")
                .fetchSemanticsNodes().isEmpty()
        }
        reopenDatabase()
        assertEquals(destination.id, runBlocking {
            repositories.notebooks.findById(notebook.id)?.parentFolderId
        })
        assertEquals(notebook.id, runBlocking {
            repositories.notebooks.listByParent(destination.id).single().id
        })
    }

    @Test fun folderMoveHidesDescendantsAndBackendRejectsCycles() {
        val moving = runBlocking { repositories.folders.create("Engineering") }
        val child = runBlocking { repositories.folders.create("Design", moving.id) }
        val grandchild = runBlocking { repositories.folders.create("Sketches", child.id) }
        val destination = runBlocking { repositories.folders.create("Storage") }
        showLibrary()

        awaitTag("library-move-folder-${moving.id}")
        compose.onNodeWithTag("library-move-folder-${moving.id}").performClick()
        awaitTag("library-destination-${destination.id}")
        assertTrue(compose.onAllNodesWithTag("library-destination-${child.id}")
            .fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("library-destination-${grandchild.id}")
            .fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("library-destination-${moving.id}")
            .fetchSemanticsNodes().isEmpty())

        // The repository must reject invalid ownership independently of the UI picker.
        assertNotNull(runCatching {
            runBlocking { repositories.folders.move(moving.id, grandchild.id) }
        }.exceptionOrNull())
        assertEquals(null, runBlocking { repositories.folders.findById(moving.id)?.parentFolderId })

        compose.onNodeWithTag("library-destination-${destination.id}").performClick()
        compose.onNodeWithTag("library-move-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.folders.findById(moving.id)?.parentFolderId == destination.id }
        }
        assertNotNull(runCatching {
            runBlocking { repositories.folders.move(destination.id, grandchild.id) }
        }.exceptionOrNull())
        reopenDatabase()
        assertEquals(destination.id, runBlocking { repositories.folders.findById(moving.id)?.parentFolderId })
        assertEquals(moving.id, runBlocking { repositories.folders.findById(child.id)?.parentFolderId })
        assertEquals(child.id, runBlocking { repositories.folders.findById(grandchild.id)?.parentFolderId })
        assertEquals(null, runBlocking { repositories.folders.findById(destination.id)?.parentFolderId })
    }

    @Test fun quickNotesCanRenameButCannotBeMovedViaUi() {
        val quickNotes = runBlocking { repositories.notebooks.ensureQuickNotes() }
        showLibrary()
        awaitTag("library-rename-notebook-${quickNotes.id}")
        assertTrue(compose.onAllNodesWithTag("library-move-notebook-${quickNotes.id}")
            .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("library-rename-notebook-${quickNotes.id}").performClick()
        compose.onNodeWithTag("library-rename-input").performTextReplacement("Inbox")
        compose.onNodeWithTag("library-rename-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.notebooks.findById(quickNotes.id)?.name == "Inbox" }
        }
        val updated = runBlocking { repositories.notebooks.ensureQuickNotes() }
        assertEquals(quickNotes.id, updated.id)
        assertEquals(null, updated.parentFolderId)
        assertEquals("Inbox", updated.name)
    }
}
