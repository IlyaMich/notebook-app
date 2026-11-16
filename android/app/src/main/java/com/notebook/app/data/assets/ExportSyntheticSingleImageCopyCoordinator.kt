package com.notebook.app.data.assets

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Only the source verifier may call the continuation with a verified, borrowed array.
 * The caller must already hold the single outer publication lease; implementations
 * must clear the borrowed array on all exits. No second lease may be acquired.
 */
internal interface ExportSingleImageByteBorrower {
    suspend fun <T> withVerifiedSingleImageBytesUnderHeldLease(
        plan: ExportCopyAdmissionPlan,
        action: suspend (ExpectedEncodedImage, ByteArray) -> T,
    ): T
}

/**
 * I4B-2C-3B: EXACTLY ONE image, SYNTHETIC unsealed destination ONLY.
 * No production wiring, export receipt, archive, cleanup, or stage re-adoption.
 * Even success is only an untrusted destination observation; the caller retains
 * responsibility for byte provenance and future crash-durability verification.
 */
internal class ExportSyntheticSingleImageCopyCoordinator(
    private val admission: ExportLeaseScopedAdmission,
    private val borrower: ExportSingleImageByteBorrower,
    private val syntheticDestination: ExportUnsealedDestinationCopyProbe,
) {
    suspend fun copy(
        request: SnapshotRequest,
        limits: ExportCopyAdmissionLimits,
    ): ExportLeaseScopedAdmissionResult<UntrustedDestinationCopyObservation> =
        admission.withAdmittedSnapshot(request, limits) { _, plan ->
            currentCoroutineContext().ensureActive()
            if (plan.images.size != 1 || plan.images.single().encodedBytes != plan.totalEncodedBytes) {
                throw IOException("Synthetic copy requires exactly one admitted image")
            }
            val source = plan.images.single()
            borrower.withVerifiedSingleImageBytesUnderHeldLease(plan) { verified, bytes ->
                currentCoroutineContext().ensureActive()
                // Fail closed even if the injected borrower violates the metadata contract.
                val expected = ExpectedEncodedImage(source.id, source.mimeType,
                    source.sha256, source.encodedBytes)
                if (verified != expected) throw IOException("Borrowed identity differs from admission")
                val result = syntheticDestination.writeBorrowedBytes(expected, bytes)
                currentCoroutineContext().ensureActive()
                if (result.sha256 != expected.sha256 || result.byteCount != expected.encodedBytes) {
                    throw IOException("Synthetic destination result differs from admitted source")
                }
                result
            }
        }
}
