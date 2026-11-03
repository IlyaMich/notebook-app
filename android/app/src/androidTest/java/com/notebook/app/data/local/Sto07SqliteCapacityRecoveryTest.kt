package com.notebook.app.data.local

import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.local.migration.AppDatabaseMigrations
import com.notebook.app.ui.editor.NoteAutosaveCoordinator
import com.notebook.app.ui.editor.SavePhase
import com.notebook.app.ui.editor.SaveRetryResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val S03_WHITE = """{"type":"blank","color":"#FFFFFF"}"""

/** Records the exception emitted by the real Room repository, without injecting one. */
private class RecordingCapacityRepository(
    private val delegate: DocumentRepository,
) : DocumentRepository {
    @Volatile var commitFailure: Exception? = null
        private set

    override suspend fun load(noteId: String): StoredNoteContent = delegate.load(noteId)

    override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
        try {
            delegate.commit(noteId, edit)
        } catch (exception: Exception) {
            commitFailure = exception
            throw exception
        }
}

/**
 * STO-07 S03-A: exercise SQLite's actual page-count capacity limit on a
 * disposable, non-WAL Room database with a single SQLite connection.
 *
 * This is NOT device-wide storage exhaustion and is not a test of the app's
 * production WAL configuration. S03-B must cover device-level low storage.
 */
@RunWith(AndroidJUnit4::class)
class Sto07SqliteCapacityRecoveryTest {

    private fun pragmaLong(sql: SupportSQLiteDatabase, statement: String): Long =
        sql.query(statement).use { cursor ->
            check(cursor.moveToFirst()) { "No result from $statement" }
            cursor.getLong(0)
        }

    private fun pragmaString(sql: SupportSQLiteDatabase, statement: String): String =
        sql.query(statement).use { cursor ->
            check(cursor.moveToFirst()) { "No result from $statement" }
            cursor.getString(0)
        }

    private fun isSqliteFull(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is SQLiteFullException ||
                current.message?.contains("SQLITE_FULL", ignoreCase = true) == true ||
                current.message?.contains("database or disk is full", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun shapeEnvelope(id: String): String =
        """{"id":"$id","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"line","geometry":{"x1":12,"y1":20,"x2":40,"y2":60},"stroke":{"color":"#2255AA","width":2,"opacity":1},"fill":null}}"""

    @Test
    fun sqliteFullRollsBackAndCapacityRestoreRetriesExactlyOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "sto07-s03-capacity-${UUID.randomUUID()}.sqlite"
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val otherNoteId = UUID.randomUUID().toString()
        val otherPageId = UUID.randomUUID().toString()
        val elementIds = (0 until 512).map { UUID.randomUUID().toString() }
        val envelopes = elementIds.map(::shapeEnvelope)
        // Separate canonical-format mistakes from genuine storage-capacity failures.
        envelopes.forEach { CanonicalContentValidator.element(it) }

        // Test-only configuration: TRUNCATE keeps the capacity pragma and Room writes
        // on a single SQLite connection instead of Android's pooled WAL connections.
        // Keep the production AppDatabaseProvider unchanged; this is not proof of
        // storage exhaustion under the production WAL configuration.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addCallback(LibrarySchemaGuards.callback)
            .addMigrations(AppDatabaseMigrations.MIGRATION_1_2)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sql = db.openHelper.writableDatabase
            sql.execSQL(
                """INSERT INTO notebooks(id, parent_folder_id, name, sort_order,
                   system_role, created_at, updated_at)
                   VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
                arrayOf(notebookId, "S03 disposable notebook"),
            )
            for ((id, title) in listOf(noteId to "S03 capacity note", otherNoteId to "Unrelated note")) {
                sql.execSQL(
                    """INSERT INTO notes(id, notebook_id, title,
                       created_at, updated_at, revision) VALUES (?, ?, ?, 100, 200, 7)""",
                    arrayOf(id, notebookId, title),
                )
            }
            for ((id, owner) in listOf(pageId to noteId, otherPageId to otherNoteId)) {
                sql.execSQL(
                    """INSERT INTO pages(id, note_id, order_index, width, height,
                       background_json, revision)
                       VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
                    arrayOf(id, owner, S03_WHITE),
                )
            }

            val realRepository = RoomDocumentRepository(db)
            val repository = RecordingCapacityRepository(realRepository)
            val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)

            val journalMode = pragmaString(sql, "PRAGMA journal_mode")
            assertEquals("S03-A requires isolated non-WAL journaling", "truncate", journalMode.lowercase())
            val originalLimit = pragmaLong(sql, "PRAGMA max_page_count")
            val currentPages = pragmaLong(sql, "PRAGMA page_count")
            val constrainedLimit = pragmaLong(sql, "PRAGMA max_page_count = $currentPages")
            assertEquals("SQLite did not apply the test capacity limit", currentPages, constrainedLimit)

