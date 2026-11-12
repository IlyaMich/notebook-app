package com.notebook.app.data.assets

/** Explicit caller policy. No defaults here are approved production quotas. */
internal data class SnapshotLimits(
    val maxPages: Int,
    val maxElements: Int,
    val maxDistinctAssets: Int,
    val maxAssetBytes: Long,
    val maxTotalAssetBytes: Long,
    val maxCanonicalJsonBytes: Long,
    val maxFolderDepth: Int,
    val maxJsonDepth: Int,
)

internal data class SnapshotRequest(val noteId: String, val limits: SnapshotLimits)

internal data class SnapshotNote(
    val id: String, val notebookId: String, val title: String,
    val createdAtMillis: Long, val updatedAtMillis: Long,
    val lastOpenedAtMillis: Long?, val revision: Long,
)

internal data class SnapshotElement(
    val id: String, val pageId: String, val orderIndex: Long,
    val kind: String, val canonicalEnvelope: String, val revision: Long,
)

internal data class SnapshotPage(
    val id: String, val noteId: String, val orderIndex: Long,
    val width: Double, val height: Double,
    val canonicalBackground: String, val revision: Long,
    val elements: List<SnapshotElement>,
)

/** Each canonical owner remains independent, including owners sharing an asset. */
internal data class SnapshotImageOwner(
    val noteId: String, val pageId: String, val elementId: String, val assetId: String,
)

/** Validated metadata only; NOT a certificate about original bytes. */
internal data class SnapshotAssetMetadata(
    val id: String, val sha256: String, val mimeType: String,
    val encodedBytes: Long, val relativePath: String, val state: String,
)

internal enum class SnapshotRejection {
    INVALID_REQUEST, NOTE_NOT_FOUND, INELIGIBLE_NOTE, INVALID_DOCUMENT_STRUCTURE,
    UNSUPPORTED_MEDIA, OWNER_EDGE_MISMATCH, ASSET_METADATA_MISSING,
    ASSET_METADATA_INVALID, RESOURCE_LIMIT_EXCEEDED, DATABASE_READ_FAILED,
}

internal sealed interface SnapshotResult {
    /**
     * Detached source observation, NOT an export-protected or verified asset snapshot.
     * A future orchestrator must acquire the shared exclusive lease BEFORE capture
     * and retain it until independently verified durable copies are complete.
     * Local consistency establishes no global ownership completeness or GC authority.
     * Reader-produced collections are defensive, unmodifiable copies.
     */
    data class Complete(
        val note: SnapshotNote,
        val pages: List<SnapshotPage>,
        val imageOwners: List<SnapshotImageOwner>,
        val assets: List<SnapshotAssetMetadata>,
        val canonicalJsonBytes: Long,
        val totalEncodedSourceBytes: Long,
    ) : SnapshotResult

    /** No private payloads, identifiers, paths or database exception messages. */
    data class Rejected(val reason: SnapshotRejection) : SnapshotResult
}
