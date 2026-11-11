
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

@RunWith(AndroidJUnit4::class)
class AndroidLibrarySidebarExpansionContinuityTest {

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
            "lib06-expansion-${UUID.randomUUID()}.sqlite"

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

    private fun showApp(): StateRestorationTester {
        val tester = StateRestorationTester(compose)

        tester.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .width(960.dp)
                        .height(650.dp),
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
    fun expandedFoldersSurviveNotebookSwitchAndBack() {
        val root = runBlocking {
            repositories.folders.create("Projects")
        }
        val nested = runBlocking {
            repositories.folders.create(
                "Archive",
                root.id,
            )
        }
        val first = runBlocking {
            repositories.notebooks.create("Notebook A")
        }
        val second = runBlocking {
            repositories.notebooks.create("Notebook B")
        }

        showApp()

        awaitTag("sidebar-expand-${root.id}")

        compose.onNodeWithTag(
            "sidebar-expand-${root.id}"
        ).performClick()

        awaitTag("sidebar-expand-${nested.id}")

        // Open the first root notebook.
        compose.onNodeWithTag(
            "library-notebook-${first.id}"
        ).performClick()

        awaitTag("sidebar-notebook-${second.id}")

        // The unrelated Projects branch must still be expanded.
        awaitTag("sidebar-expand-${nested.id}")

        // Switch notebooks without creating another Back entry.
        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).performClick()

        compose.onNodeWithTag(
            "sidebar-notebook-${second.id}"
        ).assertIsSelected()

        awaitTag("sidebar-expand-${nested.id}")

        // Return to the root library.
        compose.onNodeWithText("Back").performClick()

        awaitTag("library-notebook-${first.id}")
        awaitTag("sidebar-expand-${nested.id}")
    }

    @Test
    fun manuallyCollapsedFolderStaysCollapsedAcrossNavigation() {
        val root = runBlocking {
            repositories.folders.create("Projects")
        }
        val nested = runBlocking {
            repositories.folders.create(
                "Archive",
                root.id,
            )
        }
        val notebook = runBlocking {
            repositories.notebooks.create("Notebook A")
        }

        showApp()

        val expandTag = "sidebar-expand-${root.id}"
        val childTag = "sidebar-expand-${nested.id}"

        awaitTag(expandTag)

        // Expand and then explicitly collapse Projects.
        compose.onNodeWithTag(expandTag).performClick()
        awaitTag(childTag)

        compose.onNodeWithTag(expandTag).performClick()
        awaitNoTag(childTag)

        // Navigating to an unrelated notebook must not reopen it.
        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("library-sidebar")
        awaitNoTag(childTag)

        compose.onNodeWithText("Back").performClick()

        awaitTag(expandTag)
        awaitNoTag(childTag)
    }

    @Test
    fun expandedFoldersSurviveSavedStateRestoration() {
        val root = runBlocking {
            repositories.folders.create("Courses")
        }
        val nested = runBlocking {
            repositories.folders.create(
                "Previous Semesters",
                root.id,
            )
        }
        val notebook = runBlocking {
            repositories.notebooks.create("Current Notes")
        }

        val restoration = showApp()

        awaitTag("sidebar-expand-${root.id}")

        compose.onNodeWithTag(
            "sidebar-expand-${root.id}"
        ).performClick()

        awaitTag("sidebar-expand-${nested.id}")

        compose.onNodeWithTag(
            "library-notebook-${notebook.id}"
        ).performClick()

        awaitTag("sidebar-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()

        // The notebook route is restored.
        awaitTag("sidebar-notebook-${notebook.id}")

        compose.onNodeWithTag(
            "sidebar-notebook-${notebook.id}"
        ).assertIsSelected()

        // The unrelated manually expanded folder is restored too.
        awaitTag("sidebar-expand-${nested.id}")

        compose.onNodeWithText("Back").performClick()

        awaitTag("library-notebook-${notebook.id}")
        awaitTag("sidebar-expand-${nested.id}")
    }
}
