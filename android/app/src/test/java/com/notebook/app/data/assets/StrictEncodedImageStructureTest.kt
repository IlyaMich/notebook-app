package com.notebook.app.data.assets

import java.io.IOException
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2C-3B-I3 JVM tests of bounded format envelopes. The fixture bytes are real
 * PNG/JPEG encodings; Android instrumentation separately tests BitmapFactory.
 */
class StrictEncodedImageStructureTest {
    private val png = Base64.getDecoder().decode(
        (
            "iVBORw0KGgoAAAANSUhEUgAAAAcAAAAFCAIAAAAG+GGPAAAAGElEQVR4nGNkYGBQleZGQ0yC4syYiHJRAH8aBoBkKMQQAAAA" +
            "AElFTkSuQmCC"
        )
    )

    private val jpeg = Base64.getDecoder().decode(
        (
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsI" +
            "CQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK" +
            "CgoKCgoKCgoKCgoKCgr/wAARCAAFAAcDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
            "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6" +
            "Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG" +
            "x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
            "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5" +
            "OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE" +
            "xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDT/wCCXH7MPg7wB+zjqHi3wjd/ZNQtLGD7" +
            "PcfYo5Nm6aNG+VwVOVZhyO9FFFfxcsXXxuPxU8RaclUavKMW7csdLtPQ/kr6SfHnHHDXF+AwuUZpiMNSlg6cnGlWqU4uTrV0" +
            "5OMJJOTUUm7Xsktkj//Z"
        )
    )

    private val progressive_jpeg = Base64.getDecoder().decode(
        (
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQ" +
            "ERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU" +
            "FBQUFBQUFBQUFBQUFBT/wgARCAAFAAcDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAP/xAAVAQEBAAAAAAAAAAAA" +
            "AAAAAAAGB//aAAwDAQACEAMQAAABsJyC/8QAFxAAAwEAAAAAAAAAAAAAAAAAAwQFBv/aAAgBAQABBQLLzBrzv//EABwRAAIC" +
            "AgMAAAAAAAAAAAAAAAECAwQABhIxQf/aAAgBAwEBPwHZb9utZjSCVlHAdEj1s//EABgRAQADAQAAAAAAAAAAAAAAAAIAASES" +
            "/9oACAECAQE/Ae7bdrdn/8QAGBAAAwEBAAAAAAAAAAAAAAAAAQIRACH/2gAIAQEABj8CZ0MYAdm//8QAGBAAAgMAAAAAAAAA" +
            "AAAAAAAAAAERIUH/2gAIAQEAAT8hrPagepH/2gAMAwEAAgADAAAAEP8A/8QAFxEBAQEBAAAAAAAAAAAAAAAAAREAYf/aAAgB" +
            "AwEBPxBGLRDLKwQsAvDf/8QAGBEBAAMBAAAAAAAAAAAAAAAAAQARMUH/2gAIAQIBAT8Q1CtoPCf/xAAZEAEAAgMAAAAAAAAA" +
            "AAAAAAABABEhMUH/2gAIAQEAAT8Q5nuUtmGzSz//2Q=="
        )
    )

