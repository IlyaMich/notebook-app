package com.notebook.app.data.portable

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * PDF-01 C3: Kotlin/JVM consumer of shared page-tree metadata and annotation goldens.
 *
 * This models resolved dictionary values; it does NOT parse PDF bytes or use a
 * production native renderer/adapter. No Apple runtime evidence is implied.
 */
class PdfMetadataGeometryConformanceTest {

    private val catalog: JsonObject by lazy {
        val root = requireNotNull(System.getProperty("notebookFixturesDir")) {
            "Gradle must set notebookFixturesDir"
        }
        val fixture = File(root, "pdf/metadata_cases_v1.json")
        require(fixture.isFile) { "Missing PDF metadata fixture: $fixture" }
        Json.parseToJsonElement(fixture.readText(Charsets.UTF_8)).jsonObject
    }

    private fun cases(key: String): List<JsonObject> =
        catalog.getValue(key).jsonArray.map { it.jsonObject }

    private data class Geometry(
        val media: List<Double>,
        val crop: List<Double>,
        val rotation: Int,
        val userUnit: Double,
        val pageSize: List<Double>,
    )

    private fun numeric(value: JsonElement): Double {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Expected a number")
        require(!primitive.isString && primitive.booleanOrNull == null) {
            "Expected numeric JSON value"
        }
        val number = primitive.doubleOrNull
            ?: throw IllegalArgumentException("Invalid numeric JSON value")
        require(number.isFinite()) { "Nonfinite number" }
        return number
    }

    private fun numbers(value: JsonElement, count: Int): List<Double> {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Expected numeric array")
        require(array.size == count) { "Expected $count numbers" }
        return array.map(::numeric)
    }

