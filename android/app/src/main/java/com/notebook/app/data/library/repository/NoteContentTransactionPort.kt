package com.notebook.app.data.library.repository

import com.notebook.app.data.library.model.InitialPageSpec

/**
 * Contract for STO-02 implementation. LIB-03 owns this integration port;
 * STO-02 supplies its concrete adapter later, using the SAME AppDatabase.
 *
 * These methods must be called only inside the caller's shared Room transaction.
 * They must NOT create another database, start independent transactions, or
 * acknowledge a successful save. Any exception aborts the entire outer write.
 */
interface NoteContentTransactionPort {
    /** Insert initial page (orderIndex = 0) for a newly inserted note row. */
    suspend fun insertInitialPage(noteId: String, spec: InitialPageSpec)

    /**
     * Snapshot and clone all page/element rows in order, assigning fresh IDs.
     * Reject unsupported asset-backed content until reference handling exists.
     * No partial clone may commit.
     */
    suspend fun cloneContent(sourceNoteId: String, targetNoteId: String)
}
