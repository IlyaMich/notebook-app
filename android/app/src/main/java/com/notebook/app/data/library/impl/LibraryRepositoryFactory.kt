package com.notebook.app.data.library.impl

import android.content.Context
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.impl.RoomNoteContentTransactionAdapter
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.library.repository.NoteLifecycleRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider

/** All repositories share one production AppDatabase; no DAO is exposed to UI. */
class LibraryRepositoryFactory(private val database: AppDatabase) {
    val folders: FolderRepository = RoomFolderRepository(database)
    val notebooks: NotebookRepository = RoomNotebookRepository(database)
    val notes: NoteRepository = RoomNoteRepository(database)
    val documents: DocumentRepository = RoomDocumentRepository(database)

    /** Production wiring: the real STO-02 adapter uses this same database. */
    fun noteLifecycle(): NoteLifecycleRepository =
        RoomNoteLifecycleRepository(database, RoomNoteContentTransactionAdapter(database))

    /** Explicit injection retained for transaction fault tests and integrations. */
    fun noteLifecycle(content: NoteContentTransactionPort): NoteLifecycleRepository =
        RoomNoteLifecycleRepository(database, content)

    companion object {
        fun from(context: Context): LibraryRepositoryFactory =
            LibraryRepositoryFactory(AppDatabaseProvider.get(context))
    }
}
