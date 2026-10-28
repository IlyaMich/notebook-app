package com.notebook.app.data.library.impl

import androidx.room.withTransaction
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.FolderEntity

/** Library metadata operations; every compound mutation validates and writes atomically. */
class RoomFolderRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val createId: () -> String = LibraryRepositorySupport::newId,
) : FolderRepository {
    private val dao get() = db.folderDao()

    override suspend fun create(name: String, parentFolderId: String?): Folder = db.withTransaction {
        val cleanName = LibraryValidation.nonBlankName(name)
        LibraryRepositorySupport.requireFolderParent(dao, parentFolderId)
        val now = clock()
        val row = FolderEntity(
            id = LibraryValidation.canonicalId(createId()),
            parentFolderId = parentFolderId,
            name = cleanName,
            sortOrder = LibraryRepositorySupport.nextOrder(dao.maxActiveSortOrder(parentFolderId)),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        dao.insert(row)
        row.toModel()
    }

    override suspend fun rename(folderId: String, name: String): Folder = db.withTransaction {
        val row = LibraryRepositorySupport.requireFolder(dao, folderId)
        val cleaned = LibraryValidation.nonBlankName(name)
        if (row.name == cleaned) return@withTransaction row.toModel()
        val updated = row.copy(name = cleaned, updatedAtMillis = clock())
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun move(folderId: String, newParentFolderId: String?): Folder = db.withTransaction {
        val row = LibraryRepositorySupport.requireFolder(dao, folderId)
        LibraryRepositorySupport.requireFolderParent(dao, newParentFolderId)
        LibraryRepositorySupport.rejectCycle(dao, row.id, newParentFolderId)
        if (row.parentFolderId == newParentFolderId) return@withTransaction row.toModel()
        val updated = row.copy(
            parentFolderId = newParentFolderId,
            sortOrder = LibraryRepositorySupport.nextOrder(dao.maxActiveSortOrder(newParentFolderId)),
            updatedAtMillis = clock(),
        )
        check(dao.update(updated) == 1)
        updated.toModel()
    }

    override suspend fun findById(folderId: String): Folder? =
        dao.findById(LibraryValidation.canonicalId(folderId))?.toModel()

    override suspend fun listByParent(parentFolderId: String?): List<Folder> {
        if (parentFolderId != null) LibraryValidation.canonicalId(parentFolderId)
        return dao.listActiveByParent(parentFolderId).map { it.toModel() }
    }
}
