package com.notebook.app.data.portable

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * PDF-01 C2: independent Kotlin/JVM consumer of the shared PDF geometry vectors.
 *
 * This is test-only contract arithmetic, NOT a production PDF parser/adapter.
 * Neither PDFKit nor Android PdfRenderer is exercised by these tests.
 */
class PdfGeometryFixtureConformanceTest {

    private val catalog: JsonObject by lazy {
        val root = requireNotNull(System.getProperty("notebookFixturesDir")) {
            "Gradle must set notebookFixturesDir"
        }
        val file = File(root, "pdf/geometry_cases_v1.json")
        require(file.isFile) { "Missing PDF geometry fixture: $file" }
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }

    private val validCases: List<JsonObject>
        get() = catalog.getValue("validCases").jsonArray.map { it.jsonObject }

    private val invalidCases: List<JsonObject>
        get() = catalog.getValue("invalidCases").jsonArray.map { it.jsonObject }

    private data class Geometry(
        val crop: List<Double>,
        val size: List<Double>,
        val rotation: Int,
        val userUnit: Double,
        val affine: List<Double>,
    )

    private fun number(value: JsonElement): Double {
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Expected numeric JSON primitive")
        require(!primitive.isString && primitive.booleanOrNull == null) {
            "Expected JSON number, not a string or boolean"
        }
        val parsed = primitive.doubleOrNull
            ?: throw IllegalArgumentException("Expected finite numeric value")
        require(parsed.isFinite()) { "Nonfinite numeric value" }
        return parsed
    }

    private fun numbers(value: JsonElement, count: Int): List<Double> {
        val array = value as? JsonArray
            ?: throw IllegalArgumentException("Expected JSON array")
        require(array.size == count) { "Expected $count numbers" }
        return array.map(::number)
    }

    private fun box(value: JsonElement): List<Double> {
        val result = numbers(value, 4)
        require(result[2] > result[0] && result[3] > result[1]) {
            "PDF page box must have positive dimensions"
        }
        require((result[2] - result[0]).isFinite() && (result[3] - result[1]).isFinite()) {
            "PDF page box dimensions overflow"
        }
        return result
    }

    /** Calculate the ADR-0004 affine independently from the Python fixture consumer. */
    private fun resolve(case: JsonObject): Geometry {
        val media = box(case.getValue("mediaBox"))
        val crop = case["cropBox"]?.let(::box) ?: media
        val x0 = max(media[0], crop[0])
        val y0 = max(media[1], crop[1])
        val x1 = minOf(media[2], crop[2])
        val y1 = minOf(media[3], crop[3])
        require(x1 > x0 && y1 > y0) { "Empty effective CropBox" }

        val rotate = case.getValue("rotate").jsonPrimitive.intOrNull
            ?: throw IllegalArgumentException("Rotation must be an integer")
        require(rotate % 90 == 0) { "Rotation must be a multiple of 90" }
        val r = ((rotate % 360) + 360) % 360

        val scale = number(case.getValue("userUnit"))
        require(scale > 0.0) { "UserUnit must be positive" }
        val w = x1 - x0
        val h = y1 - y0
        val size = if (r == 90 || r == 270) listOf(h * scale, w * scale)
        else listOf(w * scale, h * scale)
        require(size.all { it.isFinite() && it > 0.0 }) { "Invalid displayed page size" }

        // Column-vector affine: X=a*x+c*y+tx, Y=b*x+d*y+ty.
        val affine = when (r) {
            0 -> listOf(scale, 0.0, 0.0, -scale, -scale * x0, scale * y1)
            90 -> listOf(0.0, scale, scale, 0.0, -scale * y0, -scale * x0)
            180 -> listOf(-scale, 0.0, 0.0, scale, scale * x1, -scale * y0)
            270 -> listOf(0.0, -scale, -scale, 0.0, scale * y1, scale * x1)
            else -> error("Rotation normalization failed")
        }
        require(affine.all { it.isFinite() }) { "Nonfinite affine coefficient" }
        val determinant = affine[0] * affine[3] - affine[1] * affine[2]
        require(determinant.isFinite() && determinant != 0.0) {
            "Noninvertible source-to-page affine"
        }
        return Geometry(listOf(x0, y0, x1, y1), size, r, scale, affine)
    }

