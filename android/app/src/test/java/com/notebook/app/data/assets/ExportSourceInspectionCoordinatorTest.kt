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

/** Synthetic sequencing only: no OS lease, Room, disk originals, native decode, or export. */
class ExportSourceInspectionCoordinatorTest {
    private val hash = "ab" + "cd" + "e".repeat(60)
    private val asset = SnapshotAssetMetadata("image-1", hash, "image/png", 10L,
        "assets/ab/cd/$hash.png", "finalized")
    private val owner = SnapshotImageOwner("note-1", "page-1", "element-1", "image-1")
    private val note = SnapshotNote("note-1", "book-1", "Sample", 1, 1, null, 1)
    private val request = SnapshotRequest("note-1", SnapshotLimits(1, 1, 1, 100, 100, 100, 1, 8))
    private val limits = ExportCopyAdmissionLimits(2, 100, 200)
    private fun complete(
        assets: List<SnapshotAssetMetadata> = listOf(asset),
        owners: List<SnapshotImageOwner> = listOf(owner),
        total: Long = assets.sumOf { it.encodedBytes },
    ) = SnapshotResult.Complete(note, emptyList(), owners, assets, 0L, total)
    private fun observed(plan: ExportCopyAdmissionPlan): ExportVerifiedSourceObservation =
        ExportVerifiedSourceObservation(plan.images.map {
            VerifiedDecodedImageInfo(it.id, it.mimeType, it.sha256, it.encodedBytes, 1, 1, 1)
        }, plan.totalEncodedBytes)

    private class FakeLease : ExportOuterLease {
        var held = false
        var enters = 0
        var exits = 0
        override suspend fun <T> withLease(action: suspend () -> T): T {
            check(!held) { "Nested lease" }
            held = true
            enters++
            return try { action() } finally { held = false; exits++ }
        }
    }

    @Test fun sourceCheckRunsAfterCaptureAndAdmissionInsideOneLease() = runBlocking {
        val lease = FakeLease()
        val events = mutableListOf<String>()
        val admission = ExportLeaseScopedAdmission(lease) {
            assertTrue(lease.held)
            events += "capture"
            complete()
        }
        val coordinator = ExportSourceInspectionCoordinator(admission) { plan ->
            assertTrue(lease.held)
            events += "inspect"
            assertEquals(10L, plan.totalEncodedBytes)
            observed(plan)
        }
        val outcome = coordinator.inspect(request, limits)
        val value = (outcome as ExportLeaseScopedAdmissionResult.CallbackCompleted).value
        assertEquals(10L, value.totalEncodedBytes)
        assertEquals(listOf("capture", "inspect"), events)
        assertEquals(1, lease.enters)
        assertEquals(1, lease.exits)
        assertFalse(lease.held)
    }

    @Test fun rejectedCaptureNeverChecksSourceBytes() = runBlocking {
        val lease = FakeLease()
        val admission = ExportLeaseScopedAdmission(lease) {
            SnapshotResult.Rejected(SnapshotRejection.INELIGIBLE_NOTE)
        }
        val coordinator = ExportSourceInspectionCoordinator(admission) { throw AssertionError("source check ran") }
        val outcome = coordinator.inspect(request, limits)
        assertEquals(SnapshotRejection.INELIGIBLE_NOTE,
            (outcome as ExportLeaseScopedAdmissionResult.Rejected).reason)
        assertEquals(1, lease.exits)
    }

    @Test fun invalidSourceAdmissionNeverChecksSourceBytes() = runBlocking {
        val lease = FakeLease()
        var checked = false
        val admission = ExportLeaseScopedAdmission(lease) {
            complete(assets = listOf(asset.copy(sha256 = "bad")))
        }
        val coordinator = ExportSourceInspectionCoordinator(admission) {
            checked = true
            observed(it)
        }
        val failure = runCatching { coordinator.inspect(request, limits) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(checked)
        assertEquals(1, lease.exits)
    }

    @Test fun sourceVerificationFailurePropagatesAndReleasesLease() = runBlocking {
        val lease = FakeLease()
        val admission = ExportLeaseScopedAdmission(lease) { complete() }
        val coordinator = ExportSourceInspectionCoordinator(admission) {
            assertTrue(lease.held)
            throw IOException("synthetic digest failure")
        }
        val failure = runCatching { coordinator.inspect(request, limits) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(lease.held)
        assertEquals(1, lease.exits)
    }

    @Test fun emptyAssetsHaveZeroVerifiedSourceBytes() = runBlocking {
        val lease = FakeLease()
        val admission = ExportLeaseScopedAdmission(lease) {
            complete(emptyList(), emptyList(), 0L)
        }
        val coordinator = ExportSourceInspectionCoordinator(admission) { observed(it) }
        val value = (coordinator.inspect(request, limits)
            as ExportLeaseScopedAdmissionResult.CallbackCompleted).value
        assertTrue(value.images.isEmpty())
        assertEquals(0L, value.totalEncodedBytes)
        assertEquals(1, lease.exits)
    }

    @Test fun preCancelledJobNeverReachesSourceInspection() {
        val lease = FakeLease()
        var captureCalled = false
        var inspectCalled = false
        val admission = ExportLeaseScopedAdmission(lease) {
            captureCalled = true
            complete()
        }
        val coordinator = ExportSourceInspectionCoordinator(admission) {
            inspectCalled = true
            observed(it)
        }
        val cancelled = Job().apply { cancel() }
        val failure = runCatching {
            runBlocking(cancelled) { coordinator.inspect(request, limits) }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(captureCalled)
        assertFalse(inspectCalled)
        assertEquals(lease.enters, lease.exits)
    }
}
