package com.notebook.app.data.assets

import android.content.Context
import android.net.Uri
import com.notebook.app.data.local.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * STO-08 D2J-A: app-scoped, explicitly invoked export orchestration built from
 * existing Room v7 immutable source binding, private stage, portable draft,
 * independent retained private archive and one-shot external handoff seams.
 *
 * This service is NOT connected to the editor UI or a background worker.
 * A client must separately implement a fresh ACTION_CREATE_DOCUMENT callback;
 * never restore/replay an old URI, automatically retry, delete provider bytes,
 * trust an external readback as durable, release a hold or initiate GC.
 *
 * There is no persisted "completed" or "safe to delete" state, even after a
 * successful return. Any failure after pin admission leaves pins protected.
 */
internal class ExportRoomBoundHandoffCoordinator(
    privateRoot: File,
    private val db: AppDatabase,
    private val snapshotLimits: SnapshotLimits,
    private val copyLimits: ExportCopyAdmissionLimits,
    private val stageLimits: ExportPrivateStageLimits,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    internal sealed interface Preparation {
        /**
         * App-private ZIP and its exact Room source binding were rechecked.
         * ONLY permits a separately initiated fresh user picker, no completion.
         */
        data class AwaitingFreshUserDocumentUntrusted(
            val operationId: String,
            val noteId: String,
            val noteRevision: Long,
            val archiveSha256: String,
            val archiveBytes: Long,
        ) : Preparation

        /** Snapshot rejected before a hold-set commit. */
        data class Rejected(val reason: SnapshotRejection) : Preparation

        /**
         * Outcome is conservatively unknown: immutable pins may have committed
         * and partial private files may exist. Do not auto-retry this operation.
         */
        data class IncompleteRetainedUntrusted(val operationId: String) : Preparation
    }

    internal sealed interface Handoff {
        /** Historical matching reread only. Never a durable receipt. */
        data class ReadbackObservedUntrusted(val operationId: String) : Handoff
        /** The operation may already have provider bytes; never retry automatically. */
        data class AttemptOutcomeUnknownUntrusted(val operationId: String) : Handoff
    }

    internal sealed interface Recovery {
        /** No v7 attempt recorded; NOT evidence that no external bytes exist. */
        data class NoAttemptRecordedUntrusted(val operationId: String) : Recovery
        /** No second publication, no replacement URI, and no provider deletion. */
        data class AttemptPendingUntrusted(val operationId: String) : Recovery
        data class ReadbackObservedUntrusted(val operationId: String) : Recovery
        data class IncompleteRetainedUntrusted(val operationId: String) : Recovery
    }

    private val root = privateRoot
    private val stage = ExportHoldAdmissionComposition.retainedPrivateCopyForRoom(
        root, db, stageLimits,
    )
    private val bound = ExportBoundPrivateStageRecovery(db, root, stageLimits)
    private val draft = ExportPrivatePortableDraftWriter(
        root, stageLimits, ExportAndroidDirectorySync::syncOrThrow,
    )
    private val archive = ExportHoldAdmissionComposition.independentPrivateArchiveForRoom(
        root, db, stageLimits,
    )
    private val saf = ExportHoldAdmissionComposition.safPublicationForRoom(
        root, db, stageLimits,
    )
    private val journal = ExportExternalAttemptJournal(db, stageLimits)

    /**
     * Only for an explicit new user request. Every invocation has a fresh UUID;
     * after interruption, callers must inspect the previous ID read-only.
     * Existing private/Room operation rows are never replaced, adopted or pruned.
     */
    suspend fun prepareNew(noteId: String): Preparation {
        currentCoroutineContext().ensureActive()
        if (!ExportSourceBindingDigests.canonicalUuid(noteId)) {
            return Preparation.Rejected(SnapshotRejection.INVALID_REQUEST)
        }
        val operationId = UUID.randomUUID().toString()
        val at = clockMillis()
        if (at < 0L) return Preparation.Rejected(SnapshotRejection.INVALID_REQUEST)
        try {
            val captured = stage.stage(
                operationId, SnapshotRequest(noteId, snapshotLimits),
                copyLimits, at,
            )
            if (captured is ExportRetainedPrivateCopyCoordinator.Result.Rejected) {
                return Preparation.Rejected(captured.reason)
            }
            check(captured is ExportRetainedPrivateCopyCoordinator.Result.StagedUntrusted)
            // Never trust an in-memory stage observation to derive the draft.
            // Reconstruct it from immutable Room v6 binding and on-disk copies.
            val first = bound.inspectReadOnly(operationId)
            if (first !is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted ||
                first.draft != null ||
                first.observation != captured.observation
            ) return Preparation.IncompleteRetainedUntrusted(operationId)

            currentCoroutineContext().ensureActive()
            draft.writeDraft(first.observation)
            // Reinspect the independent draft against Room before retaining a
            // second private copy. No live note/managed original reread here.
            val second = bound.inspectReadOnly(operationId)
            if (second !is ExportBoundPrivateStageRecovery.Result.BoundStageUntrusted ||
                second.observation != first.observation || second.draft == null
            ) return Preparation.IncompleteRetainedUntrusted(operationId)

            val stored = archive.retainPrivateCopy(operationId)
            if (stored !is ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted) {
                return Preparation.IncompleteRetainedUntrusted(operationId)
            }
            val rechecked = archive.inspectPrivateCopyReadOnly(operationId)
            if (rechecked != stored ||
                stored.archive.operationId != operationId ||
                stored.archive.sha256 != second.draft.sha256 ||
                stored.archive.bytes != second.draft.byteCount
            ) return Preparation.IncompleteRetainedUntrusted(operationId)

            currentCoroutineContext().ensureActive()
            return Preparation.AwaitingFreshUserDocumentUntrusted(
                operationId, noteId, first.observation.noteRevision,
                stored.archive.sha256, stored.archive.bytes,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return Preparation.IncompleteRetainedUntrusted(operationId)
        }
    }

    /**
     * Invoke ONLY from the immediate successful callback of a newly launched
     * ACTION_CREATE_DOCUMENT picker. No persisted URI, no automatic retry.
     * An error at ANY point may mean the provider contains partial/full bytes.
     */
    suspend fun handoffToFreshUserDocument(
        operationId: String,
        context: Context,
        freshSelectedUri: Uri,
    ): Handoff = try {
        currentCoroutineContext().ensureActive()
        if (!ExportSourceBindingDigests.canonicalUuid(operationId)) {
            Handoff.AttemptOutcomeUnknownUntrusted(operationId)
        } else when (saf.publishToSelectedSafDocument(operationId, context, freshSelectedUri)) {
            is ExportSafPublicationCoordinator.Result.ExternalBytesObservedUntrusted ->
                Handoff.ReadbackObservedUntrusted(operationId)
            is ExportSafPublicationCoordinator.Result.Incomplete ->
                Handoff.AttemptOutcomeUnknownUntrusted(operationId)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        Handoff.AttemptOutcomeUnknownUntrusted(operationId)
    }

    /** Isolated test seam: still uses the same one-shot Room v7 admission. */
    internal suspend fun handoffToFreshDestination(
        operationId: String,
        destination: ExportExternalDocumentDestination,
    ): Handoff = try {
        currentCoroutineContext().ensureActive()
        if (!ExportSourceBindingDigests.canonicalUuid(operationId)) {
            Handoff.AttemptOutcomeUnknownUntrusted(operationId)
        } else when (saf.publishToFreshUserDocument(operationId, destination)) {
            is ExportSafPublicationCoordinator.Result.ExternalBytesObservedUntrusted ->
                Handoff.ReadbackObservedUntrusted(operationId)
            is ExportSafPublicationCoordinator.Result.Incomplete ->
                Handoff.AttemptOutcomeUnknownUntrusted(operationId)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        Handoff.AttemptOutcomeUnknownUntrusted(operationId)
    }

    /**
     * Read-only recovery, safe across process death. Room v7 journal decides
     * historical attempt states; only a no-attempt classification also rereads
     * the private archive. It never recreates a picker or retries a write.
     */
    suspend fun inspectReadOnly(operationId: String): Recovery {
        if (!ExportSourceBindingDigests.canonicalUuid(operationId)) {
            return Recovery.IncompleteRetainedUntrusted(operationId)
        }
        return try {
            currentCoroutineContext().ensureActive()
            when (journal.inspectReadOnly(operationId)) {
                is ExportExternalAttemptJournal.Recovery.AttemptPendingUntrusted ->
                    Recovery.AttemptPendingUntrusted(operationId)
                is ExportExternalAttemptJournal.Recovery.ReadbackObservedUntrusted ->
                    Recovery.ReadbackObservedUntrusted(operationId)
                is ExportExternalAttemptJournal.Recovery.Incomplete ->
                    Recovery.IncompleteRetainedUntrusted(operationId)
                ExportExternalAttemptJournal.Recovery.NoAttemptObservedUntrusted -> {
                    when (archive.inspectPrivateCopyReadOnly(operationId)) {
                        is ExportIndependentPrivateArchiveCoordinator.Result.PrivateCopyObservedUntrusted ->
                            Recovery.NoAttemptRecordedUntrusted(operationId)
                        is ExportIndependentPrivateArchiveCoordinator.Result.Incomplete ->
                            Recovery.IncompleteRetainedUntrusted(operationId)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Recovery.IncompleteRetainedUntrusted(operationId)
        }
    }
}
