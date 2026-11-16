package com.notebook.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** I5-B6A: immutable export-owner rows. No release protocol is implemented. */
internal object ExportHoldSchemaGuards {
    fun install(db: SupportSQLiteDatabase) {
        val triggers = listOf(
            """CREATE TRIGGER IF NOT EXISTS `trg_export_hold_no_delete`
               BEFORE DELETE ON `export_asset_holds`
               BEGIN SELECT RAISE(ABORT, 'Export holds cannot be released'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_hold_no_update`
               BEFORE UPDATE ON `export_asset_holds`
               BEGIN SELECT RAISE(ABORT, 'Export holds cannot be changed'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_operation_no_delete`
               BEFORE DELETE ON `export_operations`
               BEGIN SELECT RAISE(ABORT, 'Export operations cannot be deleted'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_operation_no_update`
               BEFORE UPDATE ON `export_operations`
               BEGIN SELECT RAISE(ABORT, 'Export operation phase changes are not implemented'); END""",
        )
        triggers.forEach(db::execSQL)
    }
}
