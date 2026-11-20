package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Independently retained, APP-PRIVATE portable archive COPY outside the pending
 * staging directory. The caller must first verify the exact draft against the
 * immutable Room v6 binding while holding the shared asset-publication lease.
 *
 * A matching private file is NOT an exported document, publication receipt,
 * destination durability guarantee, permission to release pins or authorization
 * to reclaim originals. No public URI, cleanup, overwrite, retry/adoption or
 * global ownership-index transition is implemented here.
 */
internal class ExportIndependentPrivateArchiveStore(
    privateRoot: File,
    private val limits: ExportPrivateStageLimits,
    private val syncDirectory: (File) -> Unit,
    private val afterWrittenChunk: (Long) -> Unit = {},
) {
    internal data class CopyObservedUntrusted(
        val operationId: String,
        val privateFileName: String,
        val bytes: Long,
        val sha256: String,
    )

    private val root = privateRoot.toPath().toAbsolutePath().normalize()
    private val pending = root.resolve("pending-export-copies")
    private val retained = root.resolve("retained-private-export-archives")

    suspend fun copyNew(
        draft: ExportPrivatePortableDraftWriter.DraftUntrusted,
    ): CopyObservedUntrusted = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!canonicalId(draft.operationId) || draft.fileName != "draft.notebook" ||
            draft.bytesExceedBudget()
        ) throw IOException("Invalid private archive draft expectation")
        requireDirectory(root)
        requireDirectory(pending)
        requireDirectory(pending.resolve(draft.operationId))

        val original = pending.resolve(draft.operationId).resolve("draft.notebook")
        val sourceBefore = metadata(original)
        if (!sourceBefore.isRegularFile || sourceBefore.isSymbolicLink ||
            sourceBefore.size() != draft.byteCount
        ) throw IOException("Unsafe or mismatched private archive source")
        if (readDigest(original, draft.byteCount) != draft.sha256) {
            throw IOException("Private draft differs from independently verified expectation")
        }

        if (!Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(retained)
            syncDirectory(root.toFile())
        }
        requireDirectory(retained)

        val finalName = draft.operationId + ".notebook"
        val destination = retained.resolve(finalName)
        val temporary = retained.resolve(draft.operationId + ".copying")
        // Collisions are fail-closed. Never overwrite an existing archive, temp,
        // or presumed recovered file and never clean up an uncertain copy.
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)
        ) throw IOException("Retained private archive already exists or is interrupted")

        FileChannel.open(
            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { output ->
            FileChannel.open(original, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { input ->
                if (input.size() != draft.byteCount) {
                    throw IOException("Draft size changed during retention")
                }
                val bytes = ByteArray(8192)
                val buffer = ByteBuffer.wrap(bytes)
                var copied = 0L
                try {
                    while (copied < draft.byteCount) {
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        buffer.limit(minOf(bytes.size.toLong(),
                            draft.byteCount - copied).toInt())
                        val read = input.read(buffer)
                        if (read <= 0) throw IOException("Private archive source read stalled")
                        val writing = ByteBuffer.wrap(bytes, 0, read)
                        while (writing.hasRemaining()) {
                            if (output.write(writing) <= 0) {
                                throw IOException("Private archive copy write stalled")
                            }
                        }
                        copied += read
                        afterWrittenChunk(copied)
                    }
                    if (input.size() != copied) {
                        throw IOException("Source changed length during private copy")
                    }
                } finally {
                    bytes.fill(0)
                }
            }
            output.force(true)
        }
        currentCoroutineContext().ensureActive()
        if (!stable(sourceBefore, metadata(original)) ||
            readDigest(original, draft.byteCount) != draft.sha256 ||
            readDigest(temporary, draft.byteCount) != draft.sha256
        ) throw IOException("Private archive changed before atomic handoff")

        // A same-directory atomic move avoids ever advertising a partially
        // written file at the final name. ATOMIC_MOVE without REPLACE_EXISTING
        // is still guarded by the shared OS lease and preflight collision check.
        requireDirectory(retained)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Private archive final-name collision")
        }
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(retained.toFile())
        currentCoroutineContext().ensureActive()
        if (readDigest(destination, draft.byteCount) != draft.sha256) {
            throw IOException("Retained archive changed after atomic handoff")
        }
        requireDirectory(root)
        requireDirectory(retained)
        currentCoroutineContext().ensureActive()
        CopyObservedUntrusted(draft.operationId, finalName, draft.byteCount, draft.sha256)
    }

    /**
     * Read-only restart diagnostics. An existing final file is not adopted and
     * this method does not infer or persist an export completion receipt.
     */
    suspend fun inspectReadOnly(
        draft: ExportPrivatePortableDraftWriter.DraftUntrusted,
    ): CopyObservedUntrusted? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!canonicalId(draft.operationId) || draft.fileName != "draft.notebook" ||
            draft.bytesExceedBudget()
        ) return@withContext null
        val destination = retained.resolve(draft.operationId + ".notebook")
        val attrs = try { metadata(destination) } catch (_: IOException) {
            return@withContext null
        }
        if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != draft.byteCount) {
            return@withContext null
        }
        if (readDigest(destination, draft.byteCount) != draft.sha256) {
            return@withContext null
        }
        currentCoroutineContext().ensureActive()
        CopyObservedUntrusted(
            draft.operationId, draft.operationId + ".notebook",
            draft.byteCount, draft.sha256,
        )
    }

    private fun ExportPrivatePortableDraftWriter.DraftUntrusted.bytesExceedBudget(): Boolean =
        byteCount <= 0L || byteCount > maxArchiveBytes() ||
            !Regex("[0-9a-f]{64}").matches(sha256)

    private fun maxArchiveBytes(): Long =
        limits.maxTotalAssetBytes + 2L * limits.maxMetadataBytes +
            1024L * (limits.maxAssets + 128L)

    private fun requireDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Missing, linked or unsafe export directory")
        }
    }

    private fun metadata(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)

    private fun stable(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.isRegularFile == b.isRegularFile && a.size() == b.size() &&
            a.lastModifiedTime() == b.lastModifiedTime() &&
            (a.fileKey() == null && b.fileKey() == null ||
                a.fileKey() != null && a.fileKey() == b.fileKey())

    private suspend fun readDigest(path: Path, expectedBytes: Long): String {
        val before = metadata(path)
        if (!before.isRegularFile || before.isSymbolicLink ||
            before.size() != expectedBytes
        ) throw IOException("Retained archive member has unsafe type or size")
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(8192)
            val buffer = ByteBuffer.wrap(bytes)
            var readTotal = 0L
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val n = input.read(buffer)
                    if (n == -1) break
                    if (n <= 0 || n.toLong() > expectedBytes - readTotal) {
                        throw IOException("Retained archive digest read failed")
                    }
                    md.update(bytes, 0, n)
                    readTotal += n
                }
                if (readTotal != expectedBytes || input.size() != readTotal) {
                    throw IOException("Retained archive length changed")
                }
            } finally {
                bytes.fill(0)
            }
        }
        if (!stable(before, metadata(path))) {
            throw IOException("Retained archive identity changed")
        }
        return md.digest().joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    }

    private fun canonicalId(value: String): Boolean =
        try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }
}
