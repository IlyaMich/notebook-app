
package com.notebook.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.NotebookApp
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIB-06 C3-B1: exercise production Navigation 3 behavior using an
 * isolated Room database and controlled window constraints.
 *
 * These tests do not create or delete normal application library data.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryNavigationContinuityTest {

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
            "lib06-navigation-${UUID.randomUUID()}.sqlite"

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

    private fun awaitNoTag(tag: String) {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag(tag)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    private fun setApp(width: Dp, height: Dp) {
        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(height),
                ) {
                    NotebookApp(
                        repositoryOverride = repositories,
                    )
                }
            }
        }
    }

    @Test
    fun compactBackNavigationRestoresNestedFolderContext() {
        val root = runBlocking {
            repositories.folders.create("University")
        }

        val nested = runBlocking {
            repositories.folders.create(
                name = "Mathematics",
                parentFolderId = root.id,
            )
        }

        val notebook = runBlocking {
            repositories.notebooks.create(
                name = "Calculus",
                parentFolderId = nested.id,
            )
        }

        setApp(width = 600.dp, height = 650.dp)

        // Library -> University
        awaitTag("library-folder-${root.id}")
        compose.onNodeWithTag(
            "library-folder-${root.id}"
        ).performClick()

        // University -> Mathematics
        awaitTag("library-folder-${nested.id}")
        compose.onNodeWithTag(
            "library-folder-${nested.id}"
        ).performClick()

        // Mathematics -> Calculus
        awaitTag("library-notebook-${notebook.id}")
        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("notebook-view-list")

        // Compact notebook screen must not include the tablet sidebar.
        compose.onNodeWithTag(
            "library-sidebar"
        ).assertDoesNotExist()

        // Back returns to Mathematics.
        compose.onNodeWithText("Back").performClick()
        awaitTag("library-notebook-${notebook.id}")

        // Back returns to University.
        compose.onNodeWithTag("library-back").performClick()
        awaitTag("library-folder-${nested.id}")

        // Back returns to the root library.
        compose.onNodeWithTag("library-back").performClick()
        awaitTag("library-folder-${root.id}")
    }

    @Test
    fun tabletNotebookSwitchDoesNotAddExtraBackEntries() {
        val first = runBlocking {
            repositories.notebooks.create("Notebook A")
        }

        val second = runBlocking {
            repositories.notebooks.create("Notebook B")
        }

        setApp(width = 960.dp, height = 640.dp)

        // Open Notebook A from the root library.
        awaitTag("library-notebook-${first.id}")
        compose.onNodeWithTag(
            "library-notebook-${first.id}"
        ).performClick()

        awaitTag("library-sidebar")
        awaitTag("sidebar-notebook-${first.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${first.id}"
        ).assertIsSelected()

        // Select Notebook B using the persistent tablet sidebar.
        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).performClick()

        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).assertIsSelected()

        awaitTag("notebook-view-list")

        // One Back returns to the root library, not Notebook A.
        compose.onNodeWithText("Back").performClick()

        awaitTag("library-notebook-${first.id}")
        awaitTag("library-notebook-${second.id}")
    }

    @Test
    fun resizingKeepsSelectedNestedNotebookRoute() {
        val root = runBlocking {
            repositories.folders.create("University")
        }

        val nested = runBlocking {
            repositories.folders.create(
                name = "Mathematics",
                parentFolderId = root.id,
            )
        }

        val notebook = runBlocking {
            repositories.notebooks.create(
                name = "Calculus",
                parentFolderId = nested.id,
            )
        }

        val viewport = mutableStateOf(
            DpSize(600.dp, 650.dp)
        )

        compose.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(viewport.value.width)
                        .height(viewport.value.height),
                ) {
                    NotebookApp(
                        repositoryOverride = repositories,
                    )
                }
            }
        }

        // Navigate through the compact hierarchy.
        awaitTag("library-folder-${root.id}")
        compose.onNodeWithTag(
            "library-folder-${root.id}"
        ).performClick()

        awaitTag("library-folder-${nested.id}")
        compose.onNodeWithTag(
            "library-folder-${nested.id}"
        ).performClick()

        awaitTag("library-notebook-${notebook.id}")
        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("notebook-view-list")

        compose.onNodeWithTag(
            "library-sidebar"
        ).assertDoesNotExist()

        // Expand the available window without resetting NotebookApp.
        compose.runOnIdle {
            viewport.value = DpSize(960.dp, 650.dp)
        }

        awaitTag("library-sidebar")

        val selectedTag = "sidebar-notebook-${notebook.id}"

        // Ancestor folders should expand automatically.
        awaitTag("sidebar-expand-${root.id}")
        awaitTag("sidebar-expand-${nested.id}")
        awaitTag(selectedTag)

        compose.onNodeWithTag(
            selectedTag
        ).assertIsSelected()

        // Return to a compact window using the same navigation state.
        compose.runOnIdle {
            viewport.value = DpSize(600.dp, 650.dp)
        }

        awaitNoTag("library-sidebar")
        awaitTag("notebook-view-list")

        // The notebook route still points to Calculus.
        compose.onNodeWithText("Back").performClick()
        awaitTag("library-notebook-${notebook.id}")
    }
}
