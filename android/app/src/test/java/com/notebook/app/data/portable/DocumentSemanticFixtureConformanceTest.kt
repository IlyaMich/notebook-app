
package com.notebook.app.data.portable

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * FMT-05: Independent Kotlin semantic conformance checks.
 *
 * Test-only implementation for committed synthetic fixtures.
 * Not a complete schema validator or production importer.
 */
class DocumentSemanticFixtureConformanceTest {

    private enum class Violation {
        INVALID_PAGE_SIZE,
        UNKNOWN_ELEMENT_TYPE,
        DUPLICATE_ELEMENT_ID,
        SINGULAR_TRANSFORM,
        NONMONOTONIC_STROKE_TIME,
        IMAGE_CROP_OUT_OF_BOUNDS,
        INVALID_IMAGE_ASSET,
        INVALID_PDF_ASSET
    }

    private class SemanticRejected(
        val violation: Violation
    ) : RuntimeException(violation.name)

    private fun reject(violation: Violation): Nothing =
        throw SemanticRejected(violation)

    private val root: File
        get() = File(
            requireNotNull(
                System.getProperty("notebookFixturesDir")
            ) {
                "Gradle must set notebookFixturesDir"
            }
        )

    private fun fixture(path: String): File {
        val file = File(root, path)
        assertTrue("Missing fixture: $path", file.isFile)
        return file
    }

    private fun readObject(
        zip: ZipFile,
        path: String
    ): JsonObject {
        val entry = requireNotNull(zip.getEntry(path)) {
            "Missing ZIP member: $path"
        }

        return zip.getInputStream(entry).use { stream ->
            Json.parseToJsonElement(
                stream.bufferedReader(Charsets.UTF_8).readText()
            ).jsonObject
        }
    }

    private fun finite(value: JsonElement): Double {
        val primitive = value.jsonPrimitive
        require(!primitive.isString)

        val number = primitive.double
        require(number.isFinite())
        return number
    }

    private fun key(id: String): String =
        id.lowercase(Locale.ROOT)

    private fun checkArchive(file: File) {
        ZipFile(file).use { zip ->
            val manifest = readObject(zip, "manifest.json")

            val assetsById = manifest.getValue("assets")
                .jsonArray
                .map { it.jsonObject }
                .associateBy {
                    key(
                        it.getValue("id")
                            .jsonPrimitive.content
                    )
                }

            val seenElementIds = mutableSetOf<String>()

            for (reference in manifest.getValue("pages").jsonArray) {
                val path = reference.jsonObject
                    .getValue("path").jsonPrimitive.content

                val page = readObject(zip, path)
                val size = page.getValue("size").jsonObject

                val width = finite(size.getValue("width"))
                val height = finite(size.getValue("height"))

                if (width <= 0.0 || height <= 0.0) {
                    reject(Violation.INVALID_PAGE_SIZE)
                }

                // PDF backgrounds reference original PDF assets.
                val background = page.getValue("background").jsonObject

                if (
                    background.getValue("type")
                        .jsonPrimitive.content == "pdfPage"
                ) {
                    val assetId = key(
                        background.getValue("assetId")
                            .jsonPrimitive.content
                    )

                    val asset = assetsById[assetId]
                    if (
                        asset == null ||
                        asset.getValue("mimeType")
                            .jsonPrimitive.content != "application/pdf"
                    ) {
                        reject(Violation.INVALID_PDF_ASSET)
                    }
                }

                for (rawElement in page.getValue("elements").jsonArray) {
                    val element = rawElement.jsonObject

                    // Element IDs are unique across the entire note.
                    val id = key(
                        element.getValue("id")
                            .jsonPrimitive.content
                    )

                    if (!seenElementIds.add(id)) {
                        reject(Violation.DUPLICATE_ELEMENT_ID)
                    }

                    val type = element.getValue("type")
                        .jsonPrimitive.content

                    if (
                        type !in setOf(
                            "stroke",
                            "shape",
                            "text",
                            "image"
                        )
                    ) {
                        reject(Violation.UNKNOWN_ELEMENT_TYPE)
                    }

                    // Affine transforms must be invertible.
                    val transform = element.getValue("transform")
                        .jsonArray

                    require(transform.size == 6)
                    val t = transform.map(::finite)

                    val determinant =
                        t[0] * t[3] - t[1] * t[2]

                    if (
                        !determinant.isFinite() ||
                        determinant == 0.0
                    ) {
                        reject(Violation.SINGULAR_TRANSFORM)
                    }

                    val payload = element.getValue("payload")
                        .jsonObject

                    when (type) {
                        "stroke" -> validateStroke(payload)
                        "image" -> validateImage(
                            payload,
                            assetsById
                        )
                        "shape", "text" -> Unit
                    }
                }
            }
        }
    }

