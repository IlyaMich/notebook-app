package com.notebook.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * STO-08: fail-closed constraints for the durable ownership-index gate.
 *
 * The gate never independently authorizes asset cleanup. In C1B-2, the only
 * writable states are UNVERIFIED and UNRESOLVED. Promotion to VERIFIED is
 * deliberately blocked until a separately reviewed audit protocol exists.
 *
 * Called only after the v4 state table is created and while the table is empty.
 * Both fresh creation and the v3-to-v4 migration must call this in their
 * database creation/migration transaction. Calling it twice must fail.
 *
 * The v4 table must declare both columns NOT NULL, primary key (id).
 */
internal object AssetReferenceIndexGuards {

    const val UNVERIFIED = "UNVERIFIED"
    const val UNRESOLVED = "UNRESOLVED"
    const val VERIFIED = "VERIFIED" // Reserved: cannot be set in C1B-2.

    fun initializeEmptyIndex(db: SupportSQLiteDatabase) {
        // In this checkpoint only the conservative initial state is insertable.
        // IS NOT rejects a NULL value as well as any other invalid state.
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS trg_asset_ref_gate_insert
               BEFORE INSERT ON asset_reference_index_state
               WHEN NEW.id IS NOT 1 OR NEW.state IS NOT 'UNVERIFIED'
               BEGIN
                   SELECT RAISE(ABORT, 'Invalid initial asset reference gate');
               END"""
        )

        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS trg_asset_ref_gate_update
               BEFORE UPDATE ON asset_reference_index_state
               WHEN NEW.id IS NOT 1 OR NEW.state IS NULL OR
                    NEW.state NOT IN ('UNVERIFIED', 'UNRESOLVED')
               BEGIN
                   SELECT RAISE(ABORT, 'Invalid asset reference gate transition');
               END"""
        )

        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS trg_asset_ref_gate_delete
               BEFORE DELETE ON asset_reference_index_state
               BEGIN
                   SELECT RAISE(ABORT, 'Asset reference gate cannot be deleted');
               END"""
        )

        db.execSQL(
            """INSERT INTO asset_reference_index_state (id, state)
               VALUES (1, 'UNVERIFIED')"""
        )
    }
}
