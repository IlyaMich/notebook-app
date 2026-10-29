package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-to-real-Room wiring with an isolated, disposable database. */
@RunWith(AndroidJUnit4::class)
class AndroidLibraryUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repositories: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "m1-ui-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repositories = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun rootLibraryProvisionsQuickNotesAndShowsRealNotebooks() {
        compose.setContent {
            MaterialTheme {
                LibraryScreen(notebookRepository = repositories.notebooks, onOpenNotebook = {})
            }
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText("Quick Notes").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Quick Notes").assertExists()
        assertEquals(1, runBlocking { repositories.notebooks.listByParent(null).size })
    }

    @Test fun createFromNotebookScreenWritesFirstPageToRoom() {
        val notebook = runBlocking { repositories.notebooks.ensureQuickNotes() }
        val opened = AtomicReference<String?>(null)
        compose.setContent {
            MaterialTheme {
                NotebookContentsScreen(
                    notebookId = notebook.id,
                    notebookRepository = repositories.notebooks,
                    noteRepository = repositories.notes,
                    noteLifecycleRepository = repositories.noteLifecycle(),
                    onBack = {},
                    onOpenNote = { opened.set(it) },
                )
            }
        }
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithText("New note").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("New note").assertExists().performClick()
        compose.waitUntil(10_000L) { opened.get() != null }
        val noteId = opened.get()
        assertNotNull(noteId)
        val document = runBlocking { repositories.documents.load(noteId!!) }
        assertEquals(1, document.pages.size)
        assertEquals(0L, document.revision)
        assertEquals(notebook.id, runBlocking { repositories.notes.findById(noteId!!)?.notebookId })
    }
}
