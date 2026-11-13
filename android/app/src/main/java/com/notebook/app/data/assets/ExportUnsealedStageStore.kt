package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** An observation about untrusted synthetic bytes. NOT a protected export receipt. */
internal data class UntrustedSyntheticWrite(
    val role: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * I4-B-2A review draft: isolated synthetic fixture staging ONLY.
 *
 * No managed-asset reads, Room, publication lease, decoding, seal, archive, recovery,
 * cleanup, ownership authority, or production wiring. Neither successful creation nor a
 * successful write claims that an export snapshot is protected or durably complete.
 *
 * Callers MUST supply a new synthetic-fixture directory and a directory sync adapter.
 * Directory-sync fakes are suitable for JVM tests only, NOT for production durability.
 * Parent-identity preflight does not defend against malicious concurrent path replacement;
 * API 26/37 confinement is a separate later gate.
 */
internal class ExportUnsealedStageStore(
    private val syntheticFixtureRoot: File,
    private val policy: ExportUnsealedStagePolicy,
    private val syncDirectory: (File) -> Unit,
    private val forceFile: (FileChannel) -> Unit = { it.force(true) },
    private val onChunkWritten: (String, Long) -> Unit = { _, _ -> },
    private val newOperationId: () -> UUID = { UUID.randomUUID() },
) {
    private val root: Path = syntheticFixtureRoot.toPath().toAbsolutePath().normalize()
    private val parent: Path = root.resolve("export-source-snapshots")

    /** Creates only a fresh, unsealed UUID operation directory. Never adopts an old stage. */
    fun createFreshStage(): UnsealedSyntheticStage = synchronized(admissionMonitor) {
        requireRealDirectory(root)
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(parent)
                syncDirectory(root.toFile())
            } catch (collision: FileAlreadyExistsException) {
                // Another cooperating creator may have created this directory.
                requireRealDirectory(parent)
            }
        }
        requireRealDirectory(root)
        requireRealDirectory(parent)
        // Also sync an older parent entry: an earlier creation might have failed its fsync.
        syncDirectory(root.toFile())
        var count = 0
        Files.newDirectoryStream(parent).use { stream ->
            for (entry in stream) {
                // Do not descend into other stages or follow unexpected children.
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ||
                    !UUID_NAME.matches(entry.fileName.toString())
                ) throw IOException("Unexpected or linked export staging entry")
                count++
                if (count >= policy.maxExistingStages) {
                    throw IOException("Synthetic export stage-count budget exhausted")
                }
            }
        }
        val id = newOperationId()
        val stage = parent.resolve(id.toString())
        // UUID is typed and generated locally, never an arbitrary caller path.
        Files.createDirectory(stage)
        // Failure here leaves the new stage untrusted and deliberately intact.
        syncDirectory(parent.toFile())
        requireRealDirectory(root)
        requireRealDirectory(parent)
        requireRealDirectory(stage)
        UnsealedSyntheticStage(id, stage)
    }

    internal inner class UnsealedSyntheticStage internal constructor(
        val operationId: UUID,
        private val directory: Path,
    ) {
        private var snapshotAttempted = false
        private var manifestAttempted = false
        private var tainted = false
        private var accountedBytes = 0L

        fun writeSyntheticSnapshot(exactBytes: ByteArray): UntrustedSyntheticWrite =
            writeFixed("snapshot.bin", exactBytes, policy.maxSnapshotBytes)

        fun writeSyntheticManifest(exactBytes: ByteArray): UntrustedSyntheticWrite =
            writeFixed("manifest.bin", exactBytes, policy.maxManifestBytes)

        private fun writeFixed(name: String, bytes: ByteArray, perFileLimit: Long): UntrustedSyntheticWrite =
            synchronized(this) {
                if (tainted) throw IOException("Untrusted stage is tainted; no retry or repair")
                when (name) {
                    "snapshot.bin" -> {
                        if (snapshotAttempted) throw IOException("Synthetic snapshot already attempted")
                        snapshotAttempted = true
                    }
                    "manifest.bin" -> {
                        if (manifestAttempted) throw IOException("Synthetic manifest already attempted")
                        manifestAttempted = true
                    }
                    else -> throw IOException("Unsupported synthetic staging filename")
                }
                try {
                    requireRealDirectory(root)
                    requireRealDirectory(parent)
                    requireRealDirectory(directory)
                    val length = bytes.size.toLong()
                    if (length > perFileLimit || length > policy.maxStageBytes - accountedBytes) {
                        throw IOException("Synthetic export staging byte limit exceeded")
                    }
                    // Reserve the full attempted size before creating the file. A failure never
                    // returns this budget, nor does it enable retry in the same stage.
                    accountedBytes += length
                    val digest = MessageDigest.getInstance("SHA-256")
                    val file = directory.resolve(name)
                    FileChannel.open(
                        file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { channel ->
                        var offset = 0
                        while (offset < bytes.size) {
                            val chunk = minOf(8_192, bytes.size - offset)
                            val buffer = ByteBuffer.wrap(bytes, offset, chunk)
                            while (buffer.hasRemaining()) {
                                val written = channel.write(buffer)
                                if (written <= 0) throw IOException("Synthetic stage write made no progress")
                            }
                            digest.update(bytes, offset, chunk)
                            offset += chunk
                            onChunkWritten(name, offset.toLong())
                        }
                        forceFile(channel)
                    }
                    requireRealDirectory(root)
                    requireRealDirectory(parent)
                    requireRealDirectory(directory)
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("Synthetic staging file is not regular")
                    }
                    syncDirectory(directory.toFile())
                    UntrustedSyntheticWrite(
                        role = name,
                        byteCount = length,
                        sha256 = digest.digest().joinToString("") {
                            "%02x".format(it.toInt() and 0xff)
                        },
                    )
                } catch (failure: Throwable) {
                    // Never delete a suspicious stage or allow further mutation after failure.
                    tainted = true
                    throw failure
                }
            }
    }

    private fun requireRealDirectory(dir: Path) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Missing, linked, or invalid synthetic staging directory")
        }
    }

    private companion object {
        val UUID_NAME: Regex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val admissionMonitor = Any() // JVM-only admission serialization; NOT cross-process.
    }
}
