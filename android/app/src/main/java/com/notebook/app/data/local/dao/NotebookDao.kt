package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notebook.app.data.local.entity.NotebookEntity

@Dao
interface NotebookDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(notebook: NotebookEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(notebook: NotebookEntity): Int

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findActiveById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE system_role = 'quick_notes' LIMIT 1")
    suspend fun findQuickNotes(): NotebookEntity?

    @Query("SELECT * FROM notebooks WHERE parent_folder_id IS :parentId AND deleted_at IS NULL ORDER BY sort_order ASC, id ASC")
    suspend fun listActiveByParent(parentId: String?): List<NotebookEntity>

    /** Nullable maximum: no active siblings returns null; order is 0-based. */
    @Query("SELECT MAX(sort_order) FROM notebooks WHERE parent_folder_id IS :parentId AND deleted_at IS NULL")
    suspend fun maxActiveSortOrder(parentId: String?): Long?

    @Query("SELECT * FROM notebooks WHERE deletion_batch_id = :batchId AND deleted_at IS NOT NULL ORDER BY id ASC")
    suspend fun listTrashedByBatch(batchId: String): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE deleted_at IS NOT NULL AND deletion_batch_id IS NOT NULL ORDER BY deleted_at DESC, id ASC")
    suspend fun listTrashed(): List<NotebookEntity>

    @Query("SELECT COUNT(*) FROM notebooks")
    suspend fun countAll(): Int
}
