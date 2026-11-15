package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Verified-by-reread bytes in an UNSEALED SYNTHETIC fixture; never an export-protection receipt. */
internal data class UntrustedDestinationCopyObservation(
    val byteCount: Long,
    val sha256: String,
)

/**
 * I4B-2C-3A: isolated single-file destination-copy experiment, NOT production staging.
 *
 * The caller owns and exclusively borrows [exactBytes]. This class neither captures Room,
 * reads managed originals, acquires leases, authorizes owners, nor publishes exports.
 * A caller must bind expectations independently and hold the outer lease when appropriate.
 * A successful reread proves only the current synthetic destination bytes, NOT crash-safe
 * durability, protection from concurrent path substitution, or authority for reclamation.
 * No retry and NO deletion after an attempted write, including errors or cancellation.
 */
internal class ExportUnsealedDestinationCopyProbe(
    private val syntheticFixtureDirectory: File,
    private val maxBytes: Long,
    private val syncDirectory: (File) -> Unit,
    private val forceFile: (FileChannel) -> Unit = { it.force(true) },
    private val onChunkWritten: (Long) -> Unit = {},
    private val afterForce: (File) -> Unit = {},
) {
    init {
        require(maxBytes in 1L..BoundedImageDecodePolicy.MAX_ENCODED_BYTES)
    }

    private val directory: Path = syntheticFixtureDirectory.toPath().toAbsolutePath().normalize()
    private var attempted = false

    suspend fun writeBorrowedBytes(
        expected: ExpectedEncodedImage,
        exactBytes: ByteArray,
    ): UntrustedDestinationCopyObservation = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        synchronized(this@ExportUnsealedDestinationCopyProbe) {
            if (attempted) throw IOException("This synthetic destination has already been attempted")
            attempted = true
            context.ensureActive()
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Synthetic destination directory missing, linked, or invalid")
            }
            if (expected.assetId.isBlank()) throw IOException("Missing source identity")
            BoundedImageDecodePolicy.requireEncoded(expected.mimeType, expected.encodedBytes)
            if (expected.encodedBytes > maxBytes ||
                expected.encodedBytes != exactBytes.size.toLong() ||
                !SHA256.matches(expected.sha256)
            ) throw IOException("Synthetic copy expectations are invalid")

            val sourceDigest = MessageDigest.getInstance("SHA-256")
            var offset = 0
            while (offset < exactBytes.size) {
                context.ensureActive()
                val count = minOf(CHUNK, exactBytes.size - offset)
                sourceDigest.update(exactBytes, offset, count)
                offset += count
            }
            if (hex(sourceDigest.digest()) != expected.sha256) {
                throw IOException("Borrowed input bytes differ from expected identity")
            }
            context.ensureActive()

            // One predetermined leaf in a caller-created synthetic fixture directory.
            val destination = directory.resolve("image-copy.bin")
            FileChannel.open(destination, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                offset = 0
                while (offset < exactBytes.size) {
                    context.ensureActive()
                    val count = minOf(CHUNK, exactBytes.size - offset)
                    val buffer = ByteBuffer.wrap(exactBytes, offset, count)
                    while (buffer.hasRemaining()) {
                        context.ensureActive()
                        if (channel.write(buffer) <= 0) {
                            throw IOException("Synthetic destination write made no progress")
                        }
                    }
                    offset += count
                    onChunkWritten(offset.toLong())
                }
                forceFile(channel)
            }
            context.ensureActive()
            afterForce(destination.toFile()) // Fault-injection seam; no production authority.

            val before = Files.readAttributes(destination, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
            if (!before.isRegularFile || before.size() != expected.encodedBytes) {
                throw IOException("Synthetic destination size or type changed")
            }
            val rereadDigest = MessageDigest.getInstance("SHA-256")
            val bufferArray = ByteArray(CHUNK)
            var total = 0L
            try {
                FileChannel.open(destination, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() != expected.encodedBytes) {
                        throw IOException("Synthetic destination length mismatch")
                    }
                    val buffer = ByteBuffer.wrap(bufferArray)
                    while (true) {
                        context.ensureActive()
                        buffer.clear()
                        val n = channel.read(buffer)
                        if (n < 0) break
                        if (n == 0 || n.toLong() > expected.encodedBytes - total) {
                            throw IOException("Synthetic destination read stalled or exceeded length")
                        }
                        rereadDigest.update(bufferArray, 0, n)
                        total += n
                    }
                    if (total != expected.encodedBytes || channel.size() != expected.encodedBytes) {
                        throw IOException("Synthetic destination length changed while rereading")
                    }
                }
            } finally {
                bufferArray.fill(0)
            }
            if (hex(rereadDigest.digest()) != expected.sha256) {
                throw IOException("Synthetic destination digest mismatch")
            }
            val after = Files.readAttributes(destination, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
            if (!after.isRegularFile || after.size() != expected.encodedBytes ||
                (before.fileKey() != null && before.fileKey() != after.fileKey())
            ) throw IOException("Synthetic destination identity changed")
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Synthetic destination directory changed")
            }
            syncDirectory(directory.toFile())
            context.ensureActive()
            UntrustedDestinationCopyObservation(total, expected.sha256)
        }
    }

    private companion object {
        const val CHUNK = 8_192
        val SHA256 = Regex("[0-9a-f]{64}")
        fun hex(bytes: ByteArray): String = bytes.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
