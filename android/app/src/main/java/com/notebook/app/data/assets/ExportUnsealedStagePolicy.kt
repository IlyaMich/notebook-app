package com.notebook.app.data.assets

/**
 * I4-B-2A review draft. EXPLICIT synthetic-fixture budgets, not approved export quotas.
 * The values are caller supplied; this class intentionally has no production defaults.
 */
internal data class ExportUnsealedStagePolicy(
    val maxSnapshotBytes: Long,
    val maxManifestBytes: Long,
    val maxStageBytes: Long,
    /** Maximum total existing UUID operation directories, including a newly admitted stage. */
    val maxExistingStages: Int,
) {
    init {
        require(maxSnapshotBytes > 0L) { "Snapshot budget must be positive" }
        require(maxManifestBytes > 0L) { "Manifest budget must be positive" }
        require(maxStageBytes > 0L) { "Stage budget must be positive" }
        require(maxExistingStages > 0) { "Stage-count budget must be positive" }
    }
}
