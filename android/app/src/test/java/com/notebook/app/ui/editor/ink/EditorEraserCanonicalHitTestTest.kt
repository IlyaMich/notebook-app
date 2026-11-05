
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEraserCanonicalHitTestTest {

    private val pageId =
        "00000000-0000-0000-0000-000000000100"

    private val strokeId =
        "00000000-0000-0000-0000-000000000101"

    private fun p(x: Double, y: Double) =
        Point(x, y)

    private fun strokeEnvelope(
        id: String = strokeId,
        a: Double = 1.0,
        b: Double = 0.0,
        c: Double = 0.0,
        d: Double = 1.0,
        tx: Double = 0.0,
        ty: Double = 0.0,
        locked: Boolean = false,
    ): String = """
        {
          "id": "$id",
          "type": "stroke",
          "transform": [$a, $b, $c, $d, $tx, $ty],
          "locked": $locked,
          "payload": {
            "encoding": "sampledPointsV1",
            "brush": {
              "kind": "pen",
              "width": 2.0,
              "color": "#2255AA",
              "opacity": 1.0
            },
            "points": [
              {"x": 0.0, "y": 0.0, "tMs": 0},
              {"x": 100.0, "y": 0.0, "tMs": 10}
            ]
          }
        }
    """.trimIndent()

    private fun page(
        envelope: String = strokeEnvelope(),
        id: String = strokeId,
    ): StoredPage = StoredPage(
        id = pageId,
        orderIndex = 0L,
        width = 595.276,
        height = 841.890,
        backgroundJson =
            """{"type":"blank","color":"#FFFFFF"}""",
        revision = 0L,
        elements = listOf(
            StoredElement(
                id = id,
                orderIndex = 0L,
                kind = "stroke",
                envelopeJson = envelope,
                revision = 0L,
            )
        ),
    )

    private fun scan(
        page: StoredPage,
        eraser: List<Point>,
    ) = EditorEraserCanonicalHitTest.scan(
        page = page,
        eraserPointsPageDu = eraser,
        eraserRadiusPageDu = 3.0,
    )

    @Test
    fun persistedCanonicalStrokeIsDetected() {
        val result = scan(
            page(),
            listOf(
                p(50.0, -10.0),
                p(50.0, 10.0),
            ),
        )

        assertEquals(
            listOf(strokeId),
            result.hitElementIds,
        )

        assertTrue(result.lockedElementIds.isEmpty())
        assertFalse(result.hasUnsupportedTransforms)
    }

    @Test
    fun distantEraserDoesNotMatchStoredStroke() {
        val result = scan(
            page(),
            listOf(
                p(200.0, 200.0),
                p(250.0, 250.0),
            ),
        )

        assertTrue(result.hitElementIds.isEmpty())
        assertFalse(result.hasUnsupportedTransforms)
    }

    @Test
    fun translatedStoredStrokeUsesPageCoordinates() {
        val translated = strokeEnvelope(
            tx = 200.0,
            ty = 100.0,
        )

        val result = scan(
            page(envelope = translated),
            listOf(
                p(250.0, 90.0),
                p(250.0, 110.0),
            ),
        )

        assertEquals(
            listOf(strokeId),
            result.hitElementIds,
        )
    }

    @Test
    fun rotatedStoredStrokeUsesCanonicalTransform() {
        val rotated = strokeEnvelope(
            a = 0.0,
            b = 1.0,
            c = -1.0,
            d = 0.0,
            tx = 50.0,
        )

        val result = scan(
            page(envelope = rotated),
            listOf(
                p(40.0, 50.0),
                p(60.0, 50.0),
            ),
        )

        assertEquals(
            listOf(strokeId),
            result.hitElementIds,
        )
    }

    @Test
    fun lockedStrokeCannotBecomeHitCandidate() {
        val result = scan(
            page(
                envelope = strokeEnvelope(
                    locked = true,
                )
            ),
            listOf(
                p(50.0, -10.0),
                p(50.0, 10.0),
            ),
        )

        assertTrue(result.hitElementIds.isEmpty())

        assertEquals(
            listOf(strokeId),
            result.lockedElementIds,
        )
    }

    @Test
    fun unsupportedTransformIsReportedNotSilentlyMissed() {
        val nonuniform = strokeEnvelope(
            a = 2.0,
            d = 1.0,
        )

        val result = scan(
            page(envelope = nonuniform),
            listOf(p(50.0, 0.0)),
        )

        assertTrue(result.hitElementIds.isEmpty())

        assertEquals(
            listOf(strokeId),
            result.unsupportedTransformElementIds,
        )

        assertTrue(result.hasUnsupportedTransforms)
    }

    @Test
    fun scanDoesNotMutateStoredEnvelope() {
        val original = strokeEnvelope()
        val storedPage = page(original)

        scan(
            storedPage,
            listOf(p(50.0, 0.0)),
        )

        assertEquals(
            original,
            storedPage.elements.single().envelopeJson,
        )
    }

    @Test
    fun emptyPageProducesNoHits() {
        val emptyPage = page().copy(
            elements = emptyList(),
        )

        val result = scan(
            emptyPage,
            listOf(p(50.0, 0.0)),
        )

        assertTrue(result.hitElementIds.isEmpty())
        assertTrue(result.lockedElementIds.isEmpty())
        assertFalse(result.hasUnsupportedTransforms)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidEraserRadiusIsRejected() {
        EditorEraserCanonicalHitTest.scan(
            page = page(),
            eraserPointsPageDu = listOf(
                p(50.0, 0.0),
            ),
            eraserRadiusPageDu = Double.NaN,
        )
    }

    @Test
    fun intersectionOnlyOutsidePageIsNotAHitCandidate() {
        // Canonical stroke is translated completely
        // outside the left edge of its owning page.
        val outside = strokeEnvelope(
            tx = -150.0,
            ty = 100.0,
        )

        val result = scan(
            page(envelope = outside),
            listOf(
                p(-100.0, 90.0),
                p(-100.0, 110.0),
            ),
        )

        assertTrue(result.hitElementIds.isEmpty())
        assertTrue(
            result.unresolvedPageClipElementIds.isEmpty()
        )
    }

    @Test
    fun uncertainPageClippingIsReportedSeparately() {
        // A portion of the stroke enters the page,
        // but the eraser's obvious intersection
        // is outside it.
        val edgeStroke = strokeEnvelope(
            tx = -99.5,
            ty = 50.0,
        )

        val result = scan(
            page(envelope = edgeStroke),
            listOf(
                p(-50.0, 50.0),
                p(10.0, 70.0),
            ),
        )

        assertTrue(result.hitElementIds.isEmpty())

        assertEquals(
            listOf(strokeId),
            result.unresolvedPageClipElementIds,
        )

        assertTrue(result.hasUnresolvedPageClipping)
        assertFalse(result.hasUnsupportedTransforms)
    }

}
