
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.*
import org.junit.Test

class StoredCanonicalStrokeDecoderTest {

    private fun envelope(
        id: String,
        locked: Boolean = false,
    ): String = """
        {
          "id": "$id",
          "type": "stroke",
          "transform": [1,0,0,1,4,-2],
          "locked": $locked,
          "payload": {
            "encoding": "sampledPointsV1",
            "brush": {
              "kind": "pencil",
              "width": 2.5,
              "color": "#00AA88",
              "opacity": 0.75
            },
            "points": [
              {
                "x": 10.25,
                "y": 20.5,
                "tMs": 0,
                "pressure": 0.4,
                "tiltRad": 0.2,
                "orientationRad": -1.2
              },
              {
                "x": 30,
                "y": 40,
                "tMs": 10
              },
              {
                "x": 31,
                "y": 41,
                "tMs": 10,
                "pressure": 0.8
              }
            ]
          }
        }
    """.trimIndent()

    private fun element(
        id: String,
        order: Long,
        locked: Boolean = false,
    ): StoredElement = StoredElement(
        id = id,
        orderIndex = order,
        kind = "stroke",
        envelopeJson = envelope(id, locked),
        revision = 3L,
    )

    private fun page(
        elements: List<StoredElement>,
    ): StoredPage = StoredPage(
        id = "page-1",
        orderIndex = 0L,
        width = 612.0,
        height = 792.0,
        backgroundJson = """{"type":"blank","color":"#FFFFFF"}""",
        revision = 3L,
        elements = elements,
    )

    @Test
    fun preservesBrushSamplesAndOptionalAttributes() {
        val original = element("stroke-1", 5L, locked = true)
        val originalJson = original.envelopeJson

        val decoded = StoredCanonicalStrokeDecoder
            .fromPage(page(listOf(original)))
            .single()

        assertEquals("page-1", decoded.pageId)
        assertEquals("stroke-1", decoded.elementId)
        assertEquals(5L, decoded.orderIndex)
        assertTrue(decoded.locked)

        assertEquals("pencil", decoded.brush.kind)
        assertEquals(2.5, decoded.brush.widthDu, 0.0)
        assertEquals("#00AA88", decoded.brush.colorHex)
        assertEquals(0.75, decoded.brush.opacity, 0.0)

        assertEquals(3, decoded.samples.size)

        val first = decoded.samples[0]
        assertEquals(DuPoint(10.25, 20.5), first.localPoint)
        assertEquals(0L, first.tMs)
        assertEquals(0.4, first.pressure!!, 0.0)
        assertEquals(0.2, first.tiltRad!!, 0.0)
        assertEquals(-1.2, first.orientationRad!!, 0.0)

        val second = decoded.samples[1]
        assertEquals(10L, second.tMs)
        assertNull(second.pressure)
        assertNull(second.tiltRad)
        assertNull(second.orientationRad)

        val third = decoded.samples[2]
        assertEquals(10L, third.tMs)
        assertEquals(0.8, third.pressure!!, 0.0)

        // Decoding must not rewrite persisted content.
        assertEquals(originalJson, original.envelopeJson)
    }

    @Test
    fun preservesStoredElementOrder() {
        val input = page(
            listOf(
                element("stroke-z", 10L),
                element("stroke-a", 20L),
            )
        )

        val decoded = StoredCanonicalStrokeDecoder.fromPage(input)

        assertEquals(
            listOf("stroke-z", "stroke-a"),
            decoded.map { it.elementId }
        )
        assertEquals(
            listOf(10L, 20L),
            decoded.map { it.orderIndex }
        )
    }

    @Test
    fun persistedTransformCanBeProjectedWithoutChangingSamples() {
        val stroke = StoredCanonicalStrokeDecoder
            .fromPage(page(listOf(element("stroke-1", 0L))))
            .single()

        val first = stroke.samples.first()
        val original = first.localPoint

        assertEquals(
            DuPoint(14.25, 18.5),
            CanonicalStrokeProjection.pagePointOrNull(
                original,
                stroke.elementTransform
            )
        )

        val positionedPage = PositionedPage(
            id = stroke.pageId,
            widthDu = 612.0,
            heightDu = 792.0,
            originXDu = 25.0,
            originYDu = 100.0,
        )

        val viewport = ViewportTransform(
            scalePxPerDu = 2.0,
            translationXPx = 10.0,
            translationYPx = 20.0,
        )

        assertEquals(
            PxPoint(88.5, 257.0),
            CanonicalStrokeProjection.viewportPointOrNull(
                original,
                stroke.elementTransform,
                positionedPage,
                viewport,
            )
        )

        // Projection leaves canonical local geometry intact.
        assertEquals(DuPoint(10.25, 20.5), first.localPoint)
    }

    @Test
    fun rejectsInvalidEncodingAndTransforms() {
        val valid = element("stroke-1", 0L)

        val badEncoding = valid.copy(
            envelopeJson = valid.envelopeJson.replace(
                "sampledPointsV1",
                "futureEncoding",
            )
        )

        val badTransform = valid.copy(
            envelopeJson = valid.envelopeJson.replace(
                "[1,0,0,1,4,-2]",
                "[1,0,0,0,4,-2]",
            )
        )

        for (invalid in listOf(badEncoding, badTransform)) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredCanonicalStrokeDecoder.fromPage(
                    page(listOf(invalid))
                )
            }
        }
    }

    @Test
    fun rejectsInvalidSamplesAndIdentityMismatch() {
        val valid = element("stroke-1", 0L)

        val reversedTime = valid.copy(
            envelopeJson = valid.envelopeJson.replace(
                """"tMs": 10""",
                """"tMs": -1""",
            )
        )

        val overflowCoordinate = valid.copy(
            envelopeJson = valid.envelopeJson.replace(
                """"x": 10.25""",
                """"x": 1e309""",
            )
        )

        val wrongId = valid.copy(id = "different-id")

        for (invalid in listOf(
            reversedTime,
            overflowCoordinate,
            wrongId,
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredCanonicalStrokeDecoder.fromPage(
                    page(listOf(invalid))
                )
            }
        }
    }
}
