package com.notebook.app.ui.editor.shapes

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.library.LibraryOperationException
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CanonicalShapeAdapterTest {
    private val fixture = Json.parseToJsonElement(File(
        requireNotNull(System.getProperty("notebookFixturesDir")),
        "shapes/semantic_shapes_v1.json",
    ).readText()).jsonObject
    private val pageId = fixture.getValue("id").jsonPrimitive.content
    private val rows = fixture.getValue("elements").jsonArray.mapIndexed { index, raw ->
        StoredElement(raw.jsonObject.getValue("id").jsonPrimitive.content, index.toLong(),
            "shape", raw.toString(), 7L)
    }
    private fun row(kind: String) = rows.single {
        json(it).getValue("payload").jsonObject.getValue("kind").jsonPrimitive.content == kind
    }
    private fun json(row: StoredElement) = Json.parseToJsonElement(row.envelopeJson).jsonObject
    private fun mutate(row: StoredElement, field: String, value: JsonElement): StoredElement =
        row.copy(envelopeJson = JsonObject(json(row) + (field to value)).toString())
    private fun payload(row: StoredElement, value: JsonObject) = mutate(row, "payload", value)
    private fun decode(row: StoredElement) = CanonicalShapeAdapter.decode(pageId, row)

    @Test fun sharedFixturePreservesAllKindsSourceMetadataPaintAndTransforms() {
        assertEquals(CanonicalShapeKind.entries.toSet(), rows.map { decode(it).kind }.toSet())
        rows.forEach { source ->
            val shape = decode(source)
            val raw = json(source)
            val p = raw.getValue("payload").jsonObject
            assertEquals(pageId, shape.pageId)
            assertSame(source, shape.source)
            assertEquals(raw.getValue("locked").jsonPrimitive.boolean, shape.locked)
            assertEquals(raw.getValue("transform").jsonArray.map { it.jsonPrimitive.double },
                shape.elementTransform.coefficients())
            assertEquals(p.getValue("stroke").jsonObject.getValue("color").jsonPrimitive.content,
                shape.stroke.colorHex)
            assertEquals(source.envelopeJson, shape.source.envelopeJson)
        }
        assertNotNull(decode(row("line")).fill) // valid even though it has no closed interior
        assertNull(decode(row("ellipse")).fill)
        assertTrue(decode(row("ellipse")).locked)
        assertEquals(-390.0, decode(row("arrow")).arrowHead!!.angleDeg, 0.0)
    }

    @Test fun noOpReturnsExactSourceIncludingWhitespaceAndNumberSpelling() {
        val source = row("rectangle").let {
            it.copy(envelopeJson = "\n  " + it.envelopeJson.replace("\"width\":3,", "\"width\":3e0,") + "\n")
        }
        assertEquals(source.envelopeJson, CanonicalShapeAdapter.replace(decode(source)))
    }

    @Test fun geometryReplacementChangesOnlyGeometryAndLeavesOriginalAvailable() {
        rows.filterNot { decode(it).locked }.forEach { source ->
            val shape = decode(source)
            val replacement = when (val g = shape.geometry) {
                is CanonicalShapeGeometry.Endpoints -> g.copy(x2 = g.x2 + 0.125)
                is CanonicalShapeGeometry.Frame -> g.copy(width = g.width + 0.125)
            }
            val updated = CanonicalShapeAdapter.replace(shape, geometry = replacement)
            val next = decode(source.copy(envelopeJson = updated))
            assertEquals(replacement, next.geometry)
            val before = json(source)
            val after = Json.parseToJsonElement(updated).jsonObject
            assertEquals(before - "payload", after - "payload")
            assertEquals(before.getValue("payload").jsonObject - "geometry",
                after.getValue("payload").jsonObject - "geometry")
            assertSame(source, shape.source)
            assertEquals(source.orderIndex, next.source.orderIndex)
            assertEquals(source.revision, next.source.revision)
        }
    }

    @Test fun paintAndHeadReplacementPreservesGeometryTransformAndOpacityNotBeingEdited() {
        val source = row("arrow")
        val shape = decode(source)
        val fill = CanonicalShapeFill("#aAbBcC", 0.25)
        val changed = CanonicalShapeAdapter.replace(shape,
            stroke = shape.stroke.copy(colorHex = "#abcdef", widthDu = 4.25),
            fill = fill, arrowHead = CanonicalShapeArrowHead(14.5, 1e300))
        val next = decode(source.copy(envelopeJson = changed))
        assertEquals(shape.geometry, next.geometry)
        assertEquals(shape.elementTransform.coefficients(), next.elementTransform.coefficients())
        assertEquals(shape.stroke.opacity, next.stroke.opacity, 0.0)
        assertEquals(fill, next.fill)
        assertEquals(1e300, next.arrowHead!!.angleDeg, 0.0)
        assertNull(decode(source.copy(envelopeJson =
            CanonicalShapeAdapter.replace(next, fill = null))).fill)
    }

    @Test fun creationUsesExistingEnvelopeWithFreshCallerIdAndIdentityAffine() {
        rows.forEach { source ->
            val shape = decode(source)
            val raw = CanonicalShapeAdapter.create(source.id, shape.kind, shape.geometry,
                shape.stroke, shape.fill, shape.arrowHead)
            val next = decode(source.copy(envelopeJson = raw))
            assertFalse(next.locked)
            assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), next.elementTransform.coefficients())
            assertEquals(shape.geometry, next.geometry)
            assertEquals(shape.stroke, next.stroke)
            assertEquals(shape.fill, next.fill)
            assertEquals(shape.arrowHead, next.arrowHead)
        }
    }

    @Test fun lockedShapeIsReadableButCannotBeReplaced() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.replace(decode(row("ellipse")),
                fill = CanonicalShapeFill("#123456", 1.0))
        }
    }

    @Test fun identityKindOwnershipMetadataAndAffineMismatchesAreRejected() {
        val source = row("line")
        val invalid = listOf(
            source.copy(kind = "stroke"),
            source.copy(id = row("arrow").id),
            source.copy(orderIndex = -1),
            source.copy(revision = -1),
            mutate(source, "type", JsonPrimitive("text")),
            mutate(source, "transform", JsonArray(listOf(1, 0, 0, 0, 0, 0).map(::JsonPrimitive))),
            mutate(source, "transform", JsonArray(listOf(1, 0).map(::JsonPrimitive))),
            mutate(source, "locked", JsonPrimitive("false")),
            mutate(source, "locked", JsonPrimitive(0)),
            mutate(source, "extra", JsonPrimitive(1)),
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { decode(candidate) }
        }
        assertThrows(LibraryOperationException::class.java) {
            CanonicalShapeAdapter.decode("not-a-page-id", source)
        }
        assertThrows(LibraryOperationException::class.java) { decode(source.copy(id = "not-a-uuid")) }
    }

    @Test fun missingUnknownAndWrongTypedPayloadFieldsAreRejected() {
        val source = row("arrow")
        val p = json(source).getValue("payload").jsonObject
        val geometry = p.getValue("geometry").jsonObject
        val invalid = listOf(
            JsonObject(p - "fill"), JsonObject(p - "arrowHead"),
            JsonObject(p + ("kind" to JsonPrimitive("triangle"))),
            JsonObject(p + ("geometry" to JsonObject(geometry + ("x1" to JsonPrimitive("10"))))),
            JsonObject(p + ("geometry" to JsonObject(geometry + ("width" to JsonPrimitive(3))))),
            JsonObject(p + ("geometry" to JsonObject(geometry - "y1"))),
            JsonObject(p + ("fill" to JsonPrimitive(false))),
            JsonObject(p + ("arrowHead" to JsonObject(mapOf(
                "length" to JsonPrimitive(0), "angleDeg" to JsonPrimitive(30))))),
            JsonObject(p + ("stroke" to JsonObject(mapOf(
                "color" to JsonPrimitive("#123456"), "width" to JsonPrimitive(2),
                "opacity" to JsonPrimitive(1.1))))),
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { decode(payload(source, candidate)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(source.copy(envelopeJson = source.envelopeJson.replace("\"x1\":10", "\"x1\":1e999")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(source.copy(envelopeJson = "not JSON"))
        }
    }

    @Test fun geometryAndArrowHeadMustMatchKindOnCreationAndReplacement() {
        val shape = decode(row("line"))
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.create(shape.source.id, CanonicalShapeKind.RECTANGLE,
                shape.geometry, shape.stroke)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.create(shape.source.id, CanonicalShapeKind.ARROW,
                shape.geometry, shape.stroke)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.replace(shape, arrowHead = CanonicalShapeArrowHead(12.0, 30.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.replace(shape, geometry = CanonicalShapeGeometry.Frame(0.0, 0.0, 1.0, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeAdapter.replace(decode(row("arrow")), arrowHead = null)
        }
    }

    @Test fun degenerateEndpointsAndFiniteUnrestrictedAnglesRemainValid() {
        val shape = decode(row("arrow"))
        val raw = CanonicalShapeAdapter.replace(shape,
            geometry = CanonicalShapeGeometry.Endpoints(-1.25, 2.5, -1.25, 2.5),
            arrowHead = CanonicalShapeArrowHead(Double.MIN_VALUE, Double.MAX_VALUE))
        val next = decode(shape.source.copy(envelopeJson = raw))
        assertEquals(Double.MAX_VALUE, next.arrowHead!!.angleDeg, 0.0)
        assertEquals(Double.MIN_VALUE, next.arrowHead.lengthDu, 0.0)
    }

    @Test fun invalidPaintAndArrowParametersFailBeforeEncoding() {
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeStroke("#123456", value, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeArrowHead(value, 30.0) }
        }
        for (value in listOf(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeStroke("#123456", 1.0, value) }
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeFill("#123456", value) }
        }
        for (color in listOf("red", "#123", "#12345678", "#GG0000")) {
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeStroke(color, 1.0, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { CanonicalShapeFill(color, 1.0) }
        }
        assertThrows(IllegalArgumentException::class.java) { CanonicalShapeArrowHead(12.0, Double.NaN) }
    }
}
