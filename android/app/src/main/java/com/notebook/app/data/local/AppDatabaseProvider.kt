package com.notebook.app.data.local

import android.content.Context
import androidx.room.Room

/** Single production DB instance. UI and STO-02 must use this provider. */
object AppDatabaseProvider {
    const val DATABASE_NAME: String = "library.sqlite"

    @Volatile private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext, DATABASE_NAME).also { instance = it }
    }

    /** Test-only helper: isolated on-disk database, identical callback/config. */
    internal fun openIsolated(context: Context, name: String): AppDatabase =
        build(context.applicationContext, name)

    private fun build(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(LibrarySchemaGuards.callback)
            // Deliberately NO fallbackToDestructiveMigration().
            .build()
}
