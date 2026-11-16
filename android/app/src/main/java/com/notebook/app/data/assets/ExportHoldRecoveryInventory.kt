package com.notebook.app.data.assets

import android.database.SQLException
import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.dao.ExportHoldRecoveryJoinRow
import com.notebook.app.data.local.entity.ExportOperationEntity
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounds only database rows. Never inspect source bytes or infer receipt/pin completeness. */
internal data class ExportHoldRecoveryLimits(
    val maxOperations: Int = 64,
    val maxHoldRows: Int = 1024,
) {
    init {
        require(maxOperations in 1..256) { "Operation bound must be 1..256" }
        require(maxHoldRows in 0..4096) { "Hold-row bound must be 0..4096" }
    }
}

/** In-memory diagnostic reasons only. None of these confer release or export authority. */
internal enum class ExportHoldRecoveryIssue {
    EMPTY_INVENTORY_UNPROVEN,
    ZERO_HOLDS_UNPROVEN,
    UNKNOWN_OPERATION_STATE,
    UNKNOWN_PROTOCOL_VERSION,
    INVALID_OPERATION_METADATA,
    MALFORMED_HOLD_ID,
    MISSING_ASSET_METADATA,
    NONFINALIZED_ASSET_METADATA,
    ORPHAN_HOLD,
}

internal data class ExportHoldRecoveryFinding(
    val operationId: String,
    val observedAssetIds: List<String>,
    val issues: Set<ExportHoldRecoveryIssue>,
) {
    /** Means only that previously committed database pins must be retained. */
    val requiresReview: Boolean get() = issues.isNotEmpty()
}

internal enum class ExportHoldRecoveryStopReason {
    OPERATION_LIMIT_EXCEEDED,
    HOLD_ROW_LIMIT_EXCEEDED,
    DATABASE_READ_FAILED,
}

/**
 * All outcomes are diagnostics, NOT evidence of protected export or safe asset reclamation.
 * Even a fully enumerated result cannot prove the original expected hold cardinality in v5.
 */
internal sealed interface ExportHoldRecoveryResult {
    data class Observed(
        val operations: List<ExportHoldRecoveryFinding>,
        val observedHoldRows: Int,
        val globalIssues: Set<ExportHoldRecoveryIssue>,
    ) : ExportHoldRecoveryResult

    data class Incomplete(val reason: ExportHoldRecoveryStopReason) : ExportHoldRecoveryResult
}

/**
 * I5-B7D-2: strictly read-only, bounded v5 Room inventory.
 * One coherent Room transaction; two bounded DAO SELECTs, no lease and no mutations.
 * Never start at application startup, update phases, adopt stages, or release holds.
 */
internal class ExportHoldRecoveryInventory(private val db: AppDatabase) {
    suspend fun inspect(limits: ExportHoldRecoveryLimits = ExportHoldRecoveryLimits()): ExportHoldRecoveryResult =
        try {
            db.withTransaction {
                currentCoroutineContext().ensureActive()
                val dao = db.exportHoldAdmissionDao()
                // One extra row distinguishes complete enumeration from a clipped inventory.
                val operations = dao.listRecoveryOperations(limits.maxOperations + 1)
                currentCoroutineContext().ensureActive()
                if (operations.size > limits.maxOperations) {
                    return@withTransaction ExportHoldRecoveryResult.Incomplete(
                        ExportHoldRecoveryStopReason.OPERATION_LIMIT_EXCEEDED,
                    )
                }
                val holds = dao.listRecoveryHoldRows(limits.maxHoldRows + 1)
                currentCoroutineContext().ensureActive()
                if (holds.size > limits.maxHoldRows) {
                    return@withTransaction ExportHoldRecoveryResult.Incomplete(
                        ExportHoldRecoveryStopReason.HOLD_ROW_LIMIT_EXCEEDED,
                    )
                }
                classify(operations, holds)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: SQLException) {
            currentCoroutineContext().ensureActive()
            ExportHoldRecoveryResult.Incomplete(ExportHoldRecoveryStopReason.DATABASE_READ_FAILED)
        }

    private fun classify(
        operations: List<ExportOperationEntity>,
        holds: List<ExportHoldRecoveryJoinRow>,
    ): ExportHoldRecoveryResult.Observed {
        val known = operations.map { it.operationId }.toHashSet()
        val global = linkedSetOf<ExportHoldRecoveryIssue>()
        if (operations.isEmpty()) global += ExportHoldRecoveryIssue.EMPTY_INVENTORY_UNPROVEN
        if (holds.any { it.operationId !in known || it.operationPresentId == null }) {
            global += ExportHoldRecoveryIssue.ORPHAN_HOLD
        }
        val byOperation = holds.groupBy { it.operationId }
        val findings = operations.map { op ->
            val rows = byOperation[op.operationId].orEmpty()
            val issues = linkedSetOf<ExportHoldRecoveryIssue>()
            if (op.state != "COMMITTED_PIN_SET") issues += ExportHoldRecoveryIssue.UNKNOWN_OPERATION_STATE
            if (op.protocolVersion != 1) issues += ExportHoldRecoveryIssue.UNKNOWN_PROTOCOL_VERSION
            if (!canonicalId(op.operationId) || !canonicalId(op.noteId) || op.noteRevision < 0 ||
                op.createdAtMillis < 0 || op.updatedAtMillis != op.createdAtMillis) {
                issues += ExportHoldRecoveryIssue.INVALID_OPERATION_METADATA
            }
            // v5 deliberately stores no expected asset count/digest: zero holds are ambiguous.
            if (rows.isEmpty()) issues += ExportHoldRecoveryIssue.ZERO_HOLDS_UNPROVEN
            if (rows.any { !canonicalId(it.assetId) || it.assetId == it.operationId } ||
                rows.map { it.assetId }.toSet().size != rows.size) {
                issues += ExportHoldRecoveryIssue.MALFORMED_HOLD_ID
            }
            if (rows.any { it.assetPresentId == null }) {
                issues += ExportHoldRecoveryIssue.MISSING_ASSET_METADATA
            }
            if (rows.any { it.assetPresentId != null && it.assetState != "finalized" }) {
                issues += ExportHoldRecoveryIssue.NONFINALIZED_ASSET_METADATA
            }
            ExportHoldRecoveryFinding(
                op.operationId,
                Collections.unmodifiableList(rows.map { it.assetId }.sorted()),
                Collections.unmodifiableSet(issues),
            )
        }
        return ExportHoldRecoveryResult.Observed(
            Collections.unmodifiableList(findings),
            holds.size,
            Collections.unmodifiableSet(global),
        )
    }

    private fun canonicalId(value: String): Boolean =
        try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }
}
