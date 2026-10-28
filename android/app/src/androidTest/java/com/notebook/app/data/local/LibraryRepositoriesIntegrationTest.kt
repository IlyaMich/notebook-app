package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.local.entity.NoteEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real on-disk Room database tests for LIB-03 metadata CRUD. */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoriesIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repos: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "lib03-crud-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repos = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun folderCreateRenameMoveAndReopenPersist() = runBlocking {
        val parent = repos.folders.create("  Parent  ")
        val child = repos.folders.create("Child")
        val renamed = repos.folders.rename(child.id, "  Renamed  ")
        assertEquals("Renamed", renamed.name)
        val moved = repos.folders.move(child.id, parent.id)
        assertEquals(parent.id, moved.parentFolderId)
        assertEquals(child.id, moved.id)
        assertEquals(0L, moved.sortOrder)
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        repos = LibraryRepositoryFactory(db)
        assertEquals(moved, repos.folders.findById(child.id))
        assertEquals(listOf(moved), repos.folders.listByParent(parent.id))
        assertEquals(listOf(parent), repos.folders.listByParent(null))
    }

    @Test fun cyclesAndMissingOrTrashedParentsAreRejectedWithoutMutation() = runBlocking {
        val root = repos.folders.create("Root")
        val middle = repos.folders.create("Middle", root.id)
        val leaf = repos.folders.create("Leaf", middle.id)
        expectError(LibraryErrorCode.CYCLE_DETECTED) { repos.folders.move(root.id, leaf.id) }
        expectError(LibraryErrorCode.CYCLE_DETECTED) { repos.folders.move(root.id, root.id) }
        expectError(LibraryErrorCode.NOT_FOUND) { repos.folders.move(middle.id, UUID.randomUUID().toString()) }
        assertNull(repos.folders.findById(root.id)?.parentFolderId)
        assertEquals(root.id, repos.folders.findById(middle.id)?.parentFolderId)
        assertEquals(middle.id, repos.folders.findById(leaf.id)?.parentFolderId)

        // Fixture-only direct DAO update simulates an independently trashed owner.
        val parentRow = db.folderDao().findById(root.id)!!
        db.folderDao().update(parentRow.copy(deletedAtMillis = 123, deletionBatchId = UUID.randomUUID().toString()))
        expectError(LibraryErrorCode.INACTIVE_OWNER) { repos.notebooks.create("Invalid", root.id) }
        expectError(LibraryErrorCode.INACTIVE_OWNER) { repos.folders.create("Invalid", root.id) }
        assertEquals(0, db.notebookDao().countAll())
    }

    @Test fun quickNotesProvisioningIsIdempotentAndRoleIsProtected() = runBlocking {
        val quick1 = repos.notebooks.ensureQuickNotes()
        val quick2 = repos.notebooks.ensureQuickNotes()
        assertEquals(quick1.id, quick2.id)
        assertTrue(quick1.isQuickNotes)
        assertNull(quick1.parentFolderId)
        val renamed = repos.notebooks.rename(quick1.id, "My Inbox")
        assertTrue(renamed.isQuickNotes)
        assertEquals(1, db.notebookDao().countAll())

        val folder = repos.folders.create("My Folder")
        expectError(LibraryErrorCode.PROTECTED_ENTITY) { repos.notebooks.move(quick1.id, folder.id) }
        assertEquals(null, repos.notebooks.findById(quick1.id)?.parentFolderId)
    }

    @Test fun notebookOwnershipAndOrderingArePersisted() = runBlocking {
        val folder = repos.folders.create("Folder")
        val first = repos.notebooks.create("First")
        val second = repos.notebooks.create("Second")
        assertEquals(0L, first.sortOrder)
        assertEquals(1L, second.sortOrder)
        val moved = repos.notebooks.move(second.id, folder.id)
        assertEquals(0L, moved.sortOrder)
        assertEquals(folder.id, moved.parentFolderId)
        assertEquals("Renamed", repos.notebooks.rename(second.id, "Renamed").name)
        assertEquals(listOf(first), repos.notebooks.listByParent(null))
        assertEquals(second.id, repos.notebooks.listByParent(folder.id).single().id)
    }

    @Test fun noteMetadataMovePreservesRevisionAndIdentityAndRejectedMoveRollsBack() = runBlocking {
        val source = repos.notebooks.create("Source")
        val target = repos.notebooks.create("Target")
        // TEST FIXTURE ONLY: production must use the atomic note + first-page lifecycle port.
        val noteId = UUID.randomUUID().toString()
        db.noteDao().insert(NoteEntity(
            id = noteId, notebookId = source.id, title = "Original",
            createdAtMillis = 100, updatedAtMillis = 100, revision = 7,
        ))
        val renamed = repos.notes.rename(noteId, "Updated")
        assertEquals(7L, renamed.revision)
        val moved = repos.notes.move(noteId, target.id)
        assertEquals(target.id, moved.notebookId)
        assertEquals(7L, moved.revision)
        assertEquals(noteId, moved.id)
        assertEquals("Updated", moved.title)
        assertEquals(7L, repos.notes.recordOpened(noteId).revision)
        assertEquals(1, repos.notes.listRecent(10).size)
        expectError(LibraryErrorCode.NOT_FOUND) { repos.notes.move(noteId, UUID.randomUUID().toString()) }
        assertEquals(target.id, repos.notes.findById(noteId)?.notebookId)
        assertEquals(listOf(noteId), repos.notes.listByNotebook(target.id).map { it.id })
        assertTrue(repos.notes.listByNotebook(source.id).isEmpty())
        expectError(LibraryErrorCode.INVALID_INPUT) { repos.notes.listRecent(0) }
    }

    private suspend fun expectError(code: LibraryErrorCode, operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: LibraryOperationException) {
            assertEquals(code, error.code)
            return
        }
        throw AssertionError("Expected LibraryOperationException with code $code")
    }
}
