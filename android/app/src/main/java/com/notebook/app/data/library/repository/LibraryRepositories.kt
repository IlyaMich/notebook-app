package com.notebook.app.data.library.repository

import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.model.Notebook

/** Implementations validate inside one DB write transaction, not from UI-only checks. */
interface FolderRepository {
    suspend fun create(name: String, parentFolderId: String? = null): Folder
    suspend fun rename(folderId: String, name: String): Folder
    suspend fun move(folderId: String, newParentFolderId: String?): Folder
    suspend fun findById(folderId: String): Folder?
    suspend fun listByParent(parentFolderId: String? = null): List<Folder>
}

interface NotebookRepository {
    /** Idempotent; guarantees the one distinguished, protected root notebook. */
    suspend fun ensureQuickNotes(): Notebook
    suspend fun create(name: String, parentFolderId: String? = null): Notebook
    suspend fun rename(notebookId: String, name: String): Notebook
    suspend fun move(notebookId: String, newParentFolderId: String?): Notebook
    suspend fun findById(notebookId: String): Notebook?
    suspend fun listByParent(parentFolderId: String? = null): List<Notebook>
}

/** Metadata-only actions; no method here creates or clones a zero-page note. */
interface NoteRepository {
    suspend fun findById(noteId: String): Note?
    suspend fun listByNotebook(notebookId: String): List<Note>
    suspend fun listRecent(limit: Int): List<Note>
    suspend fun rename(noteId: String, title: String): Note
    suspend fun move(noteId: String, newNotebookId: String): Note
    suspend fun recordOpened(noteId: String): Note
}

/**
 * The LIB-03 implementation must wrap note metadata and the STO-02 content port
 * in ONE AppDatabase.withTransaction block. Never insert metadata alone.
 */
interface NoteLifecycleRepository {
    /** Null notebook means the distinguished Quick Notes notebook. */
    suspend fun createNote(
        notebookId: String? = null,
        title: String = "",
        firstPage: InitialPageSpec = InitialPageSpec.a4PortraitBlank(),
    ): Note

    /** Clone ordered pages/elements and remap IDs; unsupported assets abort the whole write. */
    suspend fun duplicateNote(sourceNoteId: String, targetNotebookId: String? = null): Note
}

/** Single user-visible deletion action. Descendants in the same batch are restored together. */
enum class TrashedItemKind { FOLDER, NOTEBOOK, NOTE }

data class TrashedBatch(
    val id: String,
    val deletedAtMillis: Long,
    val kind: TrashedItemKind,
    val rootId: String,
    val title: String,
    val folderCount: Int,
    val notebookCount: Int,
    val noteCount: Int,
)

/** Confirmation snapshot for one asset-free deletion batch. Do not synthesize in the UI.
 * The fingerprint binds exact current membership and persisted content to the preview.
 */
data class PurgePreview(
    val deletionBatchId: String,
    val kind: TrashedItemKind,
    val title: String,
    val folderCount: Int,
    val notebookCount: Int,
    val noteCount: Int,
    val pageCount: Int,
    val elementCount: Int,
    val survivingFolderCount: Int,
    val survivingNotebookCount: Int,
    val survivingNoteCount: Int,
    val fingerprint: String,
)

/** Trash access: soft-delete/restore plus explicitly confirmed asset-free batch purge. */
interface LibraryTrashRepository {
    /** One entry per deletion batch, newest first; never expose underlying DAOs to UI. */
    suspend fun listTrashedBatches(): List<TrashedBatch>
    suspend fun trashFolder(folderId: String): String // deletion batch UUID
    suspend fun trashNotebook(notebookId: String): String
    suspend fun trashNote(noteId: String): String
    suspend fun restoreBatch(deletionBatchId: String)
    /** Read-only, fail-closed preview; unsupported/corrupt content prevents confirmation. */
    suspend fun previewPurge(deletionBatchId: String): PurgePreview
    /** Rechecks the exact preview inside one write transaction; no partial purge. */
    suspend fun purgeBatch(confirmedPreview: PurgePreview)
}
