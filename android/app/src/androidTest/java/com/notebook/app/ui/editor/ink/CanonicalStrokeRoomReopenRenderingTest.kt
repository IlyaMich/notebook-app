
package com.notebook.app.ui.editor.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.library.impl.RoomNoteRepository
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.NoteEntity
import com.notebook.app.data.local.entity.NotebookEntity
import com.notebook.app.data.local.entity.PageEntity
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.ViewportTransform
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

/**
 * EDT-07: Real Room persistence -> database reopen ->
 * canonical rendering -> editor close/reentry.
 *
 * Uses an isolated on-disk database, never production data.
 */
@RunWith(AndroidJUnit4::class)
class CanonicalStrokeRoomReopenRenderingTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry
            .getInstrumentation().targetContext

        databaseName =
            "edt07-render-${UUID.randomUUID()}.sqlite"

        db = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )

        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }

        if (::context.isInitialized &&
            ::databaseName.isInitialized
        ) {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun committedStrokeSurvivesReopenAndRendersInEditor() {
        val notebookId = UUID.randomUUID().toString()
        val noteId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val strokeId = UUID.randomUUID().toString()

        val envelope = """
            {
              "id": "$strokeId",
              "type": "stroke",
              "transform": [2,0,0,0.5,0,0],
              "locked": false,
              "payload": {
                "encoding": "sampledPointsV1",
                "brush": {
                  "kind": "pen",
                  "width": 8,
                  "color": "#FF0000",
                  "opacity": 1.0
                },
                "points": [
                  {
                    "x": -20,
                    "y": 40,
                    "tMs": 0,
                    "pressure": 0.4
                  },
                  {
                    "x": 70,
                    "y": 40,
                    "tMs": 20,
                    "pressure": 0.8
                  }
                ]
              }
            }
        """.trimIndent()

        // 1. Create the note and commit through the real
        // document repository.
        runBlocking {
            db.notebookDao().insert(
                NotebookEntity(
                    id = notebookId,
                    parentFolderId = null,
                    name = "EDT-07 test notebook",
                    sortOrder = 0,
                    createdAtMillis = 100L,
                    updatedAtMillis = 100L,
                )
            )

            db.noteDao().insert(
                NoteEntity(
                    id = noteId,
                    notebookId = notebookId,
                    title = "Saved ink test",
                    createdAtMillis = 100L,
                    updatedAtMillis = 100L,
                )
            )

            db.pageDao().insert(
                PageEntity(
                    id = pageId,
                    noteId = noteId,
                    orderIndex = 0L,
                    width = 100.0,
                    height = 100.0,
                    backgroundJson =
                        """{"type":"blank","color":"#FFFFFF"}""",
                    revision = 0L,
                )
            )

            val repository = RoomDocumentRepository(db)

            val commit = repository.commit(
                noteId,
                DocumentEdit.InsertElements(
                    pageId = pageId,
                    envelopes = listOf(envelope),
                ),
            )

            assertEquals(1L, commit.revision)
            assertEquals(noteId, commit.noteId)

            val saved = repository.load(noteId)

            assertEquals(1L, saved.revision)
            assertEquals(
                envelope,
                saved.pages.single()
                    .elements.single().envelopeJson,
            )
        }

        // 2. Physically close and reopen SQLite.
        // This prevents the test from accidentally using
        // the original in-memory document snapshot.
        db.close()

        db = AppDatabaseProvider.openIsolated(
            context,
            databaseName,
        )

        val reopenedRepository = RoomDocumentRepository(db)

        val reopened = runBlocking {
            reopenedRepository.load(noteId)
        }

        assertEquals(1L, reopened.revision)
        assertEquals(pageId, reopened.pages.single().id)

        val page = reopened.pages.single()
        val element = page.elements.single()

        assertEquals(strokeId, element.id)
        assertEquals("stroke", element.kind)
        assertEquals(0L, element.orderIndex)
        assertEquals(envelope, element.envelopeJson)

        // 3. Check that canonical samples and brush data
        // survived the database round-trip unchanged.
        val decoded = StoredCanonicalStrokeDecoder
            .fromPage(page)
            .single()

        assertEquals(strokeId, decoded.elementId)
        assertEquals(8.0, decoded.brush.widthDu, 0.0)
        assertEquals("#FF0000", decoded.brush.colorHex)
        assertEquals(2, decoded.samples.size)

        assertEquals(
            -20.0,
            decoded.samples[0].xDu,
            0.0,
        )
        assertEquals(0L, decoded.samples[0].tMs)
        assertEquals(
            0.4,
            decoded.samples[0].pressure!!,
            0.0,
        )
        assertEquals(20L, decoded.samples[1].tMs)
        assertEquals(
            0.8,
            decoded.samples[1].pressure!!,
            0.0,
        )

        // 4. Render from the reopened Room snapshot.
        // Nonuniform element transform + viewport scale:
        // local x -> x * 4; local y -> y * 1.
        val renderPlan = CanonicalStrokeRenderPlan.build(
            page = page,
            positionedPage = PositionedPage(
                id = pageId,
                widthDu = 100.0,
                heightDu = 100.0,
                originXDu = 0.0,
                originYDu = 0.0,
            ),
            viewport = ViewportTransform(
                scalePxPerDu = 2.0,
                translationXPx = 0.0,
                translationYPx = 0.0,
            ),
        )

        val bitmap = Bitmap.createBitmap(
            256,
            256,
            Bitmap.Config.ARGB_8888,
        )

        try {
            CanonicalStrokeCanvasRenderer.draw(
                Canvas(bitmap),
                renderPlan,
            )

            // The transformed centerline crosses
            // viewport y=40.
            assertEquals(
                Color.RED,
                bitmap.getPixel(100, 40),
            )

            // Brush radius becomes 4 px vertically.
            // y=47 must remain transparent.
            assertEquals(
                0,
                Color.alpha(bitmap.getPixel(100, 47)),
            )

            // The page ends at viewport x=200.
            // The line extends beyond it but must clip.
            assertEquals(
                0,
                Color.alpha(bitmap.getPixel(210, 40)),
            )

            assertTrue(
                Color.alpha(bitmap.getPixel(2, 40)) > 0
            )
        } finally {
            bitmap.recycle()
        }

        // 5. Mount the real production NoteEditorScreen
        // using the reopened Room repositories.
        val noteRepository = RoomNoteRepository(db)
        val editorVisible = mutableStateOf(true)
        val openingNumber = mutableIntStateOf(0)

        rule.setContent {
            if (editorVisible.value) {
                // A different key models a new editor
                // entry instead of reusing its prior
                // session ViewModel.
                val session:
                    NoteEditorSessionViewModel = viewModel(
                    key = "edt07-opening-${openingNumber.intValue}"
                )

                NoteEditorScreen(
                    noteId = noteId,
                    noteRepository = noteRepository,
                    documentRepository = reopenedRepository,
                    sessionViewModel = session,
                    navigationBackRequests = 0,
                    onBack = {
                        editorVisible.value = false
                    },
                )
            }
        }

        fun awaitEditor() {
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText(
                    "Saved locally - revision 1"
                ).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithContentDescription(
                        "Read-only note viewport with 1 pages"
                    ).fetchSemanticsNodes().isNotEmpty()
            }

            rule.waitForIdle()
        }

        awaitEditor()

        // 6. Close through the actual editor Back button,
        // then reopen with a fresh session identity.
        rule.onNodeWithText("Back").performClick()

        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(
                "Read-only note viewport with 1 pages"
            ).fetchSemanticsNodes().isEmpty()
        }

        rule.runOnIdle {
            openingNumber.intValue += 1
            editorVisible.value = true
        }

        awaitEditor()

        // 7. Inspect real Compose viewport pixels.
        val image = rule
            .onNodeWithContentDescription(
                "Read-only note viewport with 1 pages"
            )
            .captureToImage()

        val pixels = image.toPixelMap()
        var foundRedStroke = false

        scan@ for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = pixels[x, y]

                if (
                    color.red > 0.9f &&
                    color.green < 0.15f &&
                    color.blue < 0.15f &&
                    color.alpha > 0.9f
                ) {
                    foundRedStroke = true
                    break@scan
                }
            }
        }

        assertTrue(
            "Reopened editor must display the saved red stroke",
            foundRedStroke,
        )

        // Rendering and reopening must not commit edits.
        val finalSnapshot = runBlocking {
            reopenedRepository.load(noteId)
        }

        assertEquals(1L, finalSnapshot.revision)
        assertEquals(
            envelope,
            finalSnapshot.pages.single()
                .elements.single().envelopeJson,
        )
    }
}
