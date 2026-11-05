package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * STO-04 Stage 2C1: bridge verified, synchronized private asset bytes to Room metadata.
 *
 * The file is finalized first. A DB write failure may leave an unreferenced file;
 * this is intentional and safer than allowing a committed row to point at missing bytes.
 * No element/page references, export pins, deletion, or startup reconciliation are
 * created here. Callers must not interpret a finalized metadata row as an owner pin.
 *
 * This class is deliberately not connected to UI/import entry points yet.
 */
internal class ManagedAssetRegistrar(
    private val fileStore: ManagedAssetFileStore,
    private val assetDao: AssetDao,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Caller owns and closes the input stream. */
    suspend fun register(input: InputStream, declaredMimeType: String): AssetEntity {
        // Fully hash, validate and synchronize the file before any database access.
        val finalized = fileStore.stageAndFinalize(input, declaredMimeType)

        // Idempotent imports must preserve the original stable asset ID.
        assetDao.findBySha256(finalized.sha256)?.let { return verifyMatch(it, finalized) }

        val candidate = AssetEntity(
            id = newId(),
            sha256 = finalized.sha256,
            mimeType = finalized.mimeType,
            byteSize = finalized.byteSize,
            relativePath = finalized.relativePath,
            state = FINALIZED_STATE,
        )
        require(candidate.id.isNotBlank()) { "Asset ID must not be blank" }

        try {
            // Unique digest/path indexes arbitrate concurrent registrars across instances.
            assetDao.insert(candidate)
        } catch (writeFailure: Exception) {
            // A concurrent importer might have inserted the same asset after our first
            // lookup. Re-read only to distinguish a matching committed winner from a
            // failed write. Never replace, delete, or rewrite the competing row.
            val winner = try {
                assetDao.findBySha256(finalized.sha256)
            } catch (readFailure: Exception) {
                writeFailure.addSuppressed(readFailure)
                throw writeFailure
            }
            if (winner == null) throw writeFailure
            return verifyMatch(winner, finalized)
        }
        return candidate
    }

    private fun verifyMatch(row: AssetEntity, file: ManagedAssetFileStore.FinalizedFile): AssetEntity {
        if (row.id.isBlank() ||
            row.sha256 != file.sha256 ||
            row.mimeType != file.mimeType ||
            row.byteSize != file.byteSize ||
            row.relativePath != file.relativePath ||
            row.state != FINALIZED_STATE
        ) {
            throw IOException("Asset metadata conflicts with verified finalized bytes")
        }
        return row
    }

    companion object {
        private const val FINALIZED_STATE = "finalized"
    }
}
