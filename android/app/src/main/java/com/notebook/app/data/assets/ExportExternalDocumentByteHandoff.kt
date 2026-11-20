package com.notebook.app.data.assets

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A single-use writable destination and a fresh independent read handle.
 * This API cannot certify an external provider's persistence or atomicity.
 * Callers must acquire a NEW SAF ACTION_CREATE_DOCUMENT URI per attempt.
 */
internal interface ExportExternalDocumentDestination {
    interface Sink : Closeable {
        val output: OutputStream
        /** Must fail closed if the provider cannot synchronize the bytes. */
        fun syncOrThrow()
    }

    fun openWriteOnce(): Sink
    fun openReadBack(): InputStream
}

/**
 * Copies only an immutable-identity, app-private retained archive; never opens
 * managed originals or removes an interrupted external destination.
 *
 * A matching readback is an UNTRUSTED byte observation, not an independently
 * durable publication receipt. Never releases export holds, changes Room, or
 * promotes the global ownership gate.
 */
internal class ExportExternalDocumentByteHandoff(
    privateRoot: File,
    private val limits: ExportPrivateStageLimits,
    private val afterCopiedChunk: (Long) -> Unit = {},
    private val afterReadbackChunk: (Long) -> Unit = {},
) {
    internal data class RereadObservedUntrusted(
        val operationId: String,
        val byteCount: Long,
        val sha256: String,
    )

    private val root = privateRoot.toPath().toAbsolutePath().normalize()
    private val retained = root.resolve("retained-private-export-archives")
    private val shaPattern = Regex("[0-9a-f]{64}")

    suspend fun copyAndReread(
        expected: ExportIndependentPrivateArchiveStore.CopyObservedUntrusted,
        destination: ExportExternalDocumentDestination,
    ): RereadObservedUntrusted = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!canonicalId(expected.operationId) ||
            expected.privateFileName != expected.operationId + ".notebook" ||
            !shaPattern.matches(expected.sha256) ||
            expected.bytes <= 0L || expected.bytes > maxArchiveBytes()
        ) throw IOException("Unsafe or over-budget export byte expectation")

        requireDirectory(root)
        requireDirectory(retained)
        val source = retained.resolve(expected.privateFileName)
        val before = fileAttrs(source)
        if (!before.isRegularFile || before.isSymbolicLink ||
            before.size() != expected.bytes
        ) throw IOException("Retained private archive missing or changed")

        // Independent preflight: even a caller-supplied 'observed' record is
        // not accepted as a file-integrity proof.
        if (hashFile(source, expected.bytes) != expected.sha256) {
            throw IOException("Retained private archive digest mismatch")
        }

        // A SAF document may already be visible or partially written once
        // openWriteOnce() begins. On ANY exception, leave it alone and retain
        // every Room hold; do not attempt a blind retry or provider deletion.
        destination.openWriteOnce().use { sink ->
            FileChannel.open(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != expected.bytes) {
                    throw IOException("Retained source size changed")
                }
                val md = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArray(8192)
                val buffer = ByteBuffer.wrap(bytes)
                var count = 0L
                try {
                    while (count < expected.bytes) {
                        currentCoroutineContext().ensureActive()
                        buffer.clear()
                        buffer.limit(minOf(bytes.size.toLong(),
                            expected.bytes - count).toInt())
                        val n = channel.read(buffer)
                        if (n <= 0) throw IOException("Retained source read stalled")
                        sink.output.write(bytes, 0, n)
                        md.update(bytes, 0, n)
                        count += n
                        afterCopiedChunk(count)
                    }
                    if (channel.size() != count ||
                        digestHex(md.digest()) != expected.sha256
                    ) throw IOException("Source changed during external copy")
                } finally {
                    bytes.fill(0)
                }
            }
            sink.output.flush()
            // Providers lacking a supported data-sync contract MUST throw.
            sink.syncOrThrow()
        }
        currentCoroutineContext().ensureActive()

        destination.openReadBack().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(8192)
            var count = 0L
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(bytes)
                    if (n == -1) break
                    if (n <= 0 || n.toLong() > expected.bytes - count) {
                        throw IOException("External provider readback is incomplete or oversized")
                    }
                    md.update(bytes, 0, n)
                    count += n
                    afterReadbackChunk(count)
                }
                if (count != expected.bytes || digestHex(md.digest()) != expected.sha256) {
                    throw IOException("External document readback does not match private archive")
                }
            } finally {
                bytes.fill(0)
            }
        }

        // Re-read the exact private source after provider roundtrip to detect
        // replacement or mutation. Neither this nor an external readback proves
        // provider-level durability after process death/power loss.
        if (!stable(before, fileAttrs(source)) ||
            hashFile(source, expected.bytes) != expected.sha256
        ) throw IOException("Private archive changed during external handoff")
        currentCoroutineContext().ensureActive()
        RereadObservedUntrusted(expected.operationId, expected.bytes, expected.sha256)
    }

    private fun maxArchiveBytes(): Long =
        limits.maxTotalAssetBytes + 2L * limits.maxMetadataBytes +
            1024L * (limits.maxAssets + 128L)

    private fun requireDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Missing or linked private archive directory")
        }
    }

    private fun fileAttrs(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun stable(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.isRegularFile == b.isRegularFile && a.size() == b.size() &&
            a.lastModifiedTime() == b.lastModifiedTime() &&
            (a.fileKey() == null && b.fileKey() == null ||
                a.fileKey() != null && a.fileKey() == b.fileKey())

    private suspend fun hashFile(path: Path, expectedBytes: Long): String {
        val before = fileAttrs(path)
        if (!before.isRegularFile || before.isSymbolicLink ||
            before.size() != expectedBytes
        ) throw IOException("Unsafe private archive member")
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteArray(8192)
            val buffer = ByteBuffer.wrap(bytes)
            var count = 0L
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val n = channel.read(buffer)
                    if (n == -1) break
                    if (n <= 0 || n.toLong() > expectedBytes - count) {
                        throw IOException("Private archive digest read failed")
                    }
                    md.update(bytes, 0, n)
                    count += n
                }
                if (channel.size() != count || count != expectedBytes) {
                    throw IOException("Private archive size changed during read")
                }
            } finally {
                bytes.fill(0)
            }
        }
        if (!stable(before, fileAttrs(path))) {
            throw IOException("Private archive identity changed during reread")
        }
        return digestHex(md.digest())
    }

    private fun digestHex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun canonicalId(value: String): Boolean =
        try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }
}
