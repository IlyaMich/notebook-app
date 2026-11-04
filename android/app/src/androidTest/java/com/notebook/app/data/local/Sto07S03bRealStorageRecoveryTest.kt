package com.notebook.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.ui.editor.NoteAutosaveCoordinator
import com.notebook.app.ui.editor.SavePhase
import com.notebook.app.ui.editor.SaveRetryResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in destructive-to-disposable-AVD pressure experiment. Never run on a real device.
 * Host PowerShell runner checks the exact AVD identity before any action, and fills
 * only /data/local/tmp/sto07_s03b_pressure. Do not use Gradle connectedAndroidTest here.
 *
 * Keeps one NoteAutosaveCoordinator alive across disk pressure and relief: memory-only
 * pending commands cannot survive an instrumentation process restart.
 *
 * This is NOT a normal CI test; both phases are skipped without the host's phase flag.
 */
@RunWith(AndroidJUnit4::class)
class Sto07S03bRealStorageRecoveryTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val baselineMarker
        get() = context.getSharedPreferences("sto07-s03b-device-baseline", Context.MODE_PRIVATE)
    private val pressureMarker
        get() = context.getSharedPreferences("sto07-s03b-pressure-note", Context.MODE_PRIVATE)

    private fun requirePhase(name: String) {
        assumeTrue(
            "S03-B device pressure needs the guarded disposable-AVD host runner",
            InstrumentationRegistry.getArguments().getString("sto07Phase") == name,
        )
    }

    private fun id(key: String): String =
        pressureMarker.getString(key, null) ?: error("Missing pressure fixture key: $key")

    private fun shapeEnvelope(elementId: String) =
        """{"id":"$elementId","type":"shape","transform":[1,0,0,1,0,0],"locked":false,"payload":{"kind":"line","geometry":{"x1":12,"y1":20,"x2":40,"y2":60},"stroke":{"color":"#2255AA","width":2,"opacity":1},"fill":null}}"""

    private suspend fun assertOriginalBaseline(db: AppDatabase) {
        val originalId = baselineMarker.getString("noteId", null)
            ?: error("Run the verified S03-B baseline fixture first")
        val pageId = baselineMarker.getString("pageId", null)
            ?: error("Missing baseline page ID")
        val elementId = baselineMarker.getString("elementId", null)
            ?: error("Missing baseline shape ID")
        val note = RoomDocumentRepository(db).load(originalId)
        assertEquals("Baseline revision changed", 7L, note.revision)
        assertEquals(1, note.pages.size)
        assertEquals(pageId, note.pages.single().id)
        assertEquals(1, note.pages.single().elements.size)
        assertEquals(elementId, note.pages.single().elements.single().id)
        assertEquals(shapeEnvelope(elementId), db.elementDao().listByPage(pageId).single().elementJson)
    }

    private fun ensurePressureNote(db: AppDatabase): Pair<String, String> {
        if (pressureMarker.contains("noteId")) {
            return id("noteId") to id("pageId")
        }
        val notebookId = baselineMarker.getString("notebookId", null)
            ?: error("Missing S03-B baseline notebook")
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            sql.execSQL(
                """INSERT INTO notes(id, notebook_id, title, created_at, updated_at, revision)
                   VALUES (?, ?, ?, ?, ?, 7)""",
                arrayOf<Any>(noteId, notebookId, "S03B temporary pressure fixture", now, now),
            )
            sql.execSQL(
                """INSERT INTO pages(id, note_id, order_index, width, height, background_json, revision)
                   VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
                arrayOf(pageId, noteId, """{"type":"blank","color":"#FFFFFF"}"""),
            )
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        assertTrue(
            "Could not persist the temporary pressure fixture IDs",
            pressureMarker.edit()
                .putString("noteId", noteId)
                .putString("pageId", pageId)
                .commit(),
        )
        return noteId to pageId
    }

    private class RecordingRoomRepository(private val delegate: DocumentRepository) : DocumentRepository {
        @Volatile var commitError: Exception? = null
            private set

        override suspend fun load(noteId: String): StoredNoteContent = delegate.load(noteId)
        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
            try {
                delegate.commit(noteId, edit)
            } catch (exception: Exception) {
                commitError = exception
                throw exception
            }
    }

    private fun recognizedStorageFailure(cause: Throwable?): Boolean {
        var current = cause
        while (current != null) {
            val message = current.message.orEmpty()
            if (current is SQLiteFullException ||
                message.contains("SQLITE_FULL", ignoreCase = true) ||
                message.contains("database or disk is full", ignoreCase = true) ||
                message.contains("cannot rollback - no transaction is active", ignoreCase = true)
            ) return true
            current = current.cause
        }
        return false
    }

    private suspend fun awaitSignal(filename: String) = withTimeout(180_000) {
        val file = File(context.filesDir, filename)
        while (!file.exists()) delay(250)
    }

    @Test
    fun deviceFullFailureAndRetryInProductionRoom(): Unit = runBlocking {
        requirePhase("pressure")
        val db = AppDatabaseProvider.get(context) // REAL app DB, production WAL config.
        check(!pressureMarker.getBoolean("recovered", false)) {
            "S03-B pressure fixture already recovered; do not rerun destructively"
        }
        assertOriginalBaseline(db)
        val (noteId, pageId) = ensurePressureNote(db)
        val repository = RecordingRoomRepository(RoomDocumentRepository(db))
        val before = repository.load(noteId)
        assertEquals("Temporary note is not a clean revision-7 fixture", 7L, before.revision)
        assertEquals("Temporary page should have no elements", 0, before.pages.single().elements.size)
        assertEquals(pageId, before.pages.single().id)

        // Large, fully valid edit: exceeds the runner's 16 MiB guest free-space target
        // after WAL/index overhead. Test must fail if it unexpectedly saves anyway.
        val elementIds = (0 until ELEMENT_COUNT).map { UUID.randomUUID().toString() }
        val envelopes = elementIds.map(::shapeEnvelope)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NoteAutosaveCoordinator(noteId, repository, scope, 7L)
        val goFile = File(context.filesDir, GO_FILE)
        val recoverFile = File(context.filesDir, RECOVER_FILE)
        goFile.delete()
        recoverFile.delete()
        try {
            Log.i(LOG_TAG, "S03B_ARMED elements=$ELEMENT_COUNT noteId=$noteId")
            awaitSignal(GO_FILE)
            coordinator.enqueue(DocumentEdit.InsertElements(pageId = pageId, envelopes = envelopes))
            val terminal = withTimeout(180_000) {
                coordinator.state.first {
                    it.phase == SavePhase.FAILED ||
                        (it.phase == SavePhase.SAVED && it.committedRevision >= 8L)
                }
            }
            if (terminal.phase != SavePhase.FAILED) {
                Log.e(LOG_TAG, "S03B_UNEXPECTED_SAVED state=$terminal")
            }
            assertEquals("Real storage pressure did not cause FAILED", SavePhase.FAILED, terminal.phase)
            assertEquals(7L, terminal.committedRevision)
            assertEquals(1, terminal.pendingCommandCount)
            assertTrue("Failure message must be visible", !terminal.failureMessage.isNullOrBlank())
            assertTrue(
                "Unexpected repository failure; not identified as storage-related: ${repository.commitError}",
                recognizedStorageFailure(repository.commitError),
            )
            Log.i(LOG_TAG, "S03B_FAILED revision=7 pending=1 error=${repository.commitError}")
            awaitSignal(RECOVER_FILE) // Host must remove all fillers BEFORE signaling.

            val rolledBack = repository.load(noteId)
            assertEquals("Partial commit escaped the failed transaction", 7L, rolledBack.revision)
            assertEquals(0, rolledBack.pages.single().elements.size)
            assertOriginalBaseline(db)

            assertEquals(SaveRetryResult.STARTED, coordinator.retry())
            val saved = withTimeout(180_000) {
                coordinator.state.first {
                    it.phase == SavePhase.SAVED && it.committedRevision == 8L
                }
            }
            assertEquals(0, saved.pendingCommandCount)
            assertEquals(null, saved.failureMessage)
            val recovered = repository.load(noteId)
            assertEquals(8L, recovered.revision)
            val stored = recovered.pages.single().elements
            assertEquals(ELEMENT_COUNT, stored.size)
            assertEquals(ELEMENT_COUNT, stored.map { it.id }.toSet().size)
            assertEquals(elementIds.toSet(), stored.map { it.id }.toSet())
            assertEquals(SaveRetryResult.NOT_NEEDED, coordinator.retry())
            assertOriginalBaseline(db)
            assertTrue(
                pressureMarker.edit()
                    .putString("firstInsertedId", elementIds.first())
                    .putString("lastInsertedId", elementIds.last())
                    .putBoolean("recovered", true)
                    .commit(),
            )
            Log.i(LOG_TAG, "S03B_RECOVERED revision=8 elements=$ELEMENT_COUNT")
        } finally {
            scope.cancel()
            goFile.delete()
            recoverFile.delete()
        }
        Unit
    }

    @Test
    fun verifyRecoveryAfterFreshProcess(): Unit = runBlocking {
        requirePhase("verifyPressure")
        assertTrue("Recovery marker missing", pressureMarker.getBoolean("recovered", false))
        val db = AppDatabaseProvider.get(context)
        val recovered = RoomDocumentRepository(db).load(id("noteId"))
        assertEquals(8L, recovered.revision)
        assertEquals(id("pageId"), recovered.pages.single().id)
        val elements = recovered.pages.single().elements
        assertEquals(ELEMENT_COUNT, elements.size)
        assertEquals(ELEMENT_COUNT, elements.map { it.id }.toSet().size)
        assertEquals(pressureMarker.getString("firstInsertedId", null), elements.first().id)
        assertEquals(pressureMarker.getString("lastInsertedId", null), elements.last().id)
        assertOriginalBaseline(db)
        Log.i(LOG_TAG, "S03B_VERIFIED_IN_NEW_PROCESS revision=8 elements=$ELEMENT_COUNT")
        Unit
    }

    companion object {
        private const val LOG_TAG = "STO07_S03B_PRESSURE"
        private const val ELEMENT_COUNT = 50000
        private const val GO_FILE = "sto07-s03b-pressure-go.flag"
        private const val RECOVER_FILE = "sto07-s03b-pressure-recover.flag"
    }
}
