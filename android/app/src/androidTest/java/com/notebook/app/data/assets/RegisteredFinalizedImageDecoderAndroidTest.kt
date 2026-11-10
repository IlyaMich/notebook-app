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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** I2 proves genuine full Android bitmap decoding; malformed-media gate remains I3. */
@RunWith(AndroidJUnit4::class)
class RegisteredFinalizedImageDecoderAndroidTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var root: File
    private var db: AppDatabase? = null

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "sto08-c2c3b-i2-decoder-$suffix.sqlite"
        root = File(context.filesDir, "sto08-c2c3b-i2-decoder-$suffix")
        assertTrue("Cannot create isolated asset root", root.mkdir())
        db = AppDatabaseProvider.openIsolated(context, databaseName)
    }

    @After fun tearDown() {
        db?.close()
        db = null
        if (::context.isInitialized && ::databaseName.isInitialized) {
            context.deleteDatabase(databaseName)
        }
        if (::root.isInitialized && root.name.startsWith("sto08-c2c3b-i2-decoder-")) {
            root.deleteRecursively()
        }
    }

    private fun liveDb(): AppDatabase = requireNotNull(db)

    private fun realImage(format: Bitmap.CompressFormat, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(38, 102, 189))
            ByteArrayOutputStream().use { output ->
                assertTrue("Unable to encode real image", bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun register(bytes: ByteArray, mime: String): AssetEntity =
        ManagedAssetRegistrar(ManagedAssetFileStore(root), liveDb().assetDao())
            .register(ByteArrayInputStream(bytes), mime)

    private suspend fun checkDecoded(format: Bitmap.CompressFormat, mime: String) {
        val bytes = realImage(format, 7, 5)
        val asset = register(bytes, mime)
        val result = RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        assertEquals(asset.id, result.assetId)
        assertEquals(mime, result.mimeType)
        assertEquals(asset.sha256, result.sha256)
        assertEquals(bytes.size.toLong(), result.encodedBytes)
        assertEquals(7, result.width)
        assertEquals(5, result.height)
        assertEquals(35L, result.pixels)
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals(1, liveDb().assetDao().countAll())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun fullyDecodesRealPngAndReturnsOnlyMetadata() = runBlocking {
        checkDecoded(Bitmap.CompressFormat.PNG, "image/png")
    }

    @Test fun fullyDecodesRealJpegAndReturnsOnlyMetadata() = runBlocking {
        checkDecoded(Bitmap.CompressFormat.JPEG, "image/jpeg")
    }

    @Test fun oversizedIntrinsicWidthRejectsBeforeFullDecodeAndDoesNotWrite() = runBlocking {
        val bytes = realImage(Bitmap.CompressFormat.PNG, 4097, 1)
        val asset = register(bytes, "image/png")
        val failure = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }.exceptionOrNull()
        assertTrue("Expected dimension IOException, got $failure", failure is IOException)
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertArrayEquals(bytes, File(root, asset.relativePath).readBytes())
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
    }

    @Test fun signatureOnlyPngDoesNotPassActualDecode() = runBlocking {
        val signatureOnly = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0,
        )
        val asset = register(signatureOnly, "image/png")
        val failure = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun signatureOnlyJpegDoesNotPassActualDecode() = runBlocking {
        val signatureOnly = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3,
        )
        val asset = register(signatureOnly, "image/jpeg")
        val failure = runCatching {
            RegisteredFinalizedImageDecoder(root, liveDb().assetDao()).inspect(asset.id)
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
        assertEquals(asset, liveDb().assetDao().findById(asset.id))
        assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        assertEquals("released", AssetAcquisitionLeaseCoordinator(root).withLease { "released" })
    }

    @Test fun preCancelledInspectionDoesNotDecodeOrChangeOwnership() {
        val cancelledJob = Job().apply { cancel() }
        val failure = runCatching {
            runBlocking(cancelledJob) {
                RegisteredFinalizedImageDecoder(root, liveDb().assetDao())
                    .inspect("unregistered")
            }
        }.exceptionOrNull()
        assertTrue("Expected cancellation, got $failure", failure is CancellationException)
        runBlocking {
            assertEquals(0, liveDb().assetDao().countAll())
            assertEquals("UNVERIFIED", liveDb().assetOwnershipDao().readFailClosedGateStatus())
        }
    }
}
