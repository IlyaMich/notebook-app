package com.notebook.app.ui.editor.rendering

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.shapes.*
import com.notebook.app.ui.editor.viewport.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PageElementRenderPlanTest {
    private val pageId = "7d6acdd3-504f-4bb3-9c70-3ba7af56b4ba"
    private val shapeId = "10000000-0000-4000-8000-000000000001"
    private fun shape(kind: CanonicalShapeKind = CanonicalShapeKind.RECTANGLE,
        order: Long = 1, transform: String = "[1,0,0,1,0,0]"): StoredElement {
        val geometry = if (kind == CanonicalShapeKind.LINE || kind == CanonicalShapeKind.ARROW)
            CanonicalShapeGeometry.Endpoints(10.0, 20.0, 40.0, 50.0)
        else CanonicalShapeGeometry.Frame(10.0, 20.0, 30.0, 40.0)
        val raw = CanonicalShapeAdapter.create(shapeId, kind, geometry,
            CanonicalShapeStroke("#FF0000", 3.0, 0.5),
            arrowHead = if (kind == CanonicalShapeKind.ARROW) CanonicalShapeArrowHead(12.0, 30.0) else null)
        val json = Json.parseToJsonElement(raw).jsonObject
        return StoredElement(shapeId, order, "shape",
            JsonObject(json + ("transform" to Json.parseToJsonElement(transform))).toString(), 1)
    }
    private fun stroke(id: String, order: Long) = StoredElement(id, order, "stroke",
        """{"id":"$id","type":"stroke","transform":[1,0,0,1,0,0],"locked":false,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","color":"#0000FF","width":3,"opacity":1},"points":[{"x":10,"y":20,"tMs":0}]}}""", 1)
    private fun page(elements: List<StoredElement>) =
        StoredPage(pageId, 0, 100.0, 100.0, """{"type":"blank","color":"#FFFFFF"}""", 1, elements)
    private fun position() = PositionedPage(pageId, 100.0, 100.0, 90.0, 816.0)
    private val camera = ViewportTransform(2.0, 30.0, -100.0)

    @Test fun mixedCommandsUsePersistedOrderAcrossKinds() {
        val source = page(listOf(stroke("top", 2), shape(), stroke("bottom", 0)))
        val plan = PageElementRenderPlan.build(source, position(), camera)
        assertEquals(listOf("bottom", shapeId, "top"), plan.commands.map { it.elementId })
        assertTrue(plan.commands[0] is PageElementCommand.Ink)
        assertTrue(plan.commands[1] is PageElementCommand.Shape)
        assertTrue(plan.unavailableElementIds.isEmpty())
        assertEquals(listOf("top", shapeId, "bottom"), source.elements.map { it.id })
    }

    @Test fun allFourKindsKeepCanonicalGeometryAndLockedState() {
        for (kind in CanonicalShapeKind.entries) {
            val row = shape(kind)
            val original = Json.parseToJsonElement(row.envelopeJson).jsonObject
            val locked = row.copy(envelopeJson = JsonObject(original + ("locked" to JsonPrimitive(true))).toString())
            val source = page(listOf(locked))
            val command = PageElementRenderPlan.build(source, position(), camera).commands.single() as PageElementCommand.Shape
            assertEquals(kind, command.shape.kind)
            assertTrue(command.shape.locked)
            assertEquals(locked.envelopeJson, command.shape.source.envelopeJson)
        }
    }

    @Test fun fullAffineAndMixedPageOriginUseSameCompositionAsInk() {
        val source = page(listOf(shape(transform = "[2,0.25,0.5,3,5,7]")))
        val plan = PageElementRenderPlan.build(source, position(), camera)
        assertEquals(210.0, plan.clipPx.left, 0.0)
        assertEquals(1532.0, plan.clipPx.top, 0.0)
        assertEquals(410.0, plan.clipPx.right, 0.0)
        assertEquals(1732.0, plan.clipPx.bottom, 0.0)
        val affine = (plan.commands.single() as PageElementCommand.Shape).localToViewport
        assertEquals(PxPoint(280.0, 1671.0), affine.mapPointOrNull(DuPoint(10.0, 20.0)))
        assertEquals(PxPoint(3.0, 18.0), affine.mapVectorOrNull(DuPoint(0.0, 3.0)))
    }

    @Test fun cameraChangesReuseDecodedSnapshotWithoutChangingSource() {
        val source = page(listOf(shape()))
        val decoded = PageElementRenderPlan.decode(source)
        val before = source.copy(elements = source.elements.toList())
        val first = PageElementRenderPlan.build(source, position(), camera, decoded)
        val second = PageElementRenderPlan.build(source, position(), ViewportTransform(1.0, 0.0, 0.0), decoded)
        assertNotEquals(first.clipPx, second.clipPx)
        assertSame((first.commands.single() as PageElementCommand.Shape).shape,
            (second.commands.single() as PageElementCommand.Shape).shape)
        assertEquals(before, source)
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.build(source.copy(revision = 2), position(), camera, decoded)
        }
    }

    @Test fun malformedOrUnprojectableShapeIsReportedWithoutHidingNeighboringInk() {
        for (bad in listOf(
            shape().copy(envelopeJson = "{}"),
            shape(transform = "[1e308,0,0,1,0,0]"),
        )) {
            val source = page(listOf(stroke("bottom", 0), bad, stroke("top", 2)))
            val plan = PageElementRenderPlan.build(source, position(), camera)
            assertEquals(setOf(shapeId), plan.unavailableElementIds)
            assertEquals(listOf("bottom", "top"), plan.commands.map { it.elementId })
            assertEquals(bad.envelopeJson, source.elements[1].envelopeJson)
        }
    }

    @Test fun invalidPageCameraOwnershipAndOrderingFailExplicitly() {
        val source = page(listOf(shape()))
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.build(source, position().copy(id = "other"), camera)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.build(source.copy(width = -1.0), position(), camera)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.build(source, position(), camera.copy(scalePxPerDu = 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.decode(page(listOf(shape(), shape())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.decode(page(listOf(stroke("a", 1), shape())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageElementRenderPlan.decode(page(listOf(shape().copy(kind = "unknown"))))
        }
    }

    @Test fun deferredTextAndImageDoNotChangeSupportedElementOrder() {
        val source = page(listOf(stroke("bottom", 0),
            StoredElement("text", 1, "text", "{}", 1), shape(order = 2),
            StoredElement("image", 3, "image", "{}", 1), stroke("top", 4)))
        assertEquals(listOf("bottom", shapeId, "top"),
            PageElementRenderPlan.build(source, position(), camera).commands.map { it.elementId })
    }
}
