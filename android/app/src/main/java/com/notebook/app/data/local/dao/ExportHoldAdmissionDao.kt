package com.notebook.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notebook.app.data.local.entity.ExportAssetHoldEntity
import com.notebook.app.data.local.entity.ExportOperationEntity

/** I5-B7B: insert-only primitives. The calling coordinator owns the ONE Room transaction. */
@Dao
public interface ExportHoldAdmissionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: ExportOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHold(hold: ExportAssetHoldEntity)

    @Query("SELECT * FROM export_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun findOperation(operationId: String): ExportOperationEntity?

    @Query("SELECT * FROM export_asset_holds WHERE operation_id = :operationId ORDER BY asset_id ASC")
    suspend fun listHolds(operationId: String): List<ExportAssetHoldEntity>
}