    private fun validateStroke(payload: JsonObject) {
        val points = payload.getValue("points").jsonArray
        require(points.isNotEmpty())

        var previousTime = -1L

        for (rawPoint in points) {
            val point = rawPoint.jsonObject
            val time = point.getValue("tMs")
                .jsonPrimitive.long

            if (time < 0L || time < previousTime) {
                reject(Violation.NONMONOTONIC_STROKE_TIME)
            }

            previousTime = time
        }
    }

    private fun validateImage(
        payload: JsonObject,
        assetsById: Map<String, JsonObject>
    ) {
        val assetId = key(
            payload.getValue("assetId")
                .jsonPrimitive.content
        )

        val asset = assetsById[assetId]

        if (
            asset == null ||
            asset.getValue("mimeType")
                .jsonPrimitive.content !in
            setOf("image/png", "image/jpeg")
        ) {
            reject(Violation.INVALID_IMAGE_ASSET)
        }

        val crop = payload["crop"]?.takeUnless {
            it is JsonNull
        }?.jsonObject ?: return

        val x = finite(crop.getValue("x"))
        val y = finite(crop.getValue("y"))
        val width = finite(crop.getValue("width"))
        val height = finite(crop.getValue("height"))

        if (
            x !in 0.0..1.0 ||
            y !in 0.0..1.0 ||
            width !in 0.0..1.0 ||
            height !in 0.0..1.0 ||
            x + width > 1.0 ||
            y + height > 1.0
        ) {
            reject(Violation.IMAGE_CROP_OUT_OF_BOUNDS)
        }
    }

    private val expectedRejections = linkedMapOf(
        "invalid/negative_page_width.notebook" to
            Violation.INVALID_PAGE_SIZE,

        "invalid/unknown_element_type.notebook" to
            Violation.UNKNOWN_ELEMENT_TYPE,

        "invalid/duplicate_element_id.notebook" to
            Violation.DUPLICATE_ELEMENT_ID,

        "invalid/singular_transform.notebook" to
            Violation.SINGULAR_TRANSFORM,

        "invalid/nonmonotonic_stroke_time.notebook" to
            Violation.NONMONOTONIC_STROKE_TIME,

        "invalid/out_of_bounds_image_crop.notebook" to
            Violation.IMAGE_CROP_OUT_OF_BOUNDS,

        "invalid/missing_image_asset_reference.notebook" to
            Violation.INVALID_IMAGE_ASSET,

        "invalid/wrong_pdf_background_asset.notebook" to
            Violation.INVALID_PDF_ASSET
    )

    @Test
    fun invalidSemanticFixturesRejectForExpectedReason() {
        val catalog = Json.parseToJsonElement(
            File(root, "expected_results.json").readText()
        ).jsonObject

        val listed = catalog.getValue("invalid")
            .jsonArray.map {
                it.jsonObject.getValue("path")
                    .jsonPrimitive.content
            }

        assertEquals(24, listed.size)
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(8, expectedRejections.size)
        assertTrue(listed.containsAll(expectedRejections.keys))

        expectedRejections.forEach { (path, expected) ->
            val file = fixture(path)

            val error = assertThrows(
                "$path should reject as $expected",
                SemanticRejected::class.java
            ) {
                checkArchive(file)
            }

            assertEquals(
                "Wrong rejection category: $path",
                expected,
                error.violation
            )
        }
    }

    @Test
    fun validDocumentsPassSelectedSemanticChecks() {
        val validPaths = listOf(
            "valid/empty_page_v1.notebook",
            "valid/single_point_stroke_v1.notebook",
            "valid/mixed_pages_v1.notebook"
        )

        validPaths.forEach { path ->
            checkArchive(fixture(path))
        }
    }
}
