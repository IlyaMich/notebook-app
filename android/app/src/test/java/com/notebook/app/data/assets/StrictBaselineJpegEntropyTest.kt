package com.notebook.app.data.assets

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real encoded baseline and progressive controls; no fake JPEG signatures. */
class StrictBaselineJpegEntropyTest {
    private val jpeg = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDA" +
        "sKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAF" +
        "AAcDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhBy" +
        "JxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT" +
        "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAA" +
        "AAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRom" +
        "JygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExc" +
        "bHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDT/wCCXH7MPg7wB+zjqHi3wjd/ZNQtLGD7PcfYo5Nm6aNG+VwV" +
        "OVZhyO9FFFfxcsXXxuPxU8RaclUavKMW7csdLtPQ/kr6SfHnHHDXF+AwuUZpiMNSlg6cnGlWqU4uTrV05OMJJOTUUm7Xsktkj//Z"
    )

    private val gray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PT" +
        "gyPC4zNDL/wAALCAAFAAcBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIh" +
        "MUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhI" +
        "WGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/AOL8" +
        "OaTb7Bx29K//2Q=="
    )

    private val sub2 = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsICQoKCgoKBggLDA" +
        "sKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgr/wAARCAAF" +
        "AAcDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhBy" +
        "JxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKT" +
        "lJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAA" +
        "AAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRom" +
        "JygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExc" +
        "bHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4v/Yt/Z48Ef2dF+7/AOWJ/wCWY9PrRRRXmcRZXl/9rVP3aPpM" +
        "hxmK/syHvs//2Q=="
    )

    private val progressive = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGB" +
        "YUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wgARCAAF" +
        "AAcDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAP/xAAVAQEBAAAAAAAAAAAAAAAAAAAGB//aAAwDAQACEAMQAAABsJyC/8QAFxAAAw" +
        "EAAAAAAAAAAAAAAAAAAwQFBv/aAAgBAQABBQLLzBrzv//EABwRAAICAgMAAAAAAAAAAAAAAAECAwQABhIxQf/aAAgBAwEBPwHZb9utZjSCVlHA" +
        "dEj1s//EABgRAQADAQAAAAAAAAAAAAAAAAIAASES/9oACAECAQE/Ae7bdrdn/8QAGBAAAwEBAAAAAAAAAAAAAAAAAQIRACH/2gAIAQEABj8CZ0" +
        "MYAdm//8QAGBAAAgMAAAAAAAAAAAAAAAAAAAERIUH/2gAIAQEAAT8hrPagepH/2gAMAwEAAgADAAAAEP8A/8QAFxEBAQEBAAAAAAAAAAAAAAAA" +
        "AREAYf/aAAgBAwEBPxBGLRDLKwQsAvDf/8QAGBEBAAMBAAAAAAAAAAAAAAAAAQARMUH/2gAIAQIBAT8Q1CtoPCf/xAAZEAEAAgMAAAAAAAAAAA" +
        "AAAAABABEhMUH/2gAIAQEAAT8Q5nuUtmGzSz//2Q=="
    )

    private fun rejects(bytes: ByteArray) {
        val failure = runCatching { StrictBaselineJpegEntropy.validate(bytes) }.exceptionOrNull()
        assertTrue("Expected IOException; actual=$failure", failure is IOException)
    }

    private fun scanStart(bytes: ByteArray): Int {
        var p = 2
        while (p < bytes.size) {
            require((bytes[p++].toInt() and 255) == 255)
            val marker = bytes[p++].toInt() and 255
            val length = ((bytes[p].toInt() and 255) shl 8) or (bytes[p + 1].toInt() and 255)
            if (marker == 0xda) return p + length
            p += length
        }
        error("No SOS")
    }

    private fun mutateFirstSegment(markerWanted: Int, replacement: (ByteArray, Int, Int) -> Unit): ByteArray {
        val changed = jpeg.copyOf()
        var p = 2
        while (p + 4 <= changed.size) {
            require((changed[p++].toInt() and 255) == 255)
            val marker = changed[p++].toInt() and 255
            val length = ((changed[p].toInt() and 255) shl 8) or (changed[p + 1].toInt() and 255)
            require(length >= 2 && p + length <= changed.size)
            if (marker == markerWanted) {
                replacement(changed, p + 2, p + length)
                return changed
            }
            p += length
        }
        error("Missing requested JPEG marker")
    }

    @Test fun invalidUnusedAcHuffmanSymbolRejects() {
        val changed = jpeg.copyOf()
        var p = 2
        var mutated = false
        while (p + 4 <= changed.size) {
            require((changed[p++].toInt() and 255) == 255)
            val marker = changed[p++].toInt() and 255
            val length = ((changed[p].toInt() and 255) shl 8) or (changed[p + 1].toInt() and 255)
            require(length >= 2 && p + length <= changed.size)
            if (marker == 0xc4 && (changed[p + 2].toInt() and 255) == 0x10) {
                val countsStart = p + 3
                val symbolCount = (0 until 16).sumOf { changed[countsStart + it].toInt() and 255 }
                require(symbolCount > 0)
                val lastSymbol = countsStart + 16 + symbolCount - 1
                require(lastSymbol < p + length)
                changed[lastSymbol] = 0xff.toByte() // Illegal baseline AC category 15.
                mutated = true
                break
            }
            p += length
        }
        require(mutated)
        rejects(changed)
    }

    @Test fun invalidDqtPrecisionRejects() = rejects(
        mutateFirstSegment(0xdb) { bytes, start, _ -> bytes[start] = 0xf0.toByte() },
    )

    @Test fun zeroDqtCoefficientRejects() = rejects(
        mutateFirstSegment(0xdb) { bytes, start, _ -> bytes[start + 1] = 0 },
    )

    @Test fun undefinedSofQuantizationTableRejects() = rejects(
        mutateFirstSegment(0xc0) { bytes, start, _ -> bytes[start + 8] = 3 },
    )

    @Test fun validRealBaselineColorPasses() = StrictBaselineJpegEntropy.validate(jpeg)
    @Test fun validRealBaselineGrayscalePasses() = StrictBaselineJpegEntropy.validate(gray)
    @Test fun validRealSubsampledBaselinePasses() = StrictBaselineJpegEntropy.validate(sub2)

    @Test fun shortenedEntropyWithEoiPreservedRejects() {
        val start = scanStart(jpeg)
        val cut = start + (jpeg.size - 2 - start) / 2
        rejects(jpeg.copyOfRange(0, cut) + byteArrayOf(0xff.toByte(), 0xd9.toByte()))
    }

    @Test fun shortenedSubsampledEntropyWithEoiPreservedRejects() {
        val start = scanStart(sub2)
        val cut = start + (sub2.size - 2 - start) / 2
        rejects(sub2.copyOfRange(0, cut) + byteArrayOf(0xff.toByte(), 0xd9.toByte()))
    }

    @Test fun missingEoiRejects() = rejects(jpeg.copyOf(jpeg.size - 2))
    @Test fun trailingScanBytesRejects() = rejects(jpeg.copyOf(jpeg.size - 2) + byteArrayOf(1, 2, 3, -1, -39))

    @Test fun scanMarkerWithoutDrIRejects() {
        val changed = jpeg.copyOf(jpeg.size - 2) + byteArrayOf(-1, -48, -1, -39)
        rejects(changed)
    }

    @Test fun invalidDhtSymbolRejects() {
        val changed = jpeg.copyOf()
        val start = scanStart(changed)
        // Damage entropy while retaining full marker envelope.
        changed[start] = -1
        rejects(changed)
    }

    @Test fun noMcuDataRejects() {
        rejects(jpeg.copyOfRange(0, scanStart(jpeg)) + byteArrayOf(-1, -39))
    }

    @Test fun progressiveInputIsTemporarilyFailClosedNotAcceptedUnchecked() = rejects(progressive)

    @Test fun checkpointCanAbortValidation() {
        var count = 0
        val thrown = runCatching {
            StrictBaselineJpegEntropy.validate(jpeg) {
                count++
                if (count >= 1) throw IOException("Checkpoint cancellation test")
            }
        }.exceptionOrNull()
        assertTrue("Checkpoint must throw IOException", thrown is IOException)
    }
}
