package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * STO-08 D2J-B: read-only, note-scoped diagnostic projection for the editor.
 * No stage, source file, provider URI, export hold or original is opened here.
 *
 * An "observed" row is never a durable external export completion receipt.
 * The returned value never recommends retry, expiry, cleanup, or deletion.
 */
enum class ExportNoteHistoryState {
    NO_ATTEMPT_RECORDED_UNTRUSTED,
    ATTEMPT_PENDING_UNTRUSTED,
    READBACK_OBSERVED_UNTRUSTED,
    INCOMPLETE_UNTRUSTED,
}

data class ExportNoteHistoryEntry(
    val operationId: String,
    val createdAtMillis: Long,
    val state: ExportNoteHistoryState,
)

sealed interface ExportNoteHistoryReport {
    data class ObservedUntrusted(val entries: List<ExportNoteHistoryEntry>) : ExportNoteHistoryReport
    data object IncompleteUntrusted : ExportNoteHistoryReport
}

/**
 * The entire bounded note history and v7 journal classification occur within
 * one read-only outer Room transaction; the nested journal read joins it.
 * Immutable records can still be appended after this observation returns.
 */
internal class ExportNoteHistoryReader(
    private val db: AppDatabase,
    private val limits: ExportPrivateStageLimits,
    private val maxRows: Int = 20,
) {
    init { require(maxRows in 1..100) }

    suspend fun inspect(noteId: String): ExportNoteHistoryReport {
        if (!isCanonicalUuid(noteId)) return ExportNoteHistoryReport.IncompleteUntrusted
        return try {
            db.withTransaction<ExportNoteHistoryReport> {
                currentCoroutineContext().ensureActive()
                val rows = db.exportHoldAdmissionDao()
                    .listNoteExportOperationsBounded(noteId, maxRows + 1)
                if (rows.size > maxRows) {
                    return@withTransaction ExportNoteHistoryReport.IncompleteUntrusted
                }
                val journal = ExportExternalAttemptJournal(db, limits)
                val entries = ArrayList<ExportNoteHistoryEntry>(rows.size)
                for (row in rows) {
                    currentCoroutineContext().ensureActive()
                    if (row.noteId != noteId || !isCanonicalUuid(row.operationId) ||
                        row.createdAtMillis < 0L ||
                        row.protocolVersion !in 1..2 ||
                        row.state != "COMMITTED_PIN_SET"
                    ) return@withTransaction ExportNoteHistoryReport.IncompleteUntrusted

                    val state = when (journal.inspectReadOnly(row.operationId)) {
                        ExportExternalAttemptJournal.Recovery.NoAttemptObservedUntrusted ->
                            ExportNoteHistoryState.NO_ATTEMPT_RECORDED_UNTRUSTED
                        is ExportExternalAttemptJournal.Recovery.AttemptPendingUntrusted ->
                            ExportNoteHistoryState.ATTEMPT_PENDING_UNTRUSTED
                        is ExportExternalAttemptJournal.Recovery.ReadbackObservedUntrusted ->
                            ExportNoteHistoryState.READBACK_OBSERVED_UNTRUSTED
                        is ExportExternalAttemptJournal.Recovery.Incomplete ->
                            ExportNoteHistoryState.INCOMPLETE_UNTRUSTED
                    }
                    entries += ExportNoteHistoryEntry(row.operationId, row.createdAtMillis, state)
                }
                ExportNoteHistoryReport.ObservedUntrusted(entries.toList())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ExportNoteHistoryReport.IncompleteUntrusted
        }
    }

    private fun isCanonicalUuid(raw: String): Boolean =
        try { UUID.fromString(raw).toString() == raw }
        catch (_: IllegalArgumentException) { false }
}
