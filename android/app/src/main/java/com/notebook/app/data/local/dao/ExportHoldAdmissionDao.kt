package com.notebook.app.data.local.dao

import androidx.room.ColumnInfo
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

    /** The +1 probe is supplied by the bounded read-only recovery coordinator. */
    @Query("SELECT * FROM export_operations ORDER BY operation_id ASC LIMIT :rowLimit")
    suspend fun listRecoveryOperations(rowLimit: Int): List<ExportOperationEntity>

    /** LEFT JOIN deliberately preserves missing assets/operations for fail-closed diagnostics. */
    @Query("""SELECT h.operation_id AS operation_id, h.asset_id AS asset_id,
           op.operation_id AS operation_present_id, a.id AS asset_present_id,
           a.state AS asset_state
           FROM export_asset_holds h
           LEFT JOIN export_operations op ON op.operation_id = h.operation_id
           LEFT JOIN assets a ON a.id = h.asset_id
           ORDER BY h.operation_id ASC, h.asset_id ASC LIMIT :rowLimit""")
    suspend fun listRecoveryHoldRows(rowLimit: Int): List<ExportHoldRecoveryJoinRow>
}

/** Only stored metadata is observed: no original-byte inspection or protected receipt. */
data class ExportHoldRecoveryJoinRow(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "operation_present_id") val operationPresentId: String?,
    @ColumnInfo(name = "asset_present_id") val assetPresentId: String?,
    @ColumnInfo(name = "asset_state") val assetState: String?,
)
