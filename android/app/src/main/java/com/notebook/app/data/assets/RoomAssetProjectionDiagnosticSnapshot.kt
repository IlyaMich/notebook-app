package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import kotlinx.coroutines.CancellationException

/**
 * I5-A5: diagnostic-only bounded observation of canonical JSON owners and both
 * durable projection tables within ONE outer Room read transaction. The nested
 * RoomAssetOwnerInventory scan participates in that transaction; it never
 * grants complete-reference, export-protection or cleanup authority.
 */
internal class RoomAssetProjectionDiagnosticSnapshot(private val db: AppDatabase) {
    sealed interface Result {
        data class Completed(
            val canonical: AssetOwnerInventory.Report,
            val diagnostic: AssetProjectionDiagnosticComparator.Diagnostic,
            val gateStatus: String,
        ) : Result
        data class Incomplete(val reason: String) : Result
    }

    suspend fun scan(): Result = try {
        db.withTransaction<Result> {
            val canonical = when (val result = RoomAssetOwnerInventory(db).scan()) {
                is RoomAssetOwnerInventory.Scan.Incomplete ->
                    return@withTransaction Result.Incomplete("canonical scan incomplete")
                is RoomAssetOwnerInventory.Scan.Completed -> result.report
            }
            // MAX_PAGES / MAX_ELEMENTS match RoomAssetOwnerInventory's bounds.
            // LIMIT max+1 detects overflow, including unexpected projection rows.
            val pages = db.assetOwnershipDao().listPageReferencesForDiagnostic(10_001)
            if (pages.size > 10_000) {
                return@withTransaction Result.Incomplete("page projection limit exceeded")
            }
            val elements = db.assetOwnershipDao().listElementReferencesForDiagnostic(50_001)
            if (elements.size > 50_000) {
                return@withTransaction Result.Incomplete("element projection limit exceeded")
            }
            val diagnostic = AssetProjectionDiagnosticComparator.compareObserved(
                canonical,
                pages.map { AssetProjectionDiagnosticComparator.PageEdge(it.pageId, it.assetId) },
                elements.map { AssetProjectionDiagnosticComparator.ElementEdge(it.elementId, it.assetId) },
            )
            Result.Completed(canonical, diagnostic,
                db.assetOwnershipDao().readFailClosedGateStatus())
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // Never leak document text, file paths, or SQL details to callers.
        Result.Incomplete("transactional diagnostic scan failed")
    }
}
