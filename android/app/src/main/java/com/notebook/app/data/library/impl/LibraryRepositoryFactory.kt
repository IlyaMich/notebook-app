package com.notebook.app.data.library.impl

import android.content.Context
import java.io.File
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.impl.RoomNoteContentTransactionAdapter
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.library.repository.NoteLifecycleRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.library.repository.LibraryTrashRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider

/** All repositories share one production AppDatabase; no DAO is exposed to UI. */
class LibraryRepositoryFactory(
    private val database: AppDatabase,
    private val verifiedMediaRoot: File? = null,
) {
    val folders: FolderRepository = RoomFolderRepository(database)
    val notebooks: NotebookRepository = RoomNotebookRepository(database)
    val notes: NoteRepository = RoomNoteRepository(database)
    // The production composition root supplies application-private storage.
    // Database-only test factories remain explicitly fail-closed for media writes.
    val documents: DocumentRepository = RoomDocumentRepository(
        database, verifiedImageRoot = verifiedMediaRoot
    )
    val trash: LibraryTrashRepository = RoomLibraryTrashRepository(database)

    /** Production wiring: the real STO-02 adapter uses this same database. */
    fun noteLifecycle(): NoteLifecycleRepository =
        RoomNoteLifecycleRepository(database, RoomNoteContentTransactionAdapter(database))

    /** Explicit injection retained for transaction fault tests and integrations. */
    fun noteLifecycle(content: NoteContentTransactionPort): NoteLifecycleRepository =
        RoomNoteLifecycleRepository(database, content)

    companion object {
        fun from(context: Context): LibraryRepositoryFactory =
            LibraryRepositoryFactory(
                AppDatabaseProvider.get(context.applicationContext),
                context.applicationContext.filesDir,
            )
    }
}
