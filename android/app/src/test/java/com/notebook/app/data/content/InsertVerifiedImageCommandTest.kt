package com.notebook.app.data.content

import com.notebook.app.data.content.model.ImageFrame
import com.notebook.app.data.content.model.InsertVerifiedImageCommand
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure input checks; no Room access, lease acquisition, or media authorization. */
class InsertVerifiedImageCommandTest {
    private val note = "11111111-1111-4111-8111-111111111111"
    private val page = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val element = "33333333-3333-4333-8333-333333333333"
    private val asset = "44444444-4444-4444-8444-444444444444"

    private fun command(
        noteId: String = note,
        pageId: String = page,
        elementId: String = element,
        assetId: String = asset,
        revision: Long = 7L,
        at: Int? = null,
        transform: List<Double> = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        frame: ImageFrame = ImageFrame(12.0, 18.0, 80.0, 60.0),
        opacity: Double = 1.0,
    ) = InsertVerifiedImageCommand(
        noteId, pageId, elementId, assetId, revision, at, transform, frame, opacity,
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid command")
        } catch (error: LibraryOperationException) {
            assertEquals(LibraryErrorCode.INVALID_INPUT, error.code)
        }
    }

    @Test fun validInputsProduceDefensiveMetadataOnlySnapshot() {
        val mutable = mutableListOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val validated = command(transform = mutable, at = 0, opacity = 0.5).validated()
        assertEquals(note, validated.noteId)
        assertEquals(page, validated.pageId)
        assertEquals(element, validated.elementId)
        assertEquals(asset, validated.assetId)
        assertEquals(7L, validated.expectedNoteRevision)
        assertEquals(0, validated.at)
        assertEquals(0.5, validated.opacity, 0.0)
        assertNotSame(mutable, validated.transform)
        mutable[0] = 100.0
        assertEquals(1.0, validated.transform[0], 0.0)
    }

    @Test fun nonCanonicalOrMissingIdsReject() {
        rejects { command(noteId = "").validated() }
        rejects { command(pageId = page.uppercase()).validated() }
        rejects { command(elementId = "not-an-id").validated() }
        rejects { command(assetId = "").validated() }
    }

    @Test fun negativeExpectedRevisionOrIndexReject() {
        rejects { command(revision = -1L).validated() }
        rejects { command(at = -1).validated() }
        assertEquals(Long.MAX_VALUE, command(revision = Long.MAX_VALUE).validated().expectedNoteRevision)
    }

    @Test fun wrongSizeNonFiniteAndSingularTransformsReject() {
        rejects { command(transform = listOf(1.0)).validated() }
        rejects { command(transform = listOf(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0)).validated() }
        rejects { command(transform = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)).validated() }
        rejects { command(transform = listOf(Double.MAX_VALUE, 0.0, 0.0, Double.MAX_VALUE, 0.0, 0.0)).validated() }
    }

    @Test fun nonFiniteFrameAndNonPositiveSizesReject() {
        rejects { command(frame = ImageFrame(Double.NaN, 0.0, 12.0, 12.0)).validated() }
        rejects { command(frame = ImageFrame(0.0, Double.POSITIVE_INFINITY, 12.0, 12.0)).validated() }
        rejects { command(frame = ImageFrame(0.0, 0.0, 0.0, 12.0)).validated() }
        rejects { command(frame = ImageFrame(0.0, 0.0, 12.0, -1.0)).validated() }
    }

    @Test fun outOfRangeOpacityRejects() {
        rejects { command(opacity = -0.01).validated() }
        rejects { command(opacity = 1.01).validated() }
        rejects { command(opacity = Double.NaN).validated() }
        rejects { command(opacity = Double.POSITIVE_INFINITY).validated() }
        assertEquals(0.0, command(opacity = 0.0).validated().opacity, 0.0)
    }

    @Test fun validNegativeTranslationAndZeroInsertionIndexAreAllowed() {
        val result = command(
            at = 0,
            transform = listOf(1.0, 0.0, 0.0, 1.0, -10.0, -20.0),
            frame = ImageFrame(-4.0, -8.0, 1.0, 2.0),
        ).validated()
        assertEquals(-10.0, result.transform[4], 0.0)
        assertEquals(-4.0, result.frame.x, 0.0)
        assertTrue(result.frame.width > 0.0)
    }
}
