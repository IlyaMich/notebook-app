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
}
