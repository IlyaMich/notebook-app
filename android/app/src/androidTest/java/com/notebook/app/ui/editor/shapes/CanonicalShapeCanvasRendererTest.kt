package com.notebook.app.ui.editor.shapes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.rendering.PageElementCanvasRenderer
import com.notebook.app.ui.editor.rendering.PageElementRenderPlan
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.ViewportTransform
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanonicalShapeCanvasRendererTest {
    private val pageId = "7d6acdd3-504f-4bb3-9c70-3ba7af56b4ba"
    private val shapeId = "10000000-0000-4000-8000-000000000001"
    private fun shape(
        kind: CanonicalShapeKind,
        geometry: CanonicalShapeGeometry = if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
            CanonicalShapeGeometry.Endpoints(20.0, 50.0, 80.0, 50.0)
        else CanonicalShapeGeometry.Frame(20.0, 20.0, 60.0, 60.0),
        transform: String = "[1,0,0,1,0,0]",
        width: Double = 6.0,
        opacity: Double = 1.0,
        fill: CanonicalShapeFill? = null,
        order: Long = 1,
    ): StoredElement {
        val raw = CanonicalShapeAdapter.create(shapeId, kind, geometry,
            CanonicalShapeStroke("#FF0000", width, opacity), fill,
            if (kind == CanonicalShapeKind.ARROW) CanonicalShapeArrowHead(20.0, 30.0) else null)
        val obj = Json.parseToJsonElement(raw).jsonObject
        return StoredElement(shapeId, order, "shape",
            JsonObject(obj + ("transform" to Json.parseToJsonElement(transform))).toString(), 1)
    }
    private fun stroke(id: String, color: String, order: Long, x1: Int = 20, x2: Int = 80, width: Int = 10) =
        StoredElement(id, order, "stroke",
            """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","color":"$color","width":$width,"opacity":1},"points":[{"x":$x1,"y":50,"tMs":0},{"x":$x2,"y":50,"tMs":20}]}}""", 1)
    private fun page(elements: List<StoredElement>) =
        StoredPage(pageId, 0, 100.0, 100.0, """{"type":"blank","color":"#FFFFFF"}""", 1, elements)
    private fun render(elements: List<StoredElement>, originX: Double = 0.0, originY: Double = 0.0,
        scale: Double = 1.0, check: (Bitmap, Set<String>) -> Unit) {
        val source = page(elements)
        val plan = PageElementRenderPlan.build(source,
            PositionedPage(pageId, 100.0, 100.0, originX, originY), ViewportTransform(scale, 0.0, 0.0))
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            val unavailable = PageElementCanvasRenderer.draw(Canvas(bitmap), plan)
            check(bitmap, unavailable)
            assertEquals(elements, source.elements)
        } finally { bitmap.recycle() }
    }
    private fun painted(bitmap: Bitmap, x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y)) > 0

    @Test fun allFourOutlinesHaveExpectedPathsAndNoImplicitFill() {
        for (kind in CanonicalShapeKind.entries) {
            render(listOf(shape(kind, fill = if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
                CanonicalShapeFill("#0000FF", 1.0) else null))) { bitmap, unavailable ->
                assertTrue(unavailable.isEmpty())
                if (kind == CanonicalShapeKind.RECTANGLE || kind == CanonicalShapeKind.ELLIPSE) {
                    assertTrue(painted(bitmap, 20, 50))
                    assertFalse(painted(bitmap, 50, 50))
                } else {
                    assertTrue(painted(bitmap, 50, 50))
                    assertFalse(painted(bitmap, 50, 30))
                    if (kind == CanonicalShapeKind.ARROW) assertTrue(painted(bitmap, 65, 41))
                }
            }
        }
    }

    @Test fun rectangleAndEllipseFillUseTheirOwnOpacity() {
        for (kind in listOf(CanonicalShapeKind.RECTANGLE, CanonicalShapeKind.ELLIPSE)) {
            render(listOf(shape(kind, opacity = 0.0, fill = CanonicalShapeFill("#0000FF", 0.5)))) { bitmap, unavailable ->
                assertTrue(unavailable.isEmpty())
                val center = bitmap.getPixel(50, 50)
                assertTrue(Color.alpha(center) in 127..128)
                assertEquals(255, Color.blue(center))
                assertEquals(0, Color.red(center))
                assertFalse(painted(bitmap, 10, 10))
            }
        }
    }

    @Test fun zeroOpacityPaintLeavesCanvasUnchanged() {
        for (kind in CanonicalShapeKind.entries) {
            render(listOf(shape(kind, opacity = 0.0, fill = CanonicalShapeFill("#0000FF", 0.0)))) { bitmap, unavailable ->
                assertTrue(unavailable.isEmpty())
                assertFalse(painted(bitmap, 50, 50))
                assertFalse(painted(bitmap, 20, 50))
            }
        }
    }

    @Test fun allFourShapesAreClippedToOwningPageAtZoom() {
        for (kind in CanonicalShapeKind.entries) {
            val g = if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
                CanonicalShapeGeometry.Endpoints(-10.0, 50.0, 110.0, 50.0)
            else CanonicalShapeGeometry.Frame(-10.0, 30.0, 120.0, 40.0)
            render(listOf(shape(kind, g, fill = CanonicalShapeFill("#00FF00", 1.0))),
                originX = 20.0, originY = 30.0, scale = 2.0) { bitmap, unavailable ->
                assertTrue(unavailable.isEmpty())
                assertFalse(painted(bitmap, 30, 160))
                assertTrue(painted(bitmap, 50, 160))
                assertTrue(painted(bitmap, 230, 160))
                assertFalse(painted(bitmap, 250, 160))
            }
        }
    }

    @Test fun nonuniformAffineTransformsTheOutlineFootprint() {
        render(listOf(shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(20.0, 40.0, 40.0, 40.0),
            transform = "[2,0,0,0.5,0,0]", width = 10.0))) { bitmap, unavailable ->
            assertTrue(unavailable.isEmpty())
            assertTrue(painted(bitmap, 60, 20))
            assertTrue(painted(bitmap, 35, 20)) // transformed round cap
            assertFalse(painted(bitmap, 60, 26))
        }
    }

    @Test fun rotationReflectionAndShearPreserveLocalSource() {
        val cases = listOf(
            Triple("[0,1,-1,0,100,0]", 70 to 50, 30 to 50),
            Triple("[-1,0,0,1,100,0]", 80 to 30, 20 to 30),
            Triple("[1,0,1,1,0,0]", 50 to 30, 20 to 30),
        )
        for ((transform, inside, outside) in cases) {
            render(listOf(shape(CanonicalShapeKind.LINE,
                CanonicalShapeGeometry.Endpoints(10.0, 30.0, 30.0, 30.0),
                transform = transform))) { bitmap, unavailable ->
                assertTrue(unavailable.isEmpty())
                // The rotated segment is at x=70, y=10..30.
                val hit = if (transform.startsWith("[0,")) 70 to 20 else inside
                assertTrue(painted(bitmap, hit.first, hit.second))
                assertFalse(painted(bitmap, outside.first, outside.second))
            }
        }
    }

    @Test fun strokeShapeStrokeInterleavingUsesCanonicalOrder() {
        val elements = listOf(
            stroke("top", "#00FF00", 2, 40, 60, 8),
            shape(CanonicalShapeKind.RECTANGLE, CanonicalShapeGeometry.Frame(30.0, 30.0, 40.0, 40.0),
                opacity = 0.0, fill = CanonicalShapeFill("#0000FF", 1.0)),
            stroke("bottom", "#FF0000", 0, width = 20),
        )
        render(elements) { bitmap, unavailable ->
            assertTrue(unavailable.isEmpty())
            assertEquals(Color.RED, bitmap.getPixel(25, 50))
            assertEquals(Color.BLUE, bitmap.getPixel(35, 50))
            assertEquals(Color.GREEN, bitmap.getPixel(50, 50))
            assertEquals(Color.BLUE, bitmap.getPixel(65, 50))
            assertEquals(Color.RED, bitmap.getPixel(75, 50))
        }
    }

    @Test fun transparentShapeCompositesOverInkInOrder() {
        render(listOf(stroke("bottom", "#FF0000", 0),
            shape(CanonicalShapeKind.RECTANGLE, opacity = 0.0,
                fill = CanonicalShapeFill("#0000FF", 0.5)))) { bitmap, unavailable ->
            assertTrue(unavailable.isEmpty())
            val center = bitmap.getPixel(50, 50)
            assertEquals(255, Color.alpha(center))
            assertTrue(Color.red(center) in 120..135)
            assertTrue(Color.blue(center) in 120..135)
        }
    }

    @Test fun transparentArrowTipIsPaintedOnceAndZeroLengthArrowHasNoHead() {
        render(listOf(shape(CanonicalShapeKind.ARROW, opacity = 0.5, width = 10.0))) { bitmap, unavailable ->
            assertTrue(unavailable.isEmpty())
            assertTrue(Color.alpha(bitmap.getPixel(80, 50)) in 120..135)
        }
        render(listOf(shape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(50.0, 50.0, 50.0, 50.0), width = 10.0))) { bitmap, unavailable ->
            assertTrue(unavailable.isEmpty())
            assertTrue(painted(bitmap, 50, 50))
            assertFalse(painted(bitmap, 40, 50))
        }
    }

    @Test fun invalidOrUnrepresentableShapeDoesNotHideFollowingInk() {
        for (bad in listOf(shape(CanonicalShapeKind.RECTANGLE, width = 1e40),
            shape(CanonicalShapeKind.RECTANGLE).copy(envelopeJson = "{}"))) {
            render(listOf(bad, stroke("following", "#00FF00", 2))) { bitmap, unavailable ->
                assertEquals(setOf(shapeId), unavailable)
                assertEquals(Color.GREEN, bitmap.getPixel(50, 50))
            }
        }
    }

    @Test fun distinctEndpointsCollapsedByFloatConversionAreReportedWithoutChangingSource() {
        for (kind in listOf(CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW)) {
            // In double precision this is a visible 50-PX segment from (20,25) to (70,25).
            val row = shape(kind,
                CanonicalShapeGeometry.Endpoints(16777216.0, 0.5, 16777217.0, 0.5),
                transform = "[50,0,0,50,-838860780,0]", width = 0.06)
            val original = row.envelopeJson
            render(listOf(row, stroke("following", "#00FF00", 2))) { bitmap, unavailable ->
                assertEquals(setOf(shapeId), unavailable)
                assertFalse(painted(bitmap, 30, 25))
                assertEquals(Color.GREEN, bitmap.getPixel(50, 50))
            }
            assertEquals(original, row.envelopeJson)
            val command = PageElementRenderPlan.build(page(listOf(row)),
                PositionedPage(pageId, 100.0, 100.0, 0.0, 0.0),
                ViewportTransform(1.0, 0.0, 0.0)).commands.single() as
                com.notebook.app.ui.editor.rendering.PageElementCommand.Shape
            assertThrows(IllegalArgumentException::class.java) {
                CanonicalShapeCanvasRenderer.CommandRenderer().validate(kind, command.geometry,
                    command.shape.stroke, command.shape.arrowHead, command.localToViewport)
            }
        }
    }

    @Test fun rendererRestoresCallerClipAndMatrix() {
        val source = page(listOf(shape(CanonicalShapeKind.RECTANGLE, transform = "[2,0,0,2,0,0]")))
        val plan = PageElementRenderPlan.build(source,
            PositionedPage(pageId, 100.0, 100.0, 20.0, 30.0), ViewportTransform(1.0, 0.0, 0.0))
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val count = canvas.saveCount
            PageElementCanvasRenderer.draw(canvas, plan)
            assertEquals(count, canvas.saveCount)
            canvas.drawColor(Color.BLUE)
            assertEquals(Color.BLUE, bitmap.getPixel(1, 1))
        } finally { bitmap.recycle() }
    }
}
