package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * STO-04 Stage 2C2: a bounded, read-only snapshot comparison of the asset directory
 * and Room's metadata. This is NOT a repair operation, an asset-open authorization,
 * or proof that files cannot change immediately after this scan.
 *
 * Never deletes, renames, quarantines, inserts, or updates anything. A valid final
 * without metadata may be awaiting another writer; missing metadata or missing bytes
 * MUST NOT be used as permission to reclaim an asset. Document/export references are
 * not tracked yet.
 */
internal class ManagedAssetReconciler(
    private val privateFilesDir: File,
    private val assetDao: AssetDao,
    private val inventory: ManagedAssetRecoveryInventory = ManagedAssetRecoveryInventory(privateFilesDir),
    private val maximumMetadataRows: Int = 10_000,
    private val pageSize: Int = 128,
) {
    init {
        require(maximumMetadataRows > 0)
        require(pageSize > 0)
    }

    enum class Kind {
        VERIFIED_REGISTERED,
        VALID_UNREGISTERED_FINAL,
        INVALID_OR_UNREADABLE_FINAL,
        STAGING_OR_UNEXPECTED_ENTRY,
        METADATA_CONFLICT,
        MISSING_OR_UNSCANNED_FINAL,
    }

    data class Finding(
        val relativePath: String,
        val kind: Kind,
        val assetId: String? = null,
        val filesystemKind: ManagedAssetRecoveryInventory.Kind? = null,
    )

    /** Fails closed if filesystem enumeration or metadata pagination is incomplete. */
    suspend fun inspect(): List<Finding> {
        // Inventory is read first. No file is changed while reconciling metadata.
        val files = inventory.scan()
        val rows = loadRowsBounded()
        val byPath = rows.associateBy { it.relativePath }
        val seenFilePaths = HashSet<String>()
        val report = ArrayList<Finding>(files.size + rows.size)

        for (file in files) {
            seenFilePaths.add(file.relativePath)
            val row = byPath[file.relativePath]
            when (file.kind) {
                ManagedAssetRecoveryInventory.Kind.VALID_FINAL -> {
                    when {
                        row == null -> report.add(Finding(file.relativePath, Kind.VALID_UNREGISTERED_FINAL))
                        !matchesVerifiedFile(row, file.relativePath) -> report.add(
                            Finding(file.relativePath, Kind.METADATA_CONFLICT, row.id)
                        )
                        else -> report.add(Finding(file.relativePath, Kind.VERIFIED_REGISTERED, row.id))
                    }
                }
                ManagedAssetRecoveryInventory.Kind.STAGING_FILE,
                ManagedAssetRecoveryInventory.Kind.UNEXPECTED_ENTRY,
                ManagedAssetRecoveryInventory.Kind.SYMLINK -> report.add(
                    Finding(file.relativePath, Kind.STAGING_OR_UNEXPECTED_ENTRY, row?.id, file.kind)
                )
                else -> report.add(
                    Finding(file.relativePath, Kind.INVALID_OR_UNREADABLE_FINAL, row?.id, file.kind)
                )
            }
        }

        // Also report metadata rows with no corresponding verified scan entry.
        // The scanner may have skipped an unexpected parent directory; do not claim
        // the file is definitively absent or perform a filesystem cleanup.
        for (row in rows) {
            if (row.relativePath !in seenFilePaths) {
                val kind = if (!validMetadataShape(row)) Kind.METADATA_CONFLICT
                    else Kind.MISSING_OR_UNSCANNED_FINAL
                report.add(Finding(row.relativePath, kind, row.id))
            }
        }
        return report
    }

    private suspend fun loadRowsBounded(): List<AssetEntity> {
        val rows = ArrayList<AssetEntity>()
        var cursor: String? = null
        while (true) {
            // Request one extra record beyond the limit to detect truncation.
            val limit = minOf(pageSize.toLong(), maximumMetadataRows.toLong() - rows.size + 1L).toInt()
            if (limit <= 0) throw IOException("Asset metadata scan limit exceeded")
            val batch = assetDao.listAfterId(cursor, limit)
            if (batch.size > limit) throw IOException("Asset DAO returned too many records")
            if (batch.isEmpty()) break
            for (row in batch) {
                if (cursor != null && row.id <= cursor) {
                    throw IOException("Asset DAO pagination was not strictly ordered")
                }
                cursor = row.id
                rows.add(row)
                if (rows.size > maximumMetadataRows) {
                    throw IOException("Asset metadata scan limit exceeded")
                }
            }
            if (batch.size < limit) break
        }
        if (rows.map { it.relativePath }.toSet().size != rows.size) {
            throw IOException("Duplicate asset metadata paths")
        }
        return rows
    }

    private fun validMetadataShape(row: AssetEntity): Boolean {
        if (row.id.isBlank() || row.state != "finalized" || row.byteSize <= 0L) return false
        val mimeExtension = when (row.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "application/pdf" -> "pdf"
            else -> return false
        }
        if (!Regex("[0-9a-f]{64}").matches(row.sha256)) return false
        val expected = "assets/${row.sha256.substring(0, 2)}/${row.sha256.substring(2, 4)}/${row.sha256}.$mimeExtension"
        return row.relativePath == expected
    }

    private fun matchesVerifiedFile(row: AssetEntity, relativePath: String): Boolean {
        if (!validMetadataShape(row) || row.relativePath != relativePath) return false
        // The path is now known to be strictly canonical (never ../ or an absolute path),
        // and the inventory already validated its hash/signature without following links.
        return try {
            val path = File(privateFilesDir, relativePath).toPath()
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(path) == row.byteSize
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
