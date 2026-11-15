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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real app-private fixture bytes and real shared OS lease, but never the managed app asset tree. */
@RunWith(AndroidJUnit4::class)
class ExportLeasedSourceByteInspectorAndroidTest {
    private lateinit var root: File

    @Before fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "sto08-i4b2c2b-realfile-" + UUID.randomUUID())
        assertTrue("Cannot create isolated test root", root.mkdir())
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            assertTrue("Cannot clean isolated test root", root.deleteRecursively())
        }
    }

    private class FakeDao(var row: AssetEntity?) : AssetDao {
        var reads = 0
        var replaceAfterFirstRead: AssetEntity? = null
        override suspend fun insert(asset: AssetEntity) { error("Never write assets in this test") }
        override suspend fun findById(id: String): AssetEntity? {
            reads++
            return if (reads > 1 && replaceAfterFirstRead != null) replaceAfterFirstRead
                else row?.takeIf { it.id == id }
        }
        override suspend fun findBySha256(sha256: String): AssetEntity? = null
        override suspend fun findByRelativePath(relativePath: String): AssetEntity? = null
        override suspend fun listByState(state: String, limit: Int): List<AssetEntity> = emptyList()
        override suspend fun listAfterId(afterId: String?, limit: Int): List<AssetEntity> = emptyList()
        override suspend fun countAll(): Int = if (row == null) 0 else 1
    }

    private data class Fixture(val row: AssetEntity, val path: File, val bytes: ByteArray)

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(39, 107, 193))
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun fixture(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): Fixture {
        val bytes = encoded(format)
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val hash = sha(bytes)
        val relative = "assets/${hash.take(2)}/${hash.substring(2, 4)}/$hash.$extension"
        val path = File(root, relative)
        assertTrue("Cannot create sharded test asset directory", path.parentFile!!.mkdirs())
        path.writeBytes(bytes)
        return Fixture(AssetEntity("test-image-id", hash, mime, bytes.size.toLong(),
            relative, "finalized"), path, bytes)
    }

    private fun plan(row: AssetEntity, total: Long = row.byteSize): ExportCopyAdmissionPlan =
        ExportCopyAdmissionPlan(listOf(SnapshotAssetMetadata(row.id, row.sha256,
            row.mimeType, row.byteSize, row.relativePath, row.state)), total)

    private suspend fun inspect(row: AssetEntity, dao: AssetDao = FakeDao(row),
        plan: ExportCopyAdmissionPlan = plan(row)): ExportVerifiedSourceObservation {
        val lease = AssetAcquisitionLeaseCoordinator(root)
        return withTimeout(15_000L) {
            lease.withLease {
                ExportLeasedSourceByteInspector(root, dao).inspectUnderHeldLease(plan)
            }
        }
    }

    private suspend fun assertIoAndLeaseReleased(
        row: AssetEntity, dao: AssetDao, plan: ExportCopyAdmissionPlan = plan(row)
    ) {
        val lease = AssetAcquisitionLeaseCoordinator(root)
        val caught = runCatching {
            withTimeout(15_000L) {
                lease.withLease {
                    ExportLeasedSourceByteInspector(root, dao).inspectUnderHeldLease(plan)
                }
            }
        }.exceptionOrNull()
        assertTrue("Expected IOException, got $caught", caught is IOException)
        assertEquals("lease released", withTimeout(5_000L) {
            lease.withLease { "lease released" }
        })
    }

    @Test fun validPngRealBytesUnderOneLeaseAndOriginalUnchanged() = runBlocking {
        val f = fixture()
        val before = f.path.readBytes()
        val observation = inspect(f.row)
        assertEquals(1, observation.images.size)
        assertEquals(f.row.sha256, observation.images.single().sha256)
        assertEquals(f.row.byteSize, observation.totalEncodedBytes)
        assertEquals(7, observation.images.single().width)
        assertEquals(5, observation.images.single().height)
        assertArrayEquals(before, f.path.readBytes())
    }

    @Test fun validJpegRealBytesUnderOneLeaseAndOriginalUnchanged() = runBlocking {
        val f = fixture(Bitmap.CompressFormat.JPEG)
        val observation = inspect(f.row)
        assertEquals("image/jpeg", observation.images.single().mimeType)
        assertEquals(f.row.sha256, observation.images.single().sha256)
        assertEquals(35L, observation.images.single().pixels)
        assertArrayEquals(f.bytes, f.path.readBytes())
    }

    @Test fun corruptedOriginalIsRejectedAndLeaseReleased() = runBlocking {
        val f = fixture()
        f.path.writeBytes(f.bytes.reversedArray())
        assertIoAndLeaseReleased(f.row, FakeDao(f.row))
    }

    @Test fun changedRoomMetadataIsRejectedAndLeaseReleased() = runBlocking {
        val f = fixture()
        val dao = FakeDao(f.row)
        dao.replaceAfterFirstRead = f.row.copy(state = "staged")
        assertIoAndLeaseReleased(f.row, dao)
        assertTrue(dao.reads >= 2)
    }

    @Test fun missingOriginalFailsClosedAndReleasesLease() = runBlocking {
        val f = fixture()
        assertTrue(f.path.delete())
        assertIoAndLeaseReleased(f.row, FakeDao(f.row))
    }

    @Test fun alteredAdmittedDigestCannotReadValidOriginal() = runBlocking {
        val f = fixture()
        val wrongHash = "0".repeat(64)
        val bad = f.row.copy(sha256 = wrongHash,
            relativePath = "assets/00/00/$wrongHash.png")
        assertIoAndLeaseReleased(bad, FakeDao(f.row))
        assertArrayEquals(f.bytes, f.path.readBytes())
    }

    @Test fun mismatchedAggregateByteTotalIsRejected() = runBlocking {
        val f = fixture()
        assertIoAndLeaseReleased(f.row, FakeDao(f.row), plan(f.row, f.row.byteSize + 1))
    }

    @Test fun preCancelledInspectionDoesNotAlterOriginal() {
        val f = fixture()
        val job = Job().apply { cancel() }
        val failure = runCatching {
            runBlocking(job) {
                inspect(f.row)
            }
        }.exceptionOrNull()
        assertTrue("Expected cancellation, got $failure", failure is CancellationException)
        assertArrayEquals(f.bytes, f.path.readBytes())
    }
}