    private fun sourceToPage(geometry: Geometry, source: List<Double>): List<Double> {
        val m = geometry.affine
        val x = m[0] * source[0] + m[2] * source[1] + m[4]
        val y = m[1] * source[0] + m[3] * source[1] + m[5]
        require(x.isFinite() && y.isFinite()) { "Mapped point is nonfinite" }
        return listOf(x, y)
    }

    /** Invert using the explicit rotation formulas, not the forward affine. */
    private fun pageToSource(geometry: Geometry, page: List<Double>): List<Double> {
        val (x0, y0, x1, y1) = geometry.crop
        val x = page[0] / geometry.userUnit
        val y = page[1] / geometry.userUnit
        val source = when (geometry.rotation) {
            0 -> listOf(x0 + x, y1 - y)
            90 -> listOf(x0 + y, y0 + x)
            180 -> listOf(x1 - x, y0 + y)
            270 -> listOf(x1 - y, y1 - x)
            else -> error("Unexpected normalized rotation")
        }
        require(source.all { it.isFinite() }) { "Inverse-mapped point is nonfinite" }
        return source
    }

    private fun assertNumbersClose(label: String, expected: List<Double>, actual: List<Double>) {
        assertEquals("$label length", expected.size, actual.size)
        expected.indices.forEach { index ->
            val tolerance = max(1e-9, 1e-12 * abs(expected[index]))
            assertTrue(
                "$label [$index]: expected ${expected[index]}, actual ${actual[index]}",
                actual[index].isFinite() && abs(actual[index] - expected[index]) <= tolerance,
            )
        }
    }

    @Test
    fun catalogCoversRequiredMetadataAndRotationVariants() {
        assertEquals("pdf-page-geometry-v1", catalog.getValue("contract").jsonPrimitive.content)
        assertTrue("At least eight valid cases", validCases.size >= 8)
        assertTrue("At least four invalid cases", invalidCases.size >= 4)
        val cases = validCases + invalidCases
        val ids = cases.map { it.getValue("id").jsonPrimitive.content }
        assertEquals("Case IDs must be unique", ids.size, ids.toSet().size)
        assertEquals(setOf(0, 90, 180, 270), validCases.map {
            it.getValue("rotate").jsonPrimitive.int
        }.toSet())
        assertTrue(validCases.any { "cropBox" !in it })
        assertTrue(validCases.any { number(it.getValue("userUnit")) != 1.0 })
        assertTrue(validCases.any { box(it.getValue("mediaBox")).any { n -> n < 0.0 } })
        assertTrue(validCases.all { it.getValue("points").jsonArray.size >= 3 })
    }

    @Test
    fun effectiveCropAndDisplayedDimensionsMatchSharedGoldenValues() {
        validCases.forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val geometry = resolve(case)
            assertNumbersClose("$id effective crop", numbers(case.getValue("expectedCropBox"), 4), geometry.crop)
            assertNumbersClose("$id displayed page size", numbers(case.getValue("expectedPageSize"), 2), geometry.size)
        }
    }

    @Test
    fun forwardAndInverseCoordinatesMatchIndependentGoldenVectors() {
        validCases.forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            val geometry = resolve(case)
            case.getValue("points").jsonArray.forEachIndexed { index, raw ->
                val item = raw.jsonObject
                val source = numbers(item.getValue("source"), 2)
                val canonical = numbers(item.getValue("canonical"), 2)
                assertNumbersClose("$id point $index forward", canonical, sourceToPage(geometry, source))
                assertNumbersClose("$id point $index inverse", source, pageToSource(geometry, canonical))
                assertNumbersClose(
                    "$id point $index round trip",
                    source,
                    pageToSource(geometry, sourceToPage(geometry, source)),
                )
            }
        }
    }

    @Test
    fun invalidPdfPageMetadataFailsExplicitly() {
        invalidCases.forEach { case ->
            val id = case.getValue("id").jsonPrimitive.content
            try {
                resolve(case)
                fail("$id: invalid metadata was accepted")
            } catch (_: IllegalArgumentException) {
                // Rejection of invalid fixture metadata is required.
            }
        }
    }
}
