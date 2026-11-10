package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C2C-3B-I3 compressed-body RED probes. The registered original itself is
 * malformed and its persisted digest deliberately matches its malformed bytes.
 * A valid PNG chunk CRC / present IEND or a JPEG EOI is not full media proof.
 * Do not treat this suite as a claim of exhaustive format validation.
 */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderCompressedBodyAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-body-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-body-$suffix")
        assertTrue("Cannot create isolated asset root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-body-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)
    private fun u8(value: Byte): Int = value.toInt() and 0xff

    private fun realImage(format: Bitmap.CompressFormat): ByteArray {
        val image = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        return try {
            for (y in 0 until 48) for (x in 0 until 64) {
                image.setPixel(x, y, Color.rgb(
                    (x * 19 + y * 7) and 255,
                    (y * 13 + x * 11) and 255,
                    (x * 3 + y * 23) and 255,
                ))
            }
            ByteArrayOutputStream().use { out ->
                assertTrue("Could not encode real image", image.compress(format, 95, out))
                out.toByteArray()
            }
        } finally {
            image.recycle()
        }
    }

    private suspend fun register(bytes: ByteArray, mime: String): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), mime)

    private suspend fun assertValid(bytes: ByteArray, mime: String) {
        val asset = register(bytes, mime)
        val result = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        assertEquals(mime, result.mimeType)
        assertEquals(64, result.width)
        assertEquals(48, result.height)
        assertEquals(asset.sha256, result.sha256)
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    private suspend fun assertRejectsCorruptOriginal(bytes: ByteArray, mime: String, scenario: String) {
        val asset = register(bytes, mime)
        val original = File(root, asset.relativePath)
        assertArrayEquals("Original bytes unchanged", bytes, original.readBytes())
        val snapshot = ReadOnlyFinalizedAssetVerifier(
            root, liveDb().assetDao(), BoundedImageDecodePolicy.MAX_ENCODED_BYTES,
        ).verify(asset.id)
        assertEquals(bytes.size.toLong(), snapshot.byteSize)
        assertEquals(asset.sha256, snapshot.sha256)
        assertEquals(mime, snapshot.mimeType)

        val outcome = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }
        assertTrue(
            "SECURITY GATE: $scenario accepted malformed bytes, or wrong error: " +
                outcome.fold({ "ACCEPTED $it" }, { "${it::class.java.name}: ${it.message}" }),
            outcome.exceptionOrNull() is IOException,
        )
        assertArrayEquals("Original bytes unchanged", bytes, original.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    private data class PngChunk(val offset: Int, val payload: Int, val length: Int, val end: Int)

    private fun idatChunk(bytes: ByteArray, last: Boolean): PngChunk {
        require(bytes.size > 45 && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG")
        var offset = 8
        var result: PngChunk? = null
        while (offset + 12 <= bytes.size) {
            val length = (u8(bytes[offset]).toLong() shl 24) or
                (u8(bytes[offset + 1]).toLong() shl 16) or
                (u8(bytes[offset + 2]).toLong() shl 8) or u8(bytes[offset + 3]).toLong()
            val end = offset.toLong() + 12 + length
            require(length <= Int.MAX_VALUE && end <= bytes.size)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IDAT") {
                val chunk = PngChunk(offset, offset + 8, length.toInt(), end.toInt())
                if (!last) return chunk
                result = chunk
            }
            offset = end.toInt()
        }
        return requireNotNull(result) { "No IDAT found in genuine PNG fixture" }
    }

    /** Rebuild IDAT length and CRC so only its zlib payload is invalid. */
    private fun rewriteIdat(
        png: ByteArray,
        last: Boolean = true,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        val idat = idatChunk(png, last)
        val changed = transform(png.copyOfRange(idat.payload, idat.payload + idat.length))
        require(changed.isNotEmpty())
        val chunk = ByteArrayOutputStream()
        for (shift in intArrayOf(24, 16, 8, 0)) chunk.write(changed.size ushr shift)
        val type = "IDAT".toByteArray(Charsets.US_ASCII)
        chunk.write(type)
        chunk.write(changed)
        val crc = CRC32().apply {
            update(type)
            update(changed)
        }.value
        for (shift in intArrayOf(24, 16, 8, 0)) chunk.write((crc ushr shift).toInt())
        return png.copyOfRange(0, idat.offset) + chunk.toByteArray() +
            png.copyOfRange(idat.end, png.size)
    }

    /** Insert a CRC-correct ancillary chunk before the first IDAT. */
    private fun insertPngAncillaryChunk(png: ByteArray, type: String, payload: ByteArray): ByteArray {
        require(type.length == 4)
        val beforeIdat = idatChunk(png, last = false).offset
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val chunk = ByteArrayOutputStream()
        for (shift in intArrayOf(24, 16, 8, 0)) {
            chunk.write(payload.size ushr shift)
        }
        chunk.write(typeBytes)
        chunk.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value
        for (shift in intArrayOf(24, 16, 8, 0)) {
            chunk.write((crc ushr shift).toInt())
        }
        return png.copyOfRange(0, beforeIdat) + chunk.toByteArray() +
            png.copyOfRange(beforeIdat, png.size)
    }

    private fun jpegScanStart(bytes: ByteArray): Int {
        require(bytes.size > 16 && u8(bytes[0]) == 255 && u8(bytes[1]) == 216)
        var position = 2
        while (position + 4 <= bytes.size) {
            require(u8(bytes[position]) == 255)
            while (position < bytes.size && u8(bytes[position]) == 255) position++
            require(position < bytes.size)
            val marker = u8(bytes[position++])
            require(marker !in 0xd0..0xd9 && marker != 0x01 && marker != 0x00)
            require(position + 2 <= bytes.size)
            val length = (u8(bytes[position]) shl 8) or u8(bytes[position + 1])
            require(length >= 2 && position + length <= bytes.size)
            if (marker == 0xda) return position + length
            position += length
        }
        error("Android-encoded JPEG lacks SOS")
    }

    private fun jpegWithShortenedScan(bytes: ByteArray): ByteArray {
        val start = jpegScanStart(bytes)
        require(u8(bytes[bytes.size - 2]) == 255 && u8(bytes.last()) == 217)
        val scanLength = bytes.size - 2 - start
        require(scanLength > 100)
        return bytes.copyOfRange(0, start + scanLength / 2) +
            byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    private fun jpegWithUnexpectedRestart(bytes: ByteArray): ByteArray {
        val start = jpegScanStart(bytes)
        require(u8(bytes[bytes.size - 2]) == 255 && u8(bytes.last()) == 217)
        // Android's baseline JPEG encoder normally emits no DRI/restart markers.
        // An unannounced RST marker before EOI is invalid even if EOI remains.
        require(!bytes.copyOfRange(2, start).toList().windowed(2).any {
            u8(it[0]) == 255 && u8(it[1]) == 221
        }) { "Fixture unexpectedly contains a DRI marker" }
        return bytes.copyOfRange(0, bytes.size - 2) +
            byteArrayOf(0xff.toByte(), 0xd0.toByte(), 0xff.toByte(), 0xd9.toByte())
    }

    private fun jpegWithInvalidSosSelector(bytes: ByteArray): ByteArray {
        val start = jpegScanStart(bytes)
        // SOS payload: Ns, followed by (component selector, Huffman selector) pairs.
        // Locate SOS by walking segments (not by scanning entropy bytes).
        var p = 2
        while (p < start) {
            require(u8(bytes[p]) == 255)
            p++
            while (u8(bytes[p]) == 255) p++
            val marker = u8(bytes[p++])
            val length = (u8(bytes[p]) shl 8) or u8(bytes[p + 1])
            if (marker == 0xda) {
                require(length >= 8 && u8(bytes[p + 2]) >= 1)
                return bytes.copyOf().also { it[p + 4] = 0xff.toByte() }
            }
            p += length
        }
        error("Missing SOS")
    }

    private fun jpegWithInvalidAcTableSymbol(bytes: ByteArray): ByteArray {
        val changed = bytes.copyOf()
        var p = 2
        while (p + 4 <= changed.size) {
            require(u8(changed[p++]) == 255)
            val marker = u8(changed[p++])
            val length = (u8(changed[p]) shl 8) or u8(changed[p + 1])
            require(length >= 2 && p + length <= changed.size)
            if (marker == 0xc4) {
                var q = p + 2
                val end = p + length
                while (q < end) {
                    require(end - q >= 17)
                    val kind = u8(changed[q++])
                    val symbolCount = (0 until 16).sumOf { u8(changed[q + it]) }
                    q += 16
                    require(symbolCount > 0 && q + symbolCount <= end)
                    if (kind ushr 4 == 1) {
                        // Valid segment length; invalid baseline AC size category 15.
                        changed[q + symbolCount - 1] = 0xff.toByte()
                        return changed
                    }
                    q += symbolCount
                }
            }
            if (marker == 0xda) break
            p += length
        }
        error("No baseline AC Huffman table in Android JPEG fixture")
    }

    private fun jpegWithZeroQuantizationCoefficient(bytes: ByteArray): ByteArray {
        val changed = bytes.copyOf()
        var p = 2
        while (p + 4 <= changed.size) {
            require(u8(changed[p++]) == 255)
            val marker = u8(changed[p++])
            val length = (u8(changed[p]) shl 8) or u8(changed[p + 1])
            require(length >= 2 && p + length <= changed.size)
            if (marker == 0xdb) {
                require(length >= 67)
                changed[p + 3] = 0 // Zero first 8-bit quantization coefficient.
                return changed
            }
            if (marker == 0xda) break
            p += length
        }
        error("No DQT in Android JPEG fixture")
    }

    @Test fun genuinePngCompressedBodyControlPasses() = runBlocking {
        assertValid(realImage(Bitmap.CompressFormat.PNG), "image/png")
    }

    @Test fun genuineJpegEntropyControlPasses() = runBlocking {
        assertValid(realImage(Bitmap.CompressFormat.JPEG), "image/jpeg")
    }

    @Test fun pngBadZlibHeaderWithRepairedCrcRejects() = runBlocking {
        val corrupted = rewriteIdat(realImage(Bitmap.CompressFormat.PNG), last = false) { bytes ->
            require(bytes.size > 8)
            bytes.also { it[0] = 0 }
        }
        assertRejectsCorruptOriginal(corrupted, "image/png", "bad PNG zlib header, valid IDAT CRC")
    }

    @Test fun pngBadZlibAdlerWithRepairedCrcRejects() = runBlocking {
        val corrupted = rewriteIdat(realImage(Bitmap.CompressFormat.PNG)) { bytes ->
            require(bytes.size > 8)
            bytes.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        }
        assertRejectsCorruptOriginal(corrupted, "image/png", "bad PNG zlib Adler-32, valid IDAT CRC")
    }

    @Test fun pngTruncatedZlibTrailerWithRepairedCrcRejects() = runBlocking {
        val corrupted = rewriteIdat(realImage(Bitmap.CompressFormat.PNG)) { bytes ->
            require(bytes.size > 8)
            bytes.copyOf(bytes.size - 2)
        }
        assertRejectsCorruptOriginal(corrupted, "image/png", "truncated PNG zlib trailer, valid IDAT CRC")
    }

    @Test fun pngZlibTrailingBytesInsideIdatWithRepairedCrcRejects() = runBlocking {
        val corrupted = rewriteIdat(realImage(Bitmap.CompressFormat.PNG)) { bytes ->
            bytes + byteArrayOf(0x13, 0x37, 0x42)
        }
        assertRejectsCorruptOriginal(corrupted, "image/png", "trailing PNG zlib bytes, valid IDAT CRC")
    }

    @Test fun pngZeroLengthGammaChunkWithValidCrcRejects() = runBlocking {
        // gAMA has a mandatory four-byte payload; a correct chunk CRC is not sufficient.
        val corrupted = insertPngAncillaryChunk(
            realImage(Bitmap.CompressFormat.PNG), "gAMA", byteArrayOf(),
        )
        assertRejectsCorruptOriginal(corrupted, "image/png", "zero-length PNG gAMA chunk")
    }

    @Test fun pngZeroLengthTransparencyChunkWithValidCrcRejects() = runBlocking {
        // A zero-length tRNS is invalid; tRNS is forbidden entirely for RGBA.
        val corrupted = insertPngAncillaryChunk(
            realImage(Bitmap.CompressFormat.PNG), "tRNS", byteArrayOf(),
        )
        assertRejectsCorruptOriginal(corrupted, "image/png", "zero-length PNG tRNS chunk")
    }

    @Test fun jpegShortenedEntropyButEoiPresentRejects() = runBlocking {
        val corrupted = jpegWithShortenedScan(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(corrupted, "image/jpeg", "JPEG entropy shortened, EOI preserved")
    }

    @Test fun jpegUnexpectedRestartWithoutDriRejects() = runBlocking {
        val corrupted = jpegWithUnexpectedRestart(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(corrupted, "image/jpeg", "JPEG unannounced restart marker")
    }

    @Test fun jpegInvalidUnusedAcTableSymbolRejects() = runBlocking {
        val corrupted = jpegWithInvalidAcTableSymbol(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(corrupted, "image/jpeg", "invalid AC table symbol with matching digest")
    }

    @Test fun jpegZeroQuantizationCoefficientRejects() = runBlocking {
        val corrupted = jpegWithZeroQuantizationCoefficient(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(corrupted, "image/jpeg", "zero DQT coefficient with matching digest")
    }

    @Test fun jpegUnknownSosHuffmanSelectorRejects() = runBlocking {
        val corrupted = jpegWithInvalidSosSelector(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(corrupted, "image/jpeg", "JPEG invalid Huffman selector in SOS")
    }
}
