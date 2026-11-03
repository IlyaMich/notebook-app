package com.notebook.app.ui.editor

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val S02_WHITE = """{"type":"blank","color":"#FFFFFF"}"""
private const val S02_WARM = """{"type":"blank","color":"#FFF9E9"}"""

/**
 * Test-only wrapper: unlike STO-06's pre-write pause, this gate holds an
 * OUTER Room transaction open AFTER the real RoomDocumentRepository has
 * executed the change. The nested repository transaction cannot commit to
 * SQLite independently of the outer transaction.
 *
 * Never release [releaseCommit] in the prepare phase: ADB must kill the
 * process while the outer transaction is still open.
 */
private class HoldOuterRoomTransactionRepository(
    private val db: AppDatabase,
    private val delegate: DocumentRepository,
) : DocumentRepository {
    val reachedUncommittedBoundary = CompletableDeferred<Unit>()
    private val releaseCommit = CompletableDeferred<Unit>()

    override suspend fun load(noteId: String): StoredNoteContent =
        delegate.load(noteId)

    override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit =
        db.withTransaction {
            // The real production mutation runs under the outer transaction.
            val candidate = delegate.commit(noteId, edit)
            check(candidate.revision == 8L) {
                "S02 expected the in-transaction candidate revision 8"
            }
            reachedUncommittedBoundary.complete(Unit)
            // This suspend point prevents the OUTER SQLite commit.
            releaseCommit.await()
            candidate
        }
}

/**
 * STO-07 S02: an uncommitted change must never be acknowledged as Saved.
 *
 * Host-driven only: run prepare via adb instrumentation, externally kill
 * com.notebook.app after READY_TO_KILL, then run verify via a NEW adb
 * instrumentation invocation WITHOUT reinstalling the APKs or clearing data.
 *
 * Normal Gradle/CI instrumentation runs skip both methods.
 */
@RunWith(AndroidJUnit4::class)
class Sto07UncommittedProcessDeathTest {

    @get:Rule
    val rule = createAndroidComposeRule<Nav3EditorAutosaveTestActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("sto07-s02-fixture", Context.MODE_PRIVATE)

    private fun onlyPhase(phase: String) {
        assumeTrue(
            "STO-07 S02 runs only through its ADB two-phase driver",
            InstrumentationRegistry.getArguments().getString("sto07Phase") == phase,
        )
    }

    private fun awaitText(expected: String) {
        rule.waitUntil(12_000) {
            rule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun persistedKey(key: String): String =
        prefs.getString(key, null) ?: error("Missing STO-07 S02 fixture key: $key")

    @Test
    fun prepareUncommittedWriteAndAwaitExternalKill() {
        onlyPhase("prepare")
        check(!prefs.contains("databaseName")) {
            "An earlier S02 fixture remains; inspect before starting another run"
        }

        val dbName = "sto07-s02-${UUID.randomUUID()}.sqlite"
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val otherNoteId = UUID.randomUUID().toString()
        val otherPageId = UUID.randomUUID().toString()

        val db = AppDatabaseProvider.openIsolated(context, dbName)
        val sql = db.openHelper.writableDatabase

        sql.execSQL(
            """INSERT INTO notebooks(
                id, parent_folder_id, name, sort_order,
                system_role, created_at, updated_at
            ) VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
            arrayOf(notebookId, "STO-07 S02 disposable notebook"),
        )
        for ((id, title) in listOf(
            noteId to "S02 interrupted note",
            otherNoteId to "S02 unrelated note",
        )) {
            sql.execSQL(
                """INSERT INTO notes(
                    id, notebook_id, title, created_at, updated_at, revision
                ) VALUES (?, ?, ?, 100, 200, 7)""",
                arrayOf(id, notebookId, title),
            )
        }
        for ((id, ownerId) in listOf(pageId to noteId, otherPageId to otherNoteId)) {
            sql.execSQL(
                """INSERT INTO pages(
                    id, note_id, order_index, width, height,
                    background_json, revision
                ) VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
                arrayOf(id, ownerId, S02_WHITE),
            )
        }

        val realRepository = RoomDocumentRepository(db)
        val heldRepository = HoldOuterRoomTransactionRepository(db, realRepository)
        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                notebookId = notebookId,
                noteId = noteId,
                noteRepository = RoomNoteRepository(db),
                documentRepository = heldRepository,
            )
        }

