package com.notebook.app.data.assets

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests realistic PNG zlib streams rather than signature-only synthetic headers. */
class StrictPngInflateValidationTest {
    private val signature = byteArrayOf(
        137.toByte(), 80, 78, 71, 13, 10, 26, 10,
    )

    private fun writeU32(out: ByteArrayOutputStream, n: Long) {
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((n ushr shift).toInt() and 255)
    }

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeU32(out, payload.size.toLong())
        val label = type.toByteArray(Charsets.US_ASCII)
        out.write(label)
        out.write(payload)
        val crc = CRC32().apply { update(label); update(payload) }
        writeU32(out, crc.value)
        return out.toByteArray()
    }

    private fun compress(raw: ByteArray): ByteArray {
        val deflater = Deflater(6, false)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(128)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                assertTrue("Deflater failed to progress", n > 0)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun png(zlib: ByteArray, splitIdat: Boolean = false): ByteArray {
        val ihdr = ByteArrayOutputStream().apply {
            writeU32(this, 3L)
            writeU32(this, 2L)
            write(byteArrayOf(8, 6, 0, 0, 0)) // RGBA8, no interlace
        }.toByteArray()
        val imageData = if (splitIdat) {
            val middle = zlib.size / 2
            chunk("IDAT", zlib.copyOfRange(0, middle)) +
                chunk("IDAT", zlib.copyOfRange(middle, zlib.size))
        } else {
            chunk("IDAT", zlib)
        }
        return signature + chunk("IHDR", ihdr) + imageData + chunk("IEND", byteArrayOf())
    }

    private fun raw(): ByteArray = ByteArray((3 * 4 + 1) * 2) // filter 0 for each row
    private fun valid(): ByteArray = compress(raw())

    private fun assertRejects(bytes: ByteArray) {
        val failure = runCatching {
            StrictEncodedImageStructure.validate("image/png", bytes)
            StrictPngInflateValidation.validate(bytes)
        }.exceptionOrNull()
        assertTrue("Expected checked invalid-media rejection, got $failure", failure is IOException)
    }

    @Test fun acceptsGenuineDeflateWithOneIdat() {
        val bytes = png(valid())
        StrictEncodedImageStructure.validate("image/png", bytes)
        StrictPngInflateValidation.validate(bytes)
    }

    @Test fun acceptsValidStreamSplitAcrossIdatChunks() {
        val bytes = png(valid(), splitIdat = true)
        StrictEncodedImageStructure.validate("image/png", bytes)
        StrictPngInflateValidation.validate(bytes)
    }

    @Test fun pngInflateCheckpointPropagatesCancellation() {
        val bytes = png(valid())
        StrictEncodedImageStructure.validate("image/png", bytes)
        var checkpoints = 0
        val failure = runCatching {
            StrictPngInflateValidation.validate(bytes) {
                checkpoints++
                // Initial checkpoint + IHDR, IDAT and IEND chunk checkpoints.
                // The next invocation must occur inside the inflater's try/finally.
                if (checkpoints == 5) throw CancellationException("cancel during PNG inflate")
            }
        }.exceptionOrNull()
        assertTrue("Expected cancellation inside inflate, got $failure", failure is CancellationException)
        assertTrue("Inflate checkpoint was not reached", checkpoints >= 5)
        // A cancellation does not poison future independent inspections.
        StrictPngInflateValidation.validate(bytes)
    }

    @Test fun rejectsBadZlibHeaderWithValidPngCrc() {
        val zlib = valid().also { it[0] = 0 }
        assertRejects(png(zlib))
    }

    @Test fun rejectsBadAdler32WithValidPngCrc() {
        val zlib = valid().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertRejects(png(zlib))
    }

    @Test fun rejectsMissingZlibTrailerWithValidPngCrc() {
        val zlib = valid().let { it.copyOf(it.size - 2) }
        assertRejects(png(zlib))
    }

    @Test fun rejectsTrailingZlibBytesInsideValidIdat() {
        assertRejects(png(valid() + byteArrayOf(0x13, 0x37, 0x42)))
    }

    @Test fun rejectsTooFewScanlineBytesWithValidAdler32() {
        assertRejects(png(compress(raw().copyOf(raw().size - 4))))
    }

    @Test fun rejectsTooManyScanlineBytesWithValidAdler32() {
        assertRejects(png(compress(raw() + byteArrayOf(1, 2, 3, 4))))
    }

    @Test fun rejectsInvalidPngFilterWithValidAdler32() {
        val invalid = raw().also { it[0] = 5 }
        assertRejects(png(compress(invalid)))
    }
}
