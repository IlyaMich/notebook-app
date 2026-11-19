package com.notebook.app.data.assets

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * D2-A: one shared lease encloses atomic snapshot/hold commit and exact original-byte
 * inspection. This is retention and source observation only, NOT a protected copy,
 * export receipt, durable destination, hold release, ownership promotion or GC permit.
 * Committed pins survive byte-inspection failures, cancellation, and process death.
 */
internal class ExportRetainedSourceInspectionCoordinator(
    private val outerLease: ExportOuterLease,
    private val holdAdmission: ExportAtomicHoldAdmission,
    private val inspectSource: suspend (ExportCopyAdmissionPlan) -> ExportVerifiedSourceObservation,
) {
    internal sealed interface Result {
        data class SourceObserved(
            val operationId: String,
            val retainedAssetIds: List<String>,
            val observation: ExportVerifiedSourceObservation,
        ) : Result
        data class Rejected(val reason: SnapshotRejection) : Result
    }

    suspend fun inspectAndRetain(
        operationId: String,
        request: SnapshotRequest,
        admissionLimits: ExportCopyAdmissionLimits,
        createdAtMillis: Long,
    ): Result = outerLease.withLease {
        currentCoroutineContext().ensureActive()
        when (val held = holdAdmission.persistUnderHeldLease(
            operationId, request, admissionLimits, createdAtMillis,
        )) {
            is ExportAtomicHoldCaptureResult.Rejected -> Result.Rejected(held.reason)
            is ExportAtomicHoldCaptureResult.Committed -> {
                currentCoroutineContext().ensureActive()
                // Holds are already committed. Any later failure must retain them.
                val observation = inspectSource(held.plan)
                currentCoroutineContext().ensureActive()
                val expected = held.plan.images.associateBy { it.id }
                if (observation.totalEncodedBytes != held.plan.totalEncodedBytes ||
                    observation.images.size != expected.size ||
                    held.assetIds != expected.keys.sorted() ||
                    observation.images.map { it.assetId }.toSet() != expected.keys
                ) throw IOException("Observed source set differs from committed pins")
                for (item in observation.images) {
                    val source = expected[item.assetId]
                        ?: throw IOException("Unexpected observed asset")
                    if (item.sha256 != source.sha256 ||
                        item.mimeType != source.mimeType ||
                        item.encodedBytes != source.encodedBytes
                    ) throw IOException("Observed bytes differ from committed asset metadata")
                }
                Result.SourceObserved(held.operationId, held.assetIds, observation)
            }
        }
    }
}
