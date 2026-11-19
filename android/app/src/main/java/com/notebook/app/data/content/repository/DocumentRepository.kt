package com.notebook.app.data.content.repository

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.InsertVerifiedImageCommand
import com.notebook.app.data.content.model.StoredNoteContent

/** Repository is the only supported application entry point for page/element writes. */
interface DocumentRepository {
    /** Transactional consistent snapshot; returns canonical JSON unchanged. */
    suspend fun load(noteId: String): StoredNoteContent

    /**
     * Persist a new image element only after verified original-byte inspection.
     * Implementations lacking the lease-backed writer MUST reject the command.
     * Never downgrade this to generic InsertElements or caller-supplied JSON.
     */
    suspend fun insertVerifiedImage(command: InsertVerifiedImageCommand): DocumentCommit =
        throw UnsupportedOperationException("Verified image insertion is unavailable")

    /**
     * Replace an existing image using the verified original-byte and ownership
     * protocol. This operation is never a generic ReplaceElements fallback.
     */
    suspend fun replaceVerifiedImage(command: InsertVerifiedImageCommand): DocumentCommit =
        throw UnsupportedOperationException("Verified image replacement is unavailable")

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
