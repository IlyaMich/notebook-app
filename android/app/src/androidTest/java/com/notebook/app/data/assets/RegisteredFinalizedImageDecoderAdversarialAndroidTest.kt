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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C2C-3B-I3 RED-gate probes: intentionally reject registered, digest-matching
 * corrupt originals. Android's BitmapFactory may accept some of these: failures
 * are evidence that a structural validator must be added before approval.
 * Keep this class out of CI until the fail-closed decoder is implemented.
 */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderAdversarialAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-i3-probe-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-i3-probe-$suffix")
        assertTrue("Unable to create isolated test root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-i3-probe-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    /** Genuine, deterministic, varied pixel data so PNG IDAT and JPEG scan are nontrivial. */
    private fun realImage(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        return try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    bitmap.setPixel(
                        x, y,
                        Color.rgb((x * 19 + y * 7) and 255,
                            (y * 13 + x * 11) and 255,
                            (x * 3 + y * 23) and 255),
                    )
                }
            }
            ByteArrayOutputStream().use { output ->
                assertTrue("Could not create genuine image", bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun register(bytes: ByteArray, mime: String): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), mime)

    private suspend fun assertValid(bytes: ByteArray, mime: String) {
        val asset = register(bytes, mime)
        val decoded = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        assertEquals(asset.id, decoded.assetId)
        assertEquals(mime, decoded.mimeType)
        assertEquals(64, decoded.width)
        assertEquals(48, decoded.height)
        assertEquals(asset.sha256, decoded.sha256)
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    /**
     * The deliberately corrupt image is the registered immutable original itself.
     * All I1 SHA-256 and metadata checks must PASS; only image validity can reject it.
     */
    private suspend fun assertRejectsCorruptOriginal(bytes: ByteArray, mime: String, scenario: String) {
        val asset = register(bytes, mime)
        val original = File(root, asset.relativePath)
        assertArrayEquals("Unexpected registrar byte changes: $scenario", bytes, original.readBytes())
        val snapshot = ReadOnlyFinalizedAssetVerifier(
            root, liveDb().assetDao(), BoundedImageDecodePolicy.MAX_ENCODED_BYTES,
        ).verify(asset.id)
        assertEquals(asset.sha256, snapshot.sha256)
        assertEquals(bytes.size.toLong(), snapshot.byteSize)
        assertEquals(mime, snapshot.mimeType)

        val result = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }
        assertTrue(
            "SECURITY GATE: $scenario was accepted, or rejected with wrong error: " +
                result.fold({ "ACCEPTED $it" }, { "${it::class.java.name}: ${it.message}" }),
            result.exceptionOrNull() is IOException,
        )
        assertArrayEquals("Original mutated after rejection: $scenario", bytes, original.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff

    private fun corruptPngChunkCrc(bytes: ByteArray, requestedType: String): ByteArray {
        val signature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(signature))
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = (unsigned(bytes[offset]).toLong() shl 24) or
                (unsigned(bytes[offset + 1]).toLong() shl 16) or
                (unsigned(bytes[offset + 2]).toLong() shl 8) or
                unsigned(bytes[offset + 3]).toLong()
            val end = offset.toLong() + 12L + length
            require(end <= bytes.size.toLong() && end <= Int.MAX_VALUE)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == requestedType) {
                return bytes.copyOf().also { copy ->
                    val crcStart = (end - 4L).toInt()
                    copy[crcStart] = (copy[crcStart].toInt() xor 1).toByte()
                }
            }
            offset = end.toInt()
        }
        error("Could not locate $requestedType chunk in Android-encoded PNG")
    }

    private fun withoutPngIend(bytes: ByteArray): ByteArray {
        require(bytes.size > 45)
        require(bytes.copyOfRange(bytes.size - 8, bytes.size - 4)
            .contentEquals(byteArrayOf(0x49, 0x45, 0x4e, 0x44)))
        require(bytes.copyOfRange(bytes.size - 12, bytes.size - 8)
            .contentEquals(byteArrayOf(0, 0, 0, 0)))
        return bytes.copyOf(bytes.size - 12)
    }

    private fun withoutJpegEoi(bytes: ByteArray): ByteArray {
        require(bytes.size > 16)
        require(unsigned(bytes[bytes.size - 2]) == 0xff && unsigned(bytes.last()) == 0xd9)
        return bytes.copyOf(bytes.size - 2)
    }

    private fun invalidJpegFirstSegmentLength(bytes: ByteArray): ByteArray {
        require(bytes.size > 12 && bytes.size < 32767)
        require(unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8)
        require(unsigned(bytes[2]) == 0xff)
        // Android's encoder begins with a length-bearing APP/DQT/DHT segment.
        val marker = unsigned(bytes[3])
        require(marker != 0xd8 && marker != 0xd9 && marker != 0xda &&
            marker != 0x01 && marker !in 0xd0..0xd7)
        return bytes.copyOf().also { copy ->
            copy[4] = 0x7f
            copy[5] = 0xff.toByte()
        }
    }

    @Test fun genuinePngControlPasses() = runBlocking {
        assertValid(realImage(Bitmap.CompressFormat.PNG), "image/png")
    }

    @Test fun genuineJpegControlPasses() = runBlocking {
        assertValid(realImage(Bitmap.CompressFormat.JPEG), "image/jpeg")
    }

    @Test fun pngMissingIendRejects() = runBlocking {
        val bytes = withoutPngIend(realImage(Bitmap.CompressFormat.PNG))
        assertRejectsCorruptOriginal(bytes, "image/png", "PNG missing IEND")
    }

    @Test fun pngCorruptIhdrCrcRejects() = runBlocking {
        val bytes = corruptPngChunkCrc(realImage(Bitmap.CompressFormat.PNG), "IHDR")
        assertRejectsCorruptOriginal(bytes, "image/png", "PNG IHDR CRC corruption")
    }

    @Test fun pngCorruptIdatCrcRejects() = runBlocking {
        val bytes = corruptPngChunkCrc(realImage(Bitmap.CompressFormat.PNG), "IDAT")
        assertRejectsCorruptOriginal(bytes, "image/png", "PNG IDAT CRC corruption")
    }

    @Test fun pngTrailingBytesRejects() = runBlocking {
        val bytes = realImage(Bitmap.CompressFormat.PNG) + byteArrayOf(0x13, 0x37, 0x42)
        assertRejectsCorruptOriginal(bytes, "image/png", "PNG bytes after IEND")
    }

    @Test fun jpegMissingEoiRejects() = runBlocking {
        val bytes = withoutJpegEoi(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(bytes, "image/jpeg", "JPEG missing EOI")
    }

    @Test fun jpegTrailingBytesRejects() = runBlocking {
        val bytes = realImage(Bitmap.CompressFormat.JPEG) + byteArrayOf(0x13, 0x37, 0x42)
        assertRejectsCorruptOriginal(bytes, "image/jpeg", "JPEG bytes after EOI")
    }

    @Test fun jpegInvalidFirstSegmentLengthRejects() = runBlocking {
        val bytes = invalidJpegFirstSegmentLength(realImage(Bitmap.CompressFormat.JPEG))
        assertRejectsCorruptOriginal(bytes, "image/jpeg", "JPEG first segment extends beyond EOF")
    }
}
