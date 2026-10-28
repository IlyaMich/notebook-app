package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notebook.app.data.local.entity.ElementEntity

/** Low-level DAO. One row per editable element; not a whole-page rewrite. */
@Dao
interface ElementDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(element: ElementEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(element: ElementEntity): Int

    @Query("DELETE FROM elements WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM elements WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ElementEntity?

    @Query("SELECT * FROM elements WHERE page_id = :pageId ORDER BY order_index ASC, id ASC")
    suspend fun listByPage(pageId: String): List<ElementEntity>

    @Query("SELECT COUNT(*) FROM elements WHERE page_id = :pageId")
    suspend fun countByPage(pageId: String): Int
}
