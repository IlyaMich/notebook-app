package com.notebook.app.data.library.impl

import androidx.room.withTransaction
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.LibraryTrashRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import java.util.ArrayDeque

/**
 * LIB-07 non-destructive Trash transactions. All writes share LIB-03/STO-02's
 * AppDatabase; nothing in this adapter deletes notes, pages, elements or assets.
 * Permanent purge is deliberately NOT implemented until reference-aware asset
 * lifetime management has an agreed contract.
 */
class RoomLibraryTrashRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createId: () -> String = LibraryRepositorySupport::newId,
) : LibraryTrashRepository {
    private val folders get() = db.folderDao()
    private val notebooks get() = db.notebookDao()
    private val notes get() = db.noteDao()

    override suspend fun trashNote(noteId: String): String = db.withTransaction {
        val note = requireActiveNote(noteId)
        LibraryRepositorySupport.requireNotebook(notebooks, note.notebookId)
        val batch = newBatch()
        val now = clock()
        trashActiveNote(note, batch, now)
        batch
    }

    override suspend fun trashNotebook(notebookId: String): String = db.withTransaction {
        val notebook = LibraryRepositorySupport.requireNotebook(notebooks, notebookId)
        if (notebook.systemRole == Notebook.QUICK_NOTES_ROLE) {
            LibraryRepositorySupport.error(LibraryErrorCode.PROTECTED_ENTITY, "Quick Notes cannot be trashed")
        }
        val batch = newBatch()
        val now = clock()
        // Only active notes join this batch. Previously trashed notes keep their batch.
        for (note in notes.listActiveByNotebook(notebook.id)) trashActiveNote(note, batch, now)
        trashActiveNotebook(notebook, batch, now)
        batch
    }

    override suspend fun trashFolder(folderId: String): String = db.withTransaction {
        val root = LibraryRepositorySupport.requireFolder(folders, folderId)
        val batch = newBatch()
        val now = clock()
        val activeFolders = mutableListOf<FolderEntity>()
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque<FolderEntity>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current.id)) {
                LibraryRepositorySupport.error(LibraryErrorCode.CYCLE_DETECTED, "Existing folder cycle prevents Trash")
            }
            activeFolders.add(current)
            // Do not enroll independently trashed children or their descendants.
            folders.listActiveByParent(current.id).forEach { pending.addLast(it) }
        }
        val activeNotebooks = activeFolders.flatMap { notebooks.listActiveByParent(it.id) }
        for (notebook in activeNotebooks) {
            if (notebook.systemRole == Notebook.QUICK_NOTES_ROLE) {
                LibraryRepositorySupport.error(LibraryErrorCode.PROTECTED_ENTITY, "Quick Notes cannot be trashed")
            }
            for (note in notes.listActiveByNotebook(notebook.id)) trashActiveNote(note, batch, now)
        }
        for (notebook in activeNotebooks) trashActiveNotebook(notebook, batch, now)
        // The current FK ownership is retained. Only deletion metadata changes.
        for (folder in activeFolders) {
            check(folders.update(folder.copy(
                deletedAtMillis = now,
                deletionBatchId = batch,
                originalParentFolderId = folder.parentFolderId,
                originalSortOrder = folder.sortOrder,
            )) == 1)
        }
        batch
    }

    override suspend fun restoreBatch(deletionBatchId: String): Unit = db.withTransaction {
        val batch = LibraryValidation.canonicalId(deletionBatchId)
        val pendingFolders = folders.listTrashedByBatch(batch).associateBy { it.id }.toMutableMap()
        val batchNotebooks = notebooks.listTrashedByBatch(batch)
        val batchNotes = notes.listTrashedByBatch(batch)
        if (pendingFolders.isEmpty() && batchNotebooks.isEmpty() && batchNotes.isEmpty()) {
            LibraryRepositorySupport.error(LibraryErrorCode.NOT_FOUND, "Deletion batch has no trashed members")
        }

        // Parents must become active before their children. Never restore another
        // deletion batch as a side effect, even if it currently owns a member.
        while (pendingFolders.isNotEmpty()) {
            val ready = pendingFolders.values.filter { it.originalParentFolderId == null || !pendingFolders.containsKey(it.originalParentFolderId) }
            if (ready.isEmpty()) {
                LibraryRepositorySupport.error(LibraryErrorCode.CYCLE_DETECTED, "Trash folder ancestry is inconsistent")
            }
            for (folder in ready) {
                val destination = folder.originalParentFolderId
                    ?.takeIf { folders.findActiveById(it) != null }
                val order = restoredFolderOrder(folder, destination)
                check(folders.update(folder.copy(
                    parentFolderId = destination,
                    sortOrder = order,
                    deletedAtMillis = null,
                    deletionBatchId = null,
                    originalParentFolderId = null,
                    originalSortOrder = null,
                )) == 1)
                pendingFolders.remove(folder.id)
            }
        }
        for (notebook in batchNotebooks) {
            if (notebook.systemRole == Notebook.QUICK_NOTES_ROLE) {
                LibraryRepositorySupport.error(LibraryErrorCode.PROTECTED_ENTITY, "Quick Notes may not be in Trash")
            }
            val destination = notebook.originalParentFolderId
                ?.takeIf { folders.findActiveById(it) != null }
            check(notebooks.update(notebook.copy(
                parentFolderId = destination,
                sortOrder = restoredNotebookOrder(notebook, destination),
                deletedAtMillis = null,
                deletionBatchId = null,
                originalParentFolderId = null,
                originalSortOrder = null,
            )) == 1)
        }
        for (note in batchNotes) {
            val original = note.originalNotebookId ?: note.notebookId
            val destination = notebooks.findActiveById(original)?.id
                ?: RoomNotebookRepository(db, clock, createId).ensureQuickNotes().id
            check(notes.update(note.copy(
                notebookId = destination,
                deletedAtMillis = null,
                deletionBatchId = null,
                originalNotebookId = null,
            )) == 1)
        }
    }

    private suspend fun requireActiveNote(noteId: String): NoteEntity {
        val row = notes.findById(LibraryValidation.canonicalId(noteId))
            ?: LibraryRepositorySupport.error(LibraryErrorCode.NOT_FOUND, "Note does not exist")
        if (row.deletedAtMillis != null) {
            LibraryRepositorySupport.error(LibraryErrorCode.INACTIVE_OWNER, "Note is already in Trash")
        }
        return row
    }

    private fun newBatch(): String = LibraryValidation.canonicalId(createId())

    private suspend fun trashActiveNote(note: NoteEntity, batch: String, now: Long) {
        check(note.deletedAtMillis == null)
        check(notes.update(note.copy(
            deletedAtMillis = now,
            deletionBatchId = batch,
            originalNotebookId = note.notebookId,
        )) == 1)
    }

    private suspend fun trashActiveNotebook(notebook: NotebookEntity, batch: String, now: Long) {
        check(notebook.deletedAtMillis == null)
        check(notebooks.update(notebook.copy(
            deletedAtMillis = now,
            deletionBatchId = batch,
            originalParentFolderId = notebook.parentFolderId,
            originalSortOrder = notebook.sortOrder,
        )) == 1)
    }

    private suspend fun restoredFolderOrder(folder: FolderEntity, parentId: String?): Long {
        val original = folder.originalSortOrder
        if (original != null && original >= 0 &&
            folders.listActiveByParent(parentId).none { it.sortOrder == original }
        ) return original
        return LibraryRepositorySupport.nextOrder(folders.maxActiveSortOrder(parentId))
    }

    private suspend fun restoredNotebookOrder(notebook: NotebookEntity, parentId: String?): Long {
        val original = notebook.originalSortOrder
        if (original != null && original >= 0 &&
            notebooks.listActiveByParent(parentId).none { it.sortOrder == original }
        ) return original
        return LibraryRepositorySupport.nextOrder(notebooks.maxActiveSortOrder(parentId))
    }
}
