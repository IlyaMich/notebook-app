package com.notebook.app.data.library.impl

import android.content.Context
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider

/** All repositories share one production AppDatabase; no DAO is exposed to UI. */
class LibraryRepositoryFactory(private val database: AppDatabase) {
    val folders: FolderRepository = RoomFolderRepository(database)
    val notebooks: NotebookRepository = RoomNotebookRepository(database)
    val notes: NoteRepository = RoomNoteRepository(database)

    companion object {
        fun from(context: Context): LibraryRepositoryFactory =
            LibraryRepositoryFactory(AppDatabaseProvider.get(context))
    }
}
