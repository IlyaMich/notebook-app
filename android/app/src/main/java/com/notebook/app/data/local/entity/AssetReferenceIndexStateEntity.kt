package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * STO-08 C1B-2: persisted index-completeness gate, NOT a GC permission.
 *
 * SQLite triggers constrain singleton ID 1 and permissible state transitions
 * on both fresh creation and v3-to-v4 migration, starting UNVERIFIED.
 * Direct promotion to VERIFIED remains prohibited pending reviewed audit work.
 * Missing/invalid state must be treated as unsafe by future cleanup readers.
 */
@Entity(tableName = "asset_reference_index_state")
data class AssetReferenceIndexStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "state") val state: String,
)
