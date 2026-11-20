package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * V7 append-only diagnostic of a provider returning matching bytes once.
 * No provider-independent power-loss durability or atomic visibility is claimed.
 * Strict SQL triggers prevent UPDATE/DELETE and mismatched observations.
 */
@Entity(
    tableName = "export_external_readback_observations",
    foreignKeys = [
        ForeignKey(
            entity = ExportExternalHandoffAttemptEntity::class,
            parentColumns = ["operation_id"],
            childColumns = ["operation_id"],
            onUpdate = ForeignKey.RESTRICT,
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
)
data class ExportExternalReadbackObservationEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "observed_sha256") val observedSha256: String,
    @ColumnInfo(name = "observed_bytes") val observedBytes: Long,
    @ColumnInfo(name = "observed_at_ms") val observedAtMillis: Long,
    @ColumnInfo(name = "observation_version") val observationVersion: Int,
)
