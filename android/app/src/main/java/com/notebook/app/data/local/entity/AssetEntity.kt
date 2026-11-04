package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadata for an immutable original in app-private persistent storage.
 *
 * This is a schema declaration only. Registration is not yet wired to imports,
 * and document references, export pins, reconciliation and deletion are deferred.
 * A future repository must validate these values against finalized bytes before
 * inserting a row; Room's primitive column types alone do not validate hashes,
 * paths, MIME types, byte counts or lifecycle state transitions.
 */
@Entity(
    tableName = "assets",
    indices = [
        Index(value = ["sha256"], name = "ux_assets_sha256", unique = true),
        Index(value = ["relative_path"], name = "ux_assets_relative_path", unique = true),
    ],
)
data class AssetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "state") val state: String,
)
