package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notebook.app.data.local.entity.PageEntity

/** Low-level DAO. Only STO-02's transaction-validating repository may mutate pages. */
@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(page: PageEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(page: PageEntity): Int

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE note_id = :noteId ORDER BY order_index ASC, id ASC")
    suspend fun listByNote(noteId: String): List<PageEntity>

    @Query("SELECT COUNT(*) FROM pages WHERE note_id = :noteId")
    suspend fun countByNote(noteId: String): Int
}
