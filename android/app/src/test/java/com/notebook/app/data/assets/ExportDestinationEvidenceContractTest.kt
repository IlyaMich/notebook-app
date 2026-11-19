package com.notebook.app.data.assets

import com.notebook.app.data.assets.ExportDestinationEvidenceContract as Contract
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM assertions; no paths, Room, assets, Android runtime, or export writes. */
class ExportDestinationEvidenceContractTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun goodClaim() = Contract.Claim(
        operationId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        expectedSnapshotSha256 = a,
        observedSnapshotSha256 = a,
        expectedManifestSha256 = b,
        observedManifestSha256 = b,
        expectedCopies = listOf(Contract.ExpectedCopy("asset-1", 3L, c)),
        observedCopies = listOf(Contract.ObservedCopy("asset-1", 3L, c, true, true)),
        snapshotFileForceClaimed = true,
        manifestFileForceClaimed = true,
        stageDirectorySyncClaimed = true,
    )

    private fun rejected(claim: Contract.Claim, reason: Contract.Reason) {
        assertEquals(Contract.Assessment.Rejected(reason), Contract.assess(claim))
    }

    @Test fun matchingObservationsRemainExplicitlyUntrusted() {
        assertEquals(Contract.Assessment.CompleteUntrusted, Contract.assess(goodClaim()))
    }

    @Test fun missingCopyFailsClosed() {
        rejected(goodClaim().copy(observedCopies = emptyList()), Contract.Reason.INCOMPLETE_COPY_SET)
    }

    @Test fun wrongCopyDigestFailsClosed() {
        rejected(goodClaim().copy(observedCopies = listOf(
            Contract.ObservedCopy("asset-1", 3L, b, true, true),
        )), Contract.Reason.BYTES_MISMATCH)
    }

    @Test fun wrongCopyLengthFailsClosed() {
        rejected(goodClaim().copy(observedCopies = listOf(
            Contract.ObservedCopy("asset-1", 4L, c, true, true),
        )), Contract.Reason.BYTES_MISMATCH)
    }

    @Test fun manifestMismatchFailsClosed() {
        rejected(goodClaim().copy(observedManifestSha256 = c), Contract.Reason.BYTES_MISMATCH)
    }

    @Test fun snapshotMissingFailsClosed() {
        rejected(goodClaim().copy(observedSnapshotSha256 = null), Contract.Reason.BYTES_MISMATCH)
    }

    @Test fun missingStageSyncClaimFailsClosed() {
        rejected(goodClaim().copy(stageDirectorySyncClaimed = false), Contract.Reason.INCOMPLETE_SYNC_CLAIMS)
    }

    @Test fun missingCopyForceClaimFailsClosed() {
        rejected(goodClaim().copy(observedCopies = listOf(
            Contract.ObservedCopy("asset-1", 3L, c, false, true),
        )), Contract.Reason.INCOMPLETE_SYNC_CLAIMS)
    }

    @Test fun missingManifestForceClaimFailsClosed() {
        rejected(goodClaim().copy(manifestFileForceClaimed = false), Contract.Reason.INCOMPLETE_SYNC_CLAIMS)
    }

    @Test fun cancellationRejectsEvenCompleteClaims() {
        rejected(goodClaim().copy(cancelled = true), Contract.Reason.CANCELLED)
    }

    @Test fun duplicateExpectedAssetRejects() {
        rejected(goodClaim().copy(expectedCopies = listOf(
            Contract.ExpectedCopy("asset-1", 3L, c), Contract.ExpectedCopy("asset-1", 3L, c),
        )), Contract.Reason.DUPLICATE_ASSET)
    }

    @Test fun duplicateObservedAssetRejects() {
        rejected(goodClaim().copy(observedCopies = List(2) {
            Contract.ObservedCopy("asset-1", 3L, c, true, true)
        }), Contract.Reason.DUPLICATE_ASSET)
    }

    @Test fun malformedDigestRejected() {
        rejected(goodClaim().copy(expectedManifestSha256 = "BAD"), Contract.Reason.INVALID_EXPECTATION)
    }

    @Test fun nonPositiveLengthRejected() {
        rejected(goodClaim().copy(expectedCopies = listOf(Contract.ExpectedCopy("asset-1", 0L, c))),
            Contract.Reason.INVALID_EXPECTATION)
    }

    @Test fun unexpectedObservedAssetRejected() {
        rejected(goodClaim().copy(observedCopies = listOf(
            Contract.ObservedCopy("asset-extra", 3L, c, true, true),
        )), Contract.Reason.INCOMPLETE_COPY_SET)
    }

    @Test fun emptyMediaSetStillUntrusted() {
        val claim = goodClaim().copy(expectedCopies = emptyList(), observedCopies = emptyList())
        assertEquals(Contract.Assessment.CompleteUntrusted, Contract.assess(claim))
    }
}
