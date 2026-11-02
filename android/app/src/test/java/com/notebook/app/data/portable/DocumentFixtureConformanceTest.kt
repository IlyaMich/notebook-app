
package com.notebook.app.data.portable

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * FMT-05: Independent Kotlin checks of committed portable
 * document fixtures.
 *
 * This is test-only fixture verification, not a production
 * importer or comprehensive schema/security validator.
 */
class DocumentFixtureConformanceTest {

    private val fixtureRoot: File
        get() = File(
            requireNotNull(
                System.getProperty("notebookFixturesDir")
            ) {
                "Gradle must set notebookFixturesDir"
            }
        )

    private fun fixture(name: String): File {
        val file = File(fixtureRoot, "valid/$name.notebook")
        assertTrue("Missing fixture: $file", file.isFile)
        return file
    }

    private fun readObject(
        zip: ZipFile,
        path: String
    ): JsonObject {
        val entry = requireNotNull(zip.getEntry(path)) {
            "Missing archive member: $path"
        }

        val content = zip.getInputStream(entry).use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }

        return Json.parseToJsonElement(content).jsonObject
    }

    private fun readPages(
        zip: ZipFile,
        manifest: JsonObject
    ): List<JsonObject> {
        return manifest.getValue("pages").jsonArray.map { raw ->
            val reference = raw.jsonObject
            val id = reference.getValue("id")
                .jsonPrimitive.content
            val path = reference.getValue("path")
                .jsonPrimitive.content

            assertEquals("pages/$id.json", path)

            val page = readObject(zip, path)
            assertEquals(
                id,
                page.getValue("id").jsonPrimitive.content
            )
            page
        }
    }

    private fun elements(page: JsonObject): List<JsonObject> =
        page.getValue("elements").jsonArray.map {
            it.jsonObject
        }

    @Test
    fun emptyPagePreservesDocumentStructure() {
        ZipFile(fixture("empty_page_v1")).use { zip ->
            val manifest = readObject(zip, "manifest.json")

            assertEquals(
                1,
                manifest.getValue("schemaVersion")
                    .jsonPrimitive.int
            )

            assertEquals(
                manifest.getValue("createdAt"),
                manifest.getValue("updatedAt")
            )

            assertTrue(
                manifest.getValue("assets")
                    .jsonArray.isEmpty()
            )

            val pages = readPages(zip, manifest)
            assertEquals(1, pages.size)
            assertTrue(elements(pages.single()).isEmpty())

            assertEquals(
                "blank",
                pages.single().getValue("background")
                    .jsonObject.getValue("type")
                    .jsonPrimitive.content
            )
        }
    }

    @Test
    fun singlePointStrokePreservesReflectionAndOpacity() {
        ZipFile(fixture("single_point_stroke_v1")).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            val page = readPages(zip, manifest).single()
            val element = elements(page).single()

            assertEquals(
                "dotted",
                page.getValue("background")
                    .jsonObject.getValue("type")
                    .jsonPrimitive.content
            )

            assertEquals(
                "stroke",
                element.getValue("type")
                    .jsonPrimitive.content
            )

            val transform = element.getValue("transform")
                .jsonArray.map { it.jsonPrimitive.double }

            assertEquals(
                listOf(-1.0, 0.0, 0.0, 1.0, 120.0, 0.0),
                transform
            )

            val payload = element.getValue("payload").jsonObject
            val brush = payload.getValue("brush").jsonObject

            assertEquals(
                "pencil",
                brush.getValue("kind").jsonPrimitive.content
            )
            assertEquals(
                0.0,
                brush.getValue("opacity").jsonPrimitive.double,
                0.0
            )

            val point = payload.getValue("points")
                .jsonArray.single().jsonObject

            assertEquals(
                50.0,
                point.getValue("x").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                60.0,
                point.getValue("y").jsonPrimitive.double,
                0.0
            )
            assertEquals(
                0L,
                point.getValue("tMs").jsonPrimitive.long
            )
            assertFalse(point.containsKey("pressure"))
        }
    }

    @Test
    fun mixedDocumentPreservesPageAndElementOrder() {
        ZipFile(fixture("mixed_pages_v1")).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            val pages = readPages(zip, manifest)

            assertEquals(4, pages.size)

            val backgrounds = pages.map {
                it.getValue("background")
                    .jsonObject.getValue("type")
                    .jsonPrimitive.content
            }

            assertEquals(
                listOf("ruled", "grid", "blank", "pdfPage"),
                backgrounds
            )

            val elementTypes = pages.map { page ->
                elements(page).map { element ->
                    element.getValue("type")
                        .jsonPrimitive.content
                }
            }

            assertEquals(
                listOf(
                    listOf("stroke", "shape", "text"),
                    listOf("image", "shape"),
                    listOf("stroke"),
                    listOf("text")
                ),
                elementTypes
            )

            val ids = pages.flatMap { page ->
                elements(page).map {
                    it.getValue("id").jsonPrimitive.content
                }
            }

            assertEquals(ids.size, ids.toSet().size)

            // Verify that the ordered content remains typed,
            // rather than flattened into an image.
            val arrow = elements(pages[0])[1]
            assertEquals(
                "arrow",
                arrow.getValue("payload").jsonObject
                    .getValue("kind").jsonPrimitive.content
            )

            val firstText = elements(pages[0])[2]
                .getValue("payload").jsonObject

            assertEquals(
                "F = ma • synthetic fixture",
                firstText.getValue("text")
                    .jsonPrimitive.content
            )

            val highlighter = elements(pages[2]).single()
            assertTrue(
                highlighter.getValue("locked")
                    .jsonPrimitive.boolean
            )

            val pdfBackground = pages[3]
                .getValue("background").jsonObject

            assertEquals(
                1,
                pdfBackground.getValue("pageIndex")
                    .jsonPrimitive.int
            )
        }
    }

    @Test
    fun mixedDocumentPreservesAssetReferencesAndHashes() {
        ZipFile(fixture("mixed_pages_v1")).use { zip ->
            val manifest = readObject(zip, "manifest.json")
            val pages = readPages(zip, manifest)

            val assets = manifest.getValue("assets")
                .jsonArray.map { it.jsonObject }

            assertEquals(2, assets.size)

            val assetsById = assets.associateBy {
                it.getValue("id").jsonPrimitive.content
            }

            val imageElement = elements(pages[1])[0]
                .getValue("payload").jsonObject

            val imageAssetId = imageElement.getValue("assetId")
                .jsonPrimitive.content

            assertEquals(
                "image/png",
                assetsById.getValue(imageAssetId)
                    .getValue("mimeType").jsonPrimitive.content
            )

            val pdfBackground = pages[3]
                .getValue("background").jsonObject

            val pdfAssetId = pdfBackground.getValue("assetId")
                .jsonPrimitive.content

            assertEquals(
                "application/pdf",
                assetsById.getValue(pdfAssetId)
                    .getValue("mimeType").jsonPrimitive.content
            )

            assets.forEach { asset ->
                val path = asset.getValue("path")
                    .jsonPrimitive.content
                val entry = requireNotNull(zip.getEntry(path)) {
                    "Missing asset: $path"
                }

                // These committed synthetic fixtures are small.
                // Production import must use bounded streaming.
                val bytes = zip.getInputStream(entry).use {
                    it.readBytes()
                }

                assertEquals(
                    asset.getValue("byteLength")
                        .jsonPrimitive.long,
                    bytes.size.toLong()
                )

                val actualHash = MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") {
                        "%02x".format(it.toInt() and 0xff)
                    }

                assertEquals(
                    asset.getValue("sha256")
                        .jsonPrimitive.content.lowercase(),
                    actualHash
                )
            }
        }
    }
}