            coordinator.enqueue(
                DocumentEdit.InsertElements(pageId = pageId, envelopes = envelopes),
            )

            // Observe both outcomes: a successful write is an immediate test failure,
            // not an unexplained 20-second timeout waiting for FAILED.
            val failed = withTimeoutOrNull(20_000) {
                coordinator.state.first {
                    it.phase == SavePhase.FAILED ||
                        (it.phase == SavePhase.SAVED && it.committedRevision >= 8L)
                }
            } ?: error(
                "S03-A timeout waiting for a terminal save state; " +
                    "state=${coordinator.state.value}, " +
                    "repositoryFailure=${repository.commitFailure}, " +
                    "journalMode=$journalMode, " +
                    "initialPageCount=$currentPages, " +
                    "configuredMaxPages=$constrainedLimit"
            )
            assertEquals(
                "Expected SQLITE_FULL and FAILED; " +
                    "state=$failed, repositoryFailure=${repository.commitFailure}, " +
                    "journalMode=$journalMode, pageCount=${pragmaLong(sql, "PRAGMA page_count")}, " +
                    "configuredMaxPages=$constrainedLimit",
                SavePhase.FAILED,
                failed.phase,
            )
            // SQLite may automatically roll back a transaction on SQLITE_FULL.
            // Android's subsequent Room/SQLite rollback can then surface only
            // "cannot rollback - no transaction is active", hiding the first error.
            // Prove the configured limit truly produces SQLITE_FULL through the
            // same database handle, outside Room's transaction wrapper.
            val capacityProbeId = UUID.randomUUID().toString()
            val capacityProbeFailure = try {
                sql.execSQL(
                    """INSERT INTO elements(id, page_id, order_index, kind,
                       element_json, revision) VALUES (?, ?, 0, 'shape', ?, 8)""",
                    arrayOf(capacityProbeId, pageId, "X".repeat(256 * 1024)),
                )
                null
            } catch (exception: Exception) {
                exception
            }
            assertTrue(
                "Expected direct SQLite growth probe to return SQLITE_FULL at " +
                    "max_page_count=$constrainedLimit; got $capacityProbeFailure",
                isSqliteFull(capacityProbeFailure),
            )
            val maskedRollback = repository.commitFailure?.message
                ?.contains("cannot rollback - no transaction is active", ignoreCase = true) == true
            assertTrue(
                "Room failure must be SQLITE_FULL or its known automatic-rollback " +
                    "mask; got ${repository.commitFailure}",
                isSqliteFull(repository.commitFailure) || maskedRollback,
            )
            assertEquals(7L, failed.committedRevision)
            assertEquals(1, failed.pendingCommandCount)
            assertFalse(failed.failureMessage.isNullOrBlank())

            val afterFailure = realRepository.load(noteId)
            assertEquals(7L, afterFailure.revision)
            assertEquals(pageId, afterFailure.pages.single().id)
            assertEquals(S03_WHITE, afterFailure.pages.single().backgroundJson)
            assertTrue("Partial insert escaped the aborted transaction", afterFailure.pages.single().elements.isEmpty())
            val unrelated = realRepository.load(otherNoteId)
            assertEquals(7L, unrelated.revision)
            assertEquals(otherPageId, unrelated.pages.single().id)
            assertEquals(S03_WHITE, unrelated.pages.single().backgroundJson)
            assertTrue(unrelated.pages.single().elements.isEmpty())

            // Restore capacity on this isolated DB, then use the coordinator's
            // revision-reconciled retry rather than blindly replaying the edit.
            val restored = pragmaLong(sql, "PRAGMA max_page_count = $originalLimit")
            assertTrue("Could not restore DB capacity", restored > currentPages)
            assertEquals(SaveRetryResult.STARTED, coordinator.retry())
            val saved = withTimeoutOrNull(20_000) {
                coordinator.state.first { it.phase == SavePhase.SAVED && it.committedRevision == 8L }
            } ?: error(
                "S03-A SECOND WAIT timed out before SAVED revision 8; " +
                    "state=${coordinator.state.value}, " +
                    "repositoryFailure=${repository.commitFailure}"
            )
            assertEquals(0, saved.pendingCommandCount)
            assertEquals(null, saved.failureMessage)

            val recovered = realRepository.load(noteId)
            assertEquals(8L, recovered.revision)
            val storedElements = recovered.pages.single().elements
            assertEquals(elementIds.size, storedElements.size)
            assertEquals(elementIds.toSet(), storedElements.map { it.id }.toSet())
            assertEquals(8L, db.noteDao().findById(noteId)!!.revision)
            assertEquals(7L, realRepository.load(otherNoteId).revision)
            assertEquals(SaveRetryResult.NOT_NEEDED, coordinator.retry())
            assertNotNull(repository.commitFailure)
        } finally {
            scope.cancel()
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
