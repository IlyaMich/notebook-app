package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android fixture integration: real original bytes, strict native decoding, real OS lease,
 * and isolated UNSEALED destination bytes. NOT real Room/export/publication/durability.
 * All files live under a unique test-only private directory.
 */
@RunWith(AndroidJUnit4::class)
class ExportSyntheticSingleImageCopyAndroidTest {
    private lateinit var root: File

    @Before fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "sto08-i4b2c3b-android-${UUID.randomUUID()}")
        assertTrue("Unable to create test-only root", root.mkdir())
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            assertTrue("Unable to clean test-only directory", root.deleteRecursively())
        }
    }

    private class FakeDao(private val asset: AssetEntity?) : AssetDao {
        override suspend fun insert(asset: AssetEntity) { error("Test must never write an asset row") }
        override suspend fun findById(id: String): AssetEntity? = asset?.takeIf { it.id == id }
        override suspend fun findBySha256(sha256: String): AssetEntity? = null
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? = null
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> = emptyList()
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> = emptyList()
        override suspend fun countAll(): Int = if (asset == null) 0 else 1
    }

    private data class Fixture(val row: AssetEntity, val path: File, val bytes: ByteArray)

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fixture(format: Bitmap.CompressFormat, noisy: Boolean = false): Fixture {
        val width = if (noisy) 96 else 7
        val height = if (noisy) 96 else 5
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val encoded = try {
            if (noisy) {
                val random = Random(7123L)
                val pixels = IntArray(width * height) { Color.rgb(
                    random.nextInt(256), random.nextInt(256), random.nextInt(256),
                ) }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } else {
                bitmap.eraseColor(Color.rgb(39, 107, 193))
            }
            ByteArrayOutputStream().use { output ->
                assertTrue("Image compression failed", bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        if (noisy) assertTrue("Expected multi-chunk encoded fixture", encoded.size > 8192)
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val digest = hash(encoded)
        val relative = "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.$extension"
        val file = File(root, relative)
        assertTrue("Cannot create test-only sharded directory", file.parentFile!!.mkdirs())
        file.writeBytes(encoded)
        return Fixture(AssetEntity("image-fixture", digest, mime, encoded.size.toLong(),
            relative, "finalized"), file, encoded)
    }

    private fun snapshot(f: Fixture) = SnapshotResult.Complete(
        note = SnapshotNote("note-fixture", "notebook-fixture", "Fixture", 1, 1, null, 1),
        pages = emptyList(),
        imageOwners = listOf(SnapshotImageOwner("note-fixture", "page-fixture", "element-fixture", f.row.id)),
        assets = listOf(SnapshotAssetMetadata(f.row.id, f.row.sha256, f.row.mimeType,
            f.row.byteSize, f.row.relativePath, f.row.state)),
        canonicalJsonBytes = 0,
        totalEncodedSourceBytes = f.row.byteSize,
    )

    private val request = SnapshotRequest("note-fixture", SnapshotLimits(1, 1, 1,
        BoundedImageDecodePolicy.MAX_ENCODED_BYTES, BoundedImageDecodePolicy.MAX_ENCODED_BYTES,
        200, 1, 8))
    private val limits = ExportCopyAdmissionLimits(1, BoundedImageDecodePolicy.MAX_ENCODED_BYTES,
        BoundedImageDecodePolicy.MAX_ENCODED_BYTES)

    private fun stage(): File = File(root, "isolated-stage").also {
        assertTrue("Cannot create test-only stage", it.mkdir())
    }

    private fun coordinator(
        f: Fixture,
        stage: File,
        dao: AssetDao = FakeDao(f.row),
        onChunkWritten: (Long) -> Unit = {},
        afterForce: (File) -> Unit = {},
    ): Pair<ExportSyntheticSingleImageCopyCoordinator, AssetAcquisitionLeaseCoordinator> {
        val lease = AssetAcquisitionLeaseCoordinator(root)
        val outer = object : ExportOuterLease {
            override suspend fun <T> withLease(action: suspend () -> T): T = lease.withLease(action)
        }
        val admission = ExportLeaseScopedAdmission(outer) { snapshot(f) }
        val borrower = ExportLeasedSourceByteInspector(root, dao)
        val destination = ExportUnsealedDestinationCopyProbe(stage,
            BoundedImageDecodePolicy.MAX_ENCODED_BYTES, syncDirectory = {},
            onChunkWritten = onChunkWritten, afterForce = afterForce)
        return Pair(ExportSyntheticSingleImageCopyCoordinator(admission, borrower, destination), lease)
    }

    private suspend fun verifyLeaseReleased(lease: AssetAcquisitionLeaseCoordinator) {
        assertEquals("released", withTimeout(5_000L) { lease.withLease { "released" } })
    }

    @Test fun realPngCopiesIntoUnsealedStageAndPreservesOriginal() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.PNG)
        val stage = stage()
        val (copy, lease) = coordinator(f, stage)
        val value = withTimeout(20_000L) { copy.copy(request, limits) }
        val result = (value as ExportLeaseScopedAdmissionResult.CallbackCompleted).value
        assertEquals(f.row.sha256, result.sha256)
        assertEquals(f.row.byteSize, result.byteCount)
        assertArrayEquals(f.bytes, File(stage, "image-copy.bin").readBytes())
        assertArrayEquals(f.bytes, f.path.readBytes())
        verifyLeaseReleased(lease)
        assertFalse(File(stage, "seal").exists())
    }

    @Test fun realJpegCopiesIntoUnsealedStageAndPreservesOriginal() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.JPEG)
        val stage = stage()
        val (copy, lease) = coordinator(f, stage)
        val result = (withTimeout(20_000L) { copy.copy(request, limits) }
            as ExportLeaseScopedAdmissionResult.CallbackCompleted).value
        assertEquals(f.row.sha256, result.sha256)
        assertArrayEquals(f.bytes, File(stage, "image-copy.bin").readBytes())
        assertArrayEquals(f.bytes, f.path.readBytes())
        verifyLeaseReleased(lease)
    }

    @Test fun corruptedOriginalRejectsBeforeCreatingDestination() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.PNG)
        val stage = stage()
        val altered = f.bytes.reversedArray()
        f.path.writeBytes(altered)
        val (copy, lease) = coordinator(f, stage)
        val failure = runCatching { withTimeout(20_000L) { copy.copy(request, limits) } }.exceptionOrNull()
        assertTrue("Expected fail-closed IOException, got $failure", failure is IOException)
        assertFalse(File(stage, "image-copy.bin").exists())
        assertArrayEquals(altered, f.path.readBytes())
        verifyLeaseReleased(lease)
    }

    @Test fun changedRegisteredMetadataRejectsBeforeDestination() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.PNG)
        val stage = stage()
        val (copy, lease) = coordinator(f, stage, dao = FakeDao(f.row.copy(state = "staged")))
        val failure = runCatching { withTimeout(20_000L) { copy.copy(request, limits) } }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
        assertFalse(File(stage, "image-copy.bin").exists())
        assertArrayEquals(f.bytes, f.path.readBytes())
        verifyLeaseReleased(lease)
    }

    @Test fun destinationCorruptionRejectsAndLeavesUntrustedFile() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.PNG)
        val stage = stage()
        val (copy, lease) = coordinator(f, stage, afterForce = { file ->
            val bytes = file.readBytes()
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            file.writeBytes(bytes)
        })
        val failure = runCatching { withTimeout(20_000L) { copy.copy(request, limits) } }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
        assertTrue(File(stage, "image-copy.bin").exists())
        assertArrayEquals(f.bytes, f.path.readBytes())
        verifyLeaseReleased(lease)
    }

    @Test fun interruptedWriteRetainsUntrustedPartialAndReleasesLease() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.PNG, noisy = true)
        val stage = stage()
        val (copy, lease) = coordinator(f, stage,
            onChunkWritten = { count -> if (count >= 8192L) throw IOException("test-only write fault") })
        val failure = runCatching { withTimeout(20_000L) { copy.copy(request, limits) } }.exceptionOrNull()
        assertTrue("Expected IOException, got $failure", failure is IOException)
        val destination = File(stage, "image-copy.bin")
        assertTrue(destination.exists())
        assertTrue(destination.length() in 1L until f.bytes.size.toLong())
        assertArrayEquals(f.bytes, f.path.readBytes())
        verifyLeaseReleased(lease)
    }
}
