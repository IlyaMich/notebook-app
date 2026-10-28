package com.notebook.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.notebook.app.data.local.dao.ElementDao
import com.notebook.app.data.local.dao.PageDao
import com.notebook.app.data.local.dao.FolderDao
import com.notebook.app.data.local.dao.NotebookDao
import com.notebook.app.data.local.dao.NoteDao
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.data.local.entity.FolderEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.NoteEntity

/**
 * The single Room database shared by LIB-03 and STO-02.
 *
 * V2 adds STO-02 page/element rows and keeps LIB-03 note metadata unchanged.
 * The lifecycle port remains unconnected to production until Checkpoint 3B.
 */
@Database(
    entities = [
        FolderEntity::class, NotebookEntity::class, NoteEntity::class,
        PageEntity::class, ElementEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao
    abstract fun pageDao(): PageDao
    abstract fun elementDao(): ElementDao
}
