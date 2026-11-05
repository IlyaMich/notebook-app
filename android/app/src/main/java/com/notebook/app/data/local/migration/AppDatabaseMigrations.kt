package com.notebook.app.data.local.migration

import androidx.room.migration.Migration
import com.notebook.app.data.local.AssetReferenceIndexGuards
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/** Explicit, non-destructive schema upgrades. Keep each historical migration forever. */
internal object AppDatabaseMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `pages` (
                    `id` TEXT NOT NULL,
                    `note_id` TEXT NOT NULL,
                    `order_index` INTEGER NOT NULL,
                    `width` REAL NOT NULL,
                    `height` REAL NOT NULL,
                    `background_json` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `ux_pages_note_order` ON `pages` (`note_id`, `order_index`)"
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `elements` (
                    `id` TEXT NOT NULL,
                    `page_id` TEXT NOT NULL,
                    `order_index` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `element_json` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `ux_elements_page_order` ON `elements` (`page_id`, `order_index`)"
            )

            // V1 stored note metadata but had no page table. Preserve every
            // pre-existing note (including trashed notes) by giving it exactly
            // one blank A4 page. This is a migration repair, NOT an edit: do
            // not change notes.revision, notes.updated_at or other metadata.
            // No element or asset existed in V1, so nothing can be lost here.
            db.query("SELECT id, revision FROM notes").use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val revisionIndex = cursor.getColumnIndexOrThrow("revision")
                while (cursor.moveToNext()) {
                    db.execSQL(
                        """INSERT INTO pages
                            (id, note_id, order_index, width, height, background_json, revision)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                        arrayOf(
                            UUID.randomUUID().toString(), cursor.getString(idIndex), 0L,
                            595.276, 841.890, """{"type":"blank","color":"#FFFFFF"}""",
                            cursor.getLong(revisionIndex),
                        ),
                    )
                }
            }
        }
    }
    /** Add metadata only. Never rewrite/delete legacy notes, pages, elements or Trash rows. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `assets` (
                    `id` TEXT NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `byte_size` INTEGER NOT NULL,
                    `relative_path` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_sha256` ON `assets` (`sha256`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `ux_assets_relative_path` ON `assets` (`relative_path`)"
            )
        }
    }

    /**
     * STO-08 C1B-2: additive ownership-index storage only.
     * No legacy backfill, document rewrite or deletion.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `page_asset_references` (
                    `page_id` TEXT NOT NULL,
                    `asset_id` TEXT NOT NULL,
                    PRIMARY KEY(`page_id`),
                    FOREIGN KEY(`page_id`) REFERENCES `pages`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
                    FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
                )""".trimIndent()
            )

            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `ix_page_asset_references_asset`
                   ON `page_asset_references` (`asset_id`)""".trimIndent()
            )

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `element_asset_references` (
                    `element_id` TEXT NOT NULL,
                    `asset_id` TEXT NOT NULL,
                    PRIMARY KEY(`element_id`),
                    FOREIGN KEY(`element_id`) REFERENCES `elements`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT,
                    FOREIGN KEY(`asset_id`) REFERENCES `assets`(`id`) ON UPDATE RESTRICT ON DELETE RESTRICT
                )""".trimIndent()
            )

            db.execSQL(
                """CREATE INDEX IF NOT EXISTS `ix_element_asset_references_asset`
                   ON `element_asset_references` (`asset_id`)""".trimIndent()
            )

            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `asset_reference_index_state` (
                    `id` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )

            // Empty tables do NOT imply that historical assets are unowned.
            AssetReferenceIndexGuards.initializeEmptyIndex(db)
        }
    }
}
