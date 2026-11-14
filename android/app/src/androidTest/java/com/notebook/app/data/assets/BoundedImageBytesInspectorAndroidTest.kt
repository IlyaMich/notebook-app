package com.notebook.app.data.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Direct exact-byte inspection checks. No managed asset writer, Room, or export staging. */
@RunWith(AndroidJUnit4::class)
class BoundedImageBytesInspectorAndroidTest {
    private lateinit var root: File

    @Before fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "sto08-i4b-2b-inspector-" + UUID.randomUUID())
        assertTrue("Unable to create test-only lease root", root.mkdir())
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            assertTrue("Unable to clean test-only lease root", root.deleteRecursively())
        }
    }

    private fun image(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.rgb(38, 102, 189))
            ByteArrayOutputStream().use { output ->
                assertTrue("Could not compress test image", bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expected(bytes: ByteArray, mime: String = "image/png") = ExpectedEncodedImage(
        assetId = "test-image-id", mimeType = mime, sha256 = sha(bytes),
        encodedBytes = bytes.size.toLong(),
    )

    private suspend fun requireIOException(
        bytes: ByteArray,
        expectation: ExpectedEncodedImage,
    ) {
        val before = bytes.copyOf()
        val outcome = runCatching { BoundedImageBytesInspector.inspect(expectation, bytes) }
        assertTrue("Expected IOException, outcome=$outcome", outcome.exceptionOrNull() is IOException)
        assertArrayEquals("Helper modified caller-owned bytes", before, bytes)
    }

    @Test fun validPngDirectlyInspectsWithoutRoomOrLeaseAndPreservesBytes() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        val before = bytes.copyOf()
        val info = BoundedImageBytesInspector.inspect(expected(bytes), bytes)
        assertEquals("test-image-id", info.assetId)
        assertEquals("image/png", info.mimeType)
        assertEquals(sha(bytes), info.sha256)
        assertEquals(bytes.size.toLong(), info.encodedBytes)
        assertEquals(7, info.width)
        assertEquals(5, info.height)
        assertEquals(35L, info.pixels)
        assertArrayEquals(before, bytes)
    }

    @Test fun baselineJpegDirectlyInspectsAndPreservesBytes() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.JPEG)
        val before = bytes.copyOf()
        val info = BoundedImageBytesInspector.inspect(expected(bytes, "image/jpeg"), bytes)
        assertEquals("image/jpeg", info.mimeType)
        assertEquals(7, info.width)
        assertEquals(5, info.height)
        assertEquals(35L, info.pixels)
        assertEquals(sha(bytes), info.sha256)
        assertArrayEquals(before, bytes)
    }

    @Test fun wrongWellFormedDigestIsRejectedBeforeDecode() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        val digest = sha(bytes)
        val wrong = (if (digest[0] == '0') "1" else "0") + digest.substring(1)
        requireIOException(bytes, expected(bytes).copy(sha256 = wrong))
    }

    @Test fun expectedEncodedLengthMismatchIsRejected() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        requireIOException(bytes, expected(bytes).copy(encodedBytes = bytes.size.toLong() + 1))
    }

    @Test fun invalidIdentityMimeDigestAndLengthAreRejected() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        val tuple = expected(bytes)
        requireIOException(bytes, tuple.copy(assetId = "  "))
        requireIOException(bytes, tuple.copy(mimeType = "image/gif"))
        requireIOException(bytes, tuple.copy(sha256 = "bad-digest"))
        requireIOException(bytes, tuple.copy(sha256 = tuple.sha256.uppercase()))
        requireIOException(bytes, tuple.copy(encodedBytes = 0))
        requireIOException(bytes, tuple.copy(encodedBytes = BoundedImageDecodePolicy.MAX_ENCODED_BYTES + 1))
    }

    @Test fun wrongExpectedMimeIsRejectedEvenWithCorrectBytesAndSha() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        requireIOException(bytes, expected(bytes).copy(mimeType = "image/jpeg"))
    }

    @Test fun signatureOnlyPngAndJpegAreRejectedWithMatchingDigest() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0)
        requireIOException(png, expected(png))
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 3)
        requireIOException(jpeg, expected(jpeg, "image/jpeg"))
    }

    @Test fun preCancelledCoroutineIsPropagated() {
        val bytes = image(Bitmap.CompressFormat.PNG)
        val before = bytes.copyOf()
        val cancelled = Job().apply { cancel() }
        val error = runCatching {
            runBlocking(cancelled) { BoundedImageBytesInspector.inspect(expected(bytes), bytes) }
        }.exceptionOrNull()
        assertTrue("Expected CancellationException, got $error", error is CancellationException)
        assertArrayEquals(before, bytes)
    }

    @Test fun directInspectionUnderAlreadyHeldOuterLeaseDoesNotReacquireIt() = runBlocking {
        val bytes = image(Bitmap.CompressFormat.PNG)
        val before = bytes.copyOf()
        val lease = AssetAcquisitionLeaseCoordinator(root)
        val info = withTimeout(5_000L) {
            lease.withLease { BoundedImageBytesInspector.inspect(expected(bytes), bytes) }
        }
        assertEquals(7, info.width)
        assertEquals(5, info.height)
        assertEquals("released", withTimeout(5_000L) { lease.withLease { "released" } })
        assertArrayEquals(before, bytes)
    }

    @Test fun realPngOverWidthLimitIsRejectedWithoutMutatingBytes() = runBlocking {
        val bitmap = Bitmap.createBitmap(4097, 1, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        requireIOException(bytes, expected(bytes))
    }
}
