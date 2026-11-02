
package com.notebook.app.data.portable

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * FMT-05: Test-only portable archive structure checks.
 *
 * This covers a deliberately limited set of FMT-03
 * negative fixtures. It is NOT a production ZIP importer
 * or a complete document validator.
 */
class ArchiveFixtureConformanceTest {

    private enum class Violation {
        UNSAFE_MEMBER,
        DUPLICATE_MEMBER,
        MISSING_MANIFEST,
        UNSUPPORTED_VERSION,
        MISSING_PAGE,
        UNLISTED_MEMBER,
        PAGE_ID_MISMATCH
    }

    private class FixtureRejected(
        val violation: Violation
    ) : RuntimeException(violation.name)

    private fun reject(violation: Violation): Nothing =
        throw FixtureRejected(violation)

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
        assertTrue("Fixture missing: $path", file.isFile)
        return file
    }

    private fun readObject(
        zip: ZipFile,
        name: String
    ): JsonObject {
        val entry = requireNotNull(zip.getEntry(name)) {
            "Missing member: $name"
        }

        require(entry.size in 0L..(16L * 1024 * 1024)) {
            "Unexpected test fixture member size: $name"
        }

        return zip.getInputStream(entry).use {
            Json.parseToJsonElement(
                it.bufferedReader(Charsets.UTF_8).readText()
            ).jsonObject
        }
    }

    private fun safeMemberName(name: String): Boolean {
        if (name.isEmpty() ||
            name.startsWith("/") ||
            name.contains('\\') ||
            name.contains('\u0000')
        ) {
            return false
        }

        return name.split('/').all { part ->
            part.isNotEmpty() &&
                part != "." &&
                part != ".." &&
                !part.contains(':')
        }
    }

    private fun inspectArchive(file: File) {
        ZipFile(file).use { zip ->
            val names = zip.entries()
                .asSequence()
                .map { it.name }
                .toList()

            // 1. ZIP paths must remain inside the archive.
            if (names.any { !safeMemberName(it) }) {
                reject(Violation.UNSAFE_MEMBER)
            }

            // 2. Reject duplicate and case-colliding members.
            val foldedNames = names.map {
                it.lowercase(Locale.ROOT)
            }

            if (foldedNames.size != foldedNames.toSet().size) {
                reject(Violation.DUPLICATE_MEMBER)
            }

            val nameSet = names.toSet()

            // 3. A manifest is mandatory.
            if ("manifest.json" !in nameSet) {
                reject(Violation.MISSING_MANIFEST)
            }

            val manifest = readObject(zip, "manifest.json")

            // 4. Only the agreed initial schema is supported.
            if (
                manifest.getValue("schemaVersion")
                    .jsonPrimitive.int != 1
            ) {
                reject(Violation.UNSUPPORTED_VERSION)
            }

            val references = manifest.getValue("pages")
                .jsonArray.map { it.jsonObject }

            val assetPaths = manifest.getValue("assets")
                .jsonArray.map {
                    it.jsonObject.getValue("path")
                        .jsonPrimitive.content
                }

            val pagePaths = references.map {
                it.getValue("path").jsonPrimitive.content
            }

            // 5. Every referenced page must exist.
            for (path in pagePaths) {
                if (path !in nameSet) {
                    reject(Violation.MISSING_PAGE)
                }
            }

            // 6. Reject undeclared archive files.
            val declared = buildSet {
                add("manifest.json")
                addAll(pagePaths)
                addAll(assetPaths)
            }

            if (names.any { it !in declared }) {
                reject(Violation.UNLISTED_MEMBER)
            }

            // 7. Page identity must match the manifest.
            for (reference in references) {
                val id = reference.getValue("id")
                    .jsonPrimitive.content

                val path = reference.getValue("path")
                    .jsonPrimitive.content

                val page = readObject(zip, path)

                if (
                    page.getValue("id")
                        .jsonPrimitive.content != id
                ) {
                    reject(Violation.PAGE_ID_MISMATCH)
                }
            }
        }
    }

    private val expectedRejections = linkedMapOf(
        "invalid/traversal_member.notebook" to
            Violation.UNSAFE_MEMBER,

        "invalid/absolute_path_member.notebook" to
            Violation.UNSAFE_MEMBER,

        "invalid/duplicate_zip_member.notebook" to
            Violation.DUPLICATE_MEMBER,

        "invalid/missing_manifest.notebook" to
            Violation.MISSING_MANIFEST,

        "invalid/unsupported_schema_version.notebook" to
            Violation.UNSUPPORTED_VERSION,

        "invalid/missing_page.notebook" to
            Violation.MISSING_PAGE,

        "invalid/unlisted_archive_member.notebook" to
            Violation.UNLISTED_MEMBER,

        "invalid/page_id_mismatch.notebook" to
            Violation.PAGE_ID_MISMATCH
    )

    @Test
    fun selectedInvalidArchivesRejectForExpectedReason() {
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
        assertTrue(listed.containsAll(expectedRejections.keys))
        assertEquals(8, expectedRejections.size)

        expectedRejections.forEach { (path, expected) ->
            val error = assertThrows(
                "$path should reject as $expected",
                FixtureRejected::class.java
            ) {
                inspectArchive(fixture(path))
            }

            assertEquals(
                "Wrong rejection category for $path",
                expected,
                error.violation
            )
        }
    }

    @Test
    fun validArchivesPassStructureChecks() {
        val validPaths = listOf(
            "valid/empty_page_v1.notebook",
            "valid/single_point_stroke_v1.notebook",
            "valid/mixed_pages_v1.notebook"
        )

        validPaths.forEach { path ->
            inspectArchive(fixture(path))
        }
    }
}
