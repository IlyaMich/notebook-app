package com.notebook.app.data.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.ElementEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** LIB-07 non-destructive deletion contracts on a real isolated Room database. */
@RunWith(AndroidJUnit4::class)
class RoomLibraryTrashRepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repos: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "lib07-trash-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repos = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun trashNotePreservesPageAndRestoresSameIdentityAfterReopen() = runBlocking {
        val notebook = repos.notebooks.create("Science")
        val note = repos.noteLifecycle().createNote(notebook.id, "Experiment")
        val page = db.pageDao().listByNote(note.id).single()
        val elementId = UUID.randomUUID().toString()
        val elementJson = """{"id":"$elementId","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"line","geometry":{"x1":10,"y1":10,"x2":30,"y2":30},"stroke":{"color":"#000000","width":2,"opacity":1},"fill":null}}"""
        db.elementDao().insert(ElementEntity(
            id = elementId, pageId = page.id, orderIndex = 0,
            kind = "shape", elementJson = elementJson, revision = 0,
        ))
        val batch = repos.trash.trashNote(note.id)
        assertTrue(repos.notes.listByNotebook(notebook.id).isEmpty())
        assertEquals(batch, db.noteDao().findById(note.id)?.deletionBatchId)
        assertEquals(page, db.pageDao().listByNote(note.id).single())
        assertEquals(elementJson, db.elementDao().listByPage(page.id).single().elementJson)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        repos = LibraryRepositoryFactory(db)
        repos.trash.restoreBatch(batch)
        assertEquals(note.id, repos.notes.listByNotebook(notebook.id).single().id)
        assertNull(db.noteDao().findById(note.id)?.deletedAtMillis)
        assertEquals(page, db.pageDao().listByNote(note.id).single())
        assertEquals(elementJson, db.elementDao().listByPage(page.id).single().elementJson)
        assertEquals(0L, db.noteDao().findById(note.id)?.revision)
        assertForeignKeysValid()
    }

    @Test fun trashFolderRestoresOnlyItsBatchWithoutResurrectingIndependentlyTrashedNote() = runBlocking {
        val outer = repos.folders.create("Outer")
        val inner = repos.folders.create("Inner", outer.id)
        val notebook = repos.notebooks.create("Binder", inner.id)
        val independent = repos.noteLifecycle().createNote(notebook.id, "Earlier deletion")
        val active = repos.noteLifecycle().createNote(notebook.id, "In current deletion")
        val earlierBatch = repos.trash.trashNote(independent.id)
        val folderBatch = repos.trash.trashFolder(outer.id)
        assertEquals(earlierBatch, db.noteDao().findById(independent.id)?.deletionBatchId)
        assertEquals(folderBatch, db.noteDao().findById(active.id)?.deletionBatchId)
        assertTrue(repos.folders.listByParent(null).isEmpty())
        assertEquals(1, db.pageDao().countByNote(active.id))
        repos.trash.restoreBatch(folderBatch)
        assertNull(repos.folders.findById(outer.id)?.deletedAtMillis)
        assertEquals(outer.id, repos.folders.findById(inner.id)?.parentFolderId)
        assertEquals(inner.id, repos.notebooks.findById(notebook.id)?.parentFolderId)
        assertEquals(listOf(active.id), repos.notes.listByNotebook(notebook.id).map { it.id })
        assertEquals(earlierBatch, db.noteDao().findById(independent.id)?.deletionBatchId)
        assertEquals(1, db.pageDao().countByNote(independent.id))
        assertForeignKeysValid()
    }

    @Test fun noteRestoreFallsBackToQuickNotesWhenOwnerIsStillTrashed() = runBlocking {
        val notebook = repos.notebooks.create("Old destination")
        val note = repos.noteLifecycle().createNote(notebook.id, "Orphan recovery")
        val noteBatch = repos.trash.trashNote(note.id)
        val notebookBatch = repos.trash.trashNotebook(notebook.id)
        repos.trash.restoreBatch(noteBatch)
        val quick = repos.notebooks.ensureQuickNotes()
        assertEquals(quick.id, repos.notes.findById(note.id)?.notebookId)
        assertEquals(1, db.pageDao().countByNote(note.id))
        assertNotNull(repos.notebooks.findById(notebook.id)?.deletedAtMillis)
        repos.trash.restoreBatch(notebookBatch)
        assertEquals(quick.id, repos.notes.findById(note.id)?.notebookId)
        assertForeignKeysValid()
    }

    @Test fun separatelyTrashedFolderRestoresAtRootWhenParentIsStillTrashed() = runBlocking {
        val parent = repos.folders.create("Parent")
        val child = repos.folders.create("Child", parent.id)
        val childBatch = repos.trash.trashFolder(child.id)
        repos.trash.trashFolder(parent.id)
        repos.trash.restoreBatch(childBatch)
        assertNull(repos.folders.findById(child.id)?.parentFolderId)
        assertNull(repos.folders.findById(child.id)?.deletedAtMillis)
        assertNotNull(repos.folders.findById(parent.id)?.deletedAtMillis)
        assertForeignKeysValid()
    }

    @Test fun quickNotesAndAlreadyTrashedEntriesAreRejected() = runBlocking {
        val quick = repos.notebooks.ensureQuickNotes()
        expectError(LibraryErrorCode.PROTECTED_ENTITY) { repos.trash.trashNotebook(quick.id) }
        val folder = repos.folders.create("Temporary")
        val batch = repos.trash.trashFolder(folder.id)
        expectError(LibraryErrorCode.INACTIVE_OWNER) { repos.trash.trashFolder(folder.id) }
        expectError(LibraryErrorCode.NOT_FOUND) { repos.trash.restoreBatch(UUID.randomUUID().toString()) }
        assertEquals(batch, db.folderDao().findById(folder.id)?.deletionBatchId)
        assertNull(db.notebookDao().findById(quick.id)?.deletedAtMillis)
        assertForeignKeysValid()
    }

    @Test fun failureAfterNoteUpdatesRollsBackEntireFolderDeletion() = runBlocking {
        val folder = repos.folders.create("Work")
        val notebook = repos.notebooks.create("Book", folder.id)
        val note = repos.noteLifecycle().createNote(notebook.id, "Safe")
        val page = db.pageDao().listByNote(note.id).single()
        // Abort only when the final folder update is attempted, after the note
        // and notebook were already updated by the same Room transaction.
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_lib07_folder_soft_delete
            BEFORE UPDATE OF deleted_at ON folders
            WHEN NEW.id = '${folder.id}' AND NEW.deleted_at IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'synthetic Trash failure'); END
        """.trimIndent())
        var threw = false
        try { repos.trash.trashFolder(folder.id) } catch (_: Exception) { threw = true }
        assertTrue("Expected injected trigger failure", threw)
        assertNull(db.folderDao().findById(folder.id)?.deletedAtMillis)
        assertNull(db.notebookDao().findById(notebook.id)?.deletedAtMillis)
        assertNull(db.noteDao().findById(note.id)?.deletedAtMillis)
        assertNull(db.noteDao().findById(note.id)?.deletionBatchId)
        assertEquals(page, db.pageDao().listByNote(note.id).single())
        assertEquals(listOf(note.id), repos.notes.listByNotebook(notebook.id).map { it.id })
        assertForeignKeysValid()
    }

    private suspend fun expectError(code: LibraryErrorCode, operation: suspend () -> Unit) {
        var errorCode: LibraryErrorCode? = null
        try { operation() } catch (error: LibraryOperationException) { errorCode = error.code }
        assertEquals(code, errorCode)
    }

    private fun assertForeignKeysValid() {
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }
}
