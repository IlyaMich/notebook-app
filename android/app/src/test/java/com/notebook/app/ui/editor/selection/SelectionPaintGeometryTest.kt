package com.notebook.app.ui.editor.selection

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.StoredCanonicalStrokeDecoder
import com.notebook.app.ui.editor.shapes.*
import com.notebook.app.ui.editor.viewport.DuPoint
import kotlinx.serialization.json.*
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class SelectionPaintGeometryTest {
    private val page = "00000000-0000-4000-8000-000000000001"
    private val id = "00000000-0000-4000-8000-000000000002"
    private fun row(kind: String, payload: String, affine: String = "[1,0,0,1,0,0]") =
        StoredElement(id, 3, kind,
            " \n" + """{"id":"$id","type":"$kind","locked":true,"transform":$affine,"payload":$payload}""" + "\n", 7)
    private fun ink(points: String = """{"x":10,"y":20,"tMs":0,"pressure":0.2}""",
        affine: String = "[1,0,0,1,0,0]", opacity: Double = 1.0, width: Double = 4.0) =
        row("stroke", """{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":$width,"color":"#abcdef","opacity":$opacity},"points":[$points]}""", affine)
    private fun shape(kind: CanonicalShapeKind, fill: CanonicalShapeFill? = null,
        opacity: Double = 1.0, geometry: CanonicalShapeGeometry =
            if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
                CanonicalShapeGeometry.Endpoints(10.0, 20.0, 30.0, 20.0)
            else CanonicalShapeGeometry.Frame(10.0, 20.0, 40.0, 20.0),
        affine: String = "[1,0,0,1,0,0]"): StoredElement {
        val raw = CanonicalShapeAdapter.create(id, kind, geometry, CanonicalShapeStroke("#123456", 4.0, opacity),
            fill, CanonicalShapeArrowHead(12.0, 30.0).takeIf { kind == CanonicalShapeKind.ARROW })
        val o = Json.parseToJsonElement(raw).jsonObject
        return StoredElement(id, 3, "shape", JsonObject(o + ("transform" to Json.parseToJsonElement(affine))).toString(), 7)
    }
    private fun painted(row: StoredElement) = SelectionPaintGeometry.derive(page, row) as SelectionPaintGeometry.Result.Painted
    private fun bounds(p: SelectionPaintGeometry.Result.Painted, l: Double, t: Double, r: Double, b: Double) {
        assertEquals(l, p.fullBounds.left, 1e-10); assertEquals(t, p.fullBounds.top, 1e-10)
        assertEquals(r, p.fullBounds.right, 1e-10); assertEquals(b, p.fullBounds.bottom, 1e-10)
        assertTrue(p.fullBounds.left <= l && p.fullBounds.right >= r)
    }
    @Test fun onePointDiskPreservesCanonicalSource() {
        val source = ink()
        val p = painted(source)
        assertEquals(listOf(SelectionPaintGeometry.Primitive.Capsule(DuPoint(10.0, 20.0), DuPoint(10.0, 20.0), 2.0)), p.primitives)
        bounds(p, 8.0, 18.0, 12.0, 22.0)
        assertSame(source, p.source); assertTrue(p.locked)
        assertEquals(page, p.pageId); assertEquals(3L, p.source.orderIndex)
        assertTrue(p.requiresRenderabilityCheck)
    }
    @Test fun polylineIsUnionOfRoundCapsulesNotPressureWidth() {
        val p = painted(ink("""{"x":10,"y":20,"tMs":0,"pressure":0.1},{"x":30,"y":20,"tMs":5,"pressure":1},{"x":30,"y":40,"tMs":5}"""))
        assertEquals(2, p.primitives.size)
        assertTrue(p.primitives.all { (it as SelectionPaintGeometry.Primitive.Capsule).radius == 2.0 })
        bounds(p, 8.0, 18.0, 32.0, 42.0)
    }
    @Test fun affineTransformsCompleteBrush() {
        bounds(painted(ink(affine = "[2,0,0,0.5,0,0]")), 16.0, 9.0, 24.0, 11.0)
        bounds(painted(ink(affine = "[0,1,-1,0,100,0]")), 78.0, 8.0, 82.0, 12.0)
        bounds(painted(ink(affine = "[-1,0,0,1,100,0]")), 88.0, 18.0, 92.0, 22.0)
        val pad = 2 * hypot(1.0, 0.5)
        bounds(painted(ink(affine = "[1,0,0.5,1,0,0]")), 20-pad, 18.0, 20+pad, 22.0)
    }
    @Test fun lineFillDoesNotCreateInteriorAndArrowIncludesFullHead() {
        val fill = CanonicalShapeFill("#ffffff", 1.0)
        assertEquals(1, painted(shape(CanonicalShapeKind.LINE, fill)).primitives.size)
        val arrow = painted(shape(CanonicalShapeKind.ARROW))
        assertEquals(3, arrow.primitives.size)
        bounds(arrow, 8.0, 12.0, 32.0, 28.0)
        val leg = arrow.primitives[1] as SelectionPaintGeometry.Primitive.Capsule
        assertEquals(30 - 12 * kotlin.math.cos(Math.PI / 6), leg.start.x, 1e-12)
        assertEquals(14.0, leg.start.y, 1e-12)
    }
    @Test fun zeroLengthLineAndArrowAreDotsWithoutHead() {
        for (kind in listOf(CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW)) {
            val p = painted(shape(kind, geometry = CanonicalShapeGeometry.Endpoints(10.0, 20.0, 10.0, 20.0)))
            assertEquals(1, p.primitives.size); bounds(p, 8.0, 18.0, 12.0, 22.0)
        }
    }
    @Test fun rectangleOutlineAndFillRemainDistinct() {
        assertTrue(painted(shape(CanonicalShapeKind.RECTANGLE)).primitives.single() is SelectionPaintGeometry.Primitive.FrameOutline)
        val p = painted(shape(CanonicalShapeKind.RECTANGLE, CanonicalShapeFill("#ffffff", 1.0)))
        assertEquals(2, p.primitives.size)
        assertTrue(p.primitives[0] is SelectionPaintGeometry.Primitive.FilledFrame)
        bounds(p, 8.0, 18.0, 52.0, 42.0)
    }
    @Test fun ellipseRetainsExactCurveAndAnalyticAffineSupport() {
        val p = painted(shape(CanonicalShapeKind.ELLIPSE, affine = "[2,1,-1,3,100,100]"))
        val ellipse = p.primitives.single() as SelectionPaintGeometry.Primitive.Ellipse
        assertFalse(ellipse.filled); assertEquals(2.0, ellipse.outlineRadius, 0.0)
        val dx = hypot(40.0, 10.0) + 2 * hypot(2.0, -1.0)
        val dy = hypot(20.0, 30.0) + 2 * hypot(1.0, 3.0)
        bounds(p, 130-dx, 220-dy, 130+dx, 220+dy)
        val filled = painted(shape(CanonicalShapeKind.ELLIPSE, CanonicalShapeFill("#ffffff", 1.0), opacity = 0.0))
        assertTrue((filled.primitives.single() as SelectionPaintGeometry.Primitive.Ellipse).filled)
        bounds(filled, 10.0, 20.0, 50.0, 40.0)
    }
    @Test fun effectiveAlphaAndOpenFillMatchRenderer() {
        for (opacity in listOf(0.0, 0.001)) {
            assertTrue(SelectionPaintGeometry.derive(page, ink(opacity = opacity)) is SelectionPaintGeometry.Result.NoPaint)
            assertTrue(SelectionPaintGeometry.derive(page, shape(CanonicalShapeKind.LINE,
                CanonicalShapeFill("#ffffff", 1.0), opacity)) is SelectionPaintGeometry.Result.NoPaint)
        }
        assertTrue(SelectionPaintGeometry.derive(page, ink(opacity = 0.002)) is SelectionPaintGeometry.Result.Painted)
    }
    private fun frame(kind: String, opacity: Double = 0.5): StoredElement {
        val f = """{"x":10,"y":20,"width":40,"height":20}"""
        val payload = if (kind == "image") """{"assetId":"$page","frame":$f,"opacity":$opacity,"crop":{"x":0,"y":0,"width":0.5,"height":1}}"""
        else """{"text":"שלום","frame":$f,"style":{"fontFamily":"sans","fontSize":20,"color":"#123456","bold":false,"italic":false,"underline":false,"alignment":"left"}}"""
        return row(kind, payload, "[1,0,0.5,2,0,0]")
    }
    @Test fun transformedTextAndImageFramesKeepPayloadAndAvailabilitySeparate() {
        for (kind in listOf("text", "image")) {
            val source = frame(kind); val p = painted(source)
            bounds(p, 20.0, 40.0, 70.0, 80.0)
            assertEquals(source.envelopeJson, p.frameElement!!.source.envelopeJson)
            if (kind == "image") {
                val image = p.frameElement as StoredFrameElement.Image
                assertEquals(ImageAvailability.UNVERIFIED, image.availability)
                assertNotNull(image.crop)
            }
        }
        assertTrue(SelectionPaintGeometry.derive(page, frame("image", 0.0)) is SelectionPaintGeometry.Result.NoPaint)
    }
    @Test fun clippingCandidateNeverReplacesFullBounds() {
        val p = painted(ink(affine = "[1,0,0,1,-10,0]"))
        bounds(p, -2.0, 18.0, 2.0, 22.0)
        assertEquals(0.0, p.fullBounds.candidateWithinPage(100.0, 100.0)!!.left, 0.0)
        assertNull(painted(ink(affine = "[1,0,0,1,-100,0]")).fullBounds.candidateWithinPage(100.0, 100.0))
        assertTrue(p.fullBounds.left < 0)
    }
    @Test fun unsupportedAndInvalidAreExplicit() {
        assertTrue(SelectionPaintGeometry.derive(page, ink().copy(kind = "future")) is SelectionPaintGeometry.Result.Unavailable)
        for (source in listOf(ink().copy(envelopeJson = "{}"), ink().copy(id = page),
            ink(affine = "[1,0,0,0,0,0]"), ink(affine = "[1e999,0,0,1,0,0]")))
            assertTrue(SelectionPaintGeometry.derive(page, source) is SelectionPaintGeometry.Result.Invalid)
    }
    @Test fun numericalOverflowAndTinyInverseFailWithoutRewriting() {
        for (source in listOf(ink(affine = "[1e308,0,0,1,0,0]"),
            ink(affine = "[1e-320,0,0,1,0,0]"), ink(width = Double.MIN_VALUE))) {
            val before = source.envelopeJson
            assertTrue(SelectionPaintGeometry.derive(page, source) is SelectionPaintGeometry.Result.NumericalFailure)
            assertEquals(before, source.envelopeJson)
        }
    }
    @Test fun nearSingularAndTinyValidPaintAreNotArbitrarilyDiscarded() {
        assertTrue(SelectionPaintGeometry.derive(page, ink(affine = "[1,0,1,1e-14,0,0]")) is SelectionPaintGeometry.Result.Painted)
        assertTrue(SelectionPaintGeometry.derive(page, ink(width = 1e-200)) is SelectionPaintGeometry.Result.Painted)
    }
    @Test fun primitiveWorkLimitIsExplicit() {
        assertTrue(SelectionPaintGeometry.derive(page, ink(), 0) is SelectionPaintGeometry.Result.WorkloadExceeded)
        assertTrue(SelectionPaintGeometry.derive(page, shape(CanonicalShapeKind.ARROW), 2) is SelectionPaintGeometry.Result.WorkloadExceeded)
    }
    @Test fun singleRowDecoderMatchesPageDecoderWithoutSourceChanges() {
        val source = ink()
        val before = source.envelopeJson
        val individual = StoredCanonicalStrokeDecoder.decode(page, source)
        val stored = StoredPage(page, 0, 100.0, 100.0, "blank", 0, listOf(source))
        val batch = StoredCanonicalStrokeDecoder.fromPage(stored).single()
        assertEquals(batch.copy(elementTransform = individual.elementTransform), individual)
        assertEquals(batch.elementTransform.coefficients(), individual.elementTransform.coefficients())
        painted(source)
        assertEquals(before, source.envelopeJson)
        assertEquals(0.2, individual.samples.single().pressure!!, 0.0)
        assertEquals(0L, individual.samples.single().tMs)
    }
}
