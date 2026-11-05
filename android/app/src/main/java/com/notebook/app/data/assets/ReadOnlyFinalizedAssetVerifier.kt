package com.notebook.app.data.assets

import com.notebook.app.data.local.dao.AssetDao
import com.notebook.app.data.local.entity.AssetEntity
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * STO-08 C2C-2B: read-only point-in-time integrity check of a registered finalized original.
 *
 * A successful result is NOT a media-decoding certificate, a durable acquisition lease, an
 * attachment authorization, or evidence that the file cannot change after this method returns.
 * Future writers must perform full bounded decoding and coordinate with the publication lease.
 * This verifier does not write Room rows, owner edges, or files and never permits cleanup.
 */
internal class ReadOnlyFinalizedAssetVerifier(
    private val privateFilesDir: File,
    private val assetDao: AssetDao,
    private val maximumBytes: Long = 64L * 1024L * 1024L,
) {
    init {
        require(maximumBytes > 0L) { "maximumBytes must be positive" }
    }

    /** A completed integrity observation, deliberately without a path or open file handle. */
    data class IntegritySnapshot(
        val assetId: String,
        val sha256: String,
        val mimeType: String,
        val byteSize: Long,
    )

    suspend fun verify(assetId: String): IntegritySnapshot {
        if (assetId.isBlank()) throw IOException("Missing asset ID")
        val row = assetDao.findById(assetId) ?: throw IOException("Unknown asset ID")
        return withContext(Dispatchers.IO) { checkFinalizedFile(assetId, row) }
    }

    private suspend fun checkFinalizedFile(assetId: String, row: AssetEntity): IntegritySnapshot {
        if (row.id != assetId || row.id.isBlank() || row.state != "finalized" ||
            row.byteSize <= 0L || row.byteSize > maximumBytes ||
            !SHA256_PATTERN.matches(row.sha256)
        ) throw IOException("Invalid finalized asset metadata")

        val extension = when (row.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "application/pdf" -> "pdf"
            else -> throw IOException("Unsupported asset MIME type")
        }
        val firstShard = row.sha256.substring(0, 2)
        val secondShard = row.sha256.substring(2, 4)
        val expectedPath = "assets/$firstShard/$secondShard/${row.sha256}.$extension"
        if (row.relativePath != expectedPath) {
            throw IOException("Noncanonical finalized asset path")
        }

        val root = privateFilesDir.toPath()
        val assets = root.resolve("assets")
        val first = assets.resolve(firstShard)
        val second = first.resolve(secondShard)
        for (directory in listOf(root, assets, first, second)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Missing, linked, or invalid private asset directory")
            }
        }

        val path: Path = second.resolve("${row.sha256}.$extension")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Finalized asset is missing, linked, or not a regular file")
        }

        // NOFOLLOW_LINKS also applies when opening the leaf, not only to the earlier check.
        // No filesystem scan and no mutable path is exposed by the return value.
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        var headerCount = 0
        var total = 0L
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != row.byteSize) throw IOException("Asset byte length mismatch")
            val chunk = ByteArray(8192)
            val buffer = ByteBuffer.wrap(chunk)
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Finalized asset read made no progress")
                if (count.toLong() > maximumBytes - total ||
                    count.toLong() > row.byteSize - total
                ) {
                    throw IOException("Finalized asset changed size or exceeds verification limit")
                }
                if (headerCount < header.size) {
                    val copied = minOf(count, header.size - headerCount)
                    chunk.copyInto(header, headerCount, 0, copied)
                    headerCount += copied
                }
                digest.update(chunk, 0, count)
                total += count.toLong()
            }
            if (total != row.byteSize || channel.size() != row.byteSize) {
                throw IOException("Asset changed size during verification")
            }
        }

        val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actualDigest != row.sha256) throw IOException("Finalized asset SHA-256 mismatch")
        val signatureMatches = when (extension) {
            "png" -> headerCount >= 8 && header.contentEquals(byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            ))
            "jpg" -> headerCount >= 3 && header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "pdf" -> headerCount >= 5 && header.copyOfRange(0, 5)
                .contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
            else -> false
        }
        if (!signatureMatches) throw IOException("Finalized asset signature mismatch")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Finalized asset path changed during verification")
        }
        return IntegritySnapshot(row.id, row.sha256, row.mimeType, row.byteSize)
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
