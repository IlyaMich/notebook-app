package com.notebook.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAutosaveFailureRecoveryTest {

    @Test
    fun transactionFailureRollsBackAndRetryCommitsOnce() =
        runBlocking {
            val context =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext

            val databaseName =
                "sto06-failure-${UUID.randomUUID()}.sqlite"

            val notebookId = UUID.randomUUID().toString()
            val noteId = UUID.randomUUID().toString()
            val pageId = UUID.randomUUID().toString()

            val originalBackground =
                """{"type":"blank","color":"#FFFFFF"}"""
            val changedBackground =
                """{"type":"blank","color":"#FFF9E9"}"""

            val db = AppDatabaseProvider.openIsolated(
                context,
                databaseName,
            )

            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default
            )

            try {
                val sql = db.openHelper.writableDatabase

// Create a valid disposable note in an isolated
                // Room database. No production note is touched.
                sql.execSQL(
                    """INSERT INTO notebooks(
                        id, parent_folder_id, name,
                        sort_order, system_role,
                        created_at, updated_at
                    ) VALUES (?, NULL, ?, 0, NULL, 100, 100)""",
                    arrayOf(notebookId, "Autosave test notebook"),
                )

                sql.execSQL(
                    """INSERT INTO notes(
                        id, notebook_id, title,
                        created_at, updated_at, revision
                    ) VALUES (?, ?, ?, 100, 200, 7)""",
                    arrayOf(
                        noteId,
                        notebookId,
                        "Autosave failure test",
                    ),
                )

                sql.execSQL(
                    """INSERT INTO pages(
                        id, note_id, order_index,
                        width, height, background_json, revision
                    ) VALUES (?, ?, 0, 595.276, 841.890, ?, 7)""",
                    arrayOf(
                        pageId,
                        noteId,
                        originalBackground,
                    ),
                )

                val repository = RoomDocumentRepository(db)

                val coordinator = NoteAutosaveCoordinator(
                    noteId = noteId,
                    repository = repository,
                    scope = scope,
                    initialRevision = 7L,
                )

// Inject a failure at the note revision update.
                // The earlier page mutation must roll back.
                sql.execSQL(
                    """CREATE TRIGGER sto06_injected_failure
                       BEFORE UPDATE OF revision ON notes
                       BEGIN
                           SELECT RAISE(
                               ABORT,
                               'STO06 injected write failure'
                           );
                       END"""
                )

                coordinator.enqueue(
                    DocumentEdit.ChangeBackground(
                        pageId = pageId,
                        backgroundJson = changedBackground,
                    )
                )

// First commit must fail.
                val failed = withTimeout(5_000) {
                    coordinator.state.first {
                        it.phase == SavePhase.FAILED
                    }
                }

                assertEquals(
                    7L,
                    failed.committedRevision,
                )
                assertEquals(
                    1,
                    failed.pendingCommandCount,
                )
                assertNotNull(failed.failureMessage)

// Verify the full transaction was rolled back.
                val noteAfterFailure =
                    db.noteDao().findById(noteId)!!

                val pageAfterFailure =
                    db.pageDao().listByNote(noteId).single()

                assertEquals(
                    7L,
                    noteAfterFailure.revision,
                )
                assertEquals(
                    7L,
                    pageAfterFailure.revision,
                )
                assertEquals(
                    originalBackground,
                    pageAfterFailure.backgroundJson,
                )

// Restore the ability to commit.
                sql.execSQL(
                    "DROP TRIGGER sto06_injected_failure"
                )

// Coordinator verifies current database revision
                // before reusing the pending command.
                assertEquals(
                    SaveRetryResult.STARTED,
                    coordinator.retry(),
                )

                val saved = withTimeout(5_000) {
                    coordinator.state.first {
                        it.phase == SavePhase.SAVED &&
                            it.committedRevision == 8L
                    }
                }

                assertEquals(
                    0,
                    saved.pendingCommandCount,
                )
                assertNull(saved.failureMessage)

// Reload from Room, not from the coordinator.
                val persisted = repository.load(noteId)

                assertEquals(8L, persisted.revision)
                assertEquals(1, persisted.pages.size)
                assertEquals(
                    changedBackground,
                    persisted.pages.single().backgroundJson,
                )
                assertEquals(
                    8L,
                    persisted.pages.single().revision,
                )

// Exactly one successful content revision.
                assertEquals(
                    8L,
                    db.noteDao().findById(noteId)!!.revision,
                )
            } finally {
                scope.cancel()
                db.close()
                context.deleteDatabase(databaseName)
            }
        }
}
