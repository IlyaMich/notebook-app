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

    /** v6 immutable source-to-hold binding; never creates release permissions. */
    fun installSourceBindings(db: SupportSQLiteDatabase) {
        listOf(
            """CREATE TRIGGER IF NOT EXISTS `trg_export_source_binding_no_update`
               BEFORE UPDATE ON `export_source_bindings`
               BEGIN SELECT RAISE(ABORT, 'Export source binding cannot change'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_source_binding_no_delete`
               BEFORE DELETE ON `export_source_bindings`
               BEGIN SELECT RAISE(ABORT, 'Export source binding cannot be deleted'); END""",
        ).forEach(db::execSQL)
    }

    /** V7: strictly insert-only, one attempt + at most one untrusted readback. */
    fun installExternalObservations(db: SupportSQLiteDatabase) {
        listOf(
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_attempt_no_update`
               BEFORE UPDATE ON `export_external_handoff_attempts`
               BEGIN SELECT RAISE(ABORT, 'External handoff attempt cannot change'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_attempt_no_delete`
               BEFORE DELETE ON `export_external_handoff_attempts`
               BEGIN SELECT RAISE(ABORT, 'External handoff attempt cannot be deleted'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_observation_no_update`
               BEFORE UPDATE ON `export_external_readback_observations`
               BEGIN SELECT RAISE(ABORT, 'External readback cannot change'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_observation_no_delete`
               BEFORE DELETE ON `export_external_readback_observations`
               BEGIN SELECT RAISE(ABORT, 'External readback cannot be deleted'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_attempt_validate_insert`
               BEFORE INSERT ON `export_external_handoff_attempts`
               WHEN NEW.archive_bytes <= 0 OR NEW.attempted_at_ms < 0 OR
                    NEW.attempt_version != 1 OR
                    length(NEW.destination_identity_sha256) != 64 OR
                    length(NEW.archive_sha256) != 64 OR
                    length(NEW.snapshot_sha256) != 64 OR
                    length(NEW.asset_set_sha256) != 64 OR
                    NOT EXISTS (SELECT 1 FROM export_source_bindings b
                        JOIN export_operations o ON o.operation_id=b.operation_id
                        WHERE o.operation_id=NEW.operation_id
                          AND o.protocol_version=2
                          AND o.state='COMMITTED_PIN_SET'
                          AND b.binding_version=1
                          AND b.snapshot_sha256=NEW.snapshot_sha256
                          AND b.asset_set_sha256=NEW.asset_set_sha256)
               BEGIN SELECT RAISE(ABORT, 'Invalid or unbound external attempt'); END""",
            """CREATE TRIGGER IF NOT EXISTS `trg_export_external_observation_validate_insert`
               BEFORE INSERT ON `export_external_readback_observations`
               WHEN NEW.observed_at_ms < 0 OR NEW.observation_version != 1 OR
                    NOT EXISTS (SELECT 1 FROM export_external_handoff_attempts a
                        WHERE a.operation_id=NEW.operation_id
                          AND a.archive_sha256=NEW.observed_sha256
                          AND a.archive_bytes=NEW.observed_bytes
                          AND NEW.observed_at_ms>=a.attempted_at_ms)
               BEGIN SELECT RAISE(ABORT, 'External readback differs from attempt'); END""",
        ).forEach(db::execSQL)
    }
}