        awaitText("Saved locally - revision 7")
        val baseline = runBlocking { realRepository.load(noteId) }
        assertEquals(7L, baseline.revision)
        assertEquals(pageId, baseline.pages.single().id)
        assertEquals(7L, baseline.pages.single().revision)
        assertEquals(S02_WHITE, baseline.pages.single().backgroundJson)

        rule.onNodeWithText("Test edit: change blank-page color").performClick()
        runBlocking {
            withTimeout(10_000) { heldRepository.reachedUncommittedBoundary.await() }
        }
        awaitText("Saving... - revision 7")
        rule.onNodeWithText("Saved locally - revision 8").assertDoesNotExist()

        // SharedPreferences are independent of the uncommitted Room transaction.
        // They survive a process kill so the verify phase can locate the same DB.
        assertTrue(
            "Could not record S02 fixture metadata",
            prefs.edit()
                .putString("databaseName", dbName)
                .putString("notebookId", notebookId)
                .putString("noteId", noteId)
                .putString("pageId", pageId)
                .putString("otherNoteId", otherNoteId)
                .putString("otherPageId", otherPageId)
                .putInt("preparedPid", Process.myPid())
                .commit(),
        )

        Log.i(
            "STO07_S02",
            "READY_TO_KILL pid=${Process.myPid()} committedRevision=7 " +
                "uncommittedCandidateRevision=8",
        )

        // Kill from the host before this timeout. No graceful close/rollback.
        CountDownLatch(1).await(120, TimeUnit.SECONDS)
        fail("S02 process was not killed externally before timeout")
    }

    @Test
    fun verifyUncommittedWriteRolledBackAfterRestart() {
        onlyPhase("verify")

        val dbName = persistedKey("databaseName")
        val notebookId = persistedKey("notebookId")
        val noteId = persistedKey("noteId")
        val pageId = persistedKey("pageId")
        val otherNoteId = persistedKey("otherNoteId")
        val otherPageId = persistedKey("otherPageId")

        val db = AppDatabaseProvider.openIsolated(context, dbName)
        val repository = RoomDocumentRepository(db)

        // A new process must see the last committed revision, not the
        // candidate revision from the killed in-flight transaction.
        val recovered = runBlocking { repository.load(noteId) }
        assertEquals(7L, recovered.revision)
        assertEquals(1, recovered.pages.size)
        assertEquals(pageId, recovered.pages.single().id)
        assertEquals(7L, recovered.pages.single().revision)
        assertEquals(S02_WHITE, recovered.pages.single().backgroundJson)
        assertTrue(recovered.pages.single().elements.isEmpty())
        assertEquals(
            notebookId,
            runBlocking { db.noteDao().findById(noteId) }!!.notebookId,
        )

        // A separate synthetic note was never part of the interrupted edit.
        val other = runBlocking { repository.load(otherNoteId) }
        assertEquals(7L, other.revision)
        assertEquals(otherPageId, other.pages.single().id)
        assertEquals(7L, other.pages.single().revision)
        assertEquals(S02_WHITE, other.pages.single().backgroundJson)
        assertTrue(other.pages.single().elements.isEmpty())

        // A fresh editor session reloads revision 7. The old in-memory
        // pending command is NOT claimed to survive process death.
        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                notebookId = notebookId,
                noteId = noteId,
                noteRepository = RoomNoteRepository(db),
                documentRepository = repository,
            )
        }
        awaitText("Saved locally - revision 7")
        rule.onNodeWithText("Saved locally - revision 8").assertDoesNotExist()

        // Cleanup is confined to S02's isolated database and preferences.
        rule.runOnIdle { Nav3EditorTestBridge.current = null }
        db.close()
        assertTrue(context.deleteDatabase(dbName))
        assertTrue(prefs.edit().clear().commit())
        Log.i("STO07_S02", "VERIFY_PASSED committedRevision=7")
    }
}
