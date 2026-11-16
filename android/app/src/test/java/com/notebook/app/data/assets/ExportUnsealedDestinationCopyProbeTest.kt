package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic byte-copy experiment; no Room, managed originals, lease, or export publication. */
class ExportUnsealedDestinationCopyProbeTest {
    @get:Rule val temp = TemporaryFolder()

    private fun root(): File = temp.newFolder("synthetic")
    private fun payload(size: Int = 8_193): ByteArray = ByteArray(size) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun expected(bytes: ByteArray): ExpectedEncodedImage = ExpectedEncodedImage(
        "synthetic-image-id", "image/png", sha(bytes), bytes.size.toLong(),
    )
    private fun writer(root: File, max: Long = 16_384,
        sync: (File) -> Unit = {},
        force: (java.nio.channels.FileChannel) -> Unit = { it.force(true) },
        chunk: (Long) -> Unit = {},
        after: (File) -> Unit = {},
    ) = ExportUnsealedDestinationCopyProbe(root, max, sync, force, chunk, after)

    private fun expectIo(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Expected IOException")
    }

    @Test fun writesAndIndependentlyRereadsExactBytesWithoutMutatingInput() = runBlocking {
        val directory = root()
        val source = payload()
        val before = source.copyOf()
        var syncCount = 0
        val result = writer(directory, sync = { syncCount++ })
            .writeBorrowedBytes(expected(source), source)
        assertEquals(source.size.toLong(), result.byteCount)
        assertEquals(sha(source), result.sha256)
        assertArrayEquals(source, File(directory, "image-copy.bin").readBytes())
        assertArrayEquals(before, source)
        assertEquals(1, syncCount)
        assertFalse(File(directory, "assets").exists())
        assertFalse(File(directory, "seal").exists())
    }

    @Test fun wrongInputDigestRejectedBeforeCreatingFile() = runBlocking {
        val directory = root()
        val source = payload()
        expectIo { runBlocking { writer(directory).writeBorrowedBytes(
            expected(source).copy(sha256 = "0".repeat(64)), source) } }
        assertFalse(File(directory, "image-copy.bin").exists())
    }

    @Test fun wrongLengthRejectedBeforeCreatingFile() = runBlocking {
        val directory = root()
        val source = payload()
        expectIo { runBlocking { writer(directory).writeBorrowedBytes(
            expected(source).copy(encodedBytes = source.size.toLong() + 1), source) } }
        assertFalse(File(directory, "image-copy.bin").exists())
    }

    @Test fun perCopyBudgetRejectsWithoutCreatingFile() = runBlocking {
        val directory = root()
        val source = payload()
        expectIo { runBlocking { writer(directory, max = 8_192).writeBorrowedBytes(expected(source), source) } }
        assertFalse(File(directory, "image-copy.bin").exists())
    }

    @Test fun existingDestinationCannotBeOverwritten() = runBlocking {
        val directory = root()
        val destination = File(directory, "image-copy.bin")
        destination.writeText("sentinel")
        val source = payload()
        expectIo { runBlocking { writer(directory).writeBorrowedBytes(expected(source), source) } }
        assertEquals("sentinel", destination.readText())
    }

    @Test fun midWriteFaultRetainsPartialBytesAndProhibitsRetry() = runBlocking {
        val directory = root()
        val source = payload()
        val copy = writer(directory, chunk = { if (it >= 8_192L) throw IOException("synthetic fault") })
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
        val destination = File(directory, "image-copy.bin")
        assertTrue(destination.exists())
        assertTrue(destination.length() in 1L until source.size.toLong())
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
    }

    @Test fun forcedSyncFailureRetainsUntrustedDestination() = runBlocking {
        val directory = root()
        val source = payload()
        val copy = writer(directory, force = { throw IOException("synthetic force failure") })
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
        assertTrue(File(directory, "image-copy.bin").exists())
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
    }

    @Test fun corruptionAfterForceIsDetectedByIndependentReread() = runBlocking {
        val directory = root()
        val source = payload()
        val copy = writer(directory, after = { file ->
            val bad = file.readBytes()
            bad[0] = (bad[0].toInt() xor 1).toByte()
            file.writeBytes(bad)
        })
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
        assertTrue(File(directory, "image-copy.bin").exists())
        assertArrayEquals(source, payload())
    }

    @Test fun directorySyncFailurePreventsSuccessfulObservation() = runBlocking {
        val directory = root()
        val source = payload()
        val copy = writer(directory, sync = { throw IOException("synthetic directory sync failure") })
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
        assertTrue(File(directory, "image-copy.bin").exists())
    }

    @Test fun preCancelledCoroutineCannotCreateDestination() {
        val directory = root()
        val source = payload()
        val cancelled = Job().apply { cancel() }
        val error = runCatching {
            runBlocking(cancelled) { writer(directory).writeBorrowedBytes(expected(source), source) }
        }.exceptionOrNull()
        assertTrue("Expected CancellationException, got $error", error is CancellationException)
        assertFalse(File(directory, "image-copy.bin").exists())
    }

    @Test fun midWriteCancellationLeavesPartialUntrustedFile() {
        val directory = root()
        val source = payload()
        val job = Job()
        val copy = writer(directory, chunk = { if (it >= 8_192L) job.cancel() })
        val error = runCatching {
            runBlocking(job) { copy.writeBorrowedBytes(expected(source), source) }
        }.exceptionOrNull()
        assertTrue("Expected CancellationException, got $error", error is CancellationException)
        assertTrue(File(directory, "image-copy.bin").exists())
    }

    @Test fun reusedWriterCannotOverwriteFirstCopy() = runBlocking {
        val directory = root()
        val source = payload()
        val copy = writer(directory)
        copy.writeBorrowedBytes(expected(source), source)
        expectIo { runBlocking { copy.writeBorrowedBytes(expected(source), source) } }
        assertArrayEquals(source, File(directory, "image-copy.bin").readBytes())
    }
}
