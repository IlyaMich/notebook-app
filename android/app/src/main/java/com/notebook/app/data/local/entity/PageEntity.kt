package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** STO-02 canonical page metadata. No viewport/cached rendering state is stored. */
@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = NoteEntity::class,
        parentColumns = ["id"],
        childColumns = ["note_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["note_id", "order_index"], name = "ux_pages_note_order", unique = true),
    ],
)
data class PageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Long,
    @ColumnInfo(name = "width") val width: Double,
    @ColumnInfo(name = "height") val height: Double,
    @ColumnInfo(name = "background_json") val backgroundJson: String,
    /** Last changed note revision, not a separate document save counter. */
    @ColumnInfo(name = "revision") val revision: Long,
)
