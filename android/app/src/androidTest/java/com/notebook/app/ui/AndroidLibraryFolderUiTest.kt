package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verify folder navigation and creation through the same real Room repositories used by the app. */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryFolderUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase
    private lateinit var repositories: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "m1-folders-${UUID.randomUUID()}.sqlite"
        database = AppDatabaseProvider.openIsolated(context, databaseName)
        database.openHelper.writableDatabase
        repositories = LibraryRepositoryFactory(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun creatingRootFolderPersistsAndNavigatesById() {
        val opened = AtomicReference<String?>(null)
        compose.setContent {
            MaterialTheme {
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    onOpenFolder = { opened.set(it) },
                    onOpenNotebook = {},
                )
            }
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText("Quick Notes").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-new-folder").performClick()
        compose.onNodeWithTag("library-name-input").performTextInput("Projects")
        compose.onNodeWithTag("library-create-confirm").performClick()

        compose.waitUntil(10_000L) {
            runBlocking { repositories.folders.listByParent(null).any { it.name == "Projects" } }
        }
        val created = runBlocking { repositories.folders.listByParent(null).single { it.name == "Projects" } }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("library-folder-${created.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-folder-${created.id}").performClick()
        assertEquals(created.id, opened.get())
        assertEquals(null, created.parentFolderId)
    }

    @Test fun nestedFolderAndNotebookAreCreatedUnderCurrentFolderAndSurviveReopen() {
        val parent = runBlocking { repositories.folders.create("Engineering") }
        val openedFolder = AtomicReference<String?>(null)
        val openedNotebook = AtomicReference<String?>(null)
        val backedOut = AtomicReference(false)
        compose.setContent {
            MaterialTheme {
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = parent.id,
                    onBack = { backedOut.set(true) },
                    onOpenFolder = { openedFolder.set(it) },
                    onOpenNotebook = { openedNotebook.set(it) },
                )
            }
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText("Engineering").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-new-folder").performClick()
        compose.onNodeWithTag("library-name-input").performTextInput("Research")
        compose.onNodeWithTag("library-create-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.folders.listByParent(parent.id).any { it.name == "Research" } }
        }
        val nested = runBlocking { repositories.folders.listByParent(parent.id).single() }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("library-folder-${nested.id}").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("library-new-notebook").performClick()
        compose.onNodeWithTag("library-name-input").performTextInput("Specifications")
        compose.onNodeWithTag("library-create-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { repositories.notebooks.listByParent(parent.id).any { it.name == "Specifications" } }
        }
        val notebook = runBlocking { repositories.notebooks.listByParent(parent.id).single() }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag("library-notebook-${notebook.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-folder-${nested.id}").performClick()
        assertEquals(nested.id, openedFolder.get())
        compose.onNodeWithTag("library-notebook-${notebook.id}").performClick()
        assertEquals(notebook.id, openedNotebook.get())
        compose.onNodeWithTag("library-back").performClick()
        assertTrue(backedOut.get())

        // Same on-disk DB, new Room connection: hierarchy and IDs are unchanged.
        database.close()
        database = AppDatabaseProvider.openIsolated(context, databaseName)
        repositories = LibraryRepositoryFactory(database)
        assertEquals(parent.id, runBlocking { repositories.folders.findById(nested.id)?.parentFolderId })
        assertEquals(parent.id, runBlocking { repositories.notebooks.findById(notebook.id)?.parentFolderId })
        assertTrue(runBlocking { repositories.notebooks.listByParent(null).none { it.id == notebook.id } })
    }
}
