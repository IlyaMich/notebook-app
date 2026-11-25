package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.AssetEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * STO-08 D3-A: bounded, non-mutating observations of canonical owners, both
 * projected owner tables, asset metadata, and registered finalized file bytes.
 *
 * IMPORTANT: This is NOT an ownership-completeness certificate, a GC candidate
 * selector, a release decision, or authority to delete any original. An empty
 * owner set and an internally consistent scan have NO reclaim meaning. All
 * results are point-in-time, and uncoordinated writers are not excluded.
 *
 * The OS acquisition lease is taken BEFORE any Room transaction or file read.
 * All input is from trusted app-private root and actual Room state; no external
 * URI, user-selected path, live export, or state transition is accepted.
 */
internal class Sto08D3aReadOnlyOwnershipPreflight(
    privateRoot: File,
    private val db: AppDatabase,
    private val maxAssetRows: Int = 128,
    private val maxSingleAssetBytes: Long = 64L * 1024L * 1024L,
    private val maxTotalAssetBytes: Long = 256L * 1024L * 1024L,
    private val afterAssetChecked: suspend (Int) -> Unit = {},
) {
    init {
        require(maxAssetRows in 1..10_000)
        require(maxSingleAssetBytes > 0)
        require(maxTotalAssetBytes >= maxSingleAssetBytes)
    }

    internal enum class Reason {
        CANONICAL_SCAN_INCOMPLETE,
        CANONICAL_OR_PROJECTION_MISMATCH,
        METADATA_SCAN_INCOMPLETE,
        UNSAFE_ASSET_METADATA,
        FILE_INTEGRITY_OR_ACCESS_FAILURE,
        CHANGED_DURING_INSPECTION,
        RESOURCE_BUDGET_EXCEEDED,
        UNKNOWN_GATE_STATE,
    }

    internal sealed interface Result {
        /** No proof of zero owners or GC eligibility, even when activeOwners is 0. */
        data class ObservedConsistentUntrusted(
            val activeOwners: Int,
            val trashOwners: Int,
            val registeredAssets: Int,
            val metadataWithoutRecognizedOwner: Int,
            val ownershipGate: String,
        ) : Result

        /** Conflicting canonical/projection/metadata observations; retain all. */
        data class Mismatch(val reason: Reason) : Result

        /** Missing/over-budget/unreadable state; retain all. */
        data class Incomplete(val reason: Reason) : Result
    }

    private sealed interface Capture {
        data class Ready(
            val scan: RoomAssetProjectionDiagnosticSnapshot.Result.Completed,
            val assets: List<AssetEntity>,
        ) : Capture

        data class Rejected(val result: Result) : Capture
    }

    private val root = privateRoot
    private val lease = AssetAcquisitionLeaseCoordinator(root)

    suspend fun inspect(): Result = try {
        lease.withLease {
            currentCoroutineContext().ensureActive()
            val initial = capture()
            if (initial is Capture.Rejected) return@withLease initial.result
            check(initial is Capture.Ready)

            val verifier = ReadOnlyFinalizedAssetVerifier(
                root, db.assetDao(), maxSingleAssetBytes,
            )
            for ((index, row) in initial.assets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val bytes = try {
                    verifier.verify(row.id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return@withLease Result.Incomplete(
                        Reason.FILE_INTEGRITY_OR_ACCESS_FAILURE,
                    )
                }
                if (bytes.assetId != row.id ||
                    bytes.sha256 != row.sha256 ||
                    bytes.byteSize != row.byteSize ||
                    bytes.mimeType != row.mimeType
                ) return@withLease Result.Mismatch(Reason.UNSAFE_ASSET_METADATA)
                afterAssetChecked(index + 1)
            }

            // The second bounded Room snapshot detects observed source/index/
            // metadata changes while file I/O was outside SQLite transactions.
            // This is diagnostic only; it is not a serializable GC preflight.
            val final = capture()
            if (final !is Capture.Ready || final != initial) {
                return@withLease Result.Incomplete(Reason.CHANGED_DURING_INSPECTION)
            }
            currentCoroutineContext().ensureActive()
            Result.ObservedConsistentUntrusted(
                activeOwners = initial.scan.canonical.owners.count { !it.inTrash },
                trashOwners = initial.scan.canonical.owners.count { it.inTrash },
                registeredAssets = initial.assets.size,
                metadataWithoutRecognizedOwner =
                    initial.scan.canonical.metadataWithoutRecognizedOwner.size,
                ownershipGate = initial.scan.gateStatus,
            )
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        Result.Incomplete(Reason.FILE_INTEGRITY_OR_ACCESS_FAILURE)
    }

    /** One bounded Room transaction: no write DAO, no original-file access. */
    private suspend fun capture(): Capture = db.withTransaction<Capture> {
        currentCoroutineContext().ensureActive()
        val scan = when (val result = RoomAssetProjectionDiagnosticSnapshot(db).scan()) {
            is RoomAssetProjectionDiagnosticSnapshot.Result.Incomplete ->
                return@withTransaction Capture.Rejected(
                    Result.Incomplete(Reason.CANONICAL_SCAN_INCOMPLETE),
                )
            is RoomAssetProjectionDiagnosticSnapshot.Result.Completed -> result
        }
        if (scan.canonical.hasUnresolved || scan.diagnostic.hasObservedMismatch) {
            return@withTransaction Capture.Rejected(
                Result.Mismatch(Reason.CANONICAL_OR_PROJECTION_MISMATCH),
            )
        }
        if (scan.gateStatus != "UNVERIFIED") {
            // UNRESOLVED or malformed/unknown status never upgrades a claim.
            return@withTransaction Capture.Rejected(
                Result.Incomplete(Reason.UNKNOWN_GATE_STATE),
            )
        }

        val assetDao = db.assetDao()
        val count = assetDao.countAll()
        if (count < 0 || count > maxAssetRows) {
            return@withTransaction Capture.Rejected(
                Result.Incomplete(Reason.RESOURCE_BUDGET_EXCEEDED),
            )
        }
        val assets = assetDao.listAfterId(null, maxAssetRows + 1)
        if (assets.size != count ||
            assets.map { it.id }.toSet().size != assets.size ||
            assets.zipWithNext().any { (a, b) -> a.id >= b.id }
        ) return@withTransaction Capture.Rejected(
            Result.Incomplete(Reason.METADATA_SCAN_INCOMPLETE),
        )
        val expectedIds = scan.canonical.owners.mapTo(mutableSetOf()) { it.assetId } +
            scan.canonical.metadataWithoutRecognizedOwner
        if (assets.map { it.id }.toSet() != expectedIds) {
            return@withTransaction Capture.Rejected(
                Result.Mismatch(Reason.UNSAFE_ASSET_METADATA),
            )
        }
        var bytes = 0L
        for (asset in assets) {
            if (asset.id.isBlank() || asset.state != "finalized" ||
                asset.byteSize <= 0L || asset.byteSize > maxSingleAssetBytes
            ) return@withTransaction Capture.Rejected(
                Result.Incomplete(Reason.UNSAFE_ASSET_METADATA),
            )
            if (asset.byteSize > maxTotalAssetBytes - bytes) {
                return@withTransaction Capture.Rejected(
                    Result.Incomplete(Reason.RESOURCE_BUDGET_EXCEEDED),
                )
            }
            bytes += asset.byteSize
        }
        Capture.Ready(scan, assets)
    }
}
