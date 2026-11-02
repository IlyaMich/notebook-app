
package com.notebook.app.data.portable

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * FMT-05: Test-only conformance checks for the remaining
 * FMT-03 metadata, asset, and archive safety fixtures.
 *
 * This is not a production importer or general-purpose
 * untrusted ZIP/JSON validator.
 */
class DocumentIntegrityFixtureConformanceTest {

    private enum class Violation {
        ZIP_SYMLINK,
        DUPLICATE_MANIFEST_KEY,
        ASSET_HASH_MISMATCH,
        ASSET_LENGTH_MISMATCH,
        MISSING_ASSET,
        DUPLICATE_ASSET_ID,
        CREATION_AFTER_UPDATE,
        NON_UTC_TIMESTAMP
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

    /**
     * Java ZipFile does not expose Unix file type bits.
     *
     * Inspect the ZIP central directory's external attributes
     * to identify symlinks in the committed small fixtures.
     *
     * ZIP64 and arbitrary untrusted archives are out of scope.
     */
    private fun containsUnixSymlink(file: File): Boolean {
        require(file.length() in 22L..2_000_000L) {
            "Unexpected fixture archive size"
        }

        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        fun u16(at: Int): Int =
            buffer.getShort(at).toInt() and 0xffff

        fun u32(at: Int): Long =
            buffer.getInt(at).toLong() and 0xffffffffL

        val minimum = maxOf(
            0,
            bytes.size - 22 - 65535
        )

        val eocd = (bytes.size - 22 downTo minimum)
            .firstOrNull { u32(it) == 0x06054b50L }
            ?: error("Missing ZIP end-of-central-directory record")

        val centralStart = u32(eocd + 16).toInt()
        val centralSize = u32(eocd + 12).toInt()
        val centralEnd = centralStart.toLong() + centralSize

        require(
            centralStart >= 0 &&
                centralSize >= 0 &&
                centralEnd <= eocd.toLong()
        ) {
            "Invalid fixture central directory"
        }

        var cursor = centralStart

        while (cursor.toLong() < centralEnd) {
            require(cursor.toLong() + 46 <= centralEnd)
            require(u32(cursor) == 0x02014b50L) {
                "Invalid central directory entry"
            }

            val externalAttributes = u32(cursor + 38)
            val unixMode = (externalAttributes ushr 16).toInt()

            if ((unixMode and 0xF000) == 0xA000) {
                return true
            }

            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val commentLength = u16(cursor + 32)

            cursor += 46 + nameLength +
                extraLength + commentLength

            require(cursor.toLong() <= centralEnd)
        }

        require(cursor.toLong() == centralEnd)
        return false
    }

    private fun readManifest(zip: ZipFile): JsonObject {
        val entry = requireNotNull(
            zip.getEntry("manifest.json")
        ) {
            "Fixture manifest missing"
        }

        val text = zip.getInputStream(entry).use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }

        // The committed duplicate-key fixture inserts a
        // second "title" member into the root manifest.
        //
        // This detects that specific fixture mutation.
        // A production parser must detect duplicate keys
        // at every object depth.
        val titleKeys = Regex(
            """(?m)^\s*"title"\s*:"""
        ).findAll(text).count()

        if (titleKeys > 1) {
            reject(Violation.DUPLICATE_MANIFEST_KEY)
        }

        return Json.parseToJsonElement(text).jsonObject
    }

    private fun utcTimestamp(value: String): OffsetDateTime {
        val parsed = OffsetDateTime.parse(value)

        if (parsed.offset != ZoneOffset.UTC) {
            reject(Violation.NON_UTC_TIMESTAMP)
        }

        return parsed
    }

    private fun checkTimestamps(manifest: JsonObject) {
        val created = utcTimestamp(
            manifest.getValue("createdAt")
                .jsonPrimitive.content
        )

        val updated = utcTimestamp(
            manifest.getValue("updatedAt")
                .jsonPrimitive.content
        )

        if (created.toInstant().isAfter(updated.toInstant())) {
            reject(Violation.CREATION_AFTER_UPDATE)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }

    private fun checkAssets(
        zip: ZipFile,
        manifest: JsonObject
    ) {
        val seenIds = mutableSetOf<String>()
        val seenPaths = mutableSetOf<String>()

        val assets = manifest.getValue("assets")
            .jsonArray.map { it.jsonObject }

        // Check all identities before reading asset bytes.
        for (asset in assets) {
            val id = asset.getValue("id")
                .jsonPrimitive.content
                .lowercase(Locale.ROOT)

            val path = asset.getValue("path")
                .jsonPrimitive.content

            if (!seenIds.add(id) || !seenPaths.add(path)) {
                reject(Violation.DUPLICATE_ASSET_ID)
            }
        }

        for (asset in assets) {
            val path = asset.getValue("path")
                .jsonPrimitive.content

            val entry = zip.getEntry(path)
                ?: reject(Violation.MISSING_ASSET)

            // Only committed, small synthetic fixtures
            // are inspected here. Production import needs
            // bounded streaming and decompression limits.
            require(entry.size in 0L..2_000_000L) {
                "Unexpected fixture asset size"
            }

            val bytes = zip.getInputStream(entry).use {
                it.readBytes()
            }

            val expectedLength = asset.getValue("byteLength")
                .jsonPrimitive.long

            if (bytes.size.toLong() != expectedLength) {
                reject(Violation.ASSET_LENGTH_MISMATCH)
            }

            val expectedHash = asset.getValue("sha256")
                .jsonPrimitive.content

            if (!sha256(bytes).equals(
                    expectedHash,
                    ignoreCase = true
                )
            ) {
                reject(Violation.ASSET_HASH_MISMATCH)
            }
        }
    }

    private fun checkArchive(file: File) {
        if (containsUnixSymlink(file)) {
            reject(Violation.ZIP_SYMLINK)
        }

        ZipFile(file).use { zip ->
            val manifest = readManifest(zip)

            checkTimestamps(manifest)
            checkAssets(zip, manifest)
        }
    }

    private val expectedRejections = linkedMapOf(
        "invalid/symlink_member.notebook" to
            Violation.ZIP_SYMLINK,

        "invalid/duplicate_manifest_json_key.notebook" to
            Violation.DUPLICATE_MANIFEST_KEY,

        "invalid/altered_png_bytes.notebook" to
            Violation.ASSET_HASH_MISMATCH,

        "invalid/incorrect_asset_length.notebook" to
            Violation.ASSET_LENGTH_MISMATCH,

        "invalid/missing_asset_member.notebook" to
            Violation.MISSING_ASSET,

        "invalid/duplicate_asset_id.notebook" to
            Violation.DUPLICATE_ASSET_ID,

        "invalid/creation_after_modification.notebook" to
            Violation.CREATION_AFTER_UPDATE,

        "invalid/non_utc_timestamp.notebook" to
            Violation.NON_UTC_TIMESTAMP
    )

    @Test
    fun remainingInvalidFixturesRejectForExpectedReason() {
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
            val error = assertThrows(
                "$path should reject as $expected",
                FixtureRejected::class.java
            ) {
                checkArchive(fixture(path))
            }

            assertEquals(
                "Wrong rejection reason for $path",
                expected,
                error.violation
            )
        }
    }

    @Test
    fun validDocumentsPassIntegrityChecks() {
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
