package com.notebook.app.data.assets

import com.notebook.app.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Injected seam for sequencing tests; the production adapter uses the existing OS lease. */
internal interface ExportOuterLease {
    suspend fun <T> withLease(action: suspend () -> T): T
}

/**
 * An observed, metadata-only phase outcome. CallbackCompleted is NOT a protected export
 * receipt, asset provenance certificate, durable file copy, or cleanup authorization.
 */
internal sealed interface ExportLeaseScopedAdmissionResult<out T> {
    data class CallbackCompleted<T>(val value: T) : ExportLeaseScopedAdmissionResult<T>
    data class Rejected(val reason: SnapshotRejection) : ExportLeaseScopedAdmissionResult<Nothing>
}

/**
 * STO-08 I4B-2C-2A: acquire the ONE shared publication lease before Room capture,
 * validate the detached snapshot against explicit admission limits, and keep that
 * lease held throughout a caller-supplied suspend callback.
 *
 * The callback is an internal future integration seam, NOT a protected copier. It must
 * not reacquire the non-reentrant acquisition lease, invoke ManagedAssetRegistrar or
 * ManagedAssetFileStore publication, or open a nested Room writer transaction.
 * This class does NOT open assets, verify exact bytes, decode images, write stage files,
 * publish an archive, or enable asset reclamation. Any later protected-copy claim
 * needs separately reviewed source/destination byte and durability verification.
 */
internal class ExportLeaseScopedAdmission(
    private val outerLease: ExportOuterLease,
    private val capture: suspend (SnapshotRequest) -> SnapshotResult,
) {
    suspend fun <T> withAdmittedSnapshot(
        request: SnapshotRequest,
        limits: ExportCopyAdmissionLimits,
        action: suspend (SnapshotResult.Complete, ExportCopyAdmissionPlan) -> T,
    ): ExportLeaseScopedAdmissionResult<T> = outerLease.withLease {
        currentCoroutineContext().ensureActive()
        when (val snapshot = capture(request)) {
            is SnapshotResult.Rejected -> ExportLeaseScopedAdmissionResult.Rejected(snapshot.reason)
            is SnapshotResult.Complete -> {
                currentCoroutineContext().ensureActive()
                val plan = ExportCopyAdmission.plan(snapshot, limits)
                currentCoroutineContext().ensureActive()
                val value = action(snapshot, plan)
                currentCoroutineContext().ensureActive()
                ExportLeaseScopedAdmissionResult.CallbackCompleted(value)
            }
        }
    }

    companion object {
        /** Production composition only: uses the SAME lock domain as asset publication. */
        fun forRoom(privateFilesDir: File, db: AppDatabase): ExportLeaseScopedAdmission {
            val coordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir)
            val reader = ExportSourceSnapshotReader(db)
            return ExportLeaseScopedAdmission(
                outerLease = object : ExportOuterLease {
                    override suspend fun <T> withLease(action: suspend () -> T): T =
                        coordinator.withLease(action)
                },
                capture = { request -> reader.capture(request) },
            )
        }
    }
}
