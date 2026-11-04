
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class EditorPenDocumentCodecTest {

    private val pageId = "page-1"
    private val elementId =
        "11111111-2222-4333-8444-555555555555"

    private val layout = requireNotNull(
        ViewportGeometry.layoutOrNull(
            listOf(PageSizeDu(pageId, 600.0, 800.0))
        )
    )

    private val camera = ViewportTransform(
        scalePxPerDu = 2.0,
        translationXPx = 10.0,
        translationYPx = 20.0,
    )

    private val viewport = PxSize(1500.0, 1200.0)

    private fun captured(
        samples: List<EditorPenPoint> = listOf(
            EditorPenPoint(110.0, 220.0, 1000L),
            EditorPenPoint(130.0, 250.0, 1025L),
        ),
    ) = EditorPenPreview(
        pointerId = 7L,
        pageId = pageId,
        startTransform = camera,
        startViewport = viewport,
        samples = samples,
    )

    private fun decode(envelope: String): StoredCanonicalStroke {
        val page = StoredPage(
            id = pageId,
            orderIndex = 0L,
            width = 600.0,
            height = 800.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = listOf(
                StoredElement(
                    id = elementId,
                    orderIndex = 0L,
                    kind = "stroke",
                    envelopeJson = envelope,
                    revision = 1L,
                ),
            ),
        )
        return StoredCanonicalStrokeDecoder.fromPage(page).single()
    }

    @Test
    fun roundTripsThroughProductionDecoder() {
        val envelope = EditorPenDocumentCodec.encode(
            completed = captured(),
            layout = layout,
            elementId = elementId,
        )

        val decoded = decode(envelope)

        assertEquals(elementId, decoded.elementId)
        assertEquals(pageId, decoded.pageId)
        assertEquals("pen", decoded.brush.kind)
        assertEquals(3.0, decoded.brush.widthDu, 0.000001)
        assertEquals("#2255AA", decoded.brush.colorHex)
        assertEquals(1.0, decoded.brush.opacity, 0.0)

        assertEquals(2, decoded.samples.size)

        assertEquals(50.0, decoded.samples[0].xDu, 0.000001)
        assertEquals(100.0, decoded.samples[0].yDu, 0.000001)
        assertEquals(0L, decoded.samples[0].tMs)

        assertEquals(60.0, decoded.samples[1].xDu, 0.000001)
        assertEquals(115.0, decoded.samples[1].yDu, 0.000001)
        assertEquals(25L, decoded.samples[1].tMs)
    }

    @Test
    fun canonicalBrushWidthDoesNotDependOnZoom() {
        val original = captured()

        val zoomed = original.copy(
            startTransform = camera.copy(
                scalePxPerDu = 4.0,
            ),
            samples = listOf(
                EditorPenPoint(210.0, 420.0, 1000L),
                EditorPenPoint(250.0, 480.0, 1025L),
            ),
        )

        val first = decode(
            EditorPenDocumentCodec.encode(
                original, layout, elementId
            )
        )

        val second = decode(
            EditorPenDocumentCodec.encode(
                zoomed, layout, elementId
            )
        )

        assertEquals(
            first.brush.widthDu,
            second.brush.widthDu,
            0.0,
        )
        assertEquals(
            first.samples[0].xDu,
            second.samples[0].xDu,
            0.000001,
        )
        assertEquals(
            first.samples[0].yDu,
            second.samples[0].yDu,
            0.000001,
        )
    }

    @Test
    fun supportsSinglePointStroke() {
        val envelope = EditorPenDocumentCodec.encode(
            captured(
                listOf(EditorPenPoint(110.0, 220.0, 1000L))
            ),
            layout,
            elementId,
        )

        val stroke = decode(envelope)

        assertEquals(1, stroke.samples.size)
        assertEquals(0L, stroke.samples.single().tMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSamplesOutsideStartingPage() {
        val invalid = captured(
            listOf(
                EditorPenPoint(110.0, 220.0, 1000L),
                EditorPenPoint(1300.0, 250.0, 1020L),
            )
        )

        EditorPenDocumentCodec.encode(
            invalid, layout, elementId
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMonotonicTimestamps() {
        val invalid = captured(
            listOf(
                EditorPenPoint(110.0, 220.0, 1000L),
                EditorPenPoint(130.0, 250.0, 999L),
            )
        )

        EditorPenDocumentCodec.encode(
            invalid, layout, elementId
        )
    }

    @Test
    fun encodingDoesNotMutateCapturedSamples() {
        val original = captured()
        val samplesBefore = original.samples.toList()

        EditorPenDocumentCodec.encode(
            original, layout, elementId
        )

        assertEquals(samplesBefore, original.samples)
    }
}
