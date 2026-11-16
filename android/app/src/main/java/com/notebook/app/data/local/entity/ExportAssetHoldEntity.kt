package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** I5-B6A: retained per-operation asset edge; no release or GC API. */
@Entity(
    tableName = "export_asset_holds",
    primaryKeys = ["operation_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = ExportOperationEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["operation_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["asset_id"], name = "ix_export_asset_holds_asset")],
)
data class ExportAssetHoldEntity(
    @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
)
