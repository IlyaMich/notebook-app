package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** LIB-03 row. Historical original_parent_folder_id intentionally has no FK. */
@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(
        entity = FolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["parent_folder_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["parent_folder_id", "deleted_at", "sort_order", "id"], name = "ix_folders_parent_listing"),
        Index(value = ["deletion_batch_id"], name = "ix_folders_deletion_batch"),
    ],
)
data class FolderEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "parent_folder_id") val parentFolderId: String?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    @ColumnInfo(name = "deleted_at") val deletedAtMillis: Long? = null,
    @ColumnInfo(name = "deletion_batch_id") val deletionBatchId: String? = null,
    @ColumnInfo(name = "original_parent_folder_id") val originalParentFolderId: String? = null,
    @ColumnInfo(name = "original_sort_order") val originalSortOrder: Long? = null,
)
