
package com.notebook.app.ui.editor.ink

import android.content.Context
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.ui.editor.Nav3EditorAutosaveTestActivity
import com.notebook.app.ui.editor.Nav3EditorTestBridge
import com.notebook.app.ui.editor.Nav3EditorTestConfig
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorInkActivityRecreationTest {

    @get:Rule
    val rule =
        createAndroidComposeRule<Nav3EditorAutosaveTestActivity>()

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var databaseName: String
    private lateinit var noteId: String
    private lateinit var pageId: String

    private val viewportDescription =
        "Read-only note viewport with 1 pages"

    @Before
    fun setUp() {
        context = InstrumentationRegistry
            .getInstrumentation()
            .targetContext

        databaseName =
            "edt09-recreation-${UUID.randomUUID()}.sqlite"

        context.deleteDatabase(databaseName)

        database = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )

        val note = runBlocking {
            LibraryRepositoryFactory(database)
                .noteLifecycle()
                .createNote(
                    notebookId = null,
                    title = "EDT-09 Recreation Test",
                    firstPage =
                        InitialPageSpec.a4PortraitBlank(),
                )
        }

        noteId = note.id

        pageId = runBlocking {
            RoomDocumentRepository(database)
                .load(noteId)
                .pages.single()
                .id
        }

        // The existing debug Activity owns setContent().
        // Its Compose UI reads this configuration, including
        // after a real Android Activity recreation.
        rule.runOnIdle {
            Nav3EditorTestBridge.current =
                Nav3EditorTestConfig(
                    notebookId = "edt09-recreation-host",
                    noteId = noteId,
                    noteRepository =
                        RoomNoteRepository(database),
                    documentRepository =
                        RoomDocumentRepository(database),
                )
        }
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            // Dispose the editor before closing its database.
            runCatching {
                rule.runOnIdle {
                    Nav3EditorTestBridge.current = null
                }
                rule.waitForIdle()
            }

            database.close()
        }

        if (
            ::context.isInitialized &&
            ::databaseName.isInitialized
        ) {
            context.deleteDatabase(databaseName)
        }
    }

    private fun awaitEditor(revision: Long) {
        rule.waitUntil(15_000L) {
            rule.onAllNodesWithText(
                "Saved locally - revision $revision"
            ).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithContentDescription(
                    viewportDescription
                ).fetchSemanticsNodes().isNotEmpty()
        }

        rule.waitForIdle()
    }

    private fun enableFingerWriting() {
        rule.onNodeWithText(
            "Enable finger Pen writing"
        ).performClick()

        rule.waitForIdle()
    }

    private fun hasBlueInk(): Boolean {
        val image = rule
            .onNodeWithContentDescription(
                viewportDescription
            )
            .captureToImage()

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
    fun completedStrokeSurvivesActivityRecreation() {
        awaitEditor(revision = 0L)

        enableFingerWriting()

        // Complete an ordinary Pen stroke.
        rule.onNodeWithContentDescription(
            viewportDescription
        ).performTouchInput {
            down(percentOffset(0.32f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
            up()
        }

        // Wait for confirmed Room persistence.
        awaitEditor(revision = 1L)

        val before = runBlocking {
            RoomDocumentRepository(database).load(noteId)
        }

        assertEquals(1L, before.revision)
        assertEquals(pageId, before.pages.single().id)
        assertEquals(
            1,
            before.pages.single().elements.size,
        )

        val savedElement =
            before.pages.single().elements.single()

        assertEquals("stroke", savedElement.kind)

        // This invokes actual Activity destruction and
        // recreation, including a new Compose composition.
        rule.activityRule.scenario.recreate()

        awaitEditor(revision = 1L)

        val after = runBlocking {
            RoomDocumentRepository(database).load(noteId)
        }

        // Recreation must neither lose nor duplicate ink.
        assertEquals(1L, after.revision)
        assertEquals(pageId, after.pages.single().id)
        assertEquals(
            1,
            after.pages.single().elements.size,
        )

        val reopenedElement =
            after.pages.single().elements.single()

        assertEquals(
            savedElement.id,
            reopenedElement.id,
        )

        assertEquals(
            savedElement.envelopeJson,
            reopenedElement.envelopeJson,
        )

        assertTrue(
            "Completed ink must render after recreation",
            hasBlueInk(),
        )
    }

    @Test
    fun unfinishedStrokeIsDiscardedOnActivityRecreation() {
        awaitEditor(revision = 0L)

        enableFingerWriting()

        // Start drawing but deliberately do not release.
        rule.onNodeWithContentDescription(
            viewportDescription
        ).performTouchInput {
            down(percentOffset(0.32f, 0.20f))
            moveTo(percentOffset(0.50f, 0.30f))
        }

        rule.waitForIdle()

        assertTrue(
            "Live ink should appear before interruption",
            hasBlueInk(),
        )

        val before = runBlocking {
            RoomDocumentRepository(database).load(noteId)
        }

        assertEquals(0L, before.revision)
        assertTrue(
            before.pages.single().elements.isEmpty()
        )

        // Recreate the Activity while the gesture is
        // unfinished. Android will pause and destroy
        // the old composition.
        rule.activityRule.scenario.recreate()

        awaitEditor(revision = 0L)

        assertFalse(
            "Unfinished preview must not survive recreation",
            hasBlueInk(),
        )

        val after = runBlocking {
            RoomDocumentRepository(database).load(noteId)
        }

        assertEquals(
            "Interrupted drawing must not advance revision",
            0L,
            after.revision,
        )

        assertEquals(pageId, after.pages.single().id)

        assertTrue(
            "Interrupted drawing must not insert an element",
            after.pages.single().elements.isEmpty(),
        )
    }
}
