package com.notebook.app.data.library.impl

import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.local.dao.FolderDao
import com.notebook.app.data.local.dao.NotebookDao
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.model.Notebook
import java.util.UUID

/** Repository-level guards; callers run the ownership checks inside AppDatabase.withTransaction. */
internal object LibraryRepositorySupport {
    fun newId(): String = UUID.randomUUID().toString()

    fun error(code: LibraryErrorCode, message: String): Nothing =
        throw LibraryOperationException(code, message)

    suspend fun requireFolder(dao: FolderDao, id: String): FolderEntity {
        val entity = dao.findById(LibraryValidation.canonicalId(id))
            ?: error(LibraryErrorCode.NOT_FOUND, "Folder does not exist")
        if (entity.deletedAtMillis != null) error(LibraryErrorCode.INACTIVE_OWNER, "Folder is in Trash")
        return entity
    }

    suspend fun requireFolderParent(dao: FolderDao, parentId: String?) {
        if (parentId != null) requireFolder(dao, parentId)
    }

    suspend fun requireNotebook(dao: NotebookDao, id: String): NotebookEntity {
        val entity = dao.findById(LibraryValidation.canonicalId(id))
            ?: error(LibraryErrorCode.NOT_FOUND, "Notebook does not exist")
        if (entity.deletedAtMillis != null) error(LibraryErrorCode.INACTIVE_OWNER, "Notebook is in Trash")
        return entity
    }

    fun nextOrder(maximum: Long?): Long {
        if (maximum == Long.MAX_VALUE) error(LibraryErrorCode.INVALID_INPUT, "Library sort order is exhausted")
        return if (maximum == null) 0L else maximum + 1L
    }

    /** Repeated ancestor walks detect both the proposed cycle and preexisting corrupt cycles. */
    suspend fun rejectCycle(dao: FolderDao, movingFolderId: String, destinationId: String?) {
        var current = destinationId
        val seen = mutableSetOf<String>()
        while (current != null) {
            if (current == movingFolderId || !seen.add(current)) {
                error(LibraryErrorCode.CYCLE_DETECTED, "Folder move would create a cycle")
            }
            current = requireFolder(dao, current).parentFolderId
        }
    }
}

internal fun FolderEntity.toModel(): Folder = Folder(
    id = id, parentFolderId = parentFolderId, name = name, sortOrder = sortOrder,
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    deletedAtMillis = deletedAtMillis, deletionBatchId = deletionBatchId,
    originalParentFolderId = originalParentFolderId, originalSortOrder = originalSortOrder,
)

internal fun NotebookEntity.toModel(): Notebook = Notebook(
    id = id, parentFolderId = parentFolderId, name = name, color = color,
    sortOrder = sortOrder, systemRole = systemRole,
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    deletedAtMillis = deletedAtMillis, deletionBatchId = deletionBatchId,
    originalParentFolderId = originalParentFolderId, originalSortOrder = originalSortOrder,
)

internal fun NoteEntity.toModel(): Note = Note(
    id = id, notebookId = notebookId, title = title,
    createdAtMillis = createdAtMillis, updatedAtMillis = updatedAtMillis,
    lastOpenedAtMillis = lastOpenedAtMillis, revision = revision,
    deletedAtMillis = deletedAtMillis, deletionBatchId = deletionBatchId,
    originalNotebookId = originalNotebookId,
)
