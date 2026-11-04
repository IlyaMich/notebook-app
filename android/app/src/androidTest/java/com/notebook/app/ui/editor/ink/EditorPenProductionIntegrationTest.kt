
package com.notebook.app.ui.editor.ink

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import com.notebook.app.ui.screens.NoteEditorScreen
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorPenProductionIntegrationTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AppDatabase

    private val viewportDescription =
        "Read-only note viewport with 1 pages"

    @Before
    fun setUp() {
        context = InstrumentationRegistry
            .getInstrumentation().targetContext

        databaseName =
            "edt09-pen-${UUID.randomUUID()}.sqlite"

        context.deleteDatabase(databaseName)

        database = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }

        if (::context.isInitialized &&
            ::databaseName.isInitialized
        ) {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createNote(): Pair<String, String> =
        runBlocking {
            val note = LibraryRepositoryFactory(database)
                .noteLifecycle()
                .createNote(
                    notebookId = null,
                    title = "EDT-09 Pen test",
                    firstPage =
                        InitialPageSpec.a4PortraitBlank(),
                )

            val page = RoomDocumentRepository(database)
                .load(note.id)
                .pages.single()

            note.id to page.id
        }

    private fun awaitEditor(revision: Long) {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(
                "Saved locally - revision $revision"
            ).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithContentDescription(
                    viewportDescription
                ).fetchSemanticsNodes().isNotEmpty()
        }

        rule.waitForIdle()
    }

    private fun drawOneFingerStroke() {
        rule.onNodeWithContentDescription(
            viewportDescription
        ).performTouchInput {
            val start = percentOffset(0.32f, 0.20f)
            val end = percentOffset(0.50f, 0.30f)

            down(start)
            moveTo(end)
            up()
        }
    }

    private fun savedBlueInkIsVisible(): Boolean {
        val image = rule.onNodeWithContentDescription(
            viewportDescription
        ).captureToImage()

        val pixels = image.toPixelMap()

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = pixels[x, y]

                if (
                    color.red < 0.30f &&
                    color.green in 0.20f..0.52f &&
                    color.blue > 0.55f &&
                    color.alpha > 0.90f
                ) {
                    return true
                }
            }
        }

        return false
    }

    @Test
    fun fingerPenStrokeAutosavesAndSurvivesReopen() {
        val (noteId, pageId) = createNote()

        val editorVisible = mutableStateOf(true)
        val openingNumber = mutableIntStateOf(0)

        rule.setContent {
            if (editorVisible.value) {
                val session: NoteEditorSessionViewModel =
                    viewModel(
                        key = "edt09-${openingNumber.intValue}"
                    )

                NoteEditorScreen(
                    noteId = noteId,
                    noteRepository =
                        RoomNoteRepository(database),
                    documentRepository =
                        RoomDocumentRepository(database),
                    sessionViewModel = session,
                    navigationBackRequests = 0,
                    onBack = {
                        editorVisible.value = false
                    },
                )
            }
        }

        awaitEditor(revision = 0L)

        rule.onNodeWithText(
            "Enable finger Pen writing"
        ).performClick()

        drawOneFingerStroke()

        // Wait for the real coordinator and Room commit.
        awaitEditor(revision = 1L)

        val saved = runBlocking {
            RoomDocumentRepository(database).load(noteId)
        }

        assertEquals(1L, saved.revision)
        assertEquals(1, saved.pages.size)

        val page = saved.pages.single()
        assertEquals(pageId, page.id)
        assertEquals(1, page.elements.size)

        val element = page.elements.single()
        assertEquals("stroke", element.kind)

        val originalJson = element.envelopeJson

        val decoded = StoredCanonicalStrokeDecoder
            .fromPage(page)
            .single()

        assertEquals(element.id, decoded.elementId)
        assertEquals(pageId, decoded.pageId)
        assertEquals("pen", decoded.brush.kind)
        assertEquals(3.0, decoded.brush.widthDu, 0.0)
        assertTrue(decoded.samples.size >= 2)
        assertEquals(0L, decoded.samples.first().tMs)

        // Close the editor before closing SQLite.
        rule.runOnIdle {
            editorVisible.value = false
        }
        rule.waitForIdle()

        database.close()

        database = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )

        val reopenedRepository =
            RoomDocumentRepository(database)

        val reopened = runBlocking {
            reopenedRepository.load(noteId)
        }

        assertEquals(1L, reopened.revision)
        assertEquals(
            originalJson,
            reopened.pages.single()
                .elements.single()
                .envelopeJson,
        )

        // Re-enter the production editor with a fresh
        // Navigation-entry-style ViewModel identity.
        rule.runOnIdle {
            openingNumber.intValue += 1
            editorVisible.value = true
        }

        awaitEditor(revision = 1L)

        assertTrue(
            "Saved Pen ink must render after Room reopen",
            savedBlueInkIsVisible(),
        )

        // Reopening and rendering must never rewrite JSON
        // or increment the document revision.
        val finalSnapshot = runBlocking {
            reopenedRepository.load(noteId)
        }

        assertEquals(1L, finalSnapshot.revision)
        assertEquals(
            originalJson,
            finalSnapshot.pages.single()
                .elements.single()
                .envelopeJson,
        )
    }
}
