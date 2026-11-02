
package com.notebook.app.data.portable

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkFixtureConformanceTest {

    private val json = Json

    private val inkDir: File
        get() = File(
            requireNotNull(System.getProperty("notebookFixturesDir")) {
                "Gradle must set notebookFixturesDir"
            },
            "ink"
        )

    private fun cases(group: String): List<String> {
        val catalog = json.parseToJsonElement(
            File(inkDir, "expected_results.json").readText()
        ).jsonObject

        return catalog.getValue(group).jsonArray.map {
            it.jsonObject.getValue("path").jsonPrimitive.content
        }
    }

    @Test
    fun validInkFixturesPassKotlinValidation() {
        val paths = cases("valid")
        assertEquals(6, paths.size)

        paths.forEach { path ->
            val result = runCatching {
                validateInkArchive(File(inkDir, path))
            }
            assertTrue(
                "$path should be valid: ${result.exceptionOrNull()}",
                result.isSuccess
            )
            assertTrue(
                "$path should contain canonical strokes",
                result.getOrThrow() > 0
            )
        }
    }


    @Test
    fun invalidInkFixturesFailKotlinValidation() {
        val paths = cases("invalid")
        assertEquals(11, paths.size)
        assertEquals(paths.size, paths.toSet().size)

        paths.forEach { path ->
            val fixture = File(inkDir, path)

            assertTrue(
                "Fixture missing: $path",
                fixture.isFile
            )

            // Confirm basic archive structure independently
            // of the intentionally invalid ink payload.
            ZipFile(fixture).use { zip ->
                val manifest = readObject(zip, "manifest.json")
                val pages = manifest.getValue("pages").jsonArray

                assertTrue(
                    "$path has no pages",
                    pages.isNotEmpty()
                )

                pages.forEach { reference ->
                    val pagePath = reference.jsonObject
                        .getValue("path").jsonPrimitive.content

                    assertTrue(
                        "$path is missing $pagePath",
                        zip.getEntry(pagePath) != null
                    )
                }
            }

            val result = runCatching {
                validateInkArchive(fixture)
            }

            assertTrue(
                "$path was unexpectedly accepted",
                result.isFailure
            )
        }
    }


    private fun readObject(
        zip: ZipFile,
        path: String
    ): JsonObject {
        val entry = requireNotNull(zip.getEntry(path)) {
            "Missing archive member: $path"
        }
        require(!entry.isDirectory)

        val text = zip.getInputStream(entry).use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun finite(value: JsonElement): Double {
        val primitive = value.jsonPrimitive
        require(!primitive.isString)
        val number = primitive.double
        require(number.isFinite()) {
            "Non-finite coordinate or characteristic"
        }
        return number
    }

    private fun unit(value: JsonElement) {
        require(finite(value) in 0.0..1.0)
    }

    private fun validateInkArchive(file: File): Int {
        require(file.isFile) { "Missing fixture: $file" }

        return ZipFile(file).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            require(
                manifest.getValue("schemaVersion").jsonPrimitive.int == 1
            )

            val pages = manifest.getValue("pages").jsonArray
            require(pages.isNotEmpty())

            val pageIds = mutableSetOf<String>()
            val elementIds = mutableSetOf<String>()
            var strokeCount = 0

            for (reference in pages) {
                val ref = reference.jsonObject
                val pageId = ref.getValue("id").jsonPrimitive.content
                val path = ref.getValue("path").jsonPrimitive.content

                require(pageIds.add(pageId))
                require(path == "pages/$pageId.json")

                val page = readObject(zip, path)
                require(page.getValue("id").jsonPrimitive.content == pageId)

                val size = page.getValue("size").jsonObject
                require(finite(size.getValue("width")) > 0.0)
                require(finite(size.getValue("height")) > 0.0)

                for (rawElement in page.getValue("elements").jsonArray) {
                    val element = rawElement.jsonObject

                    val elementId =
                        element.getValue("id").jsonPrimitive.content
                    require(elementIds.add(elementId))

                    require(
                        element.getValue("type").jsonPrimitive.content ==
                            "stroke"
                    )

                    val transform =
                        element.getValue("transform").jsonArray
                    require(transform.size == 6)

                    val t = transform.map(::finite)
                    val determinant = t[0] * t[3] - t[1] * t[2]
                    require(determinant.isFinite() && determinant != 0.0)

                    validateStroke(element.getValue("payload").jsonObject)
                    strokeCount++
                }
            }

            require(strokeCount > 0)
            strokeCount
        }
    }

    private fun validateStroke(payload: JsonObject) {
        require(
            payload.getValue("encoding").jsonPrimitive.content ==
                "sampledPointsV1"
        )

        val brush = payload.getValue("brush").jsonObject
        require(
            brush.getValue("kind").jsonPrimitive.content in
                setOf("pen", "pencil", "highlighter")
        )
        require(finite(brush.getValue("width")) > 0.0)
        require(
            Regex("^#[0-9A-Fa-f]{6}$").matches(
                brush.getValue("color").jsonPrimitive.content
            )
        )
        unit(brush.getValue("opacity"))

        val points = payload.getValue("points").jsonArray
        require(points.isNotEmpty())

        var previousTime = -1L

        for (rawPoint in points) {
            val point = rawPoint.jsonObject
            finite(point.getValue("x"))
            finite(point.getValue("y"))

            val timeValue = point.getValue("tMs").jsonPrimitive
            require(!timeValue.isString)
            val time = timeValue.long

            require(time >= 0L && time >= previousTime)
            previousTime = time

            point["pressure"]?.let(::unit)

            point["tiltRad"]?.let {
                require(finite(it) in 0.0..(Math.PI / 2.0))
            }

            point["orientationRad"]?.let(::finite)
        }
    }

    // FMT-05: Read canonical values independently from
    // the portable fixture archives.

    private fun validFixturePages(name: String): List<JsonObject> {
        val archive = File(inkDir, "valid/$name.notebook")

        return ZipFile(archive).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            manifest.getValue("pages").jsonArray.map { reference ->
                val path = reference.jsonObject
                    .getValue("path").jsonPrimitive.content
                readObject(zip, path)
            }
        }
    }

    private fun singleStrokePayload(name: String): JsonObject {
        val page = validFixturePages(name).single()
        val element = page.getValue("elements")
            .jsonArray.single().jsonObject

        return element.getValue("payload").jsonObject
    }

    private fun assertSamples(
        payload: JsonObject,
        xs: List<Double>,
        ys: List<Double>,
        times: List<Long>,
        pressures: List<Double>? = null
    ) {
        val points = payload.getValue("points")
            .jsonArray.map { it.jsonObject }

        assertEquals(xs.size, ys.size)
        assertEquals(xs.size, times.size)
        pressures?.let { assertEquals(xs.size, it.size) }
        assertEquals(xs.size, points.size)

        points.forEachIndexed { index, point ->
            assertEquals(
                "x[$index]",
                xs[index],
                point.getValue("x").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                "y[$index]",
                ys[index],
                point.getValue("y").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                "tMs[$index]",
                times[index],
                point.getValue("tMs").jsonPrimitive.long
            )

            if (pressures == null) {
                assertTrue(
                    "Unexpected pressure[$index]",
                    !point.containsKey("pressure")
                )
            } else {
                assertEquals(
                    "pressure[$index]",
                    pressures[index],
                    point.getValue("pressure").jsonPrimitive.double,
                    0.0
                )
            }
        }
    }

    @Test
    fun penFixturesPreserveExactOrderedSamples() {
        val straight = singleStrokePayload("ink_pen_straight_v1")
        val straightBrush = straight.getValue("brush").jsonObject

        assertEquals(
            "pen",
            straightBrush.getValue("kind").jsonPrimitive.content
        )
        assertEquals(
            2.5,
            straightBrush.getValue("width").jsonPrimitive.double,
            0.0
        )

        assertSamples(
            straight,
            xs = listOf(60.25, 110.5, 180.75),
            ys = listOf(120.0, 120.0, 120.0),
            times = listOf(0L, 17L, 34L),
            pressures = listOf(0.5, 0.5, 0.5)
        )

        val curve = singleStrokePayload("ink_pen_curve_v1")
        val curveBrush = curve.getValue("brush").jsonObject

        assertEquals(
            3.25,
            curveBrush.getValue("width").jsonPrimitive.double,
            0.0
        )
        assertEquals(
            "#0A54C0",
            curveBrush.getValue("color").jsonPrimitive.content
        )

        assertSamples(
            curve,
            xs = listOf(44.125, 58.5, 78.875, 94.25, 130.0),
            ys = listOf(95.75, 86.25, 103.5, 142.125, 150.75),
            times = listOf(0L, 11L, 11L, 27L, 42L),
            pressures = listOf(0.2, 0.45, 0.88, 0.51, 0.1)
        )
    }

    @Test
    fun pencilPreservesTiltAndSignedOrientation() {
        val payload = singleStrokePayload("ink_pencil_tilt_v1")
        val brush = payload.getValue("brush").jsonObject

        assertEquals(
            "pencil",
            brush.getValue("kind").jsonPrimitive.content
        )
        assertEquals(
            0.9,
            brush.getValue("opacity").jsonPrimitive.double,
            0.0
        )

        assertSamples(
            payload,
            xs = listOf(37.5, 68.25, 101.125),
            ys = listOf(55.5, 79.25, 62.875),
            times = listOf(0L, 14L, 33L),
            pressures = listOf(0.38, 0.59, 0.77)
        )

        val points = payload.getValue("points")
            .jsonArray.map { it.jsonObject }

        val tilts = listOf(0.0, 0.48, Math.PI / 2.0)
        val orientations = listOf(-Math.PI / 2.0, 0.25, 3.0)

        points.forEachIndexed { index, point ->
            assertEquals(
                "tilt[$index]",
                tilts[index],
                point.getValue("tiltRad").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                "orientation[$index]",
                orientations[index],
                point.getValue("orientationRad").jsonPrimitive.double,
                0.0
            )
        }
    }

    @Test
    fun optionalCharacteristicsRemainAbsent() {
        val highlighter = singleStrokePayload("ink_highlighter_v1")
        val brush = highlighter.getValue("brush").jsonObject

        assertEquals(
            "highlighter",
            brush.getValue("kind").jsonPrimitive.content
        )
        assertEquals(
            14.0,
            brush.getValue("width").jsonPrimitive.double,
            0.0
        )
        assertEquals(
            "#FFC633",
            brush.getValue("color").jsonPrimitive.content
        )
        assertEquals(
            0.35,
            brush.getValue("opacity").jsonPrimitive.double,
            0.0
        )

        assertSamples(
            highlighter,
            xs = listOf(35.0, 210.0),
            ys = listOf(180.5, 182.0),
            times = listOf(0L, 28L)
        )

        val single = singleStrokePayload("ink_single_sample_v1")

        assertSamples(
            single,
            xs = listOf(300.0),
            ys = listOf(400.0),
            times = listOf(0L)
        )

        for (payload in listOf(highlighter, single)) {
            for (rawPoint in payload.getValue("points").jsonArray) {
                val point = rawPoint.jsonObject
                assertTrue(!point.containsKey("pressure"))
                assertTrue(!point.containsKey("tiltRad"))
                assertTrue(!point.containsKey("orientationRad"))
            }
        }
    }

    @Test
    fun mixedSizePagesPreserveOwnershipOrderAndTransform() {
        val name = "ink_page_local_v1"
        val archive = File(inkDir, "valid/$name.notebook")

        ZipFile(archive).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            val refs = manifest.getValue("pages").jsonArray
            assertEquals(2, refs.size)

            val pages = refs.map { reference ->
                val ref = reference.jsonObject
                val page = readObject(
                    zip,
                    ref.getValue("path").jsonPrimitive.content
                )

                assertEquals(
                    ref.getValue("id").jsonPrimitive.content,
                    page.getValue("id").jsonPrimitive.content
                )
                page
            }

            val firstSize = pages[0].getValue("size").jsonObject
            val secondSize = pages[1].getValue("size").jsonObject

            assertEquals(
                595.276,
                firstSize.getValue("width").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                841.89,
                firstSize.getValue("height").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                792.0,
                secondSize.getValue("width").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                612.0,
                secondSize.getValue("height").jsonPrimitive.double,
                0.0
            )

            val first = pages[0].getValue("elements")
                .jsonArray.single().jsonObject
            val second = pages[1].getValue("elements")
                .jsonArray.single().jsonObject

            assertTrue(
                pages[0].getValue("id") != pages[1].getValue("id")
            )
            assertTrue(
                first.getValue("id") != second.getValue("id")
            )

            val transform = second.getValue("transform")
                .jsonArray.map { it.jsonPrimitive.double }

            assertEquals(
                listOf(1.0, 0.0, 0.0, 1.0, 4.0, -2.0),
                transform
            )

            assertSamples(
                first.getValue("payload").jsonObject,
                xs = listOf(12.0, 595.276),
                ys = listOf(16.0, 841.89),
                times = listOf(0L, 30L)
            )

            assertSamples(
                second.getValue("payload").jsonObject,
                xs = listOf(5.0, 390.0),
                ys = listOf(6.0, 120.0),
                times = listOf(0L, 50L)
            )
        }
    }
}
