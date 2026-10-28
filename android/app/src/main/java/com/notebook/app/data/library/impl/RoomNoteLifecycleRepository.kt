package com.notebook.app.data.library.impl

import androidx.room.withTransaction
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.library.repository.NoteLifecycleRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.NoteEntity

/**
 * LIB-03's single note lifecycle transaction owner.
 *
 * This coordinator is not usable for production note creation until STO-02
 * supplies a real NoteContentTransactionPort backed by THIS AppDatabase.
 * There is deliberately no metadata-only or no-op production fallback.
 */
class RoomNoteLifecycleRepository(
    private val db: AppDatabase,
    private val content: NoteContentTransactionPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createId: () -> String = LibraryRepositorySupport::newId,
) : NoteLifecycleRepository {

    override suspend fun createNote(
        notebookId: String?,
        title: String,
        firstPage: InitialPageSpec,
    ): Note = db.withTransaction {
        // STO-02 must additionally validate the canonical background payload.
        val validatedPage = LibraryValidation.initialPage(firstPage)
        val notebook = if (notebookId == null) {
            // Nested Room transaction joins the same shared database transaction.
            RoomNotebookRepository(db, clock, createId).ensureQuickNotes()
        } else {
            LibraryRepositorySupport.requireNotebook(db.notebookDao(), notebookId).toModel()
        }

        val now = clock()
        val note = NoteEntity(
            id = LibraryValidation.canonicalId(createId()),
            notebookId = notebook.id,
            title = title,
            createdAtMillis = now,
            updatedAtMillis = now,
            revision = 0L,
        )
        db.noteDao().insert(note)
        // Throws on missing/invalid/unsupported content, rolling back the note
        // and any newly provisioned Quick Notes row in this same transaction.
        content.insertInitialPage(note.id, validatedPage)
        note.toModel()
    }

    override suspend fun duplicateNote(
        sourceNoteId: String,
        targetNotebookId: String?,
    ): Note = db.withTransaction {
        val source = db.noteDao().findById(LibraryValidation.canonicalId(sourceNoteId))
            ?: LibraryRepositorySupport.error(LibraryErrorCode.NOT_FOUND, "Source note does not exist")
        if (source.deletedAtMillis != null) {
            LibraryRepositorySupport.error(LibraryErrorCode.INACTIVE_OWNER, "Source note is in Trash")
        }
        // Even if a stale note row exists, never clone into an inactive notebook.
        val notebookId = targetNotebookId ?: source.notebookId
        LibraryRepositorySupport.requireNotebook(db.notebookDao(), notebookId)

        val now = clock()
        val duplicate = NoteEntity(
            id = LibraryValidation.canonicalId(createId()),
            notebookId = notebookId,
            title = source.title, // Preserve title; UI may rename afterwards.
            createdAtMillis = now,
            updatedAtMillis = now,
            revision = 0L,
        )
        db.noteDao().insert(duplicate)
        // STO-02 must snapshot ordered content, remap page/element IDs and
        // fail on unsupported asset references. One outer transaction only.
        content.cloneContent(source.id, duplicate.id)
        duplicate.toModel()
    }
}
