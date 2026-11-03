package com.notebook.app.ui.editor

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.local.AppDatabaseProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * STO-07 S01: Acknowledged save survives *actual process termination*.
 *
 * This class MUST be driven by a host-side ADB script. The prepare phase
 * deliberately does not finish: the host confirms its PID and force-stops
 * the running app. The subsequent verify phase is a new instrumentation run.
 *
 * Without -e sto07Phase prepare|verify, both tests are skipped in CI.
 */
@RunWith(AndroidJUnit4::class)
class Sto07AcknowledgedRevisionProcessDeathTest {

    @get:Rule
    val rule = createAndroidComposeRule<Nav3EditorAutosaveTestActivity>()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prefs
        get() = context.getSharedPreferences("sto07-s01-fixture", Context.MODE_PRIVATE)

    private fun onlyPhase(phase: String) {
        assumeTrue(
            "STO-07 S01 runs only through its ADB two-phase driver",
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
        prefs.getString(key, null) ?: error("Missing STO-07 S01 fixture key: $key")

    @Test
    fun prepareAcknowledgedRevisionAndAwaitExternalKill() {
        onlyPhase("prepare")

        check(!prefs.contains("databaseName")) {
            "An earlier S01 fixture is still present; inspect it before starting another run"
        }

        val dbName = "sto07-s01-${UUID.randomUUID()}.sqlite"
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val otherNoteId = UUID.randomUUID().toString()
        val otherPageId = UUID.randomUUID().toString()
        val white = """{"type":"blank","color":"#FFFFFF"}"""
        val warm = """{"type":"blank","color":"#FFF9E9"}"""

        // Isolated app-private test database, never the production library.sqlite.
        val db = AppDatabaseProvider.openIsolated(context, dbName)
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            """INSERT INTO notebooks(
                id, parent_folder_id, name, sort_order,
                system_role, created_at, updated_at
            ) VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
            arrayOf(notebookId, "STO-07 disposable notebook"),
        )
        for ((id, title) in listOf(
            noteId to "S01 acknowledged note",
            otherNoteId to "S01 unrelated note",
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
                arrayOf(id, ownerId, white),
            )
        }

        val documentRepository = RoomDocumentRepository(db)
        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                notebookId = notebookId,
                noteId = noteId,
                noteRepository = RoomNoteRepository(db),
                documentRepository = documentRepository,
            )
        }

        awaitText("Saved locally - revision 7")
        rule.onNodeWithText("Test edit: change blank-page color").performClick()
        awaitText("Saved locally - revision 8")

        // Independent canonical Room read, not just the in-memory coordinator.
        val saved = runBlocking { documentRepository.load(noteId) }
        assertEquals(8L, saved.revision)
        assertEquals(pageId, saved.pages.single().id)
        assertEquals(8L, saved.pages.single().revision)
        assertEquals(warm, saved.pages.single().backgroundJson)
        assertTrue(saved.pages.single().elements.isEmpty())

        val other = runBlocking { documentRepository.load(otherNoteId) }
        assertEquals(7L, other.revision)
        assertEquals(white, other.pages.single().backgroundJson)

        // commit() writes the fixture marker before the external process kill.
        assertTrue(
            "Could not durably record the S01 fixture",
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

        Log.i("STO07_S01", "READY_TO_KILL pid=${Process.myPid()} revision=8")

        // The host MUST force-stop this process before the timeout.
        // A normal test return / Activity recreation would be invalid evidence.
        CountDownLatch(1).await(120, TimeUnit.SECONDS)
        fail("S01 process was not killed externally before the timeout")
    }

    @Test
    fun verifyAcknowledgedRevisionAfterNewProcessStarts() {
        onlyPhase("verify")

        val dbName = persistedKey("databaseName")
        val notebookId = persistedKey("notebookId")
        val noteId = persistedKey("noteId")
        val pageId = persistedKey("pageId")
        val otherNoteId = persistedKey("otherNoteId")
        val otherPageId = persistedKey("otherPageId")
        val warm = """{"type":"blank","color":"#FFF9E9"}"""
        val white = """{"type":"blank","color":"#FFFFFF"}"""

        val db = AppDatabaseProvider.openIsolated(context, dbName)
        val repository = RoomDocumentRepository(db)

        val recovered = runBlocking { repository.load(noteId) }
        assertEquals(8L, recovered.revision)
        assertEquals(1, recovered.pages.size)
        assertEquals(pageId, recovered.pages.single().id)
        assertEquals(8L, recovered.pages.single().revision)
        assertEquals(warm, recovered.pages.single().backgroundJson)
        assertTrue(recovered.pages.single().elements.isEmpty())
        assertEquals(notebookId, runBlocking { db.noteDao().findById(noteId) }!!.notebookId)

        // An unrelated note in the same isolated library must be untouched.
        val other = runBlocking { repository.load(otherNoteId) }
        assertEquals(7L, other.revision)
        assertEquals(otherPageId, other.pages.single().id)
        assertEquals(7L, other.pages.single().revision)
        assertEquals(white, other.pages.single().backgroundJson)

        // Reopen the *same* editor with a brand-new ViewModel/coordinator.
        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                notebookId = notebookId,
                noteId = noteId,
                noteRepository = RoomNoteRepository(db),
                documentRepository = repository,
            )
        }
        awaitText("Saved locally - revision 8")

        // Only erase this test's isolated fixture after all assertions pass.
        rule.runOnIdle { Nav3EditorTestBridge.current = null }
        db.close()
        assertTrue(context.deleteDatabase(dbName))
        assertTrue(prefs.edit().clear().commit())
        Log.i("STO07_S01", "VERIFY_PASSED revision=8")
    }
}
