
package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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

        // Expanding the hierarchy does not navigate.
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

    @Test
    fun selectedNotebookIsExposedInSidebarSemantics() {
        val notebook = runBlocking {
            repositories.notebooks.create("Selected Notebook")
        }

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    activeNotebookId = notebook.id,
                    onOpenFolder = {},
                    onOpenNotebook = {},
                )
            }
        }

        val tag = "sidebar-notebook-${notebook.id}"
        awaitTag(tag)

        compose.onNodeWithTag(tag).assertIsSelected()
    }

    @Test
    fun selectedNestedNotebookAutomaticallyExpandsAncestors() {
        val root = runBlocking {
            repositories.folders.create("University")
        }
        val nested = runBlocking {
            repositories.folders.create("Mathematics", root.id)
        }
        val notebook = runBlocking {
            repositories.notebooks.create("Calculus", nested.id)
        }

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    activeNotebookId = notebook.id,
                    onOpenFolder = {},
                    onOpenNotebook = {},
                )
            }
        }

        awaitTag("sidebar-expand-${root.id}")
        awaitTag("sidebar-expand-${nested.id}")

        val selectedTag = "sidebar-notebook-${notebook.id}"
        awaitTag(selectedTag)

        compose.onNodeWithTag(selectedTag).assertIsSelected()
    }

    @Test
    fun folderActionsHaveAccessibleLabelsAndIndependentBehavior() {
        val folder = runBlocking {
            repositories.folders.create("Projects")
        }
        val opened = AtomicReference<String?>(null)

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = folder.id,
                    onOpenFolder = { opened.set(it) },
                    onOpenNotebook = {},
                )
            }
        }

        val expandTag = "sidebar-expand-${folder.id}"
        val openTag = "sidebar-open-folder-${folder.id}"

        awaitTag(expandTag)

        compose.onNodeWithTag(expandTag)
            .assertContentDescriptionEquals("Expand folder Projects")

        compose.onNodeWithTag(openTag)
            .assertContentDescriptionEquals("Open folder Projects")
            .assertIsSelected()

        compose.onNodeWithTag(expandTag).performClick()

        compose.onNodeWithTag(expandTag)
            .assertContentDescriptionEquals("Collapse folder Projects")

        // Expanding must not open the folder.
        assertEquals(null, opened.get())

        compose.onNodeWithTag(openTag).performClick()
        assertEquals(folder.id, opened.get())
    }

    @Test
    fun notebookHasAccessibleNameAndSelectedState() {
        val notebook = runBlocking {
            repositories.notebooks.create("Research Notes")
        }

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    activeNotebookId = notebook.id,
                    onOpenFolder = {},
                    onOpenNotebook = {},
                )
            }
        }

        val tag = "sidebar-notebook-${notebook.id}"
        awaitTag(tag)

        compose.onNodeWithTag(tag)
            .assertContentDescriptionEquals(
                "Open notebook Research Notes"
            )
            .assertIsSelected()
    }

    @Test
    fun sidebarActionsMeetMinimumTouchTargetSize() {
        val folder = runBlocking {
            repositories.folders.create("Projects")
        }
        val notebook = runBlocking {
            repositories.notebooks.create("Research Notes")
        }

        compose.setContent {
            MaterialTheme {
                LibraryHierarchySidebar(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    activeFolderId = null,
                    onOpenFolder = {},
                    onOpenNotebook = {},
                )
            }
        }

        val expandTag = "sidebar-expand-${folder.id}"
        val openTag = "sidebar-open-folder-${folder.id}"
        val notebookTag = "sidebar-notebook-${notebook.id}"

        awaitTag(expandTag)
        awaitTag(notebookTag)

        compose.onNodeWithTag(expandTag)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        compose.onNodeWithTag(openTag)
            .assertHeightIsAtLeast(48.dp)

        compose.onNodeWithTag(notebookTag)
            .assertHeightIsAtLeast(48.dp)
    }
}
