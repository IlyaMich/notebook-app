package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notebook.app.data.local.entity.AssetEntity

/**
 * Low-level asset metadata access. This DAO deliberately has no delete, replace,
 * or reference-counting operations. The durable file/DB/ref protocol must be
 * implemented before any caller registers assets or reclaims originals.
 */
@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(asset: AssetEntity)

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE relative_path = :relativePath LIMIT 1")
    suspend fun findByRelativePath(relativePath: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE state = :state ORDER BY id ASC LIMIT :limit")
    suspend fun listByState(state: String, limit: Int): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM assets")
    suspend fun countAll(): Int
}
