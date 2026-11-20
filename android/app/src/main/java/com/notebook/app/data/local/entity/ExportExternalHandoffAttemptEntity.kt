package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * V7 append-only record of ONE external provider write attempt per export.
 * A persisted attempt is NOT a portable document, destination receipt,
 * permission to retry, hold release or GC authority.
 */
@Entity(
    tableName = "export_external_handoff_attempts",
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
data class ExportExternalHandoffAttemptEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "destination_identity_sha256") val destinationIdentitySha256: String,
    @ColumnInfo(name = "archive_sha256") val archiveSha256: String,
    @ColumnInfo(name = "archive_bytes") val archiveBytes: Long,
    @ColumnInfo(name = "snapshot_sha256") val snapshotSha256: String,
    @ColumnInfo(name = "asset_set_sha256") val assetSetSha256: String,
    @ColumnInfo(name = "attempted_at_ms") val attemptedAtMillis: Long,
    @ColumnInfo(name = "attempt_version") val attemptVersion: Int,
)
