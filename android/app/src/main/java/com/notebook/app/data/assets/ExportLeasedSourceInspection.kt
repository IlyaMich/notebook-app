package com.notebook.app.data.assets

import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.dao.AssetDao
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** An observation of checked original bytes, NOT durable export protection or a copy receipt. */
internal data class ExportVerifiedSourceObservation(
    val images: List<VerifiedDecodedImageInfo>,
    val totalEncodedBytes: Long,
)

/**
 * I4B-2C-2B: exact original-byte inspection. Must ONLY be invoked from a callback
 * already holding the one shared asset-publication lease. This class never acquires
 * that non-reentrant lease and never reads from, stages, or publishes an export.
 * Original arrays are bounded and cleared on every exit, including cancellation.
 */
internal class ExportLeasedSourceByteInspector(
    private val privateFilesDir: File,
    private val assetDao: AssetDao,
) {
    suspend fun inspectUnderHeldLease(
        plan: ExportCopyAdmissionPlan,
    ): ExportVerifiedSourceObservation = withContext(Dispatchers.IO) {
        val observed = ArrayList<VerifiedDecodedImageInfo>()
        var total = 0L
        for (expected in plan.images) {
            currentCoroutineContext().ensureActive()
            BoundedImageDecodePolicy.requireEncoded(expected.mimeType, expected.encodedBytes)
            if (expected.encodedBytes > plan.totalEncodedBytes - total) {
                throw IOException("Export source total exceeds admitted budget")
            }
            val before = assetDao.findById(expected.id)
                ?: throw IOException("Admitted source is no longer registered")
            if (before.id != expected.id || before.sha256 != expected.sha256 ||
                before.mimeType != expected.mimeType || before.byteSize != expected.encodedBytes ||
                before.relativePath != expected.relativePath || before.state != "finalized"
            ) throw IOException("Registered source metadata changed after capture")

            val path = checkedPath(expected)
            val priorIdentity = Files.readAttributes(
                path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
            )
            if (!priorIdentity.isRegularFile || priorIdentity.size() != expected.encodedBytes) {
                throw IOException("Invalid original file identity or byte size")
            }
            val bytes = ByteArray(expected.encodedBytes.toInt())
            try {
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() != expected.encodedBytes) {
                        throw IOException("Original length changed before reading")
                    }
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        currentCoroutineContext().ensureActive()
                        if (channel.read(buffer) <= 0) {
                            throw IOException("Original read truncated or stalled")
                        }
                    }
                    if (channel.size() != expected.encodedBytes) {
                        throw IOException("Original length changed during reading")
                    }
                }
                val info = BoundedImageBytesInspector.inspect(
                    ExpectedEncodedImage(expected.id, expected.mimeType,
                        expected.sha256, expected.encodedBytes), bytes,
                )
                currentCoroutineContext().ensureActive()
                val afterIdentity = Files.readAttributes(
                    path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
                )
                if (!afterIdentity.isRegularFile ||
                    afterIdentity.size() != expected.encodedBytes ||
                    (priorIdentity.fileKey() != null &&
                        priorIdentity.fileKey() != afterIdentity.fileKey())
                ) throw IOException("Original path identity changed during inspection")
                val after = assetDao.findById(expected.id)
                    ?: throw IOException("Registered source disappeared during inspection")
                if (after != before) throw IOException("Registered source changed during inspection")
                currentCoroutineContext().ensureActive()
                observed.add(info)
                total += expected.encodedBytes
            } finally {
                bytes.fill(0)
            }
        }
        currentCoroutineContext().ensureActive()
        if (total != plan.totalEncodedBytes) {
            throw IOException("Verified source total differs from admitted total")
        }
        ExportVerifiedSourceObservation(Collections.unmodifiableList(ArrayList(observed)), total)
    }

    private fun checkedPath(expected: SnapshotAssetMetadata): Path {
        val hash = expected.sha256
        if (!Regex("[0-9a-f]{64}").matches(hash)) {
            throw IOException("Invalid source SHA-256")
        }
        val extension = when (expected.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> throw IOException("Unsupported original MIME")
        }
        val required = "assets/${hash.substring(0, 2)}/${hash.substring(2, 4)}/$hash.$extension"
        if (expected.relativePath != required) {
            throw IOException("Noncanonical original path")
        }
        val root = privateFilesDir.toPath()
        val assets = root.resolve("assets")
        val shard1 = assets.resolve(hash.substring(0, 2))
        val shard2 = shard1.resolve(hash.substring(2, 4))
        for (dir in listOf(root, assets, shard1, shard2)) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Original directory missing, linked, or invalid")
            }
        }
        val file = shard2.resolve("$hash.$extension")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Original missing, linked, or invalid")
        }
        return file
    }
}

/** Composes admission and exact-byte inspection so both run within a SINGLE outer lease. */
internal class ExportSourceInspectionCoordinator(
    private val admission: ExportLeaseScopedAdmission,
    private val inspectSources: suspend (ExportCopyAdmissionPlan) -> ExportVerifiedSourceObservation,
) {
    suspend fun inspect(
        request: SnapshotRequest,
        limits: ExportCopyAdmissionLimits,
    ): ExportLeaseScopedAdmissionResult<ExportVerifiedSourceObservation> =
        admission.withAdmittedSnapshot(request, limits) { _, plan ->
            currentCoroutineContext().ensureActive()
            inspectSources(plan)
        }

    companion object {
        fun forRoom(privateFilesDir: File, db: AppDatabase): ExportSourceInspectionCoordinator {
            val reader = ExportLeasedSourceByteInspector(privateFilesDir, db.assetDao())
            return ExportSourceInspectionCoordinator(
                ExportLeaseScopedAdmission.forRoom(privateFilesDir, db),
                reader::inspectUnderHeldLease,
            )
        }
    }
}
