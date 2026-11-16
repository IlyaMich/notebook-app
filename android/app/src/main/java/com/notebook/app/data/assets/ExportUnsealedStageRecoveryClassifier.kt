package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID

/**
 * Unsealed, UNTRUSTED shape observations only. In particular, BOTH_FILES_PRESENT does
 * not attest file contents, fsync, ownership, crash durability, or export protection.
 */
internal enum class UnsealedStageRecoveryShape {
    EMPTY,
    SNAPSHOT_ONLY,
    MANIFEST_ONLY,
    BOTH_FILES_PRESENT,
}

internal data class UnsealedStageRecoveryFinding(
    val operationId: UUID,
    val shape: UnsealedStageRecoveryShape,
    val snapshotBytes: Long?,
    val manifestBytes: Long?,
)

internal data class UnsealedStageRecoveryInventory(
    val stagingParentPresent: Boolean,
    val findings: List<UnsealedStageRecoveryFinding>,
)

/**
 * I4B-2C-3C-2: READ-ONLY synthetic stage shape classifier for diagnostic use only.
 *
 * Never creates, opens for writing, repairs, adopts, deletes, seals, or publishes a stage.
 * Inspects direct directory entries and no-follow file attributes, NEVER payload contents.
 * Unexpected entries, symlinks, wrong file types, and breached budgets fail closed.
 * Scans are not atomic against external mutation or process death and confer ZERO asset
 * ownership, protection, export, or reclamation authority even after a successful scan.
 * This classifier is NOT connected to application startup or any production export path.
 */
internal class ExportUnsealedStageRecoveryClassifier(
    private val syntheticFixtureRoot: File,
    private val policy: ExportUnsealedStagePolicy,
) {
    private val root: Path = syntheticFixtureRoot.toPath().toAbsolutePath().normalize()
    private val parent: Path = root.resolve("export-source-snapshots")

    fun inspectReadOnly(): UnsealedStageRecoveryInventory {
        requireDirectory(root)
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            return UnsealedStageRecoveryInventory(false, emptyList())
        }
        requireDirectory(parent)
        val findings = ArrayList<UnsealedStageRecoveryFinding>()
        Files.newDirectoryStream(parent).use { entries ->
            for (stage in entries) {
                if (findings.size >= policy.maxExistingStages) {
                    throw IOException("Unsealed stage inventory count exceeds configured limit")
                }
                val name = stage.fileName.toString()
                if (!UUID_NAME.matches(name)) {
                    throw IOException("Unexpected unsealed stage entry name")
                }
                val operationId = try { UUID.fromString(name) } catch (_: IllegalArgumentException) {
                    throw IOException("Invalid unsealed stage operation ID")
                }
                if (operationId.toString() != name) {
                    throw IOException("Noncanonical unsealed stage operation ID")
                }
                findings.add(inspectOneStage(stage, operationId))
            }
        }
        requireDirectory(root)
        requireDirectory(parent)
        findings.sortBy { it.operationId.toString() }
        return UnsealedStageRecoveryInventory(
            true, Collections.unmodifiableList(ArrayList(findings)),
        )
    }

    private fun inspectOneStage(stage: Path, operationId: UUID): UnsealedStageRecoveryFinding {
        val initial = directoryAttributes(stage)
        var snapshotBytes: Long? = null
        var manifestBytes: Long? = null
        var childCount = 0
        Files.newDirectoryStream(stage).use { children ->
            for (child in children) {
                childCount++
                if (childCount > 2) throw IOException("Unexpected additional unsealed stage entry")
                val name = child.fileName.toString()
                val limit = when (name) {
                    "snapshot.bin" -> policy.maxSnapshotBytes
                    "manifest.bin" -> policy.maxManifestBytes
                    else -> throw IOException("Unexpected unsealed stage payload entry")
                }
                val attributes = Files.readAttributes(
                    child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isRegularFile || attributes.size() > limit || attributes.size() < 0L) {
                    throw IOException("Unsafe or over-budget unsealed stage payload")
                }
                when (name) {
                    "snapshot.bin" -> snapshotBytes = attributes.size()
                    "manifest.bin" -> manifestBytes = attributes.size()
                }
            }
        }
        val length = (snapshotBytes ?: 0L)
        val remaining = policy.maxStageBytes - length
        if (remaining < 0L || (manifestBytes ?: 0L) > remaining) {
            throw IOException("Unsealed stage aggregate byte limit exceeded")
        }
        val after = directoryAttributes(stage)
        if (initial.fileKey() != null && initial.fileKey() != after.fileKey()) {
            throw IOException("Unsealed stage directory identity changed during inspection")
        }
        val shape = when {
            snapshotBytes == null && manifestBytes == null -> UnsealedStageRecoveryShape.EMPTY
            snapshotBytes != null && manifestBytes == null -> UnsealedStageRecoveryShape.SNAPSHOT_ONLY
            snapshotBytes == null -> UnsealedStageRecoveryShape.MANIFEST_ONLY
            else -> UnsealedStageRecoveryShape.BOTH_FILES_PRESENT
        }
        return UnsealedStageRecoveryFinding(operationId, shape, snapshotBytes, manifestBytes)
    }

    private fun directoryAttributes(path: Path): BasicFileAttributes {
        val attrs = Files.readAttributes(
            path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        if (!attrs.isDirectory) throw IOException("Missing, linked or invalid unsealed stage directory")
        return attrs
    }

    private fun requireDirectory(path: Path) { directoryAttributes(path) }

    private companion object {
        val UUID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
