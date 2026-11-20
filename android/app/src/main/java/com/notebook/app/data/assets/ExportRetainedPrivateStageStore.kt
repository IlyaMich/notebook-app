package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Per-operation limits, not automatically approved user-facing export quotas.
 * A stage is private and untrusted, even after every file was synced and re-read.
 */
internal data class ExportPrivateStageLimits(
    val maxAssets: Int,
    val maxAssetBytes: Long,
    val maxTotalAssetBytes: Long,
    val maxMetadataBytes: Long,
    val maxExistingStages: Int,
) {
    init {
        require(maxAssets in 1..64)
        require(maxAssetBytes in 1L..BoundedImageDecodePolicy.MAX_ENCODED_BYTES)
        require(maxTotalAssetBytes in 1L..(64L shl 20))
        require(maxMetadataBytes in 1L..(8L shl 20))
        require(maxExistingStages in 1..1024)
    }
}

internal data class ExportPrivateCopyObservation(
    val assetId: String,
    val byteCount: Long,
    val sha256: String,
    val fileName: String,
)

internal data class ExportUntrustedPrivateStageObservation(
    val operationId: String,
    val noteId: String,
    val noteRevision: Long,
    val snapshotSha256: String,
    val manifestSha256: String,
    val copies: List<ExportPrivateCopyObservation>,
)

/**
 * Real app-private, CREATE_NEW-only copy staging. No app entry point, publishing,
 * completion receipt, stage adoption, cleanup, deletion or GC authority.
 *
 * The caller must already hold the one shared publication lease and a committed
 * hold set. This class cannot attest to the lock, Room source authenticity or
 * power-loss durability on every filesystem. Every failure leaves staged files
 * untrusted and retained for a separate read-only recovery review.
 */
