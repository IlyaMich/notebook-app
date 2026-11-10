package com.notebook.app.data.content.repository

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent

/** Repository is the only supported application entry point for page/element writes. */
interface DocumentRepository {
    /** Transactional consistent snapshot; returns canonical JSON unchanged. */
    suspend fun load(noteId: String): StoredNoteContent

    /** Returns only after durable Room commit. A no-op returns the existing revision. */
    suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit

    /**
     * Atomically reject this edit unless the note is still at [expectedRevision].
     * The comparison and any mutation MUST occur in the same storage transaction.
     *
     * Fail closed for existing repository decorators/test doubles that have not
     * explicitly implemented conditional commits. Never emulate this using
     * load() followed by commit(): that creates a time-of-check/time-of-use race.
     */
    suspend fun commitIfRevision(
        noteId: String,
        expectedRevision: Long,
        edit: DocumentEdit,
    ): DocumentCommit = throw UnsupportedOperationException(
        "This repository does not support atomic revision-guarded commits"
    )
}
