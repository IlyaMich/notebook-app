package com.notebook.app.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.notebook.app.data.local.entity.ExportAssetHoldEntity
import com.notebook.app.data.local.entity.ExportOperationEntity
import com.notebook.app.data.local.entity.ExportSourceBindingEntity
import com.notebook.app.data.local.entity.ExportExternalHandoffAttemptEntity
import com.notebook.app.data.local.entity.ExportExternalReadbackObservationEntity

/** I5-B7B: insert-only primitives. The calling coordinator owns the ONE Room transaction. */
@Dao
public interface ExportHoldAdmissionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: ExportOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHold(hold: ExportAssetHoldEntity)

    /** Never replace/update; the operation and every hold must share this transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSourceBinding(binding: ExportSourceBindingEntity)

    @Query("SELECT * FROM export_source_bindings WHERE operation_id = :operationId LIMIT 1")
    suspend fun findSourceBinding(operationId: String): ExportSourceBindingEntity?

    /** V7 immutable handoff journal. The caller owns each Room transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalAttempt(attempt: ExportExternalHandoffAttemptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExternalReadback(observation: ExportExternalReadbackObservationEntity)

    @Query("SELECT * FROM export_external_handoff_attempts WHERE operation_id = :operationId LIMIT 1")
    suspend fun findExternalAttempt(operationId: String): ExportExternalHandoffAttemptEntity?

    @Query("SELECT * FROM export_external_readback_observations WHERE operation_id = :operationId LIMIT 1")
    suspend fun findExternalReadback(operationId: String): ExportExternalReadbackObservationEntity?

    @Query("SELECT * FROM export_operations WHERE operation_id = :operationId LIMIT 1")
    suspend fun findOperation(operationId: String): ExportOperationEntity?

    @Query("SELECT * FROM export_asset_holds WHERE operation_id = :operationId ORDER BY asset_id ASC")
    suspend fun listHolds(operationId: String): List<ExportAssetHoldEntity>

    /** Plus-one probe prevents unbounded recovery of malformed/excessive pin sets. */
    @Query("SELECT * FROM export_asset_holds WHERE operation_id = :operationId ORDER BY asset_id ASC LIMIT :rowLimit")
    suspend fun listHoldsBounded(operationId: String, rowLimit: Int): List<ExportAssetHoldEntity>

    /** The +1 probe is supplied by the bounded read-only recovery coordinator. */
    @Query("SELECT * FROM export_operations ORDER BY operation_id ASC LIMIT :rowLimit")
    suspend fun listRecoveryOperations(rowLimit: Int): List<ExportOperationEntity>

    /**
     * D2J-B: read-only note-scoped export history for conservative UI display.
     * The caller always applies a plus-one limit and rejects overflow; no
     * provider URI, original bytes or private staging path is returned.
     */
    @Query("""
        SELECT * FROM export_operations WHERE note_id = :noteId
        ORDER BY created_at_ms DESC, operation_id DESC LIMIT :rowLimit
    """)
    suspend fun listNoteExportOperationsBounded(noteId: String, rowLimit: Int): List<ExportOperationEntity>

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
