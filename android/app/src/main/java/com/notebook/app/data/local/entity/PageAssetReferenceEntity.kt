package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * STO-08 C1B-2A: proposed durable projection of a PDF-backed page's direct owner.
 *
 * Not registered in AppDatabase until the explicit v3 -> v4 migration and
 * historical tests are ready. Canonical pages.background_json remains authoritative.
 * Empty or incomplete reference tables must never authorize asset deletion.
 */
@Entity(
    tableName = "page_asset_references",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["page_id"],
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
        Index(value = ["asset_id"], name = "ix_page_asset_references_asset"),
    ],
)
data class PageAssetReferenceEntity(
    @PrimaryKey @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
)
