package com.notebook.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.notebook.app.data.local.dao.FolderDao
import com.notebook.app.data.local.dao.NotebookDao
import com.notebook.app.data.local.dao.NoteDao
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.NoteEntity

/**
 * The single Room database shared by LIB-03 and STO-02.
 *
 * Version 1 contains only library metadata. The public note lifecycle remains
 * unimplemented until STO-02 can insert its first page in the SAME transaction.
 * Adding page/element entities will require an explicit versioned migration.
 */
@Database(
    entities = [FolderEntity::class, NotebookEntity::class, NoteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao
}
