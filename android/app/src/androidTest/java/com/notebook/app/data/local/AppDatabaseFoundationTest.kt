package com.notebook.app.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
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

/** Integration tests against actual on-device SQLite, not a mock/in-memory DB. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseFoundationTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dbName = "lib03-foundation-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, dbName)
        // Force physical creation so callbacks/triggers are installed.
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test fun folderPersistsAcrossCloseAndReopen() = runBlocking {
        val folder = folderRow()
        db.folderDao().insert(folder)
        assertEquals(folder, db.folderDao().findById(folder.id))
        db.close()
        db = AppDatabaseProvider.openIsolated(context, dbName)
        assertEquals(folder, db.folderDao().findById(folder.id))
    }

    @Test fun foreignKeyPreventsInvalidNotebookAndNoteOwnership() = runBlocking {
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        val badNotebook = notebookRow(parent = UUID.randomUUID().toString())
        expectSqlFailure { db.notebookDao().insert(badNotebook) }
        assertEquals(0, db.notebookDao().countAll())
        val badNote = NoteEntity(
            id = UUID.randomUUID().toString(),
            notebookId = UUID.randomUUID().toString(),
            title = "Invalid owner", createdAtMillis = 123, updatedAtMillis = 123,
        )
        expectSqlFailure { db.noteDao().insert(badNote) }
        assertEquals(0, db.noteDao().countAll())
    }

    @Test fun quickNotesIsUniqueProtectedAndRootOnly() = runBlocking {
        val quick = notebookRow(role = "quick_notes")
        db.notebookDao().insert(quick)
        assertEquals(quick, db.notebookDao().findQuickNotes())
        expectSqlFailure { db.notebookDao().insert(notebookRow(role = "quick_notes")) }
        expectSqlFailure { db.notebookDao().insert(notebookRow(role = "other")) }

        val rootFolder = folderRow()
        db.folderDao().insert(rootFolder)
        expectSqlFailure {
            db.notebookDao().update(quick.copy(parentFolderId = rootFolder.id))
        }
        expectSqlFailure { db.notebookDao().update(quick.copy(deletedAtMillis = 999)) }
        expectSqlFailure { db.notebookDao().update(quick.copy(systemRole = null)) }
        expectSqlFailure {
            db.openHelper.writableDatabase.execSQL("DELETE FROM notebooks WHERE id = '${quick.id}'")
        }
        assertEquals(quick, db.notebookDao().findQuickNotes())
        assertEquals(1, db.notebookDao().countAll())
    }

    @Test fun folderCannotBeItsOwnParentOrReferenceAbsentParent() = runBlocking {
        val missingParent = UUID.randomUUID().toString()
        expectSqlFailure { db.folderDao().insert(folderRow(parent = missingParent)) }
        val id = UUID.randomUUID().toString()
        expectSqlFailure { db.folderDao().insert(folderRow(id = id, parent = id)) }
        assertEquals(0, db.folderDao().countAll())
    }

    private suspend fun expectSqlFailure(operation: suspend () -> Unit) {
        var rejected = false
        try {
            operation()
        } catch (expected: Exception) {
            rejected = true
        }
        assertTrue("Expected SQLite constraint failure", rejected)
    }

    private fun folderRow(
        id: String = UUID.randomUUID().toString(),
        parent: String? = null,
    ) = FolderEntity(
        id = id, parentFolderId = parent, name = "Folder", sortOrder = 0,
        createdAtMillis = 123, updatedAtMillis = 123,
    )

    private fun notebookRow(
        parent: String? = null,
        role: String? = null,
    ) = NotebookEntity(
        id = UUID.randomUUID().toString(), parentFolderId = parent,
        name = "Notebook", sortOrder = 0, systemRole = role,
        createdAtMillis = 123, updatedAtMillis = 123,
    )
}
