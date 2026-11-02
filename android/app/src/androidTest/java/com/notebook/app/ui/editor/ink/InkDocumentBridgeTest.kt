package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID

/** CP2A uses a repository fake: proves command/codec boundary, NOT Room durability. */
@RunWith(AndroidJUnit4::class)
class InkDocumentBridgeTest {
    private val noteId = UUID.randomUUID().toString()
    private val pageId = UUID.randomUUID().toString()
    private val otherPageId = UUID.randomUUID().toString()
    private val elementId = UUID.randomUUID().toString()

    private fun stroke() = CanonicalInkStroke(
        brush = CanonicalInkBrush("pen", 2.75, "#2255AA", 0.8),
        points = listOf(
            CanonicalInkPoint(80.5, 100.25, 0, pressure = 0.3, tiltRad = 0.2, orientationRad = -0.5),
            CanonicalInkPoint(122.125, 104.5, 18, pressure = 0.65),
            CanonicalInkPoint(140.5, 109.25, 36), // Optional fields intentionally absent.
        ),
    )

    @Test fun envelopeMatchesCanonicalFMT04Structure() {
        val json = JSONObject(CanonicalInkDocumentCodec.encode(elementId, stroke()))
        assertEquals(elementId, json.getString("id"))
        assertEquals("stroke", json.getString("type"))
        assertFalse(json.getBoolean("locked"))
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            (0 until 6).map { json.getJSONArray("transform").getDouble(it) })
        val payload = json.getJSONObject("payload")
        assertEquals("sampledPointsV1", payload.getString("encoding"))
        assertEquals(2.75, payload.getJSONObject("brush").getDouble("width"), 0.0)
        val points = payload.getJSONArray("points")
        assertEquals(3, points.length())
        assertTrue(points.getJSONObject(0).has("pressure"))
        assertTrue(points.getJSONObject(0).has("tiltRad"))
        assertTrue(points.getJSONObject(0).has("orientationRad"))
        assertEquals(0.65, points.getJSONObject(1).getDouble("pressure"), 0.0)
        val noOptional = points.getJSONObject(2)
        assertFalse(noOptional.has("pressure"))
        assertFalse(noOptional.has("tiltRad"))
        assertFalse(noOptional.has("orientationRad"))
        // Native Ink and viewport information are not persisted in the envelope.
        assertFalse(json.toString().contains("pxPerDu"))
        assertFalse(json.toString().contains("StrokeInputBatch"))
    }

    @Test fun encodeDecodePreservesCanonicalSamplesAndOptionalAbsence() {
        val expected = stroke()
        val actual = CanonicalInkDocumentCodec.decode(
            CanonicalInkDocumentCodec.encode(elementId, expected),
        )
        assertEquals(elementId, actual.elementId)
        assertEquals(expected, actual.stroke)
        assertFalse(actual.locked)
        assertEquals(0.65, actual.stroke.points[1].pressure!!, 0.0)
        assertNull(actual.stroke.points[2].pressure)
        assertNull(actual.stroke.points[2].tiltRad)
        assertNull(actual.stroke.points[2].orientationRad)
    }

    @Test fun readOnlyDecoderExposesLockedFlagWithoutRewritingSource() {
        val source = JSONObject(CanonicalInkDocumentCodec.encode(elementId, stroke()))
            .put("locked", true)
            .put("futureOptionalMetadata", "retained in repository")
            .toString()
        val decoded = CanonicalInkDocumentCodec.decode(source)
        assertTrue(decoded.locked)
        assertTrue(source.contains("futureOptionalMetadata"))
    }

    @Test fun unsupportedOrMalformedEnvelopesFailExplicitly() {
        val original = CanonicalInkDocumentCodec.encode(elementId, stroke())
        expectFailure {
            CanonicalInkDocumentCodec.decode(JSONObject(original)
                .put("type", "shape").toString())
        }
        expectFailure {
            val json = JSONObject(original)
            json.getJSONObject("payload").put("encoding", "nativeInkBlobV1")
            CanonicalInkDocumentCodec.decode(json.toString())
        }
        expectFailure {
            CanonicalInkDocumentCodec.decode(JSONObject(original)
                .put("transform", org.json.JSONArray(listOf(1, 0, 0, 1, 7, 0))).toString())
        }
        expectFailure {
            val json = JSONObject(original)
            json.getJSONObject("payload").getJSONArray("points")
                .getJSONObject(0).put("tMs", 0.25)
            CanonicalInkDocumentCodec.decode(json.toString())
        }
        expectFailure { CanonicalInkDocumentCodec.encode("invalid-id", stroke()) }
    }

    @Test fun insertsOneEnvelopeViaDocumentRepositoryAndReloadsPageLocalData() = runBlocking {
        val fake = FakeDocumentRepository(noteId, pageId, otherPageId)
        val bridge = InkDocumentRepositoryBridge(fake)
        assertEquals(0L, fake.load(noteId).revision)
        val commit = bridge.insertCompletedStroke(noteId, pageId, elementId, stroke())
        assertEquals(1L, commit.revision)
        assertEquals(1, fake.insertEdits.size)
        assertEquals(pageId, fake.insertEdits.single().pageId)
        assertEquals(1, fake.insertEdits.single().envelopes.size)
        assertEquals(listOf(StoredCanonicalInkStroke(elementId, stroke(), false)),
            bridge.loadSupportedStrokes(noteId, pageId))
        assertTrue(bridge.loadSupportedStrokes(noteId, otherPageId).isEmpty())
        assertEquals(1, fake.load(noteId).pages.first().elements.size)
        assertEquals(1L, fake.load(noteId).revision)
    }

    @Test fun failedCommitPropagatesAndDoesNotReportSuccess() = runBlocking {
        val fake = FakeDocumentRepository(noteId, pageId, otherPageId)
        fake.failNextCommit = true
        val bridge = InkDocumentRepositoryBridge(fake)
        try {
            bridge.insertCompletedStroke(noteId, pageId, elementId, stroke())
            fail("Repository failure should propagate to the caller")
        } catch (expected: IllegalStateException) {
            assertEquals("Synthetic storage failure", expected.message)
        }
        assertEquals(0L, fake.load(noteId).revision)
        assertTrue(bridge.loadSupportedStrokes(noteId, pageId).isEmpty())
    }

    private inline fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected a rejected operation")
        } catch (_: IllegalArgumentException) {
            // Canonical validation / unsupported payload error.
        } catch (_: IllegalStateException) {
            // Fake repository's forced persistence error.
        } catch (_: org.json.JSONException) {
            // Malformed JSON payload.
        }
    }

    private class FakeDocumentRepository(
        private val expectedNoteId: String,
        private val targetPageId: String,
        private val emptyPageId: String,
    ) : DocumentRepository {
        var failNextCommit: Boolean = false
        val insertEdits = mutableListOf<DocumentEdit.InsertElements>()
        private var revision: Long = 0
        private val elements = mutableListOf<StoredElement>()

        override suspend fun load(noteId: String): StoredNoteContent {
            require(noteId == expectedNoteId)
            return StoredNoteContent(noteId, revision, listOf(
                StoredPage(targetPageId, 0L, 612.0, 792.0, "{}", 0L, elements.toList()),
                StoredPage(emptyPageId, 1L, 792.0, 612.0, "{}", 0L, emptyList()),
            ))
        }

        override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit {
            require(noteId == expectedNoteId)
            if (failNextCommit) {
                failNextCommit = false
                throw IllegalStateException("Synthetic storage failure")
            }
            val insert = edit as? DocumentEdit.InsertElements
                ?: throw IllegalArgumentException("CP2A fake accepts inserts only")
            require(insert.pageId == targetPageId)
            require(insert.envelopes.size == 1)
            val json = JSONObject(insert.envelopes.single())
            val id = json.getString("id")
            require(elements.none { it.id == id })
            insertEdits.add(insert)
            revision++
            elements.add(StoredElement(id, elements.size.toLong(), "stroke", insert.envelopes.single(), revision))
            return DocumentCommit(noteId, revision, 1L)
        }
    }
}
