package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * STO-08 C1B-2: durable projection of an image element's direct owner.
 *
 * Registered in Room v4. Canonical elements.element_json remains authoritative.
 * Legacy backfill and transactional writers are pending.
 * Empty or incomplete reference tables must never authorize asset deletion.
 */
@Entity(
    tableName = "element_asset_references",
    foreignKeys = [
        ForeignKey(
            entity = ElementEntity::class,
            parentColumns = ["id"],
            childColumns = ["element_id"],
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
    indices = [
        Index(value = ["asset_id"], name = "ix_element_asset_references_asset"),
    ],
)
data class ElementAssetReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "element_id") val elementId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
)
