package com.notebook.app.data.assets

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One shared lease protects immutable Room v6 evidence, recovered private-stage
 * proof and a fresh independent app-private archive copy. No source originals are
 * opened. No completed external export, receipt, hold release or GC is authorized.
 */
internal class ExportIndependentPrivateArchiveCoordinator(
    private val outerLease: ExportOuterLease,
    private val recovery: ExportBoundPrivateStageRecovery,
    private val store: ExportIndependentPrivateArchiveStore,
) {
    internal sealed interface Result {
        data class PrivateCopyObservedUntrusted(
            val archive: ExportIndependentPrivateArchiveStore.CopyObservedUntrusted,
        ) : Result

        data class Incomplete(val reason: String) : Result
    }

    suspend fun retainPrivateCopy(operationId: String): Result = outerLease.withLease {
        currentCoroutineContext().ensureActive()
        val first = recovery.inspectReadOnly(operationId)
        if (first is ExportBoundPrivateStageRecovery.Result.Incomplete) {
            return@withLease Result.Incomplete("Room-bound source/stage incomplete: " + first.reason)
        }
        check(first is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted)
        val draft = first.draft
            ?: return@withLease Result.Incomplete("No independently verified portable draft")
        val copied = store.copyNew(draft)
        currentCoroutineContext().ensureActive()
        val second = recovery.inspectReadOnly(operationId)
        if (second !is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted ||
            second.draft != draft
        ) throw IOException("Room-bound private draft changed during archive retention")
        if (store.inspectReadOnly(draft) != copied) {
            throw IOException("Independent archive changed after retained copy")
        }
        currentCoroutineContext().ensureActive()
        Result.PrivateCopyObservedUntrusted(copied)
    }

    /** Diagnostic only: never adopts, repairs, returns a completion receipt or releases holds. */
    suspend fun inspectPrivateCopyReadOnly(operationId: String): Result = outerLease.withLease {
        currentCoroutineContext().ensureActive()
        val bound = recovery.inspectReadOnly(operationId)
        if (bound !is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted ||
            bound.draft == null
        ) return@withLease Result.Incomplete("No matching Room-bound portable draft")
        val copy = store.inspectReadOnly(bound.draft)
            ?: return@withLease Result.Incomplete("No independently matching retained private archive")
        currentCoroutineContext().ensureActive()
        Result.PrivateCopyObservedUntrusted(copy)
    }
}
