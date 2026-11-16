package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
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

/** JVM synthetic fixture only; no Room, OS file lease, real image decoder, or export. */
class ExportSyntheticSingleImageCopyCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()

    private val data = ByteArray(8_193) { (it % 251).toByte() }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun asset(id: String = "image-1", bytes: ByteArray = data): SnapshotAssetMetadata {
        val digest = hash(bytes)
        return SnapshotAssetMetadata(id, digest, "image/png", bytes.size.toLong(),
            "assets/${digest.take(2)}/${digest.substring(2, 4)}/$digest.png", "finalized")
    }
    private val note = SnapshotNote("note-1", "book-1", "Sample", 1, 1, null, 1)
    private val request = SnapshotRequest("note-1", SnapshotLimits(2, 2, 2, 20_000L, 40_000L, 200, 1, 8))
    private val limits = ExportCopyAdmissionLimits(2, 20_000L, 40_000L)
    private fun complete(assets: List<SnapshotAssetMetadata> = listOf(asset())): SnapshotResult.Complete =
        SnapshotResult.Complete(note, emptyList(), assets.mapIndexed { i, a ->
            SnapshotImageOwner("note-1", "page-$i", "element-$i", a.id)
        }, assets, 0L, assets.sumOf { it.encodedBytes })

    private class FakeLease : ExportOuterLease {
        var held = false
        var entries = 0
        var exits = 0
        override suspend fun <T> withLease(action: suspend () -> T): T {
            check(!held) { "nested lease" }
            held = true
            entries++
            return try { action() } finally { held = false; exits++ }
        }
    }

    private class FakeBorrower(
        private val lease: FakeLease,
        private val data: ByteArray,
        private val alterTuple: Boolean = false,
        private val fail: Boolean = false,
    ) : ExportSingleImageByteBorrower {
        var calls = 0
        var cleared = false
        override suspend fun <T> withVerifiedSingleImageBytesUnderHeldLease(
            plan: ExportCopyAdmissionPlan,
            action: suspend (ExpectedEncodedImage, ByteArray) -> T,
        ): T {
            assertTrue(lease.held)
            calls++
            if (fail) throw IOException("synthetic source failure")
            val source = plan.images.single()
            val expected = ExpectedEncodedImage(source.id, source.mimeType,
                if (alterTuple) "0".repeat(64) else source.sha256, source.encodedBytes)
            val borrowed = data.copyOf()
            return try { action(expected, borrowed) } finally {
                borrowed.fill(0)
                cleared = borrowed.all { it == 0.toByte() }
            }
        }
    }

    private fun destination(
        directory: File,
        limit: Long = 16_384L,
        chunk: (Long) -> Unit = {},
        after: (File) -> Unit = {},
    ) = ExportUnsealedDestinationCopyProbe(directory, limit, {},
        onChunkWritten = chunk, afterForce = after)

    @Test fun singleAdmittedImageCopiesAndRereadsWithinOneOuterLease() = runBlocking {
        val directory = temp.newFolder("success")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        var captured = false
        val admission = ExportLeaseScopedAdmission(lease) { assertTrue(lease.held); captured = true; complete() }
        val outcome = ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits)
        val value = (outcome as ExportLeaseScopedAdmissionResult.CallbackCompleted).value
        assertTrue(captured)
        assertEquals(hash(data), value.sha256)
        assertEquals(data.size.toLong(), value.byteCount)
        assertArrayEquals(data, File(directory, "image-copy.bin").readBytes())
        assertTrue(borrower.cleared)
        assertEquals(1, borrower.calls)
        assertEquals(1, lease.entries)
        assertEquals(1, lease.exits)
        assertFalse(lease.held)
    }

    @Test fun snapshotRejectionSkipsBorrowAndDestination() = runBlocking {
        val directory = temp.newFolder("rejected")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        val admission = ExportLeaseScopedAdmission(lease) {
            SnapshotResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE)
        }
        val result = ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits)
        assertTrue(result is ExportLeaseScopedAdmissionResult.Rejected)
        assertEquals(0, borrower.calls)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun emptyImageSnapshotRejectedWithoutCreatingDestination() = runBlocking {
        val directory = temp.newFolder("empty")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        val admission = ExportLeaseScopedAdmission(lease) { complete(emptyList()) }
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, borrower.calls)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun multipleImagesRejectedWithoutBorrowing() = runBlocking {
        val directory = temp.newFolder("multiple")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        val admission = ExportLeaseScopedAdmission(lease) { complete(listOf(asset("a"), asset("b"))) }
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, borrower.calls)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun borrowerWrongIdentityRejectedBeforeDestination() = runBlocking {
        val directory = temp.newFolder("wrongidentity")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data, alterTuple = true)
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, borrower.calls)
        assertTrue(borrower.cleared)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun changedBorrowedBytesRejectBeforeDestination() = runBlocking {
        val directory = temp.newFolder("changedbytes")
        val lease = FakeLease()
        val corrupted = data.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val borrower = FakeBorrower(lease, corrupted)
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertTrue(borrower.cleared)
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun sourceFailureReleasesLeaseAndSkipsDestination() = runBlocking {
        val directory = temp.newFolder("sourceerror")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data, fail = true)
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            destination(directory)).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun postForceCorruptionRejectsAndLeavesUntrustedFile() = runBlocking {
        val directory = temp.newFolder("corruptedDest")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val copy = destination(directory, after = { file ->
            val bad = file.readBytes()
            bad[0] = (bad[0].toInt() xor 1).toByte()
            file.writeBytes(bad)
        })
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            copy).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun interruptedWriteLeavesUntrustedPartialAndReleasesLease() = runBlocking {
        val directory = temp.newFolder("interrupted")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val copy = destination(directory, chunk = { if (it >= 8_192L) throw IOException("write fault") })
        val error = runCatching { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
            copy).copy(request, limits) }.exceptionOrNull()
        assertTrue(error is IOException)
        val file = File(directory, "image-copy.bin")
        assertTrue(file.exists())
        assertTrue(file.length() in 1L until data.size.toLong())
        assertEquals(lease.entries, lease.exits)
    }

    @Test fun preCancelledJobDoesNotCaptureOrBorrow() {
        val directory = temp.newFolder("cancelled")
        val lease = FakeLease()
        val borrower = FakeBorrower(lease, data)
        var captured = false
        val admission = ExportLeaseScopedAdmission(lease) { captured = true; complete() }
        val job = Job().apply { cancel() }
        val error = runCatching {
            runBlocking(job) { ExportSyntheticSingleImageCopyCoordinator(admission, borrower,
                destination(directory)).copy(request, limits) }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(captured)
        assertEquals(0, borrower.calls)
        assertFalse(File(directory, "image-copy.bin").exists())
        assertEquals(lease.entries, lease.exits)
    }
}
