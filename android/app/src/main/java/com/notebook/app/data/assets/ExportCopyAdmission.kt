package com.notebook.app.data.assets

import java.io.IOException
import java.util.Collections

/** Explicit review limits for an untrusted metadata-only export-copy plan. */
internal data class ExportCopyAdmissionLimits(
    val maxAssets: Int,
    val maxAssetBytes: Long,
    val maxTotalBytes: Long,
) {
    init {
        require(maxAssets > 0)
        require(maxAssetBytes > 0L)
        require(maxTotalBytes > 0L)
    }
}

/**
 * I4B-2C-1: a detached, bounded plan derived from metadata, NOT protected copies.
 * This does not inspect source bytes, acquire a lease, authorize ownership, or stage files.
 * A future copier must capture under the outer acquisition lease, independently re-read
 * and verify the registered source bytes, inspect destination bytes and establish durable
 * destination protection before returning a protected-export result.
 */
internal data class ExportCopyAdmissionPlan(
    val images: List<SnapshotAssetMetadata>,
    val totalEncodedBytes: Long,
)

internal object ExportCopyAdmission {
    private val sha256Pattern = Regex("[0-9a-f]{64}")

    fun plan(
        snapshot: SnapshotResult.Complete,
        limits: ExportCopyAdmissionLimits,
    ): ExportCopyAdmissionPlan {
        val images = snapshot.assets
        if (images.size > limits.maxAssets) throw IOException("Export asset count exceeds limit")
        val ids = HashSet<String>()
        var total = 0L
        for (image in images) {
            if (image.id.isBlank() || !ids.add(image.id) ||
                image.state != "finalized" || !sha256Pattern.matches(image.sha256) ||
                image.encodedBytes <= 0L || image.encodedBytes > limits.maxAssetBytes
            ) throw IOException("Invalid or duplicate source image metadata")
            val suffix = when (image.mimeType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> throw IOException("Unsupported source image MIME")
            }
            val digest = image.sha256
            val requiredPath = "assets/${digest.substring(0, 2)}/${digest.substring(2, 4)}/$digest.$suffix"
            if (image.relativePath != requiredPath) throw IOException("Invalid image source path")
            if (image.encodedBytes > limits.maxTotalBytes - total) {
                throw IOException("Export total source bytes exceed limit")
            }
            total += image.encodedBytes
        }
        // Every listed image needs an owner, and every owner needs a listed image.
        val referenced = HashSet<String>()
        val ownerKeys = HashSet<String>()
        for (owner in snapshot.imageOwners) {
            if (owner.noteId != snapshot.note.id || owner.pageId.isBlank() ||
                owner.elementId.isBlank() || owner.assetId !in ids ||
                !ownerKeys.add(owner.pageId + "\u0000" + owner.elementId)
            ) throw IOException("Invalid image ownership observation")
            referenced.add(owner.assetId)
        }
        if (referenced != ids || snapshot.totalEncodedSourceBytes != total) {
            throw IOException("Inconsistent source image metadata totals or ownership")
        }
        return ExportCopyAdmissionPlan(
            Collections.unmodifiableList(ArrayList(images)), total,
        )
    }
}