    /** Insert a CRC-correct ancillary chunk immediately before IDAT or IEND. */
    private fun insertPngChunk(png: ByteArray, before: String, type: String, data: ByteArray): ByteArray {
        require(type.length == 4)
        var offset = 8
        while (offset + 12 <= png.size) {
            val length = (0..3).fold(0L) { value, i ->
                (value shl 8) or (png[offset + i].toLong() and 255L)
            }
            require(length <= Int.MAX_VALUE && offset + length + 12L <= png.size)
            if (String(png, offset + 4, 4, Charsets.US_ASCII) == before) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                val crc = CRC32().apply {
                    update(typeBytes)
                    update(data)
                }.value
                val chunk = java.io.ByteArrayOutputStream().apply {
                    for (shift in intArrayOf(24, 16, 8, 0)) write(data.size ushr shift)
                    write(typeBytes)
                    write(data)
                    for (shift in intArrayOf(24, 16, 8, 0)) write((crc ushr shift).toInt())
                }.toByteArray()
                return png.copyOfRange(0, offset) + chunk + png.copyOfRange(offset, png.size)
            }
            offset += (length + 12L).toInt()
        }
        error("PNG fixture missing $before")
    }

    private fun pngWithRgbaHeader(png: ByteArray): ByteArray = png.copyOf().also { changed ->
        require((changed[25].toInt() and 255) == 2)
        changed[25] = 6
        val crc = CRC32().apply { update(changed, 12, 17) }.value
        for ((i, shift) in intArrayOf(24, 16, 8, 0).withIndex()) {
            changed[29 + i] = (crc ushr shift).toByte()
        }
    }

    private fun rejects(mime: String, bytes: ByteArray) {
        val problem = runCatching {
            StrictEncodedImageStructure.validate(mime, bytes)
        }.exceptionOrNull()
        assertTrue("Expected IOException but got $problem", problem is IOException)
    }

    @Test fun validGenuinePngAndBothJpegKindsPass() {
        StrictEncodedImageStructure.validate("image/png", png)
        StrictEncodedImageStructure.validate("image/jpeg", jpeg)
        StrictEncodedImageStructure.validate("image/jpeg", progressive_jpeg)
    }

    @Test fun pngWithoutIendRejects() = rejects("image/png", png.copyOf(png.size - 12))

    @Test fun pngWithTrailingBytesRejects() = rejects("image/png", png + byteArrayOf(1, 2))

    @Test fun pngWithIhdrCrcCorruptionRejects() {
        val changed = png.copyOf()
        changed[29] = (changed[29].toInt() xor 1).toByte()
        rejects("image/png", changed)
    }

    @Test fun pngWithCorruptIdatCrcRejects() {
        val changed = png.copyOf()
        // This fixture has one IDAT, followed immediately by a 12-byte IEND.
        val crcOffset = changed.size - 12 - 4
        changed[crcOffset] = (changed[crcOffset].toInt() xor 1).toByte()
        rejects("image/png", changed)
    }

    @Test fun pngWithExcessiveChunkLengthRejects() {
        val changed = png.copyOf()
        changed[8] = 0x7f
        rejects("image/png", changed)
    }

    @Test fun pngMissingIdatRejects() {
        val iend = png.copyOfRange(png.size - 12, png.size)
        val withoutIdat = png.copyOfRange(0, 33) + iend
        rejects("image/png", withoutIdat)
    }

    @Test fun pngValidGammaAndRgbTransparencyPass() {
        val gamma = byteArrayOf(0, 0, 0xb1.toByte(), 0x8f.toByte()) // 45455
        val withGamma = insertPngChunk(png, "IDAT", "gAMA", gamma)
        val withTransparency = insertPngChunk(
            withGamma, "IDAT", "tRNS", byteArrayOf(0, 0, 0, 0, 0, 0),
        )
        StrictEncodedImageStructure.validate("image/png", withTransparency)
    }

    @Test fun pngEmptyGammaWithValidCrcRejects() {
        rejects("image/png", insertPngChunk(png, "IDAT", "gAMA", byteArrayOf()))
    }

    @Test fun pngEmptyRgbTransparencyWithValidCrcRejects() {
        rejects("image/png", insertPngChunk(png, "IDAT", "tRNS", byteArrayOf()))
    }

    @Test fun pngRgbaTransparencyWithValidCrcRejects() {
        val rgba = pngWithRgbaHeader(png)
        rejects(
            "image/png", insertPngChunk(rgba, "IDAT", "tRNS", byteArrayOf(0, 0, 0, 0, 0, 0)),
        )
    }

    @Test fun pngDuplicateGammaWithValidCrcRejects() {
        val gamma = byteArrayOf(0, 0, 0xb1.toByte(), 0x8f.toByte())
        val first = insertPngChunk(png, "IDAT", "gAMA", gamma)
        rejects("image/png", insertPngChunk(first, "IDAT", "gAMA", gamma))
    }

    @Test fun pngGammaAfterIdatWithValidCrcRejects() {
        rejects(
            "image/png", insertPngChunk(png, "IEND", "gAMA", byteArrayOf(0, 0, 0, 1)),
        )
    }

    @Test fun pngTransparencyAfterIdatWithValidCrcRejects() {
        rejects(
            "image/png", insertPngChunk(png, "IEND", "tRNS", byteArrayOf(0, 0, 0, 0, 0, 0)),
        )
    }

    @Test fun jpegWithoutEoiRejects() = rejects("image/jpeg", jpeg.copyOf(jpeg.size - 2))

    @Test fun jpegWithTrailingBytesRejects() = rejects("image/jpeg", jpeg + byteArrayOf(1, 2))

    @Test fun jpegWithInvalidSegmentLengthRejects() {
        val changed = jpeg.copyOf()
        changed[4] = 0x7f
        changed[5] = 0xff.toByte()
        rejects("image/jpeg", changed)
    }

    @Test fun jpegWithReservedMarkerRejects() {
        val changed = jpeg.copyOf()
        changed[3] = 0x02 // Replace APP0 with an unassigned marker ID.
        rejects("image/jpeg", changed)
    }

    @Test fun progressiveJpegWithoutEoiRejects() = rejects(
        "image/jpeg", progressive_jpeg.copyOf(progressive_jpeg.size - 2),
    )

    @Test fun wrongMimeRejects() {
        rejects("image/jpeg", png)
        rejects("image/png", jpeg)
    }
}
