package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ExportAssetHoldEntity
import com.notebook.app.data.local.entity.ExportOperationEntity
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A committed DB hold set, NOT verified original bytes or a protected export receipt. */
internal sealed interface ExportAtomicHoldResult {
    data class Committed(val operationId: String, val assetIds: List<String>) : ExportAtomicHoldResult
    data class Rejected(val reason: SnapshotRejection) : ExportAtomicHoldResult
}

/**
 * I5-B7B isolated persistence seam; NO production export path calls this coordinator.
 * The outer exclusive acquisition lease MUST precede the single Room transaction.
 * Inside that transaction canonical source capture, edge verification, admission and
 * operation + ALL distinct hold inserts are one atomic unit. No second lease, byte
 * access, stage write, completion receipt, release, VERIFIED promotion or cleanup.
 *
 * A committed result says only that these Room rows persisted together. The lease
 * expires independently. Committed pins survive cancellation or caller failure.
 */
internal class ExportAtomicHoldAdmission(
    private val db: AppDatabase,
    private val outerLease: ExportOuterLease,
    private val reader: ExportSourceSnapshotReader = ExportSourceSnapshotReader(db),
    private val afterHoldInserted: suspend (Int) -> Unit = {}, // fault-injection seam; never installed by production
) {
    suspend fun persist(
        operationId: String,
        request: SnapshotRequest,
        admissionLimits: ExportCopyAdmissionLimits,
        createdAtMillis: Long,
    ): ExportAtomicHoldResult {
        // A malformed ID or invalid clock is rejected before opening any transaction.
        val parsed = try { UUID.fromString(operationId) } catch (_: IllegalArgumentException) { null }
        require(parsed != null && parsed.toString() == operationId) { "Canonical export operation UUID required" }
        require(createdAtMillis >= 0L) { "Negative creation timestamp" }
        return outerLease.withLease {
            currentCoroutineContext().ensureActive()
            db.withTransaction {
                currentCoroutineContext().ensureActive()
                when (val source = reader.captureInTransaction(request)) {
                    is SnapshotResult.Rejected -> ExportAtomicHoldResult.Rejected(source.reason)
                    is SnapshotResult.Complete -> {
                        // plan() independently checks owner occurrences and distinct-asset set.
                        val plan = ExportCopyAdmission.plan(source, admissionLimits)
                        val ids = plan.images.map { it.id }
                        if (ids.size != ids.toSet().size) throw IOException("Duplicate asset identity")
                        val dao = db.exportHoldAdmissionDao()
                        dao.insertOperation(
                            ExportOperationEntity(
                                operationId = operationId,
                                noteId = source.note.id,
                                noteRevision = source.note.revision,
                                state = "COMMITTED_PIN_SET",
                                createdAtMillis = createdAtMillis,
                                updatedAtMillis = createdAtMillis,
                                protocolVersion = 1,
                            ),
                        )
                        for ((index, id) in ids.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            dao.insertHold(ExportAssetHoldEntity(operationId, id))
                            afterHoldInserted(index + 1)
                        }
                        currentCoroutineContext().ensureActive()
                        // Assert the exact committed set inside this same transaction.
                        val actual = dao.listHolds(operationId).map { it.assetId }
                        check(actual == ids.sorted()) { "Incomplete or unexpected export hold set" }
                        ExportAtomicHoldResult.Committed(operationId, actual)
                    }
                }
            }
        }
    }
}
