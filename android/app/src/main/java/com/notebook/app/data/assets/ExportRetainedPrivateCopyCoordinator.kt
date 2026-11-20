package com.notebook.app.data.assets

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * D2-B: one lease -> one transactional snapshot and persisted pin set -> independent
 * private byte copies -> snapshot/manifest staging. No second lease, nested Room
 * transaction, stage adoption or return to live note state after capture.
 *
 * StagedUntrusted is NOT an export, sealed archive, durable protection receipt,
 * recovery clearance, release authority or GC eligibility. Any failure after
 * commit leaves immutable export holds in Room, including cancellation/death.
 */
internal class ExportRetainedPrivateCopyCoordinator(
    private val outerLease: ExportOuterLease,
    private val holdAdmission: ExportAtomicHoldAdmission,
    private val borrower: ExportHeldAssetBorrower,
    private val stages: ExportRetainedPrivateStageStore,
) {
    internal sealed interface Result {
        data class StagedUntrusted(val observation: ExportUntrustedPrivateStageObservation) : Result
        data class Rejected(val reason: SnapshotRejection) : Result
    }

    suspend fun stage(
        operationId: String,
        request: SnapshotRequest,
        limits: ExportCopyAdmissionLimits,
        createdAtMillis: Long,
    ): Result = outerLease.withLease {
        currentCoroutineContext().ensureActive()
        when (val held = holdAdmission.persistUnderHeldLease(
            operationId, request, limits, createdAtMillis,
        )) {
            is ExportAtomicHoldCaptureResult.Rejected -> Result.Rejected(held.reason)
            is ExportAtomicHoldCaptureResult.Committed -> {
                // This is after the Room transaction committed. Pins MUST remain
                // retained on any later failure, including a stage-sync failure.
                currentCoroutineContext().ensureActive()
                val snapshot = ExportSourceBindingDigests.snapshot(operationId, held.snapshot)
                if (ExportSourceBindingDigests.sha256(snapshot) != held.binding.snapshotSha256 ||
                    ExportSourceBindingDigests.assetSetDigest(held.plan.images) != held.binding.assetSetSha256 ||
                    held.binding.expectedAssetCount != held.assetIds.size ||
                    held.binding.expectedAssetBytes != held.plan.totalEncodedBytes ||
                    held.binding.bindingVersion != ExportSourceBindingDigests.BINDING_VERSION
                ) throw IOException("Export source differs from immutable Room binding")
                val staged = stages.createNew(operationId)
                val stagedDigest = staged.writeCapturedSnapshot(snapshot)
                if (stagedDigest != held.binding.snapshotSha256) {
                    throw IOException("Private snapshot digest differs from immutable Room capture")
                }
                val copied = ArrayList<ExportPrivateCopyObservation>()
                var bytes = 0L
                for (expected in held.plan.images) {
                    currentCoroutineContext().ensureActive()
                    val copy = borrower.withBytesUnderHeldLease(expected) { verified, raw ->
                        if (verified.assetId != expected.id ||
                            verified.mimeType != expected.mimeType ||
                            verified.sha256 != expected.sha256 ||
                            verified.encodedBytes != expected.encodedBytes
                        ) throw IOException("Borrowed source differs from committed snapshot")
                        staged.copyAsset(verified, raw)
                    }
                    if (copy.byteCount != expected.encodedBytes ||
                        copy.sha256 != expected.sha256 ||
                        copy.assetId != expected.id ||
                        copy.byteCount > held.plan.totalEncodedBytes - bytes
                    ) throw IOException("Destination copy differs from committed source")
                    bytes += copy.byteCount
                    copied += copy
                }
                if (bytes != held.plan.totalEncodedBytes ||
                    copied.map { it.assetId }.sorted() != held.assetIds
                ) throw IOException("Independent copy set incomplete")
                val snapshotDigest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(snapshot).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val manifest = ExportCapturedDocumentCodec.manifest(
                    operationId, held.snapshot, snapshotDigest, copied,
                )
                staged.writeCopyManifest(manifest)
                currentCoroutineContext().ensureActive()
                Result.StagedUntrusted(staged.observeUntrusted(
                    held.snapshot.note.id, held.snapshot.note.revision, held.assetIds,
                ))
            }
        }
    }
}
