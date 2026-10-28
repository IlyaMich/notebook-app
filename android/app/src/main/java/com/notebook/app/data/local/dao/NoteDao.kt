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

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun countAll(): Int
}
