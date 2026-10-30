
package com.notebook.app.ui.editor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.EditorRoute
import com.notebook.app.NotebookRoute
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.screens.NoteEditorScreen
import com.notebook.app.ui.theme.NotebookTheme
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val WHITE =
    """{"type":"blank","color":"#FFFFFF"}"""
private const val WARM =
    """{"type":"blank","color":"#FFF9E9"}"""

/**
 * A test-only wrapper around real Room persistence.
 * Pausing happens before entering the SQLite transaction.
 */
private class PausableDocumentRepository(
    private val delegate: DocumentRepository,
) : DocumentRepository {
    val entered = CompletableDeferred<Unit>()
    val attempts = AtomicInteger(0)

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun load(
        noteId: String,
    ): StoredNoteContent = delegate.load(noteId)

    override suspend fun commit(
        noteId: String,
        edit: DocumentEdit,
    ): DocumentCommit {
        attempts.incrementAndGet()

        val currentGate = gate
        if (currentGate != null) {
            entered.complete(Unit)
            currentGate.await()
        }

        return delegate.commit(noteId, edit)
    }
}

private class EditorFixture(
    val database: AppDatabase,
    val databaseName: String,
    val notebookId: String,
    val noteId: String,
    val pageId: String,
) {
    val noteRepository = RoomNoteRepository(database)

    val documentRepository = PausableDocumentRepository(
        RoomDocumentRepository(database)
    )
}

@RunWith(AndroidJUnit4::class)
class Nav3EditorAutosaveUiTest {

    @get:Rule
    val rule =
        createAndroidComposeRule<Nav3EditorAutosaveTestActivity>()

    private lateinit var context: Context
    private lateinit var fixture: EditorFixture

    @Before
    fun setUp() {
        context = InstrumentationRegistry
            .getInstrumentation().targetContext

        val databaseName =
            "sto06-nav3-${UUID.randomUUID()}.sqlite"
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()

        val db = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )

        // Use a disposable, isolated Room database.
        val sql = db.openHelper.writableDatabase

        sql.execSQL(
            """INSERT INTO notebooks(
                id, parent_folder_id, name, sort_order,
                system_role, created_at, updated_at
            ) VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
            arrayOf(notebookId, "UI test notebook"),
        )

        sql.execSQL(
            """INSERT INTO notes(
                id, notebook_id, title,
                created_at, updated_at, revision
            ) VALUES (?, ?, ?, 100, 200, 7)""",
            arrayOf(noteId, notebookId, "UI test note"),
        )

        sql.execSQL(
            """INSERT INTO pages(
                id, note_id, order_index, width, height,
                background_json, revision
            ) VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
            arrayOf(pageId, noteId, WHITE),
        )

        fixture = EditorFixture(
            db,
            databaseName,
            notebookId,
            noteId,
            pageId,
        )

        rule.runOnIdle {
            Nav3EditorTestBridge.current = Nav3EditorTestConfig(
                notebookId = fixture.notebookId,
                noteId = fixture.noteId,
                noteRepository = fixture.noteRepository,
                documentRepository = fixture.documentRepository,
            )
        }

        awaitText("Saved locally - revision 7")
    }

    @After
    fun tearDown() {
        if (::fixture.isInitialized) {
            // Unblock anything still suspended on test teardown.
            fixture.documentRepository.gate?.complete(Unit)

            rule.runOnIdle {
                Nav3EditorTestBridge.current = null
            }

            fixture.database.close()
            context.deleteDatabase(fixture.databaseName)
        }
    }

    private fun awaitText(text: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun startBlockedEdit(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        fixture.documentRepository.gate = gate

        rule.onNodeWithText(
            "Test edit: change blank-page color"
        ).performClick()

        runBlocking {
            withTimeout(5_000) {
                fixture.documentRepository.entered.await()
            }
        }

        awaitText("Saving... - revision 7")
        return gate
    }

    @Test
    fun nav3EditorSaveSurvivesRecreation() {
        val gate = startBlockedEdit()

        rule.activityRule.scenario.recreate()

        // A newly constructed coordinator would incorrectly
        // show Saved at revision 7. The real one stays Saving.
        awaitText("Saving... - revision 7")

        fixture.documentRepository.gate = null
        gate.complete(Unit)

        awaitText("Saved locally - revision 8")

        val persisted = runBlocking {
            RoomDocumentRepository(fixture.database)
                .load(fixture.noteId)
        }

        assertEquals(8L, persisted.revision)
        assertEquals(
            WARM,
            persisted.pages.single().backgroundJson,
        )
        assertEquals(
            1,
            fixture.documentRepository.attempts.get(),
        )
    }

    @Test
    fun failedSaveRemainsVisibleAfterRecreationAndCanRetry() {
        val sql = fixture.database.openHelper.writableDatabase

        // Reject the note revision update; Room must roll
        // back the page edit in the same transaction.
        sql.execSQL(
            """CREATE TRIGGER sto06_nav3_fail
               BEFORE UPDATE OF revision ON notes
               BEGIN
                   SELECT RAISE(
                       ABORT, 'STO06 UI injected failure'
                   );
               END"""
        )

        rule.onNodeWithText(
            "Test edit: change blank-page color"
        ).performClick()

        awaitText("Save failed - revision 7")
        rule.onNodeWithText("Retry save").assertIsDisplayed()

        rule.activityRule.scenario.recreate()

        awaitText("Save failed - revision 7")
        rule.onNodeWithText("Retry save").assertIsDisplayed()

        // Confirm the first transaction did not commit.
        val before = runBlocking {
            RoomDocumentRepository(fixture.database)
                .load(fixture.noteId)
        }

        assertEquals(7L, before.revision)
        assertEquals(
            WHITE,
            before.pages.single().backgroundJson,
        )

        // Remove the failure, then retry from the UI.
        sql.execSQL("DROP TRIGGER sto06_nav3_fail")

        rule.onNodeWithText("Retry save").performClick()

        awaitText("Saved locally - revision 8")

        val after = runBlocking {
            RoomDocumentRepository(fixture.database)
                .load(fixture.noteId)
        }

        assertEquals(8L, after.revision)
        assertEquals(
            WARM,
            after.pages.single().backgroundJson,
        )
        assertEquals(
            2,
            fixture.documentRepository.attempts.get(),
        )
    }

    @Test
    fun backNavigationBlockedDuringPendingSave() {
        val gate = startBlockedEdit()

        // Verify the visible toolbar Back action.
        rule.onNodeWithText("Back").performClick()

        awaitText(
            "A save is still pending. Wait for confirmation."
        )
        awaitText("Saving... - revision 7")

        // Verify the Android system Back path too.
        rule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        awaitText("Saving... - revision 7")

        fixture.documentRepository.gate = null
        gate.complete(Unit)

        awaitText("Saved locally - revision 8")

        // Navigation now becomes safe.
        rule.onNodeWithText("Back").performClick()
        awaitText("Notebook destination")

        assertEquals(
            1,
            fixture.documentRepository.attempts.get(),
        )
    }
}
