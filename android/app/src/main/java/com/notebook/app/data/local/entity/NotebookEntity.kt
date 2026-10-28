package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Quick Notes is recognized by system_role, never by name or a synthetic ID. */
@Entity(
    tableName = "notebooks",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["parent_folder_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["parent_folder_id", "deleted_at", "sort_order", "id"], name = "ix_notebooks_parent_listing"),
        Index(value = ["deletion_batch_id"], name = "ix_notebooks_deletion_batch"),
        // SQLite UNIQUE allows multiple NULLs. A trigger restricts non-NULL values
        // to 'quick_notes', making this equivalent to the proposed partial index.
        Index(value = ["system_role"], name = "ux_notebooks_quick_notes_role", unique = true),
    ],
)
data class NotebookEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "system_role") val systemRole: String? = null,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at") val deletedAtMillis: Long? = null,
    @ColumnInfo(name = "deletion_batch_id") val deletionBatchId: String? = null,
    @ColumnInfo(name = "original_parent_folder_id") val originalParentFolderId: String? = null,
    @ColumnInfo(name = "original_sort_order") val originalSortOrder: Long? = null,
)
