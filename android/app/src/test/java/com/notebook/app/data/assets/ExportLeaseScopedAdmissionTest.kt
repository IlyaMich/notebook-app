package com.notebook.app.data.assets

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic sequencing only. Does not open originals, Room, export files, or OS locks. */
class ExportLeaseScopedAdmissionTest {
    private val digest = "ab" + "cd" + "e".repeat(60)
    private val image = SnapshotAssetMetadata(
        "image-1", digest, "image/png", 10L,
        "assets/ab/cd/$digest.png", "finalized",
    )
    private val note = SnapshotNote("note-1", "book-1", "Example", 1, 1, null, 1)
    private val request = SnapshotRequest("note-1", SnapshotLimits(1, 1, 1, 100, 100, 100, 1, 8))
    private val limits = ExportCopyAdmissionLimits(2, 100, 200)

    private fun complete(
        assets: List<SnapshotAssetMetadata> = listOf(image),
        owners: List<SnapshotImageOwner> = listOf(
            SnapshotImageOwner("note-1", "page-1", "element-1", "image-1"),
        ),
        bytes: Long = assets.sumOf { it.encodedBytes },
    ) = SnapshotResult.Complete(note, emptyList(), owners, assets, 0L, bytes)

    private class FakeLease : ExportOuterLease {
        var entered = 0
        var exited = 0
        var held = false
        override suspend fun <T> withLease(action: suspend () -> T): T {
            check(!held) { "nested lease" }
            entered++
            held = true
            return try { action() } finally { held = false; exited++ }
        }
    }

    @Test fun capturesThenAdmitsThenCallsBackInsideOneLease() = runBlocking {
        val lease = FakeLease()
        var captures = 0
        val runner = ExportLeaseScopedAdmission(lease) {
            assertTrue(lease.held)
            captures++
            complete()
        }
        val result = runner.withAdmittedSnapshot(request, limits) { snapshot, plan ->
            assertTrue(lease.held)
            assertEquals(snapshot.assets, plan.images)
            assertEquals(10L, plan.totalEncodedBytes)
            "metadata-only"
        }
        assertEquals("metadata-only", (result as ExportLeaseScopedAdmissionResult.CallbackCompleted<*>).value)
        assertEquals(1, captures)
        assertEquals(1, lease.entered)
        assertEquals(1, lease.exited)
        assertFalse(lease.held)
    }

    @Test fun rejectedCaptureDoesNotRunAdmissionCallback() = runBlocking {
        val lease = FakeLease()
        var called = false
        val runner = ExportLeaseScopedAdmission(lease) {
            assertTrue(lease.held)
            SnapshotResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE)
        }
        val result = runner.withAdmittedSnapshot(request, limits) { _, _ ->
            called = true
        }
        assertEquals(SnapshotRejection.INELIGIBLE_NOTE,
            (result as ExportLeaseScopedAdmissionResult.Rejected).reason)
        assertFalse(called)
        assertEquals(1, lease.exited)
    }

    @Test fun invalidAdmissionPreventsCallbackAndReleasesLease() = runBlocking {
        val lease = FakeLease()
        var called = false
        val runner = ExportLeaseScopedAdmission(lease) {
            complete(assets = listOf(image.copy(sha256 = "bad")))
        }
        val failure = runCatching {
            runner.withAdmittedSnapshot(request, limits) { _, _ -> called = true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(called)
        assertEquals(1, lease.exited)
    }

    @Test fun callbackFailurePropagatesAndReleasesLease() = runBlocking {
        val lease = FakeLease()
        val runner = ExportLeaseScopedAdmission(lease) { complete() }
        val failure = runCatching {
            runner.withAdmittedSnapshot(request, limits) { _, _ ->
                assertTrue(lease.held)
                throw IOException("synthetic callback fault")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(lease.held)
        assertEquals(1, lease.exited)
    }

    @Test fun captureFailurePropagatesAndDoesNotCallCallback() = runBlocking {
        val lease = FakeLease()
        var called = false
        val runner = ExportLeaseScopedAdmission(lease) {
            throw IOException("synthetic capture fault")
        }
        val failure = runCatching {
            runner.withAdmittedSnapshot(request, limits) { _, _ -> called = true }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(called)
        assertEquals(1, lease.exited)
    }

    @Test fun preCancelledCoroutineNeverCallsCaptureOrCallback() {
        val lease = FakeLease()
        var captured = false
        var called = false
        val runner = ExportLeaseScopedAdmission(lease) {
            captured = true
            complete()
        }
        val cancelled = Job().apply { cancel() }
        val failure = runCatching {
            runBlocking(cancelled) {
                runner.withAdmittedSnapshot(request, limits) { _, _ -> called = true }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(captured)
        assertFalse(called)
        assertEquals(lease.entered, lease.exited)
    }

    @Test fun emptyAssetSnapshotAllowsOnlyMetadataCallback() = runBlocking {
        val lease = FakeLease()
        val runner = ExportLeaseScopedAdmission(lease) {
            complete(assets = emptyList(), owners = emptyList(), bytes = 0L)
        }
        val result = runner.withAdmittedSnapshot(request, limits) { _, plan ->
            assertTrue(lease.held)
            assertTrue(plan.images.isEmpty())
            plan.totalEncodedBytes
        }
        assertEquals(0L, (result as ExportLeaseScopedAdmissionResult.CallbackCompleted<*>).value)
        assertEquals(1, lease.exited)
    }

    @Test fun duplicateOwnerEntryRejectsBeforeCallback() = runBlocking {
        val lease = FakeLease()
        val owner = SnapshotImageOwner("note-1", "page-1", "element-1", "image-1")
        val runner = ExportLeaseScopedAdmission(lease) {
            complete(owners = listOf(owner, owner))
        }
        val failure = runCatching {
            runner.withAdmittedSnapshot(request, limits) { _, _ -> fail("callback ran") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, lease.exited)
    }
}
