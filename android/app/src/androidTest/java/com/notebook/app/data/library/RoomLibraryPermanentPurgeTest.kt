package com.notebook.app.data.library

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.PurgeStep
import com.notebook.app.data.library.impl.RoomLibraryTrashRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryPermanentPurgeTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AppDatabase
    private lateinit var repos: LibraryRepositoryFactory

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        name = "purge-${UUID.randomUUID()}.sqlite"
        db = AppDatabaseProvider.openIsolated(context, name)
        db.openHelper.writableDatabase
        repos = LibraryRepositoryFactory(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun makeShape(pageId: String, order: Long = 0L): String {
        val id = UUID.randomUUID().toString()
        val json = """{"id":"$id","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"line","geometry":{"x1":10,"y1":10,"x2":20,"y2":20},"stroke":{"color":"#000000","width":2,"opacity":1},"fill":null}}"""
        db.elementDao().insert(ElementEntity(id, pageId, order, "shape", json, 0))
        return id
    }

    private fun assertFkClean() {
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { c ->
            assertEquals(0, c.count)
        }
    }

    private suspend fun expectError(code: LibraryErrorCode, action: suspend () -> Unit) {
        var observed: LibraryErrorCode? = null
        try { action() } catch (ex: LibraryOperationException) { observed = ex.code }
        assertEquals(code, observed)
    }

    @Test fun p01_notePurgeDeletesPagesAndElementsButNotIndependentContent() = runBlocking {
        val book = repos.notebooks.create("Book")
        val doomed = repos.noteLifecycle().createNote(book.id, "Doomed")
        val safe = repos.noteLifecycle().createNote(book.id, "Safe")
        val page1 = db.pageDao().listByNote(doomed.id).single()
        val page2 = PageEntity(UUID.randomUUID().toString(), doomed.id, 1L, 612.0, 792.0,
            """{"type":"blank","color":"#FFFFFF"}""", 0L)
        db.pageDao().insert(page2)
        makeShape(page1.id); makeShape(page2.id)
        val safePage = db.pageDao().listByNote(safe.id).single()
        val batch = repos.trash.trashNote(doomed.id)
        val preview = repos.trash.previewPurge(batch)
        assertEquals(1, preview.noteCount)
        assertEquals(2, preview.pageCount)
        assertEquals(2, preview.elementCount)
        repos.trash.purgeBatch(preview)
        assertNull(db.noteDao().findById(doomed.id))
        assertTrue(db.pageDao().listByNote(doomed.id).isEmpty())
        assertTrue(db.elementDao().listByPage(page1.id).isEmpty())
        assertTrue(db.elementDao().listByPage(page2.id).isEmpty())
        assertEquals(safePage, db.pageDao().listByNote(safe.id).single())
        assertFkClean()
    }

    @Test fun p02_folderPurgeDeletesOnlySelectedBatchAtAnyDepth() = runBlocking {
        val outer = repos.folders.create("Outer")
        val middle = repos.folders.create("Middle", outer.id)
        val inner = repos.folders.create("Inner", middle.id)
        val book = repos.notebooks.create("Book", inner.id)
        val note = repos.noteLifecycle().createNote(book.id, "Work")
        val safe = repos.notebooks.create("Other")
        val batch = repos.trash.trashFolder(outer.id)
        val preview = repos.trash.previewPurge(batch)
        assertEquals(3, preview.folderCount)
        assertEquals(1, preview.notebookCount)
        assertEquals(1, preview.noteCount)
        repos.trash.purgeBatch(preview)
        assertNull(db.folderDao().findById(outer.id))
        assertNull(db.folderDao().findById(middle.id))
        assertNull(db.folderDao().findById(inner.id))
        assertNull(db.notebookDao().findById(book.id))
        assertNull(db.noteDao().findById(note.id))
        assertNotNull(db.notebookDao().findById(safe.id))
        assertFkClean()
    }

    @Test fun p03_independentlyTrashedNoteSurvivesPurgeAndCanRestoreToQuickNotes() = runBlocking {
        val quick = repos.notebooks.ensureQuickNotes()
        val folder = repos.folders.create("Root")
        val book = repos.notebooks.create("Book", folder.id)
        val older = repos.noteLifecycle().createNote(book.id, "Earlier")
        val younger = repos.noteLifecycle().createNote(book.id, "Later")
        val page = db.pageDao().listByNote(older.id).single()
        val elementId = makeShape(page.id)
        val earlierBatch = repos.trash.trashNote(older.id)
        val earlierSnapshot = db.noteDao().findById(older.id)!!
        val laterBatch = repos.trash.trashFolder(folder.id)
        val preview = repos.trash.previewPurge(laterBatch)
        assertEquals(1, preview.survivingNoteCount)
        repos.trash.purgeBatch(preview)
        val survived = db.noteDao().findById(older.id)!!
        assertEquals(quick.id, survived.notebookId)
        assertEquals(earlierBatch, survived.deletionBatchId)
        assertEquals(earlierSnapshot.deletedAtMillis, survived.deletedAtMillis)
        assertEquals(earlierSnapshot.originalNotebookId, survived.originalNotebookId)
        assertEquals(earlierSnapshot.revision, survived.revision)
        assertEquals(page, db.pageDao().listByNote(older.id).single())
        assertNotNull(db.elementDao().findById(elementId))
        assertNull(db.noteDao().findById(younger.id))
        assertEquals(1, repos.trash.listTrashedBatches().size)
        repos.trash.restoreBatch(earlierBatch)
        assertEquals(quick.id, repos.notes.findById(older.id)?.notebookId)
        assertFkClean()
    }

    @Test fun p04_independentlyTrashedFolderAndNotebookBecomeTrashedRoots() = runBlocking {
        val parent = repos.folders.create("Parent")
        val child = repos.folders.create("Child", parent.id)
        val book = repos.notebooks.create("Independent", parent.id)
        val note = repos.noteLifecycle().createNote(book.id, "Unchanged")
        val childBatch = repos.trash.trashFolder(child.id)
        val bookBatch = repos.trash.trashNotebook(book.id)
        val childBefore = db.folderDao().findById(child.id)!!
        val bookBefore = db.notebookDao().findById(book.id)!!
        val parentBatch = repos.trash.trashFolder(parent.id)
        val preview = repos.trash.previewPurge(parentBatch)
        assertEquals(1, preview.survivingFolderCount)
        assertEquals(1, preview.survivingNotebookCount)
        repos.trash.purgeBatch(preview)
        assertNull(db.folderDao().findById(parent.id))
        assertEquals(childBefore.copy(parentFolderId = null), db.folderDao().findById(child.id))
        assertEquals(bookBefore.copy(parentFolderId = null), db.notebookDao().findById(book.id))
        assertEquals(bookBatch, db.noteDao().findById(note.id)?.deletionBatchId)
        repos.trash.restoreBatch(childBatch)
        repos.trash.restoreBatch(bookBatch)
        assertNull(repos.folders.findById(child.id)?.parentFolderId)
        assertNull(repos.notebooks.findById(book.id)?.parentFolderId)
        assertFkClean()
    }

    @Test fun p05_separateBatchesRemainListedAndIndependent() = runBlocking {
        val first = repos.notebooks.create("First")
        val second = repos.notebooks.create("Second")
        val a = repos.noteLifecycle().createNote(first.id, "A")
        val b = repos.noteLifecycle().createNote(second.id, "B")
        val aBatch = repos.trash.trashNote(a.id)
        val bBatch = repos.trash.trashNote(b.id)
        repos.trash.purgeBatch(repos.trash.previewPurge(aBatch))
        assertEquals(listOf(bBatch), repos.trash.listTrashedBatches().map { it.id })
        assertNotNull(db.noteDao().findById(b.id))
        assertFkClean()
    }

    @Test fun p06_unknownAndActiveBatchesAreRejected() = runBlocking {
        val quick = repos.notebooks.ensureQuickNotes()
        val unknown = UUID.randomUUID().toString()
        expectError(LibraryErrorCode.NOT_FOUND) { repos.trash.previewPurge(unknown) }
        assertNull(db.notebookDao().findById(quick.id)?.deletedAtMillis)
        val folder = repos.folders.create("Active")
        val batch = repos.trash.trashFolder(folder.id)
        // Corrupt batch membership intentionally: active row with same ID.
        val extra = repos.folders.create("Other")
        val row = db.folderDao().findById(extra.id)!!
        db.folderDao().update(row.copy(deletionBatchId = batch))
        expectError(LibraryErrorCode.INVALID_INPUT) { repos.trash.previewPurge(batch) }
        assertNotNull(db.folderDao().findById(folder.id))
        assertFkClean()
    }

    @Test fun p07_failuresAtEachWriteBoundaryRollbackAllChanges() = runBlocking {
        for (phase in PurgeStep.entries) {
            val quick = repos.notebooks.ensureQuickNotes()
            val folder = repos.folders.create("Parent-${phase.name}")
            val book = repos.notebooks.create("Book", folder.id)
            val survivor = repos.noteLifecycle().createNote(book.id, "Survivor")
            val doomed = repos.noteLifecycle().createNote(book.id, "Doomed")
            val element = makeShape(db.pageDao().listByNote(doomed.id).single().id)
            val earlier = repos.trash.trashNote(survivor.id)
            val batch = repos.trash.trashFolder(folder.id)
            val before = db.noteDao().findById(survivor.id)!!
            val page = db.pageDao().listByNote(doomed.id).single()
            val preview = repos.trash.previewPurge(batch)
            val repository = RoomLibraryTrashRepository(db, onPurgeStep = { if (it == phase) error("fault $phase") })
            var failed = false
            try { repository.purgeBatch(preview) } catch (_: IllegalStateException) { failed = true }
            assertTrue("Expected injected failure at $phase", failed)
            assertNotNull(db.folderDao().findById(folder.id))
            assertNotNull(db.notebookDao().findById(book.id))
            assertEquals(before, db.noteDao().findById(survivor.id))
            assertEquals(book.id, db.noteDao().findById(survivor.id)?.notebookId)
            assertNotNull(db.noteDao().findById(doomed.id))
            assertEquals(page, db.pageDao().listByNote(doomed.id).single())
            assertNotNull(db.elementDao().findById(element))
            assertEquals(earlier, db.noteDao().findById(survivor.id)?.deletionBatchId)
            assertEquals(quick.id, repos.notebooks.ensureQuickNotes().id)
            assertFkClean()
        }
    }

    @Test fun p08_stalePreviewIsRejectedBeforeAnyDeletion() = runBlocking {
        val book = repos.notebooks.create("Book")
        val note = repos.noteLifecycle().createNote(book.id, "Initial")
        val batch = repos.trash.trashNote(note.id)
        val oldPreview = repos.trash.previewPurge(batch)
        val row = db.noteDao().findById(note.id)!!
        db.noteDao().update(row.copy(title = "Changed while awaiting confirmation"))
        expectError(LibraryErrorCode.INVALID_INPUT) { repos.trash.purgeBatch(oldPreview) }
        assertNotNull(db.noteDao().findById(note.id))
        assertEquals(1, db.pageDao().countByNote(note.id))
        assertFkClean()
    }

    @Test fun p09_assetAndMalformedContentAbortWholeBatch() = runBlocking {
        val book = repos.notebooks.create("Book")
        val note = repos.noteLifecycle().createNote(book.id, "Unsupported")
        val page = db.pageDao().listByNote(note.id).single()
        val batch = repos.trash.trashNote(note.id)
        db.pageDao().update(page.copy(backgroundJson = """{"type":"pdfPage","assetId":"${UUID.randomUUID()}","pageIndex":0,"displayBox":"crop"}"""))
        expectError(LibraryErrorCode.UNSUPPORTED_CONTENT) { repos.trash.previewPurge(batch) }
        assertNotNull(db.noteDao().findById(note.id))
        db.pageDao().update(page)
        val id = UUID.randomUUID().toString()
        db.elementDao().insert(ElementEntity(id, page.id, 0, "image",
            """{"id":"$id","type":"image","transform":[1,0,0,1,0,0],"locked":false,"payload":{"assetId":"${UUID.randomUUID()}","frame":{"x":0,"y":0,"width":10,"height":10},"opacity":1}}""",0L))
        expectError(LibraryErrorCode.UNSUPPORTED_CONTENT) { repos.trash.previewPurge(batch) }
        val bad = db.elementDao().findById(id)!!
        db.elementDao().update(bad.copy(elementJson = "not json"))
        expectError(LibraryErrorCode.INVALID_INPUT) { repos.trash.previewPurge(batch) }
        assertNotNull(db.noteDao().findById(note.id))
        assertFkClean()
    }

    @Test fun p10_purgeAndRestoreSurviveDatabaseReopen() = runBlocking {
        val quick = repos.notebooks.ensureQuickNotes()
        val book = repos.notebooks.create("Owner")
        val note = repos.noteLifecycle().createNote(book.id, "Trashed earlier")
        val first = repos.trash.trashNote(note.id)
        val second = repos.trash.trashNotebook(book.id)
        repos.trash.purgeBatch(repos.trash.previewPurge(second))
        db.close()
        db = AppDatabaseProvider.openIsolated(context, name)
        repos = LibraryRepositoryFactory(db)
        assertNull(db.notebookDao().findById(book.id))
        assertEquals(first, db.noteDao().findById(note.id)?.deletionBatchId)
        repos.trash.restoreBatch(first)
        assertEquals(quick.id, repos.notes.findById(note.id)?.notebookId)
        assertEquals(1, db.pageDao().countByNote(note.id))
        assertFkClean()
    }
}
