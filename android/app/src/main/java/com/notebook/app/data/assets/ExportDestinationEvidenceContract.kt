package com.notebook.app.data.assets

import java.util.UUID

/**
 * R26-A: pure, in-memory review of CLAIMED unsealed destination observations.
 * No filesystem, Room, lease, production wiring, sealing, receipt or permission to release holds.
 * Caller-supplied sync flags and hashes are assertions, not independently proven durability.
 */
internal object ExportDestinationEvidenceContract {
    internal data class ExpectedCopy(
        val assetId: String,
        val byteCount: Long,
        val sha256: String,
    )

    internal data class ObservedCopy(
        val assetId: String,
        val byteCount: Long,
        val sha256: String,
        val fileForceClaimed: Boolean,
        val parentSyncClaimed: Boolean,
    )

    internal data class Claim(
        val operationId: UUID,
        val expectedSnapshotSha256: String,
        val observedSnapshotSha256: String?,
        val expectedManifestSha256: String,
        val observedManifestSha256: String?,
        val expectedCopies: List<ExpectedCopy>,
        val observedCopies: List<ObservedCopy>,
        val snapshotFileForceClaimed: Boolean,
        val manifestFileForceClaimed: Boolean,
        val stageDirectorySyncClaimed: Boolean,
        val cancelled: Boolean = false,
    )

    internal enum class Reason {
        CANCELLED, INVALID_EXPECTATION, DUPLICATE_ASSET, INCOMPLETE_COPY_SET,
        BYTES_MISMATCH, INCOMPLETE_SYNC_CLAIMS,
    }

    internal sealed interface Assessment {
        /** Observations matched, but they are UNTRUSTED and provide NO authority. */
        data object CompleteUntrusted : Assessment
        data class Rejected(val reason: Reason) : Assessment
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private const val MAX_COPIES = 1024
    private const val MAX_AGGREGATE_BYTES = 1L shl 34 // contract budget, not a writer quota

    fun assess(claim: Claim): Assessment {
        if (claim.cancelled) return Assessment.Rejected(Reason.CANCELLED)
        if (!validDigest(claim.expectedSnapshotSha256) ||
            !validDigest(claim.expectedManifestSha256) ||
            claim.expectedCopies.size > MAX_COPIES || claim.observedCopies.size > MAX_COPIES
        ) return Assessment.Rejected(Reason.INVALID_EXPECTATION)

        val expectedIds = HashSet<String>()
        var aggregate = 0L
        for (entry in claim.expectedCopies) {
            if (entry.assetId.isBlank() || entry.byteCount <= 0L ||
                !validDigest(entry.sha256) || entry.byteCount > MAX_AGGREGATE_BYTES - aggregate
            ) return Assessment.Rejected(Reason.INVALID_EXPECTATION)
            if (!expectedIds.add(entry.assetId)) return Assessment.Rejected(Reason.DUPLICATE_ASSET)
            aggregate += entry.byteCount
        }
        val observedIds = HashSet<String>()
        for (entry in claim.observedCopies) {
            if (entry.assetId.isBlank()) return Assessment.Rejected(Reason.INVALID_EXPECTATION)
            if (!observedIds.add(entry.assetId)) return Assessment.Rejected(Reason.DUPLICATE_ASSET)
        }
        if (expectedIds != observedIds) return Assessment.Rejected(Reason.INCOMPLETE_COPY_SET)
        if (claim.observedSnapshotSha256 != claim.expectedSnapshotSha256 ||
            claim.observedManifestSha256 != claim.expectedManifestSha256
        ) return Assessment.Rejected(Reason.BYTES_MISMATCH)

        val expectedById = claim.expectedCopies.associateBy { it.assetId }
        for (observed in claim.observedCopies) {
            val expected = expectedById.getValue(observed.assetId)
            if (expected.byteCount != observed.byteCount || expected.sha256 != observed.sha256) {
                return Assessment.Rejected(Reason.BYTES_MISMATCH)
            }
        }
        if (!claim.snapshotFileForceClaimed || !claim.manifestFileForceClaimed ||
            !claim.stageDirectorySyncClaimed ||
            claim.observedCopies.any { !it.fileForceClaimed || !it.parentSyncClaimed }
        ) return Assessment.Rejected(Reason.INCOMPLETE_SYNC_CLAIMS)

        // NEVER return a protected/verified/receipted state, even when all claims match.
        return Assessment.CompleteUntrusted
    }

    private fun validDigest(value: String) = SHA256.matches(value)
}
