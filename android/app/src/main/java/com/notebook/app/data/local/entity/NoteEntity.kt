package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Sole LIB-03 Room owner of notes metadata; STO-02 references this primary key. */
@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(
        entity = NotebookEntity::class,
        parentColumns = ["id"],
        childColumns = ["notebook_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["notebook_id", "deleted_at", "updated_at", "id"], name = "ix_notes_notebook_recent"),
        Index(value = ["deletion_batch_id"], name = "ix_notes_deletion_batch"),
    ],
)
data class NoteEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "notebook_id") val notebookId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAtMillis: Long? = null,
    @ColumnInfo(name = "revision") val revision: Long = 0L,
    @ColumnInfo(name = "deleted_at") val deletedAtMillis: Long? = null,
    @ColumnInfo(name = "deletion_batch_id") val deletionBatchId: String? = null,
    @ColumnInfo(name = "original_notebook_id") val originalNotebookId: String? = null,
)
