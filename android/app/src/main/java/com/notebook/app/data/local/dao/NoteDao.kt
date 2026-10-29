package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notebook.app.data.local.entity.NoteEntity

/** No standalone public note-creation API. Note lifecycle requires STO-02 content. */
@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(note: NoteEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(note: NoteEntity): Int

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findActiveById(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE notebook_id = :notebookId AND deleted_at IS NULL ORDER BY updated_at DESC, id ASC")
    suspend fun listActiveByNotebook(notebookId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY COALESCE(last_opened_at, updated_at) DESC, id ASC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deletion_batch_id = :batchId AND deleted_at IS NOT NULL ORDER BY id ASC")
    suspend fun listTrashedByBatch(batchId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deleted_at IS NOT NULL AND deletion_batch_id IS NOT NULL ORDER BY deleted_at DESC, id ASC")
    suspend fun listTrashed(): List<NoteEntity>

    /** Includes independently trashed children; purge must not rely on active-only reads. */
    @Query("SELECT * FROM notes WHERE notebook_id = :ownerId ORDER BY id ASC")
    suspend fun listAllByOwner(ownerId: String): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE deletion_batch_id = :batchId AND deleted_at IS NULL")
    suspend fun countActiveWithBatch(batchId: String): Int

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun countAll(): Int
}
