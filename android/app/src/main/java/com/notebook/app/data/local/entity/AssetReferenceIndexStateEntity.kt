package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * STO-08 C1B-2A: persisted index-completeness gate, NOT a GC permission.
 *
 * C1B-2B must enforce singleton ID 1 and recognized state values at the SQLite
 * level on both fresh creation and migration, initializing UNVERIFIED in both.
 * Missing/invalid state is always unsafe; no code may automatically set VERIFIED.
 * This declaration is not yet registered in AppDatabase or used by readers.
 */
@Entity(tableName = "asset_reference_index_state")
data class AssetReferenceIndexStateEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "state") val state: String,
)
