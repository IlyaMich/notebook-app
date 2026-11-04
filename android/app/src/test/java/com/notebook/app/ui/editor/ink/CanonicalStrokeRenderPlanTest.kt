
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.*
import org.junit.Test

class CanonicalStrokeRenderPlanTest {

    private fun stroke(
        id: String = "stroke-1",
        order: Long = 0L,
        transform: String = "[1,0,0,1,0,0]",
        locked: Boolean = false,
    ): StoredElement = StoredElement(
        id = id,
        orderIndex = order,
        kind = "stroke",
        envelopeJson = """
            {
              "id": "$id",
              "type": "stroke",
              "transform": $transform,
              "locked": $locked,
              "payload": {
                "encoding": "sampledPointsV1",
                "brush": {
                  "kind": "pen",
                  "width": 8,
                  "color": "#123456",
                  "opacity": 0.75
                },
                "points": [
                  {
                    "x": -5,
                    "y": 20,
                    "tMs": 0,
                    "pressure": 0.4
                  },
                  {
                    "x": 205,
                    "y": 40,
                    "tMs": 15
                  }
                ]
              }
            }
        """.trimIndent(),
        revision = 2L,
    )

    private fun page(
        elements: List<StoredElement>,
    ): StoredPage = StoredPage(
        id = "page-1",
        orderIndex = 0L,
        width = 200.0,
        height = 100.0,
        backgroundJson =
            """{"type":"blank","color":"#FFFFFF"}""",
        revision = 2L,
        elements = elements,
    )

    private fun positioned(
        x: Double = 0.0,
        y: Double = 0.0,
    ): PositionedPage = PositionedPage(
        id = "page-1",
        widthDu = 200.0,
        heightDu = 100.0,
        originXDu = x,
        originYDu = y,
    )

    private fun viewport(
        scale: Double = 1.0,
        x: Double = 0.0,
        y: Double = 0.0,
    ): ViewportTransform = ViewportTransform(
        scalePxPerDu = scale,
        translationXPx = x,
        translationYPx = y,
    )

    @Test
    fun createsCorrectPageClipAndPreservesStrokeData() {
        val element = stroke(locked = true)
        val originalJson = element.envelopeJson

        val plan = CanonicalStrokeRenderPlan.build(
            page(listOf(element)),
            positioned(x = 12.0, y = 24.0),
            viewport(scale = 2.0, x = -10.0, y = 5.0),
        )

        assertEquals("page-1", plan.pageId)

        assertEquals(
            PageStrokeClipPx(
                left = 14.0,
                top = 53.0,
                right = 414.0,
                bottom = 253.0,
            ),
            plan.clipPx,
        )

        val command = plan.strokes.single()
        assertEquals("stroke-1", command.stroke.elementId)
        assertTrue(command.stroke.locked)
        assertEquals(8.0, command.stroke.brush.widthDu, 0.0)
        assertEquals("#123456", command.stroke.brush.colorHex)

        assertEquals(
            PxPoint(34.0, 93.0),
            command.localToViewport.mapPointOrNull(
                DuPoint(10.0, 20.0)
            ),
        )

        assertEquals(
            DuPoint(-5.0, 20.0),
            command.stroke.samples.first().localPoint,
        )
        assertEquals(
            0.4,
            command.stroke.samples.first().pressure!!,
            0.0,
        )
        assertNull(command.stroke.samples.last().pressure)

        assertEquals(originalJson, element.envelopeJson)
    }