internal class ExportRetainedPrivateStageStore(
    private val privateRoot: File,
    private val limits: ExportPrivateStageLimits,
    private val syncDirectory: (File) -> Unit,
    private val forceFile: (FileChannel) -> Unit = { it.force(true) },
    private val afterChunk: (String, Long) -> Unit = { _, _ -> },
) {
    private val root = privateRoot.toPath().toAbsolutePath().normalize()
    private val parent = root.resolve("pending-export-copies")

    fun createNew(operationId: String): Stage {
        val uuid = canonicalId(operationId)
        requireDirectory(root)
        synchronized(creationMonitor) {
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(parent)
            }
            requireDirectory(parent)
            syncDirectory(root.toFile())
            var count = 0
            Files.newDirectoryStream(parent).use { items ->
                for (item in items) {
                    if (!Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS) ||
                        !UUID_NAME.matches(item.fileName.toString())
                    ) throw IOException("Unexpected private export-stage entry")
                    count++
                    if (count >= limits.maxExistingStages) {
                        throw IOException("Private export-stage count exceeds budget")
                    }
                }
            }
            val destination = parent.resolve(uuid)
            // No existing operation may ever be adopted or overwritten.
            Files.createDirectory(destination)
            syncDirectory(parent.toFile())
            requireDirectory(destination)
            return Stage(uuid, destination)
        }
    }

    inner class Stage internal constructor(
        val operationId: String,
        private val directory: Path,
    ) {
        private var tainted = false
        private var totalAssetBytes = 0L
        private val copies = linkedMapOf<String, ExportPrivateCopyObservation>()
        private var snapshotHash: String? = null
        private var manifestHash: String? = null

        suspend fun copyAsset(
            expected: ExpectedEncodedImage,
            borrowedBytes: ByteArray,
        ): ExportPrivateCopyObservation {
            ensureUsable()
            try {
                val id = canonicalId(expected.assetId)
                if (copies.size >= limits.maxAssets || copies.containsKey(id) ||
                    expected.encodedBytes <= 0L ||
                    expected.encodedBytes > limits.maxAssetBytes ||
                    expected.encodedBytes > limits.maxTotalAssetBytes - totalAssetBytes ||
                    expected.encodedBytes != borrowedBytes.size.toLong() ||
                    !SHA.matches(expected.sha256)
                ) throw IOException("Invalid, duplicate or over-budget source asset")
                val name = "asset-$id.bin"
                val observation = writeExact(name, borrowedBytes, expected.sha256)
                totalAssetBytes += observation.byteCount
                val copy = ExportPrivateCopyObservation(
                    id, observation.byteCount, observation.sha256, name,
                )
                copies[id] = copy
                return copy
            } catch (failure: Throwable) {
                tainted = true
                throw failure
            }
        }

        suspend fun writeCapturedSnapshot(bytes: ByteArray): String {
            ensureUsable()
            try {
                if (snapshotHash != null || bytes.size.toLong() > limits.maxMetadataBytes) {
                    throw IOException("Snapshot already attempted or over budget")
                }
                val hash = sha(bytes)
                writeExact("snapshot.json", bytes, hash)
                snapshotHash = hash
                return hash
            } catch (failure: Throwable) {
                tainted = true
                throw failure
            }
        }

        suspend fun writeCopyManifest(bytes: ByteArray): String {
            ensureUsable()
            try {
                if (snapshotHash == null || manifestHash != null ||
                    bytes.size.toLong() > limits.maxMetadataBytes
                ) throw IOException("Manifest out of order or over budget")
                val hash = sha(bytes)
                writeExact("manifest.json", bytes, hash)
                manifestHash = hash
                return hash
            } catch (failure: Throwable) {
                tainted = true
                throw failure
            }
        }

        fun observeUntrusted(
            noteId: String,
            noteRevision: Long,
            expectedAssetIds: List<String>,
        ): ExportUntrustedPrivateStageObservation {
            ensureUsable()
            if (snapshotHash == null || manifestHash == null ||
                expectedAssetIds.sorted() != copies.keys.sorted()
            ) throw IOException("Private staging is incomplete")
            return ExportUntrustedPrivateStageObservation(
                operationId, noteId, noteRevision,
                requireNotNull(snapshotHash), requireNotNull(manifestHash),
                copies.values.sortedBy { it.assetId },
            )
        }

        private fun ensureUsable() {
            if (tainted) throw IOException("Private stage tainted; never retry or adopt it")
        }

        private suspend fun writeExact(
            name: String, bytes: ByteArray, expectedSha: String,
        ): ExportPrivateCopyObservation {
            val context = currentCoroutineContext()
            context.ensureActive()
            requireDirectory(root)
            requireDirectory(parent)
            requireDirectory(directory)
            if (sha(bytes) != expectedSha) throw IOException("Borrowed bytes digest mismatch")
            val target = directory.resolve(name)
            FileChannel.open(
                target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    context.ensureActive()
                    val chunk = minOf(8192, bytes.size - offset)
                    val buffer = ByteBuffer.wrap(bytes, offset, chunk)
                    while (buffer.hasRemaining()) {
                        if (channel.write(buffer) <= 0) throw IOException("Private stage write stalled")
                    }
                    offset += chunk
                    afterChunk(name, offset.toLong())
                }
                forceFile(channel)
            }
            context.ensureActive()
            if (sha(bytes) != expectedSha) throw IOException("Borrowed bytes mutated during copy")
            // Independent bounded re-read (never just trust the writer's digest).
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var total = 0L
            try {
                FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    if (input.size() != bytes.size.toLong()) throw IOException("Destination size mismatch")
                    val chunk = ByteBuffer.wrap(buffer)
                    while (true) {
                        context.ensureActive()
                        chunk.clear()
                        val read = input.read(chunk)
                        if (read == -1) break
                        if (read == 0 || read.toLong() > bytes.size.toLong() - total) {
                            throw IOException("Destination copy reread failed")
                        }
                        digest.update(buffer, 0, read)
                        total += read
                    }
                    if (input.size() != total) throw IOException("Destination changed during reread")
                }
            } finally {
                buffer.fill(0)
            }
            if (total != bytes.size.toLong() || hex(digest.digest()) != expectedSha) {
                throw IOException("Destination copy digest or count mismatch")
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Destination copy file type changed")
            }
            syncDirectory(directory.toFile())
            requireDirectory(root)
            requireDirectory(parent)
            requireDirectory(directory)
            context.ensureActive()
            return ExportPrivateCopyObservation("", total, expectedSha, name)
        }
    }

    private fun requireDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Missing, linked or invalid private export directory")
        }
    }

    private fun canonicalId(value: String): String {
        val uuid = try { UUID.fromString(value) } catch (_: IllegalArgumentException) {
            throw IOException("Invalid export UUID")
        }
        if (uuid.toString() != value) throw IOException("Noncanonical export UUID")
        return value
    }

    private companion object {
        val creationMonitor = Any() // one-process only; never a replacement for the OS lease
        val SHA = Regex("[0-9a-f]{64}")
        val UUID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        fun hex(bytes: ByteArray): String = bytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        fun sha(bytes: ByteArray): String =
            hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
