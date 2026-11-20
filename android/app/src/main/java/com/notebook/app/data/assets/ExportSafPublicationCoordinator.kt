package com.notebook.app.data.assets

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Explicit opt-in boundary invoked ONLY after a user has created a new SAF
 * document. This service does not launch the system picker or export on startup.
 *
 * The v6 Room hold set stays committed for the entire provider transaction and
 * beyond it. Provider I/O runs OUTSIDE the asset-publication lease; only
 * read-only provenance checks acquire the shared lease before/after the write.
 *
 * Even an exact provider reread is NOT a durable or atomic completion receipt.
 * On exception the destination may contain partial bytes and MUST be handled
 * by the user/provider, never silently deleted, retried or adopted.
 */
internal class ExportSafPublicationCoordinator(
    private val privateArchive: ExportIndependentPrivateArchiveCoordinator,
    private val bytes: ExportExternalDocumentByteHandoff,
    private val journal: ExportExternalAttemptJournal,
) {
    internal sealed interface Result {
        data class ExternalBytesObservedUntrusted(
            val observation: ExportExternalDocumentByteHandoff.RereadObservedUntrusted,
        ) : Result
        data class Incomplete(val reason: String) : Result
    }

    /**
     * Call only with a URI freshly returned by ACTION_CREATE_DOCUMENT after an
     * explicit user action. Does not persist URI grants or certify provider
     * crash durability. An old URI is not a safe replacement target.
     */
    suspend fun publishToSelectedSafDocument(
        operationId: String,
        context: Context,
        freshSelectedUri: Uri,
    ): Result = publishToFreshUserDocument(
        operationId, ExportSafCreatedDocumentDestination(context, freshSelectedUri),
    )

    suspend fun publishToFreshUserDocument(
        operationId: String,
        destination: ExportExternalDocumentDestination,
    ): Result {
        currentCoroutineContext().ensureActive()
        val first = privateArchive.inspectPrivateCopyReadOnly(operationId)
        if (first !is ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted) {
            return Result.Incomplete("Immutable Room-bound private archive not verified")
        }
        // Commit a one-shot, immutable attempt BEFORE any provider byte I/O.
        // Interrupted/failed attempts are never automatically retried or removed.
        val admission = journal.beginOnce(
            first.archive, destination.destinationIdentitySha256,
        )
        if (admission is ExportExternalAttemptJournal.Admission.Incomplete) {
            return Result.Incomplete("External handoff blocked: " + admission.reason)
        }
        // Never hold the OS asset lease over a remote/cloud DocumentProvider.
        // Room v7 attempt and the v6 holds survive cancellation/process death.
        val readback = bytes.copyAndReread(first.archive, destination)
        if (readback.operationId != first.archive.operationId ||
            readback.byteCount != first.archive.bytes ||
            readback.sha256 != first.archive.sha256
        ) throw IOException("External readback did not match Room-bound archive")
        currentCoroutineContext().ensureActive()
        val after = privateArchive.inspectPrivateCopyReadOnly(operationId)
        if (after != first) {
            throw IOException("Room-bound archive provenance changed after external handoff")
        }
        currentCoroutineContext().ensureActive()
        // Persist only an untrusted diagnostic matching the original immutable
        // attempt. If this commit fails, the provider result is still ambiguous.
        journal.recordMatchingReread(readback)
        currentCoroutineContext().ensureActive()
        // Deliberately no completion receipt, Room operation phase transition,
        // hold release or GC decision.
        return Result.ExternalBytesObservedUntrusted(readback)
    }
}
