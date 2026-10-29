package com.notebook.app.data.local.migration

import androidx.room.migration.Migration
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
}
