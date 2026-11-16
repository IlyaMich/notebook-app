package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** I5-B6A: persistent operation identity only. No writer, receipt or release authority. */
@Entity(tableName = "export_operations")
data class ExportOperationEntity(
    @PrimaryKey @ColumnInfo(name = "operation_id") val operationId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "note_revision") val noteRevision: Long,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMillis: Long,
    @ColumnInfo(name = "protocol_version") val protocolVersion: Int,
)