    @Test
    fun nonUniformScaleRetainsFullBrushFootprintTransform() {
        val element = stroke(
            transform = "[2,0,0,0.5,10,20]"
        )

        val command = CanonicalStrokeRenderPlan.build(
            page(listOf(element)),
            positioned(),
            viewport(scale = 3.0),
        ).strokes.single()

        val affine = command.localToViewport

        assertEquals(
            PxPoint(90.0, 90.0),
            affine.mapPointOrNull(DuPoint(10.0, 20.0)),
        )

        // The canonical brush radius is 4 DU.
        // Its two local basis offsets transform differently.
        assertEquals(
            PxPoint(24.0, 0.0),
            affine.mapVectorOrNull(DuPoint(4.0, 0.0)),
        )
        assertEquals(
            PxPoint(0.0, 6.0),
            affine.mapVectorOrNull(DuPoint(0.0, 4.0)),
        )

        // Never collapse the transformed footprint into
        // one arbitrary scalar brush width.
        assertEquals(
            8.0,
            command.stroke.brush.widthDu,
            0.0,
        )
    }

    @Test
    fun reflectionAndRotationUseCorrectAffineConvention() {
        val reflected = CanonicalStrokeRenderPlan.build(
            page(
                listOf(
                    stroke(transform = "[-1,0,0,1,120,0]")
                )
            ),
            positioned(),
            viewport(),
        ).strokes.single()

        assertEquals(
            PxPoint(70.0, 60.0),
            reflected.localToViewport.mapPointOrNull(
                DuPoint(50.0, 60.0)
            ),
        )

        val rotated = CanonicalStrokeRenderPlan.build(
            page(
                listOf(
                    stroke(transform = "[0,1,-1,0,0,0]")
                )
            ),
            positioned(),
            viewport(),
        ).strokes.single()

        assertEquals(
            PxPoint(-4.0, 3.0),
            rotated.localToViewport.mapPointOrNull(
                DuPoint(3.0, 4.0)
            ),
        )
    }

    @Test
    fun preservesSourceOrderAndSkipsNonStrokeElements() {
        val shape = StoredElement(
            id = "shape-1",
            orderIndex = 2L,
            kind = "shape",
            envelopeJson = "{}",
            revision = 2L,
        )

        val plan = CanonicalStrokeRenderPlan.build(
            page(
                listOf(
                    stroke("stroke-a", order = 1L),
                    shape,
                    stroke("stroke-b", order = 3L),
                )
            ),
            positioned(),
            viewport(),
        )

        assertEquals(
            listOf("stroke-a", "stroke-b"),
            plan.strokes.map { it.stroke.elementId },
        )

        assertEquals(
            listOf(1L, 3L),
            plan.strokes.map { it.stroke.orderIndex },
        )
    }

    @Test
    fun outOfBoundsSamplesRemainAvailableForClippedDrawing() {
        val plan = CanonicalStrokeRenderPlan.build(
            page(listOf(stroke())),
            positioned(),
            viewport(),
        )

        val samples = plan.strokes.single().stroke.samples

        // Both points are beyond opposite horizontal edges.
        // A stroke connecting them must not be discarded.
        assertEquals(-5.0, samples.first().xDu, 0.0)
        assertEquals(205.0, samples.last().xDu, 0.0)

        assertEquals(
            PageStrokeClipPx(0.0, 0.0, 200.0, 100.0),
            plan.clipPx,
        )
    }

    @Test
    fun rejectsMismatchedOrInvalidGeometry() {
        val content = page(listOf(stroke()))

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalStrokeRenderPlan.build(
                content,
                positioned().copy(id = "wrong-page"),
                viewport(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalStrokeRenderPlan.build(
                content,
                positioned().copy(widthDu = 300.0),
                viewport(),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalStrokeRenderPlan.build(
                content,
                positioned(),
                viewport(scale = 0.0),
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalStrokeRenderPlan.build(
                content,
                positioned(x = Double.POSITIVE_INFINITY),
                viewport(),
            )
        }
    }

    @Test
    fun rejectsOverflowingViewportTransformComposition() {
        val content = page(
            listOf(
                stroke(transform = "[1,0,0,1,1e308,0]")
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            CanonicalStrokeRenderPlan.build(
                content,
                positioned(),
                viewport(scale = 4.0),
            )
        }
    }
}
