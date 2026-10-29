package com.notebook.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import com.notebook.app.ui.screens.TrashScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Integration UI tests use real production repositories and an isolated database. */
@RunWith(AndroidJUnit4::class)
class AndroidTrashUiTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repositories: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "lib07-ui-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repositories = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000L) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showRootAndTrash() {
        compose.setContent {
            var inTrash by remember { mutableStateOf(false) }
            var version by remember { mutableIntStateOf(0) }
            MaterialTheme {
                if (inTrash) {
                    TrashScreen(
                        trashRepository = repositories.trash,
                        onBack = { inTrash = false },
                        onRestored = { version++ },
                    )
                } else {
                    LibraryScreen(
                        folderRepository = repositories.folders,
                        notebookRepository = repositories.notebooks,
                        trashRepository = repositories.trash,
                        onOpenFolder = {}, onOpenNotebook = {},
                        onOpenTrash = { inTrash = true },
                        contentVersion = version,
                        onLibraryChanged = { version++ },
                    )
                }
            }
        }
    }

    @Test fun trashFolderListsOneBatchAndRestoresWholeSubtree() {
        val parent = runBlocking { repositories.folders.create("Projects") }
        val child = runBlocking { repositories.folders.create("Research", parent.id) }
        val notebook = runBlocking { repositories.notebooks.create("Notes", child.id) }
        val note = runBlocking { repositories.noteLifecycle().createNote(notebook.id, "Study") }
        val page = runBlocking { db.pageDao().listByNote(note.id).single() }
        showRootAndTrash()

        awaitTag("library-trash-folder-${parent.id}")
        compose.onNodeWithTag("library-trash-folder-${parent.id}").performClick()
        compose.onNodeWithTag("trash-cancel").performClick()
        assertNull(runBlocking { db.folderDao().findById(parent.id)?.deletedAtMillis })
        compose.onNodeWithTag("library-trash-folder-${parent.id}").performClick()
        compose.onNodeWithTag("trash-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.folderDao().findById(parent.id)?.deletedAtMillis != null }
        }
        val batch = runBlocking { db.folderDao().findById(parent.id)?.deletionBatchId }
        assertNotNull(batch)
        compose.onNodeWithTag("library-open-trash").performClick()
        awaitTag("trash-batch-$batch")
        assertEquals(1, runBlocking { repositories.trash.listTrashedBatches().size })
        val info = runBlocking { repositories.trash.listTrashedBatches().single() }
        assertEquals(2, info.folderCount)
        assertEquals(1, info.notebookCount)
        assertEquals(1, info.noteCount)

        compose.onNodeWithTag("trash-restore-$batch").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.folderDao().findById(parent.id)?.deletedAtMillis == null }
        }
        awaitTag("trash-empty")
        compose.onNodeWithText("Back").performClick()
        awaitTag("library-folder-${parent.id}")
        assertEquals(parent.id, runBlocking { repositories.folders.findById(child.id)?.parentFolderId })
        assertEquals(child.id, runBlocking { repositories.notebooks.findById(notebook.id)?.parentFolderId })
        assertEquals(page, runBlocking { db.pageDao().listByNote(note.id).single() })
    }

    @Test fun trashNotebookPreservesEarlierIndependentNoteBatch() {
        val notebook = runBlocking { repositories.notebooks.create("Mathematics") }
        val earlier = runBlocking { repositories.noteLifecycle().createNote(notebook.id, "Earlier") }
        val active = runBlocking { repositories.noteLifecycle().createNote(notebook.id, "Current") }
        val earlierBatch = runBlocking { repositories.trash.trashNote(earlier.id) }
        showRootAndTrash()
        awaitTag("library-trash-notebook-${notebook.id}")
        compose.onNodeWithTag("library-trash-notebook-${notebook.id}").performClick()
        compose.onNodeWithTag("trash-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.notebookDao().findById(notebook.id)?.deletedAtMillis != null }
        }
        val notebookBatch = runBlocking { db.notebookDao().findById(notebook.id)?.deletionBatchId!! }
        compose.onNodeWithTag("library-open-trash").performClick()
        awaitTag("trash-batch-$notebookBatch")
        awaitTag("trash-batch-$earlierBatch")
        compose.onNodeWithTag("trash-restore-$notebookBatch").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.notebookDao().findById(notebook.id)?.deletedAtMillis == null }
        }
        awaitTag("trash-batch-$earlierBatch")
        assertEquals(earlierBatch, runBlocking { db.noteDao().findById(earlier.id)?.deletionBatchId })
        assertNull(runBlocking { db.noteDao().findById(active.id)?.deletedAtMillis })
        assertEquals(1, runBlocking { db.pageDao().countByNote(active.id) })
    }

    @Test fun trashSingleNoteFromNotebookAndRestoreKeepsFirstPage() {
        val notebook = runBlocking { repositories.notebooks.create("Physics") }
        val note = runBlocking { repositories.noteLifecycle().createNote(notebook.id, "Lab") }
        val page = runBlocking { db.pageDao().listByNote(note.id).single() }
        compose.setContent {
            var inTrash by remember { mutableStateOf(false) }
            var version by remember { mutableIntStateOf(0) }
            MaterialTheme {
                if (inTrash) {
                    TrashScreen(repositories.trash, onBack = { inTrash = false },
                        onRestored = { version++ })
                } else {
                    NotebookContentsScreen(
                        notebookId = notebook.id,
                        notebookRepository = repositories.notebooks,
                        noteRepository = repositories.notes,
                        noteLifecycleRepository = repositories.noteLifecycle(),
                        trashRepository = repositories.trash,
                        onBack = { inTrash = true },
                        onOpenNote = {},
                        contentVersion = version,
                        onLibraryChanged = { version++ },
                    )
                }
            }
        }
        awaitTag("notebook-trash-note-${note.id}")
        compose.onNodeWithTag("notebook-trash-note-${note.id}").performClick()
        compose.onNodeWithTag("trash-confirm").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.noteDao().findById(note.id)?.deletedAtMillis != null }
        }
        val batch = runBlocking { db.noteDao().findById(note.id)?.deletionBatchId!! }
        compose.onNodeWithText("Back").performClick()
        awaitTag("trash-restore-$batch")
        compose.onNodeWithTag("trash-restore-$batch").performClick()
        compose.waitUntil(10_000L) {
            runBlocking { db.noteDao().findById(note.id)?.deletedAtMillis == null }
        }
        compose.onNodeWithText("Back").performClick()
        awaitTag("notebook-trash-note-${note.id}")
        assertEquals(page, runBlocking { db.pageDao().listByNote(note.id).single() })
        assertEquals(0L, runBlocking { db.noteDao().findById(note.id)?.revision })
    }

    @Test fun permanentDeleteNeedsConfirmationAndRefreshesTrash() {
        val notebook = runBlocking { repositories.notebooks.create("Temporary") }
        val note = runBlocking { repositories.noteLifecycle().createNote(notebook.id, "Discard") }
        val batch = runBlocking { repositories.trash.trashNote(note.id) }
        compose.setContent {
            MaterialTheme {
                TrashScreen(trashRepository = repositories.trash, onBack = {})
            }
        }
        awaitTag("trash-delete-$batch")
        compose.onNodeWithTag("trash-delete-$batch").performClick()
        awaitTag("trash-purge-dialog")
        compose.onNodeWithTag("trash-purge-cancel").performClick()
        assertNotNull(runBlocking { db.noteDao().findById(note.id) })
        compose.onNodeWithTag("trash-delete-$batch").performClick()
        awaitTag("trash-purge-dialog")
        compose.onNodeWithTag("trash-purge-confirm").performClick()
        compose.waitUntil(10_000L) { runBlocking { db.noteDao().findById(note.id) == null } }
        awaitTag("trash-empty")
        assertEquals(0, runBlocking { repositories.trash.listTrashedBatches().size })
        assertEquals(0, runBlocking { db.pageDao().countByNote(note.id) })
    }

    @Test fun quickNotesHasNoTrashAction() {
        val quick = runBlocking { repositories.notebooks.ensureQuickNotes() }
        showRootAndTrash()
        awaitTag("library-notebook-${quick.id}")
        assertTrue(compose.onAllNodesWithTag("library-trash-notebook-${quick.id}")
            .fetchSemanticsNodes().isEmpty())
    }
}
