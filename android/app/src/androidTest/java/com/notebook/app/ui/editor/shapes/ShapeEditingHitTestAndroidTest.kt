package com.notebook.app.ui.editor.shapes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.rendering.PageElementCommand
import com.notebook.app.ui.editor.rendering.PageElementRenderPlan
import com.notebook.app.ui.editor.viewport.*
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeEditingHitTestAndroidTest {
    private val pageId = UUID.randomUUID().toString()
    private val picker = ShapeEditingHitTest()
    private fun shape(
        kind: CanonicalShapeKind = CanonicalShapeKind.RECTANGLE,
        geometry: CanonicalShapeGeometry = CanonicalShapeGeometry.Frame(20.0, 20.0, 100.0, 100.0),
        fill: CanonicalShapeFill? = null, locked: Boolean = false,
        transform: List<Double> = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        opacity: Double = 1.0, order: Long = 0,
    ): StoredElement {
        val id = UUID.randomUUID().toString()
        val raw = Json.parseToJsonElement(CanonicalShapeAdapter.create(id, kind, geometry,
            CanonicalShapeStroke("#2255AA", 2.0, opacity), fill,
            CanonicalShapeArrowHead(12.0, 30.0).takeIf { kind == CanonicalShapeKind.ARROW })).jsonObject
        val json = JsonObject(raw + mapOf("locked" to JsonPrimitive(locked),
            "transform" to JsonArray(transform.map(::JsonPrimitive)))).toString()
        return StoredElement(id, order, "shape", json, 0)
    }
    private fun plan(rows: List<StoredElement>, camera: ViewportTransform = ViewportTransform(1.0, 0.0, 0.0)) =
        PageElementRenderPlan.build(
            StoredPage(pageId, 0, 200.0, 200.0, """{"type":"blank","color":"#FFFFFF"}""", 0, rows),
            PositionedPage(pageId, 200.0, 200.0, 0.0, 0.0), camera,
        )
    private fun pick(rows: List<StoredElement>, x: Double, y: Double, selected: String? = null) =
        picker.pick(plan(rows), PxPoint(x, y), PxSize(220.0, 220.0), selected, 24.0, 8.0)

    @Test fun hollowInteriorsDoNotSelectButVisibleFillDoesForRectangleAndEllipse() {
        listOf(CanonicalShapeKind.RECTANGLE, CanonicalShapeKind.ELLIPSE).forEach { kind ->
            val hollow = shape(kind)
            assertNull(pick(listOf(hollow), 70.0, 70.0))
            val filled = shape(kind, fill = CanonicalShapeFill("#CC3333", 0.5))
            assertEquals(filled.id, pick(listOf(filled), 70.0, 70.0)!!.shape.source.id)
        }
    }

    @Test fun reversePaintOrderChoosesTopmostUnlockedShapeAndIgnoresOtherTypes() {
        val bottom = shape(fill = CanonicalShapeFill("#CC3333", 1.0))
        val locked = shape(fill = CanonicalShapeFill("#FFE45C", 1.0), locked = true, order = 1)
        val strokeId = UUID.randomUUID().toString()
        val stroke = StoredElement(strokeId, 2, "stroke",
            """{"id":"$strokeId","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":20,"color":"#222222","opacity":1},"points":[{"x":20,"y":70,"tMs":0},{"x":120,"y":70,"tMs":10}]}}""", 0)
        assertEquals(bottom.id, pick(listOf(bottom, locked, stroke), 70.0, 70.0)!!.shape.source.id)
        val top = shape(fill = CanonicalShapeFill("#CC3333", 1.0), order = 3)
        assertEquals(top.id, pick(listOf(bottom, locked, stroke, top), 70.0, 70.0)!!.shape.source.id)
    }

    @Test fun selectedHandlesPrecedeBodyAndTiesUseStableOrderAtInclusiveRadius() {
        val line = shape(CanonicalShapeKind.LINE, CanonicalShapeGeometry.Endpoints(50.0, 50.0, 50.0, 50.0))
        val top = shape(fill = CanonicalShapeFill("#CC3333", 1.0), order = 1)
        val hit = pick(listOf(line, top), 74.0, 50.0, line.id)!!
        assertEquals(line.id, hit.shape.source.id)
        assertEquals(0, hit.handle)
        assertEquals(top.id, pick(listOf(line, top), 74.001, 50.0, line.id)!!.shape.source.id)
    }

    @Test fun clippingPrecedesToleranceSoInvisibleOutOfPagePaintCannotBeSelected() {
        val outside = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(-3.0, 30.0, -3.0, 150.0))
        assertNull(pick(listOf(outside), 2.0, 70.0))
        val partial = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(-30.0, 70.0, 30.0, 70.0))
        assertNotNull(pick(listOf(partial), 1.0, 70.0))
        assertNull(pick(listOf(partial), -1.0, 70.0))
        assertNull(pick(listOf(partial), 201.0, 70.0))
    }

    @Test fun actualNonuniformOutlineAndPhysicalPixelToleranceDetermineHit() {
        val line = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(20.0, 30.0, 80.0, 30.0),
            transform = listOf(1.0, 0.0, 0.0, 4.0, 10.0, -80.0))
        assertNotNull(pick(listOf(line), 60.0, 51.0)) // 4 PX half-width + 8 PX tolerance
        assertNull(pick(listOf(line), 60.0, 53.0))
    }

    @Test fun arrowHeadAndZeroLengthDotAreTargetableWithoutInventingAnInterior() {
        val arrow = shape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(20.0, 50.0, 100.0, 50.0))
        assertNotNull(pick(listOf(arrow), 90.0, 56.0))
        val dot = shape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(50.0, 50.0, 50.0, 50.0))
        assertNotNull(pick(listOf(dot), 58.0, 50.0))
        assertNull(pick(listOf(dot), 70.0, 50.0))
    }

    @Test fun zeroEffectiveOpacityAndLineFillProduceNoInvisibleTargets() {
        val line = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(20.0, 50.0, 120.0, 50.0),
            fill = CanonicalShapeFill("#CC3333", 1.0), opacity = 0.001)
        assertNull(pick(listOf(line), 70.0, 50.0))
        val rect = shape(fill = CanonicalShapeFill("#CC3333", 0.001), opacity = 0.0)
        assertNull(pick(listOf(rect), 70.0, 70.0))
    }

    @Test fun unavailableGeometryAndAffineDoNotHideEditableNeighborsOrRewriteSource() {
        val visible = shape(fill = CanonicalShapeFill("#CC3333", 1.0))
        val hugeGeometry = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(1e100, 1e100, 2e100, 2e100), order = 1)
        val hugeAffine = shape(transform = listOf(1e100, 0.0, 0.0, 1e100, 0.0, 0.0), order = 2)
        val rows = listOf(visible, hugeGeometry, hugeAffine)
        val bytes = rows.map { it.envelopeJson }
        assertEquals(visible.id, pick(rows, 70.0, 70.0)!!.shape.source.id)
        assertEquals(bytes, rows.map { it.envelopeJson })
    }

    @Test fun collapsedEndpointShapeCannotHideAnEditableNeighbor() {
        val visible = shape(fill = CanonicalShapeFill("#CC3333", 1.0))
        for (kind in listOf(CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW)) {
            val collapsed = shape(kind,
                CanonicalShapeGeometry.Endpoints(16777216.0, 50.0, 16777217.0, 50.0),
                transform = listOf(50.0, 0.0, 0.0, 1.0, -838860780.0, 0.0), order = 1)
            val rows = listOf(visible, collapsed)
            val original = rows.map { it.envelopeJson }
            assertNull(pick(listOf(collapsed), 32.0, 50.0))
            assertEquals(visible.id, pick(rows, 32.0, 50.0)!!.shape.source.id)
            assertEquals(original, rows.map { it.envelopeJson })
        }
    }

    @Test fun hiddenHandleCentersHaveNoHitRegionAndZoomDoesNotScaleTargetTolerance() {
        val row = shape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(-2.0, 50.0, 100.0, 50.0))
        val p = plan(listOf(row))
        val command = p.commands.single() as PageElementCommand.Shape
        assertEquals(listOf(1), picker.handlePoints(command, p, PxSize(220.0, 220.0)).map { it.first })
        val zoomed = plan(listOf(row), ViewportTransform(2.0, 10.0, 10.0))
        assertNotNull(picker.pick(zoomed, PxPoint(60.0, 119.0), PxSize(220.0, 220.0), null, 24.0, 8.0))
        assertNull(picker.pick(zoomed, PxPoint(60.0, 121.0), PxSize(220.0, 220.0), null, 24.0, 8.0))
    }
}
