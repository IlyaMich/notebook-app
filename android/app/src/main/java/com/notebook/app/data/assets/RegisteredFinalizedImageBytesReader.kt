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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * STO-08 C2C-3B-I1: leased, bounded source bytes for a FUTURE full-image decoder.
 * This is deliberately NOT a media-decoding certificate or attachment authorization.
 * No write is performed to the asset, Room, owner index, or document.
 *
 * The callback executes WHILE the acquisition lease is held. Never call a publisher or
 * acquire a second lease from it. A future decoder must return metadata, not the bytes.
 * The ephemeral input array is cleared when the callback ends, including on cancellation.
 */
internal class RegisteredFinalizedImageBytesReader(
    private val privateFilesDir: File,
    private val assetDao: AssetDao,
    private val lease: AssetAcquisitionLeaseCoordinator = AssetAcquisitionLeaseCoordinator(privateFilesDir),
) {
    suspend fun <T> withVerifiedBytes(
        assetId: String,
        action: suspend (ReadOnlyFinalizedAssetVerifier.IntegritySnapshot, ByteArray) -> T,
    ): T = lease.withLease {
        currentCoroutineContext().ensureActive()
        if (assetId.isBlank()) throw IOException("Missing registered image asset ID")
        val initial = assetDao.findById(assetId) ?: throw IOException("Unknown registered image asset")
        requireEligibleRow(assetId, initial)

        // Existing C2C-2B integrity observation is still useful but is NOT a byte snapshot.
        val snapshot = ReadOnlyFinalizedAssetVerifier(
            privateFilesDir, assetDao, BoundedImageDecodePolicy.MAX_ENCODED_BYTES,
        ).verify(assetId)
        requireMatchesSnapshot(initial, snapshot)
        currentCoroutineContext().ensureActive()

        // Re-open and verify the EXACT encoded bytes used by the subsequent I2 decoder.
        val bytes = readAndVerifyBytes(initial)
        try {
            val current = assetDao.findById(assetId)
                ?: throw IOException("Image metadata disappeared during inspection")
            if (current != initial) throw IOException("Image metadata changed during inspection")
            currentCoroutineContext().ensureActive()
            action(snapshot, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun requireEligibleRow(assetId: String, row: AssetEntity) {
        BoundedImageDecodePolicy.requireEncoded(row.mimeType, row.byteSize)
        if (row.id != assetId || row.id.isBlank() || row.state != "finalized" ||
            !SHA256_PATTERN.matches(row.sha256) || row.relativePath != expectedPath(row)
        ) {
            throw IOException("Invalid registered finalized image metadata")
        }
    }

    private fun requireMatchesSnapshot(
        row: AssetEntity,
        snapshot: ReadOnlyFinalizedAssetVerifier.IntegritySnapshot,
    ) {
        if (snapshot.assetId != row.id || snapshot.sha256 != row.sha256 ||
            snapshot.mimeType != row.mimeType || snapshot.byteSize != row.byteSize
        ) throw IOException("Finalized image metadata differs from integrity verification")
    }

    private fun expectedPath(row: AssetEntity): String {
        val suffix = when (row.mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            else -> throw IOException("Unsupported image MIME")
        }
        return "assets/${row.sha256.substring(0, 2)}/${row.sha256.substring(2, 4)}/${row.sha256}.$suffix"
    }

    private fun checkedPath(row: AssetEntity): Path {
        val root = privateFilesDir.toPath()
        val assets = root.resolve("assets")
        val first = assets.resolve(row.sha256.substring(0, 2))
        val second = first.resolve(row.sha256.substring(2, 4))
        for (dir in listOf(root, assets, first, second)) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Invalid or linked private image directory")
            }
        }
        val file = second.resolve(expectedPath(row).substringAfterLast('/'))
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Image original is missing, linked, or not regular")
        }
        return file
    }

    private suspend fun readAndVerifyBytes(row: AssetEntity): ByteArray {
        val path = checkedPath(row)
        val bytes = ByteArray(row.byteSize.toInt()) // Already checked <= 16 MiB.
        var complete = false
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                if (channel.size() != row.byteSize) throw IOException("Image size differs from metadata")
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.read(buffer)
                    if (count <= 0) throw IOException("Image read truncated or made no progress")
                }
                if (channel.size() != row.byteSize) throw IOException("Image size changed during reading")
            }
            currentCoroutineContext().ensureActive()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (digest != row.sha256) throw IOException("Image digest mismatch")
            if (!matchesSignature(row.mimeType, bytes)) throw IOException("Image MIME/signature mismatch")
            // Preflight is not enough: reject an observable path replacement after the read.
            checkedPath(row)
            complete = true
            return bytes
        } finally {
            if (!complete) bytes.fill(0)
        }
    }

    private fun matchesSignature(mime: String, bytes: ByteArray): Boolean = when (mime) {
        "image/png" -> bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        "image/jpeg" -> bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        else -> false
    }

    private companion object {
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
