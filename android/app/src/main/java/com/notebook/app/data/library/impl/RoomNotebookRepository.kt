package com.notebook.app.data.library.impl

import androidx.room.withTransaction
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.NotebookRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.NotebookEntity

/** Quick Notes is structurally recognized by system_role, never by its display name. */
class RoomNotebookRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createId: () -> String = LibraryRepositorySupport::newId,
) : NotebookRepository {
    private val dao get() = db.notebookDao()

    override suspend fun ensureQuickNotes(): Notebook = db.withTransaction {
        dao.findQuickNotes()?.let { existing ->
            if (existing.parentFolderId != null || existing.deletedAtMillis != null) {
                LibraryRepositorySupport.error(LibraryErrorCode.PROTECTED_ENTITY, "Quick Notes must be active at root")
            }
            return@withTransaction existing.toModel()
        }
        val now = clock()
        val row = NotebookEntity(
            id = LibraryValidation.canonicalId(createId()),
            parentFolderId = null,
            name = "Quick Notes",
            sortOrder = LibraryRepositorySupport.nextOrder(dao.maxActiveSortOrder(null)),
            systemRole = Notebook.QUICK_NOTES_ROLE,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.insert(row)
        row.toModel()
    }

    override suspend fun create(name: String, parentFolderId: String?): Notebook = db.withTransaction {
        val cleaned = LibraryValidation.nonBlankName(name)
        LibraryRepositorySupport.requireFolderParent(db.folderDao(), parentFolderId)
        val now = clock()
        val row = NotebookEntity(
            id = LibraryValidation.canonicalId(createId()),
            parentFolderId = parentFolderId,
            name = cleaned,
            sortOrder = LibraryRepositorySupport.nextOrder(dao.maxActiveSortOrder(parentFolderId)),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.insert(row)
        row.toModel()
    }

    override suspend fun rename(notebookId: String, name: String): Notebook = db.withTransaction {
        val row = LibraryRepositorySupport.requireNotebook(dao, notebookId)
        val cleaned = LibraryValidation.nonBlankName(name)
        if (row.name == cleaned) return@withTransaction row.toModel()
        // Display name is not identity; renaming Quick Notes is allowed by ADR-0001.
        val updated = row.copy(name = cleaned, updatedAtMillis = clock())
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun move(notebookId: String, newParentFolderId: String?): Notebook = db.withTransaction {
        val row = LibraryRepositorySupport.requireNotebook(dao, notebookId)
        if (row.systemRole == Notebook.QUICK_NOTES_ROLE && newParentFolderId != null) {
            LibraryRepositorySupport.error(LibraryErrorCode.PROTECTED_ENTITY, "Quick Notes cannot move into a folder")
        }
        LibraryRepositorySupport.requireFolderParent(db.folderDao(), newParentFolderId)
        if (row.parentFolderId == newParentFolderId) return@withTransaction row.toModel()
        val updated = row.copy(
            parentFolderId = newParentFolderId,
            sortOrder = LibraryRepositorySupport.nextOrder(dao.maxActiveSortOrder(newParentFolderId)),
            updatedAtMillis = clock(),
        )
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun findById(notebookId: String): Notebook? =
        dao.findById(LibraryValidation.canonicalId(notebookId))?.toModel()

    override suspend fun listByParent(parentFolderId: String?): List<Notebook> {
        if (parentFolderId != null) LibraryValidation.canonicalId(parentFolderId)
        return dao.listActiveByParent(parentFolderId).map { it.toModel() }
    }
}
