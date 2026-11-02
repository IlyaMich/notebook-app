package com.notebook.app.ui.editor.ink

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.content.impl.RoomDocumentRepository
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * EDT-05 CP2B: real, isolated, ON-DISK Room persistence through the production
 * DocumentRepository, not a fake DAO or the user's live library database.
 *
 * The debug-only Ink bridge and canonical adapter are used as-is. Closing and
 * reopening the database proves durability across repository/database instances.
 * This does not yet connect drawing gestures to the production editor.
 */
@RunWith(AndroidJUnit4::class)
class InkRoomPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "edt05-ink-${UUID.randomUUID()}.sqlite"
        context.deleteDatabase(databaseName)
        openDatabase()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase(): AppDatabase {
        check(database == null) { "Close the previous database before reopening" }
        return AppDatabaseProvider.openIsolated(context, databaseName).also { database = it }
    }

    private fun closeAndReopen(): AppDatabase {
        checkNotNull(database).close()
        database = null
        return openDatabase()
    }

    private suspend fun createNote(): Pair<String, String> {
        val db = checkNotNull(database)
        // The actual production lifecycle creates both note metadata and its
        // first page in a single transaction, with Quick Notes as owner.
        val note = LibraryRepositoryFactory(db).noteLifecycle().createNote(
            notebookId = null,
            title = "EDT-05 isolated test note",
            firstPage = InitialPageSpec.a4PortraitBlank(),
        )
        val pages = RoomDocumentRepository(db).load(note.id).pages
        assertEquals(1, pages.size)
        return note.id to pages.single().id
    }

    private fun strokeWithOptionalAbsence() = CanonicalInkStroke(
        brush = CanonicalInkBrush("pen", 2.75, "#2255AA", 0.8),
        points = listOf(
            CanonicalInkPoint(80.5, 100.25, 0, pressure = 0.3, tiltRad = 0.2, orientationRad = -0.5),
            CanonicalInkPoint(122.125, 104.5, 18, pressure = 0.65),
            CanonicalInkPoint(140.0, 113.75, 35),
        ),
    )

    private fun reconstructablePenStroke() = CanonicalInkStroke(
        brush = CanonicalInkBrush("pen", 3.0, "#2255AA", 1.0),
        points = listOf(
            CanonicalInkPoint(10.0, 20.0, 0, pressure = 0.3),
            CanonicalInkPoint(30.0, 28.0, 17, pressure = 0.6),
            CanonicalInkPoint(50.0, 35.0, 36, pressure = 0.8),
        ),
    )

    @Test
    fun canonicalStrokeSurvivesClosingAndReopeningOnDiskRoom() = runBlocking {
        val (noteId, pageId) = createNote()
        val elementId = UUID.randomUUID().toString()
        val stroke = strokeWithOptionalAbsence()
        val repo = RoomDocumentRepository(checkNotNull(database))
        val bridge = InkDocumentRepositoryBridge(repo)

        assertEquals(0L, repo.load(noteId).revision)
        val commit = bridge.insertCompletedStroke(noteId, pageId, elementId, stroke)
        assertEquals(1L, commit.revision)
        assertEquals(1L, repo.load(noteId).revision)

        val reopened = RoomDocumentRepository(closeAndReopen())
        val persisted = reopened.load(noteId)
        assertEquals(1L, persisted.revision)
        assertEquals(1, persisted.pages.size)
        assertEquals(pageId, persisted.pages.single().id)
        assertEquals(1, persisted.pages.single().elements.size)
        val row = persisted.pages.single().elements.single()
        assertEquals(elementId, row.id)
        assertEquals("stroke", row.kind)
        assertEquals(0L, row.orderIndex)
        assertEquals(1L, row.revision)
        // JSON object key order is non-semantic: verify the stored payload,
        // then compare its decoded canonical values rather than raw JSON text.
        val persistedPayload = JSONObject(row.envelopeJson).getJSONObject("payload")
        assertEquals("sampledPointsV1", persistedPayload.getString("encoding"))
        assertEquals(stroke.points.size, persistedPayload.getJSONArray("points").length())

        val decoded = InkDocumentRepositoryBridge(reopened).loadSupportedStrokes(noteId, pageId).single()
        assertEquals(elementId, decoded.elementId)
        assertEquals(stroke, decoded.stroke)
        assertNull(decoded.stroke.points.last().pressure)
        assertNull(decoded.stroke.points.last().tiltRad)
        assertNull(decoded.stroke.points.last().orientationRad)
    }

    @Test
    fun reloadedCanonicalSamplesReconstructNativeJetpackInk() = runBlocking {
        val (noteId, pageId) = createNote()
        val elementId = UUID.randomUUID().toString()
        val original = reconstructablePenStroke()
        InkDocumentRepositoryBridge(RoomDocumentRepository(checkNotNull(database)))
            .insertCompletedStroke(noteId, pageId, elementId, original)

        val bridge = InkDocumentRepositoryBridge(RoomDocumentRepository(closeAndReopen()))
        val fromDisk = bridge.loadSupportedStrokes(noteId, pageId).single().stroke
        assertEquals(original, fromDisk)
        val scale = InkPixelScale(2.0)
        val rebuilt = JetpackInkProbeAdapter.rebuild(fromDisk, scale)
        assertEquals(original.points.size, rebuilt.inputs.size)
        // Native Float conversion is evaluated only for rendering; the source
        // loaded from Room stays exactly canonical throughout reconstruction.
        val recaptured = JetpackInkProbeAdapter.capture(rebuilt, scale)
        original.points.zip(recaptured.points).forEach { (expected, actual) ->
            assertEquals(expected.x, actual.x, 0.01)
            assertEquals(expected.y, actual.y, 0.01)
            assertEquals(expected.tMs, actual.tMs)
            assertEquals(checkNotNull(expected.pressure), checkNotNull(actual.pressure), 0.005)
        }
        assertEquals(original, bridge.loadSupportedStrokes(noteId, pageId).single().stroke)
    }

    @Test
    fun twoCompletedStrokesProduceTwoOrderedTransactions() = runBlocking {
        val (noteId, pageId) = createNote()
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        val bridge = InkDocumentRepositoryBridge(RoomDocumentRepository(checkNotNull(database)))
        val stroke = reconstructablePenStroke()

        assertEquals(1L, bridge.insertCompletedStroke(noteId, pageId, firstId, stroke).revision)
        assertEquals(2L, bridge.insertCompletedStroke(noteId, pageId, secondId, stroke).revision)

        val reopened = RoomDocumentRepository(closeAndReopen()).load(noteId)
        assertEquals(2L, reopened.revision)
        val elements = reopened.pages.single().elements
        assertEquals(listOf(firstId, secondId), elements.map { it.id })
        assertEquals(listOf(0L, 1L), elements.map { it.orderIndex })
        assertEquals(listOf(1L, 2L), elements.map { it.revision })
    }

    @Test
    fun duplicateElementIdFailsAtomicallyAndLeavesPreviousRevisionIntact() = runBlocking {
        val (noteId, pageId) = createNote()
        val sameId = UUID.randomUUID().toString()
        val stroke = reconstructablePenStroke()
        val bridge = InkDocumentRepositoryBridge(RoomDocumentRepository(checkNotNull(database)))
        assertEquals(1L, bridge.insertCompletedStroke(noteId, pageId, sameId, stroke).revision)

        val error = captureFailure {
            bridge.insertCompletedStroke(noteId, pageId, sameId, stroke)
        }
        assertNotNull(error)
        val reloaded = RoomDocumentRepository(closeAndReopen()).load(noteId)
        assertEquals(1L, reloaded.revision)
        assertEquals(listOf(sameId), reloaded.pages.single().elements.map { it.id })
    }

    @Test
    fun wrongPageOwnershipFailsWithoutInsertingAnyElements() = runBlocking {
        val (noteId, _) = createNote()
        val otherPageId = UUID.randomUUID().toString()
        val bridge = InkDocumentRepositoryBridge(RoomDocumentRepository(checkNotNull(database)))

        val error = captureFailure {
            bridge.insertCompletedStroke(noteId, otherPageId, UUID.randomUUID().toString(),
                reconstructablePenStroke())
        }
        assertNotNull(error)
        val reloaded = RoomDocumentRepository(closeAndReopen()).load(noteId)
        assertEquals(0L, reloaded.revision)
        assertTrue(reloaded.pages.single().elements.isEmpty())
    }

    private suspend fun captureFailure(action: suspend () -> Unit): Exception {
        return try {
            action()
            fail("Expected repository to reject the transaction")
            throw AssertionError("Unreachable")
        } catch (error: Exception) {
            error
        }
    }
}
