package com.notebook.app.data.library.impl

import androidx.room.withTransaction
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase

/** Note metadata only. A note row must NOT be created here without its first page. */
class RoomNoteRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteRepository {
    private val dao get() = db.noteDao()

    override suspend fun findById(noteId: String): Note? =
        dao.findById(LibraryValidation.canonicalId(noteId))?.toModel()

    override suspend fun listByNotebook(notebookId: String): List<Note> {
        LibraryValidation.canonicalId(notebookId)
        return dao.listActiveByNotebook(notebookId).map { it.toModel() }
    }

    override suspend fun listRecent(limit: Int): List<Note> {
        if (limit !in 1..10_000) {
            LibraryRepositorySupport.error(LibraryErrorCode.INVALID_INPUT, "Recent note limit must be between 1 and 10000")
        }
        return dao.listRecent(limit).map { it.toModel() }
    }

    override suspend fun rename(noteId: String, title: String): Note = db.withTransaction {
        val row = requireActiveNote(noteId)
        if (row.title == title) return@withTransaction row.toModel()
        val updated = row.copy(title = title, updatedAtMillis = clock())
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun move(noteId: String, newNotebookId: String): Note = db.withTransaction {
        val row = requireActiveNote(noteId)
        LibraryRepositorySupport.requireNotebook(db.notebookDao(), newNotebookId)
        if (row.notebookId == newNotebookId) return@withTransaction row.toModel()
        val updated = row.copy(notebookId = newNotebookId, updatedAtMillis = clock())
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun recordOpened(noteId: String): Note = db.withTransaction {
        val row = requireActiveNote(noteId)
        val updated = row.copy(lastOpenedAtMillis = clock())
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    private suspend fun requireActiveNote(noteId: String): com.notebook.app.data.local.entity.NoteEntity {
        val row = dao.findById(LibraryValidation.canonicalId(noteId))
            ?: LibraryRepositorySupport.error(LibraryErrorCode.NOT_FOUND, "Note does not exist")
        if (row.deletedAtMillis != null) {
            LibraryRepositorySupport.error(LibraryErrorCode.INACTIVE_OWNER, "Note is in Trash")
        }
        return row
    }
}
