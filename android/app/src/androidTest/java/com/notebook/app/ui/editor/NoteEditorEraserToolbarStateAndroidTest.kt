package com.notebook.app.ui.editor

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.ui.screens.NoteEditorScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual editor toolbar state, driven by deterministic repository acknowledgements. */
@RunWith(AndroidJUnit4::class)
class NoteEditorEraserToolbarStateAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val noteId = "00000000-0000-4000-8000-000000000061"
    private val pageId = "00000000-0000-4000-8000-000000000062"
    private val notebookId = "00000000-0000-4000-8000-000000000063"
    private val white = """{"type":"blank","color":"#FFFFFF"}"""
    private val warm = """{"type":"blank","color":"#FFF9E9"}"""

    private val note = Note(
        id = noteId, notebookId = notebookId, title = "Eraser UI test",
        createdAtMillis = 100L, updatedAtMillis = 100L,
    )

    private val notes = object : NoteRepository {
        override suspend fun findById(noteId: String): Note? = note
        override suspend fun recordOpened(noteId: String): Note = note
        override suspend fun listByNotebook(notebookId: String): List<Note> = listOf(note)
        override suspend fun listRecent(limit: Int): List<Note> = listOf(note)
        override suspend fun rename(noteId: String, title: String): Note = error("Not used")
        override suspend fun move(noteId: String, newNotebookId: String): Note = error("Not used")
    }

    private class ControlledDocumentRepo(
        private val noteId: String,
        private val pageId: String,
        private val white: String,
        private val warm: String,
    ) : DocumentRepository {
        @Volatile var revision = 0L
        @Volatile var background = white
        @Volatile var stalePreviewAfterCommit = false
        @Volatile var failCommit = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var began = CompletableDeferred<Unit>()
        @Volatile var commits = 0

        override suspend fun load(noteId: String): StoredNoteContent {
            require(noteId == this.noteId)
            val visibleRevision = if (stalePreviewAfterCommit && revision > 0) revision - 1 else revision
            return StoredNoteContent(noteId, visibleRevision, listOf(
                StoredPage(pageId, 0L, 595.276, 841.890,
                    if (stalePreviewAfterCommit && revision > 0) white else background,
                    0L, emptyList()),
            ))
        }

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            require(noteId == this.noteId)
            require(edit is DocumentEdit.ChangeBackground && edit.pageId == pageId)
            began.complete(Unit)
            gate?.await()
            if (failCommit) throw IllegalStateException("Synthetic Room save failure")
            background = edit.backgroundJson
            revision++
            commits++
            return DocumentCommit(noteId, revision, 300L)
        }

        override suspend fun commitIfRevision(
            noteId: String, expectedRevision: Long, edit: DocumentEdit,
        ): DocumentCommit = error("No eraser commit is expected in toolbar-state tests")
    }

    private fun show(repo: ControlledDocumentRepo) {
        val model = NoteEditorSessionViewModel()
        compose.setContent {
            NoteEditorScreen(
                noteId = noteId, noteRepository = notes, documentRepository = repo,
                sessionViewModel = model, navigationBackRequests = 0, onBack = {},
            )
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Saved locally - revision 0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun repo() = ControlledDocumentRepo(noteId, pageId, white, warm)

    @Test fun fullScreenExposesPartialDefaultAndCanSelectWholeAndBack() {
        val repo = repo()
        show(repo)
        compose.onNodeWithText("Eraser").assertIsEnabled().performClick()
        compose.onNodeWithText("Partial eraser selected.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Whole stroke", useUnmergedTree = true).assertIsEnabled().performClick()
        compose.onNodeWithText("Whole stroke selected.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Partial eraser", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Partial eraser selected.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Undo eraser").assertIsNotEnabled()
        compose.onNodeWithText("Redo eraser").assertIsNotEnabled()
        assertEquals(0, repo.commits)
    }

    @Test fun pendingSaveDisablesEraserAndHistoryUntilAcknowledged() {
        val repo = repo()
        repo.gate = CompletableDeferred()
        show(repo)
        compose.onNodeWithText("Test edit: change blank-page color").performClick()
        compose.waitUntil(10_000) { repo.began.isCompleted }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Saving...", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Eraser").assertIsNotEnabled()
        compose.onNodeWithText("Undo eraser").assertIsNotEnabled()
        compose.onNodeWithText("Redo eraser").assertIsNotEnabled()
        repo.gate!!.complete(Unit)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Saved locally - revision 1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Eraser").assertIsEnabled()
        assertEquals(1, repo.commits)
    }

    @Test fun failedSaveNeverEnablesDestructiveControls() {
        val repo = repo()
        repo.failCommit = true
        show(repo)
        compose.onNodeWithText("Test edit: change blank-page color").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Save failed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Eraser").assertIsNotEnabled()
        compose.onNodeWithText("Undo eraser").assertIsNotEnabled()
        compose.onNodeWithText("Redo eraser").assertIsNotEnabled()
        assertEquals(0, repo.commits)
    }

    @Test fun staleRenderedPreviewDisablesEraserEvenWithConfirmedSave() {
        val repo = repo()
        repo.stalePreviewAfterCommit = true
        show(repo)
        compose.onNodeWithText("Test edit: change blank-page color").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Preview revision 0 does not match confirmed revision 1.", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Eraser").assertIsNotEnabled()
        compose.onNodeWithText("Undo eraser").assertIsNotEnabled()
        compose.onNodeWithText("Redo eraser").assertIsNotEnabled()
        assertEquals(1, repo.commits)
    }
}
