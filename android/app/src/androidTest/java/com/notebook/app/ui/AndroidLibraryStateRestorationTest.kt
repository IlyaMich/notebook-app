
package com.notebook.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
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
 * LIB-06 C3-B2 — Saved-state restoration of the production
 * navigation host and notebook presentation.
 *
 * Uses a disposable Room database. Compose restoration is
 * simulated; Activity recreation is verified separately.
 */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryStateRestorationTest {

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
            "lib06-restore-${UUID.randomUUID()}.sqlite"

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

    private fun showApp(
        width: Dp,
        height: Dp,
    ): StateRestorationTester {
        val tester = StateRestorationTester(compose)

        tester.setContent {
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

        return tester
    }

    @Test
    fun compactNestedNotebookAndBackStackSurviveRestoration() {
        val parent = runBlocking {
            repositories.folders.create("University")
        }

        val child = runBlocking {
            repositories.folders.create(
                "Mathematics",
                parent.id,
            )
        }

        val notebook = runBlocking {
            repositories.notebooks.create(
                "Calculus",
                child.id,
            )
        }

        val restoration = showApp(
            width = 600.dp,
            height = 650.dp,
        )

        awaitTag("library-folder-${parent.id}")
        compose.onNodeWithTag(
            "library-folder-${parent.id}"
        ).performClick()

        awaitTag("library-folder-${child.id}")
        compose.onNodeWithTag(
            "library-folder-${child.id}"
        ).performClick()

        awaitTag("library-notebook-${notebook.id}")
        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("notebook-view-list")

        // A compact notebook destination has no tablet sidebar.
        compose.onNodeWithTag(
            "library-sidebar"
        ).assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        awaitTag("notebook-view-list")
        compose.onNodeWithTag(
            "library-sidebar"
        ).assertDoesNotExist()

        // Restore the complete parent navigation chain.
        compose.onNodeWithText("Back").performClick()

        awaitTag("library-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "library-back"
        ).performClick()

        awaitTag("library-folder-${child.id}")

        compose.onNodeWithTag(
            "library-back"
        ).performClick()

        awaitTag("library-folder-${parent.id}")
    }

    @Test
    fun tabletSelectedNotebookSurvivesRestoration() {
        val first = runBlocking {
            repositories.notebooks.create("Notebook A")
        }

        val second = runBlocking {
            repositories.notebooks.create("Notebook B")
        }

        val restoration = showApp(
            width = 960.dp,
            height = 650.dp,
        )

        awaitTag("library-notebook-${first.id}")

        compose.onNodeWithTag(
            "library-notebook-${first.id}"
        ).performClick()

        awaitTag("sidebar-notebook-${second.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).performClick()

        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        awaitTag("sidebar-notebook-${second.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).assertIsSelected()

        awaitTag("notebook-view-list")

        // Selecting another notebook replaced the current route.
        // One Back must return to the root library.
        compose.onNodeWithText("Back").performClick()

        awaitTag("library-notebook-${first.id}")
        awaitTag("library-notebook-${second.id}")
    }

    @Test
    fun nestedNotebookGridPreferenceSurvivesRestoration() {
        val folder = runBlocking {
            repositories.folders.create("Courses")
        }

        val notebook = runBlocking {
            repositories.notebooks.create(
                "Physics",
                folder.id,
            )
        }

        val note = runBlocking {
            repositories.noteLifecycle().createNote(
                notebookId = notebook.id,
                title = "Lecture 1",
            )
        }

        val restoration = showApp(
            width = 960.dp,
            height = 650.dp,
        )

        awaitTag("library-folder-${folder.id}")

        compose.onNodeWithTag(
            "library-folder-${folder.id}"
        ).performClick()

        awaitTag("library-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("sidebar-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).assertIsSelected()

        awaitTag("notebook-view-grid")

        compose.onNodeWithTag(
            "notebook-view-grid"
        ).performClick()

        awaitTag("notebook-notes-grid")
        awaitTag("notebook-note-${note.id}")

        restoration.emulateSavedInstanceStateRestore()

        // The selected notebook and its ancestor remain accessible.
        awaitTag("sidebar-expand-${folder.id}")
        awaitTag("sidebar-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).assertIsSelected()

        // Grid mode, rather than the default List mode, is restored.
        awaitTag("notebook-notes-grid")
        awaitTag("notebook-note-${note.id}")

        compose.onNodeWithTag(
            "notebook-notes-list"
        ).assertDoesNotExist()
    }
}
