package com.notebook.app.data.library.model

/** Identity and relationships are library-local metadata, not portable page geometry. */
data class Folder(
    val id: String,
    val parentFolderId: String?,
    val name: String,
    val sortOrder: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long? = null,
    val deletionBatchId: String? = null,
    val originalParentFolderId: String? = null,
    val originalSortOrder: Long? = null,
)

data class Notebook(
    val id: String,
    val parentFolderId: String?,
    val name: String,
    val color: String? = null,
    val sortOrder: Long,
    /** Exactly one active root notebook receives this role, independent of its name. */
    val systemRole: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val deletedAtMillis: Long? = null,
    val deletionBatchId: String? = null,
    val originalParentFolderId: String? = null,
    val originalSortOrder: Long? = null,
) {
    val isQuickNotes: Boolean get() = systemRole == QUICK_NOTES_ROLE

    companion object {
        const val QUICK_NOTES_ROLE: String = "quick_notes"
    }
}

data class Note(
    val id: String,
    val notebookId: String,
    /** Local library titles may be empty; portable export chooses a valid title. */
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastOpenedAtMillis: Long? = null,
    /** Durable content revision; rename/move must not increment it. */
    val revision: Long = 0L,
    val deletedAtMillis: Long? = null,
    val deletionBatchId: String? = null,
    val originalNotebookId: String? = null,
)
