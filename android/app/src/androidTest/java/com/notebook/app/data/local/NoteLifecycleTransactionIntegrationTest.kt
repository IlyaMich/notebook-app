package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteLifecycleRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NoteEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract tests only. The test double writes a FOLDER marker in the same DB;
 * it does NOT create real pages. STO-02 must test actual pages/elements later.
 */
@RunWith(AndroidJUnit4::class)
class NoteLifecycleTransactionIntegrationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase
    private lateinit var repos: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "lib03-lifecycle-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        db.openHelper.writableDatabase
        repos = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun createInvokesContentInsideTransactionAndPersistsMetadataAfterReopen() = runBlocking {
        val owner = repos.notebooks.create("School")
        val marker = MarkerContentPort(db)
        val created = repos.noteLifecycle(marker).createNote(owner.id, "Exam")
        assertEquals(owner.id, created.notebookId)
        assertEquals(0L, created.revision)
        assertEquals(1, marker.createdCount)
        assertEquals(0, marker.clonedCount)
        assertEquals(InitialPageSpec.a4PortraitBlank(), marker.lastSpec)
        assertEquals(created.id, marker.lastCreatedId)
        assertEquals(1, db.folderDao().countAll()) // marker is committed

        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        repos = LibraryRepositoryFactory(db)
        assertEquals(created, repos.notes.findById(created.id))
        assertEquals(1, db.folderDao().countAll())
        // This test does not assert a page exists: no pages table exists yet.
    }

    @Test fun createFailureRollsBackNoteAndContentMarker() = runBlocking {
        val owner = repos.notebooks.create("Work")
        val marker = MarkerContentPort(db, failAfterMarker = true)
        expectUnsupported { repos.noteLifecycle(marker).createNote(owner.id, "Abort") }
        assertEquals(0, db.noteDao().countAll())
        assertEquals(0, db.folderDao().countAll())
        assertEquals(1, marker.createdCount)
    }

    @Test fun defaultQuickNotesProvisioningAlsoRollsBackOnContentFailure() = runBlocking {
        val marker = MarkerContentPort(db, failAfterMarker = true)
        expectUnsupported { repos.noteLifecycle(marker).createNote() }
        assertEquals(0, db.noteDao().countAll())
        assertNull(db.notebookDao().findQuickNotes())
        assertEquals(0, db.folderDao().countAll())
        // A successful content adapter also provisions Quick Notes atomically.
        val successPort = MarkerContentPort(db)
        val created = repos.noteLifecycle(successPort).createNote()
        val quick = db.notebookDao().findQuickNotes()!!
        assertEquals(quick.id, created.notebookId)
        assertEquals(1, db.notebookDao().countAll())
    }

    @Test fun duplicateDelegatesCloneAndResetsRevisionWithoutModifyingSource() = runBlocking {
        val sourceOwner = repos.notebooks.create("Source")
        val destOwner = repos.notebooks.create("Destination")
        val original = fixtureNote(sourceOwner.id, title = "Sketch", revision = 13L)
        val marker = MarkerContentPort(db)
        val copied = repos.noteLifecycle(marker).duplicateNote(original.id, destOwner.id)
        assertNotEquals(original.id, copied.id)
        assertEquals(destOwner.id, copied.notebookId)
        assertEquals(original.title, copied.title)
        assertEquals(0L, copied.revision)
        assertEquals(original.id, marker.lastSourceId)
        assertEquals(copied.id, marker.lastClonedId)
        assertEquals(1, marker.clonedCount)
        assertEquals(original, repos.notes.findById(original.id))
        assertEquals(2, db.noteDao().countAll())
        // Default destination is the source notebook, not Quick Notes.
        val copiedAgain = repos.noteLifecycle(MarkerContentPort(db)).duplicateNote(original.id)
        assertEquals(sourceOwner.id, copiedAgain.notebookId)
    }

    @Test fun duplicateFailureRollsBackCloneMarkerAndPreservesSource() = runBlocking {
        val owner = repos.notebooks.create("Source")
        val original = fixtureNote(owner.id, "Original", 8L)
        val marker = MarkerContentPort(db, failAfterMarker = true)
        expectUnsupported { repos.noteLifecycle(marker).duplicateNote(original.id) }
        assertEquals(1, db.noteDao().countAll())
        assertEquals(0, db.folderDao().countAll())
        assertEquals(original, repos.notes.findById(original.id))
    }

    @Test fun invalidSourceOrDestinationFailsBeforeContentCallback() = runBlocking {
        val owner = repos.notebooks.create("Owner")
        val original = fixtureNote(owner.id, "Existing", 1L)
        val marker = MarkerContentPort(db)
        val service = repos.noteLifecycle(marker)
        val missing = UUID.randomUUID().toString()
        expectCode(LibraryErrorCode.NOT_FOUND) { service.createNote(missing) }
        expectCode(LibraryErrorCode.NOT_FOUND) { service.duplicateNote(missing) }
        expectCode(LibraryErrorCode.NOT_FOUND) { service.duplicateNote(original.id, missing) }
        db.noteDao().update(db.noteDao().findById(original.id)!!.copy(deletedAtMillis = 42L))
        expectCode(LibraryErrorCode.INACTIVE_OWNER) { service.duplicateNote(original.id) }
        assertEquals(0, marker.createdCount)
        assertEquals(0, marker.clonedCount)
        assertEquals(1, db.noteDao().countAll())
    }

    private suspend fun fixtureNote(notebookId: String, title: String, revision: Long): com.notebook.app.data.library.model.Note {
        // Isolated fixture only. Real app note creation goes through a content adapter.
        val row = NoteEntity(
            id = UUID.randomUUID().toString(), notebookId = notebookId, title = title,
            createdAtMillis = 100L, updatedAtMillis = 200L, revision = revision,
        )
        db.noteDao().insert(row)
        return repos.notes.findById(row.id)!!
    }

    private suspend fun expectUnsupported(action: suspend () -> Unit) =
        expectCode(LibraryErrorCode.UNSUPPORTED_CONTENT, action)

    private suspend fun expectCode(code: LibraryErrorCode, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: LibraryOperationException) {
            assertEquals(code, e.code)
            return
        }
        throw AssertionError("Expected LibraryOperationException($code)")
    }

    private class MarkerContentPort(
        private val db: AppDatabase,
        private val failAfterMarker: Boolean = false,
    ) : NoteContentTransactionPort {
        var createdCount = 0
            private set
        var clonedCount = 0
            private set
        var lastCreatedId: String? = null
            private set
        var lastSpec: InitialPageSpec? = null
            private set
        var lastSourceId: String? = null
            private set
        var lastClonedId: String? = null
            private set

        override suspend fun insertInitialPage(noteId: String, spec: InitialPageSpec) {
            createdCount++
            lastCreatedId = noteId
            lastSpec = spec
            insertMarker()
        }

        override suspend fun cloneContent(sourceNoteId: String, targetNoteId: String) {
            clonedCount++
            lastSourceId = sourceNoteId
            lastClonedId = targetNoteId
            insertMarker()
        }

        private suspend fun insertMarker() {
            db.folderDao().insert(FolderEntity(
                id = UUID.randomUUID().toString(), parentFolderId = null,
                name = "TEST-ONLY transaction marker", sortOrder = 0,
                createdAtMillis = 111L, updatedAtMillis = 111L,
            ))
            if (failAfterMarker) {
                throw LibraryOperationException(
                    LibraryErrorCode.UNSUPPORTED_CONTENT,
                    "Simulated content failure after shared-database write",
                )
            }
        }
    }
}
