
package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryHierarchySidebar
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLibrarySidebarUiTest {
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

        databaseName = "lib06-sidebar-${UUID.randomUUID()}.sqlite"
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
    fun rootNotebookOpensThroughExistingCallback() {
        val quickNotes = runBlocking {
            repositories.notebooks.ensureQuickNotes()
        }
        val opened = AtomicReference<String?>(null)

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    onOpenFolder = {},
                    onOpenNotebook = { opened.set(it) },
                )
            }
        }

        val tag = "sidebar-notebook-${quickNotes.id}"
        awaitTag(tag)
        compose.onNodeWithTag(tag).performClick()

        assertEquals(quickNotes.id, opened.get())
    }

    @Test
    fun expandingNestedFolderRevealsNotebookWithoutNavigation() {
        val root = runBlocking {
            repositories.folders.create("University")
        }
        val nested = runBlocking {
            repositories.folders.create("Mathematics", root.id)
        }
        val notebook = runBlocking {
            repositories.notebooks.create("Calculus", nested.id)
        }

        val openedFolder = AtomicReference<String?>(null)
        val openedNotebook = AtomicReference<String?>(null)

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    onOpenFolder = { openedFolder.set(it) },
                    onOpenNotebook = { openedNotebook.set(it) },
                )
            }
        }

        awaitTag("sidebar-expand-${root.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).assertDoesNotExist()

        compose.onNodeWithTag(
            "sidebar-expand-${root.id}"
        ).performClick()

        awaitTag("sidebar-expand-${nested.id}")

        compose.onNodeWithTag(
            "sidebar-expand-${nested.id}"
        ).performClick()

        awaitTag("sidebar-notebook-${notebook.id}")

        // Expansion only reveals hierarchy; it does not navigate.
        assertEquals(null, openedFolder.get())
        assertEquals(null, openedNotebook.get())

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).performClick()

        assertEquals(notebook.id, openedNotebook.get())

        compose.onNodeWithTag(
            "sidebar-open-folder-${root.id}"
        ).performClick()

        assertEquals(root.id, openedFolder.get())
    }
}
