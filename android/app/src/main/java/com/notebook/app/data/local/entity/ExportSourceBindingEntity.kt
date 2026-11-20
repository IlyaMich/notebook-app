package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Additive v6 identity binding, committed in the same transaction as the v6
 * operation and all pins. Immutable, never a receipt or release authorization.
 * Legacy v5 operations intentionally have NO backfilled binding.
 */
@Entity(
    tableName = "export_source_bindings",
    foreignKeys = [
        ForeignKey(
            entity = ExportOperationEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["operation_id"],
            onUpdate = ForeignKey.RESTRICT,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class ExportSourceBindingEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "snapshot_sha256") val snapshotSha256: String,
    @ColumnInfo(name = "asset_set_sha256") val assetSetSha256: String,
    @ColumnInfo(name = "expected_asset_count") val expectedAssetCount: Int,
    @ColumnInfo(name = "expected_asset_bytes") val expectedAssetBytes: Long,
    @ColumnInfo(name = "binding_version") val bindingVersion: Int,
)
