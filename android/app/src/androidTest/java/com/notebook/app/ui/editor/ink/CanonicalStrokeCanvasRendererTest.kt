
package com.notebook.app.ui.editor.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalStrokeCanvasRendererTest {

    private fun stroke(
        id: String,
        points: String,
        transform: String = "[1,0,0,1,0,0]",
        width: Double = 10.0,
        color: String = "#FF0000",
        opacity: Double = 1.0,
        order: Long = 0L,
    ): StoredElement = StoredElement(
        id = id,
        orderIndex = order,
        kind = "stroke",
        envelopeJson = """
            {
              "id": "$id",
              "type": "stroke",
              "transform": $transform,
              "locked": false,
              "payload": {
                "encoding": "sampledPointsV1",
                "brush": {
                  "kind": "pen",
                  "width": $width,
                  "color": "$color",
                  "opacity": $opacity
                },
                "points": $points
              }
            }
        """.trimIndent(),
        revision = 1L,
    )

    private fun draw(
        elements: List<StoredElement>,
        originX: Double = 0.0,
        originY: Double = 0.0,
        scale: Double = 1.0,
    ): Bitmap {
        val page = StoredPage(
            id = "page-1",
            orderIndex = 0L,
            width = 100.0,
            height = 100.0,
            backgroundJson =
                """{"type":"blank","color":"#FFFFFF"}""",
            revision = 1L,
            elements = elements,
        )

        val positionedPage = PositionedPage(
            id = page.id,
            widthDu = page.width,
            heightDu = page.height,
            originXDu = originX,
            originYDu = originY,
        )

        val viewport = ViewportTransform(
            scalePxPerDu = scale,
            translationXPx = 0.0,
            translationYPx = 0.0,
        )

        val plan = CanonicalStrokeRenderPlan.build(
            page,
            positionedPage,
            viewport,
        )

        val bitmap = Bitmap.createBitmap(
            300,
            300,
            Bitmap.Config.ARGB_8888,
        )

        CanonicalStrokeCanvasRenderer.draw(
            Canvas(bitmap),
            plan,
        )

        return bitmap
    }

    @Test
    fun strokeCrossingPageEdgesIsClipped() {
        val element = stroke(
            id = "cross-edge",
            points = """
                [
                  {"x":-10,"y":50,"tMs":0},
                  {"x":110,"y":50,"tMs":20}
                ]
            """.trimIndent(),
        )

        val originalJson = element.envelopeJson

        // Page bounds in pixels:
        // left=40, right=240, top=60, bottom=260.
        val bitmap = draw(
            listOf(element),
            originX = 20.0,
            originY = 30.0,
            scale = 2.0,
        )

        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(30, 160)))
            assertTrue(Color.alpha(bitmap.getPixel(50, 160)) > 0)
            assertTrue(Color.alpha(bitmap.getPixel(230, 160)) > 0)
            assertEquals(0, Color.alpha(bitmap.getPixel(250, 160)))

            // Drawing must not rewrite the canonical envelope.
            assertEquals(originalJson, element.envelopeJson)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun nonUniformTransformProducesEllipticalSinglePoint() {
        val element = stroke(
            id = "scaled-dot",
            transform = "[2,0,0,0.5,0,0]",
            points = """[{"x":20,"y":40,"tMs":0}]""",
        )

        val bitmap = draw(listOf(element))

        try {
            // Original dot radius: 5 DU.
            // Center becomes (40,20).
            // Horizontal radius becomes 10 pixels.
            // Vertical radius becomes 2.5 pixels.
            assertTrue(Color.alpha(bitmap.getPixel(40, 20)) > 0)
            assertTrue(Color.alpha(bitmap.getPixel(48, 20)) > 0)

            assertEquals(
                0,
                Color.alpha(bitmap.getPixel(40, 26)),
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun strokeOrderAndOpacityAreVisibleInPixels() {
        val points = """
            [
              {"x":20,"y":50,"tMs":0},
              {"x":80,"y":50,"tMs":20}
            ]
        """.trimIndent()

        val bottom = stroke(
            id = "red-bottom",
            points = points,
            color = "#FF0000",
            order = 1L,
        )

        val top = stroke(
            id = "blue-top",
            points = points,
            color = "#0000FF",
            opacity = 0.5,
            order = 2L,
        )

        val bitmap = draw(listOf(bottom, top))

        try {
            val pixel = bitmap.getPixel(50, 50)

            assertEquals(255, Color.alpha(pixel))
            assertTrue(Color.red(pixel) in 120..135)
            assertEquals(0, Color.green(pixel))
            assertTrue(Color.blue(pixel) in 120..135)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun reflectionMovesDotWithoutChangingSource() {
        val element = stroke(
            id = "reflected",
            transform = "[-1,0,0,1,80,0]",
            points = """[{"x":10,"y":20,"tMs":0}]""",
        )

        val originalJson = element.envelopeJson
        val bitmap = draw(listOf(element))

        try {
            // Reflection maps local (10,20) to (70,20).
            assertTrue(Color.alpha(bitmap.getPixel(70, 20)) > 0)
            assertEquals(0, Color.alpha(bitmap.getPixel(10, 20)))
            assertEquals(originalJson, element.envelopeJson)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun zeroOpacityLeavesCanvasUnchanged() {
        val element = stroke(
            id = "transparent",
            points = """[{"x":50,"y":50,"tMs":0}]""",
            opacity = 0.0,
        )

        val bitmap = draw(listOf(element))

        try {
            assertEquals(
                0,
                Color.alpha(bitmap.getPixel(50, 50)),
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun outOfCanvasRangeStrokeDoesNotHideFollowingStroke() {
        val points = """[{"x":50,"y":50,"tMs":0}]"""

        val huge = stroke(
            id = "huge-width",
            points = points,
            width = 1e40,
            order = 0L,
        )

        val normal = stroke(
            id = "normal",
            points = points,
            width = 8.0,
            color = "#00FF00",
            order = 1L,
        )

        val bitmap = draw(listOf(huge, normal))

        try {
            assertEquals(
                Color.GREEN,
                bitmap.getPixel(50, 50),
            )
        } finally {
            bitmap.recycle()
        }
    }
}