    private fun box(value: JsonElement): List<Double> {
        val result = numbers(value, 4)
        val width = result[2] - result[0]
        val height = result[3] - result[1]
        require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) {
            "Invalid PDF page box"
        }
        return result
    }

    /** A node list runs from the root Pages dictionary to the leaf Page dictionary. */
    private fun inherited(nodes: List<JsonObject>, name: String): JsonElement? {
        for (node in nodes.asReversed()) {
            if (name in node) return node.getValue(name)
        }
        return null
    }

    private fun resolve(case: JsonObject): Geometry {
        val nodes = case.getValue("nodes").jsonArray.map {
            it as? JsonObject ?: throw IllegalArgumentException("Expected PDF dictionary")
        }
        require(nodes.size >= 2) { "Expected root and leaf PDF dictionaries" }

        val mediaValue = inherited(nodes, "mediaBox")
            ?: throw IllegalArgumentException("Missing inherited MediaBox")
        val media = box(mediaValue)
        val crop = inherited(nodes, "cropBox")?.let(::box) ?: media

        val rotationValue = inherited(nodes, "rotate")
        val rawRotation = if (rotationValue == null) 0 else {
            val primitive = rotationValue as? JsonPrimitive
                ?: throw IllegalArgumentException("Invalid PDF Rotate")
            require(!primitive.isString && primitive.booleanOrNull == null) {
                "Rotate must be an integer"
            }
            primitive.intOrNull ?: throw IllegalArgumentException("Rotate must be an integer")
        }
        require(rawRotation % 90 == 0) { "Rotate must be a multiple of 90" }
        val rotation = ((rawRotation % 360) + 360) % 360

        // /UserUnit belongs to the leaf page; ignore any ancestor UserUnit.
        val unit = nodes.last()["userUnit"]?.let(::numeric) ?: 1.0
        require(unit > 0.0) { "UserUnit must be positive" }

        val cropX0 = maxOf(media[0], crop[0])
        val cropY0 = maxOf(media[1], crop[1])
        val cropX1 = minOf(media[2], crop[2])
        val cropY1 = minOf(media[3], crop[3])
        val w = cropX1 - cropX0
        val h = cropY1 - cropY0
        require(w.isFinite() && h.isFinite() && w > 0.0 && h > 0.0) {
            "Empty or overflowing effective crop"
        }
        val effective = listOf(cropX0, cropY0, cropX1, cropY1)
        val dimensions = if (rotation == 90 || rotation == 270) listOf(h, w)
                         else listOf(w, h)
        val size = dimensions.map { it * unit }
        require(size.all { it.isFinite() && it > 0.0 }) { "Invalid displayed page size" }

        // Check affine coefficients and numerical invertibility, even though the
        // point conversion below uses explicit rotation equations independently.
        val affine = when (rotation) {
            0 -> listOf(unit, 0.0, 0.0, -unit, -unit * cropX0, unit * cropY1)
            90 -> listOf(0.0, unit, unit, 0.0, -unit * cropY0, -unit * cropX0)
            180 -> listOf(-unit, 0.0, 0.0, unit, unit * cropX1, -unit * cropY0)
            270 -> listOf(0.0, -unit, -unit, 0.0, unit * cropY1, unit * cropX1)
            else -> throw IllegalArgumentException("Invalid normalized rotation")
        }
        require(affine.all { it.isFinite() }) { "Nonfinite PDF affine" }
        val determinant = affine[0] * affine[3] - affine[1] * affine[2]
        require(determinant.isFinite() && determinant != 0.0) {
            "Noninvertible PDF affine"
        }
        return Geometry(media, effective, rotation, unit, size)
    }

    private fun sourceToPage(g: Geometry, source: List<Double>): List<Double> {
        val x = source[0] - g.crop[0]
        val y = source[1] - g.crop[1]
        val w = g.crop[2] - g.crop[0]
        val h = g.crop[3] - g.crop[1]
        val mapped = when (g.rotation) {
            0 -> listOf(x * g.userUnit, (h - y) * g.userUnit)
            90 -> listOf(y * g.userUnit, x * g.userUnit)
            180 -> listOf((w - x) * g.userUnit, y * g.userUnit)
            270 -> listOf((h - y) * g.userUnit, (w - x) * g.userUnit)
            else -> throw IllegalArgumentException("Unexpected rotation")
        }
        require(mapped.all { it.isFinite() }) { "Nonfinite mapped point" }
        return mapped
    }

    private fun pageToSource(g: Geometry, point: List<Double>): List<Double> {
        val x = point[0] / g.userUnit
        val y = point[1] / g.userUnit
        val source = when (g.rotation) {
            0 -> listOf(g.crop[0] + x, g.crop[3] - y)
            90 -> listOf(g.crop[0] + y, g.crop[1] + x)
            180 -> listOf(g.crop[2] - x, g.crop[1] + y)
            270 -> listOf(g.crop[2] - y, g.crop[3] - x)
            else -> throw IllegalArgumentException("Unexpected rotation")
        }
        require(source.all { it.isFinite() }) { "Nonfinite inverse point" }
        return source
    }

    private fun assertClose(label: String, expected: List<Double>, actual: List<Double>) {
        assertEquals("$label length", expected.size, actual.size)
        for (index in expected.indices) {
            val tolerance = max(1e-9, 1e-12 * abs(expected[index]))
            assertTrue(
                "$label [$index]: expected ${expected[index]}, actual ${actual[index]}",
                actual[index].isFinite() && abs(actual[index] - expected[index]) <= tolerance,
            )
        }
    }

    @Test
    fun catalogCoversInheritanceAndQuarterTurnVariants() {
        assertEquals("pdf-page-metadata-v1", catalog.getValue("contract").jsonPrimitive.content)
        val valid = cases("validCases")
        val invalid = cases("invalidCases")
        assertTrue(valid.size >= 7)
        assertTrue(invalid.size >= 12)
        val ids = (valid + invalid).map { it.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf(0, 90, 180, 270), valid.map {
            it.getValue("expected").jsonObject.getValue("rotation").jsonPrimitive.int
        }.toSet())
        assertTrue(valid.any { case ->
            val nodes = case.getValue("nodes").jsonArray.map { it.jsonObject }
            nodes.dropLast(1).any { "userUnit" in it } && "userUnit" !in nodes.last()
        })
        assertTrue(valid.all { "rectangle" in it.getValue("expected").jsonObject })
    }

    @Test
    fun inheritedEffectiveMetadataMatchesGoldenSizesAndRotations() {
        cases("validCases").forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val g = resolve(case)
            val expected = case.getValue("expected").jsonObject
            assertClose("$id media", numbers(expected.getValue("mediaBox"), 4), g.media)
            assertClose("$id crop", numbers(expected.getValue("cropBox"), 4), g.crop)
            assertClose("$id size", numbers(expected.getValue("pageSize"), 2), g.pageSize)
            assertEquals("$id rotation", expected.getValue("rotation").jsonPrimitive.int, g.rotation)
            assertEquals("$id UserUnit", numeric(expected.getValue("userUnit")), g.userUnit, 0.0)
        }
    }

    @Test
    fun annotationPointAndRectangleCornersMatchGoldenCoordinates() {
        cases("validCases").forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val g = resolve(case)
            val expected = case.getValue("expected").jsonObject
            expected.getValue("points").jsonArray.forEachIndexed { index, raw ->
                val point = raw.jsonObject
                val source = numbers(point.getValue("source"), 2)
                val golden = numbers(point.getValue("canonical"), 2)
                assertClose("$id point $index", golden, sourceToPage(g, source))
                assertClose("$id inverse $index", source, pageToSource(g, golden))
            }

            val rectangle = expected.getValue("rectangle").jsonObject
            val (x0, y0, x1, y1) = numbers(rectangle.getValue("source"), 4)
            val original = listOf(
                listOf(x0, y0), listOf(x1, y0), listOf(x1, y1), listOf(x0, y1)
            )
            val corners = original.map { sourceToPage(g, it) }
            val goldenCorners = rectangle.getValue("canonicalCorners")
                .jsonArray.map { numbers(it, 2) }
            assertEquals("$id rectangle has four corners", 4, goldenCorners.size)
            corners.indices.forEach { index ->
                assertClose("$id corner $index", goldenCorners[index], corners[index])
                assertClose("$id corner inverse $index", original[index], pageToSource(g, goldenCorners[index]))
            }
            val bounds = listOf(
                corners.minOf { it[0] }, corners.minOf { it[1] },
                corners.maxOf { it[0] }, corners.maxOf { it[1] }
            )
            assertClose("$id rectangle bounds", numbers(rectangle.getValue("canonicalBounds"), 4), bounds)
        }
    }

    @Test
    fun malformedInheritedAndNumericalMetadataIsRejected() {
        cases("invalidCases").forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            try {
                resolve(case)
                fail("$id: invalid metadata was accepted")
            } catch (_: IllegalArgumentException) {
                // No fallback to a different crop, rotation, or UserUnit is permitted.
            }
        }
    }
}
