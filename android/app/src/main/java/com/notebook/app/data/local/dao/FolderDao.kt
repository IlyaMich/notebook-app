package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notebook.app.data.local.entity.FolderEntity

/** Internal low-level writes. Call through transaction-validating repositories. */
@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(folder: FolderEntity): Int

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findActiveById(id: String): FolderEntity?

    @Query("SELECT * FROM folders WHERE parent_folder_id IS :parentId AND deleted_at IS NULL ORDER BY sort_order ASC, id ASC")
    suspend fun listActiveByParent(parentId: String?): List<FolderEntity>

    /** Nullable maximum: no active siblings returns null; order is 0-based. */
    @Query("SELECT MAX(sort_order) FROM folders WHERE parent_folder_id IS :parentId AND deleted_at IS NULL")
    suspend fun maxActiveSortOrder(parentId: String?): Long?

    @Query("SELECT * FROM folders WHERE deletion_batch_id = :batchId AND deleted_at IS NOT NULL ORDER BY id ASC")
    suspend fun listTrashedByBatch(batchId: String): List<FolderEntity>

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun countAll(): Int
}
