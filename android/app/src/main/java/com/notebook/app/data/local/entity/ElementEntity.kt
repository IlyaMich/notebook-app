package com.notebook.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One complete canonical envelope per row; 'kind' is only its validated index. */
@Entity(
    tableName = "elements",
    foreignKeys = [ForeignKey(
        entity = PageEntity::class,
        parentColumns = ["id"],
        childColumns = ["page_id"],
        onDelete = ForeignKey.RESTRICT,
        onUpdate = ForeignKey.RESTRICT,
    )],
    indices = [
        Index(value = ["page_id", "order_index"], name = "ux_elements_page_order", unique = true),
    ],
)
data class ElementEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "element_json") val elementJson: String,
    /** Last changed note revision, not a separate document save counter. */
    @ColumnInfo(name = "revision") val revision: Long,
)
