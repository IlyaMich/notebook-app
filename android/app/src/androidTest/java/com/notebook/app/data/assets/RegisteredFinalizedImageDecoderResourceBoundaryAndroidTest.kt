package com.notebook.app.data.assets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.AppDatabaseProvider
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real, internally consistent PNG boundaries; no attachment writer or owner mutations. */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderResourceBoundaryAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-boundary-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-boundary-$suffix")
        assertTrue("Cannot create boundary-test root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-boundary-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private fun writeU32(out: OutputStream, number: Long) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            out.write(((number ushr shift) and 255L).toInt())
        }
    }

    private fun chunk(out: OutputStream, name: String, payload: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        require(type.size == 4)
        writeU32(out, payload.size.toLong())
        out.write(type)
        out.write(payload)
        val crc = CRC32().apply {
            update(type)
            update(payload)
        }
        writeU32(out, crc.value)
    }

    /** Legal, zero-filled grayscale scanlines; tiny encoded size even for a large raster. */
    private fun validGrayPng(width: Int, height: Int): ByteArray {
        require(width in 1..65535 && height in 1..65535)
        val ihdr = ByteArrayOutputStream().apply {
            writeU32(this, width.toLong())
            writeU32(this, height.toLong())
            write(byteArrayOf(8, 0, 0, 0, 0)) // 8-bit grayscale, no interlace
        }.toByteArray()
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width + 1) // filter 0 plus grayscale samples
            repeat(height) { deflater.write(row) }
        }
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
            chunk(this, "IHDR", ihdr)
            chunk(this, "IDAT", compressed.toByteArray())
            chunk(this, "IEND", ByteArray(0))
        }.toByteArray()
    }

    private suspend fun register(bytes: ByteArray): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), "image/png")

    private fun assertGenuinePngBounds(bytes: ByteArray, width: Int, height: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (bitmap != null) bitmap.recycle()
        assertNull("Bounds-only decode unexpectedly returned a bitmap", bitmap)
        assertEquals("image/png", options.outMimeType)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
    }

    private suspend fun assertRejectedUnchanged(bytes: ByteArray, width: Int, height: Int) {
        assertGenuinePngBounds(bytes, width, height)
        val asset = register(bytes)
        val original = File(root, asset.relativePath)
        val failure = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }.exceptionOrNull()
        assertTrue("Expected policy IOException for ${width}x$height, got $failure", failure is IOException)
        assertArrayEquals("Original changed", bytes, original.readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun validPngAtMaximumWidthWithSmallHeightIsAccepted() = runBlocking {
        val bytes = validGrayPng(4096, 1)
        assertGenuinePngBounds(bytes, 4096, 1)
        val asset = register(bytes)
        val result = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        assertEquals(4096, result.width)
        assertEquals(1, result.height)
        assertEquals(4096L, result.pixels)
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    /** Exercise the exact approved 8,388,608-pixel / 32-MiB ARGB_8888 ceiling on Android. */
    @Test fun validPngAtExactPixelAndBitmapBudgetIsAccepted() = runBlocking {
        val width = 4096
        val height = 2048
        val budget = BoundedImageDecodePolicy.requireIntrinsicDimensions(width, height)
        assertEquals(BoundedImageDecodePolicy.MAX_PIXELS, budget.pixels)
        assertEquals(BoundedImageDecodePolicy.MAX_BITMAP_BYTES, budget.argbBytes)

        // Grayscale PNG is intentionally highly compressible. The decoder still
        // has to allocate and inspect a full-resolution ARGB_8888 bitmap.
        val bytes = validGrayPng(width, height)
        assertGenuinePngBounds(bytes, width, height)
        val asset = register(bytes)
        val result = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)

        assertEquals(asset.id, result.assetId)
        assertEquals("image/png", result.mimeType)
        assertEquals(asset.sha256, result.sha256)
        assertEquals(asset.byteSize, result.encodedBytes)
        assertEquals(width, result.width)
        assertEquals(height, result.height)
        assertEquals(budget.pixels, result.pixels)
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun genuinePngExceedingWidthLimitIsRejected() = runBlocking {
        assertRejectedUnchanged(validGrayPng(4097, 1), 4097, 1)
    }

    @Test fun genuinePngExceedingHeightLimitIsRejected() = runBlocking {
        assertRejectedUnchanged(validGrayPng(1, 4097), 1, 4097)
    }

    @Test fun genuinePngExceedingPixelLimitIsRejected() = runBlocking {
        assertRejectedUnchanged(validGrayPng(4096, 2049), 4096, 2049)
    }

    /** A real PNG original with a large legal *ancillary* chunk, streamed to disk. */
    private fun oversizedValidPng(path: File, size: Long) {
        val tinyPng = validGrayPng(1, 1)
        val headerEnd = 8 + 12 + 13 // PNG signature followed by complete IHDR
        val fillerSize = size - tinyPng.size - 12L
        require(fillerSize in 1..Int.MAX_VALUE.toLong())
        FileOutputStream(path).use { file ->
            file.write(tinyPng, 0, headerEnd)
            writeU32(file, fillerSize)
            val type = "raNd".toByteArray(Charsets.US_ASCII) // unknown ancillary; safe to skip
            file.write(type)
            val crc = CRC32().apply { update(type) }
            val fill = ByteArray(8192) { 'X'.code.toByte() }
            var remaining = fillerSize
            while (remaining > 0) {
                val count = minOf(remaining, fill.size.toLong()).toInt()
                file.write(fill, 0, count)
                crc.update(fill, 0, count)
                remaining -= count.toLong()
            }
            writeU32(file, crc.value)
            file.write(tinyPng, headerEnd, tinyPng.size - headerEnd)
            file.fd.sync()
        }
        assertEquals(size, path.length())
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun realPngOneByteAboveEncodedLimitIsRejected() = runBlocking {
        val size = BoundedImageDecodePolicy.MAX_ENCODED_BYTES + 1L
        val fixture = File(root, "oversize-source.png")
        oversizedValidPng(fixture, size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bmp = BitmapFactory.decodeFile(fixture.absolutePath, bounds)
        if (bmp != null) bmp.recycle()
        assertNull("Bounds-only decode unexpectedly allocated", bmp)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(1, bounds.outWidth)
        assertEquals(1, bounds.outHeight)
        val asset = FileInputStream(fixture).use { stream ->
            ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
                .register(stream, "image/png")
        }
        assertEquals(size, asset.byteSize)
        val finalized = File(root, asset.relativePath)
        assertEquals(asset.sha256, digest(finalized))
        val failure = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }.exceptionOrNull()
        assertTrue("Expected encoded-size IOException, got $failure", failure is IOException)
        assertEquals(size, finalized.length())
        assertEquals(asset.sha256, digest(finalized))
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }
}
