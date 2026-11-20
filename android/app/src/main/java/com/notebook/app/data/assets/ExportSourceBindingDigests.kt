package com.notebook.app.data.assets

import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Cryptographic identities of the exact immutable capture accepted inside Room.
 * A digest proves only equality to the committed DB value, not that an archive
 * has been published or that original assets can be reclaimed.
 */
internal object ExportSourceBindingDigests {
    const val BINDING_VERSION = 1
    const val MAX_CAPTURED_SNAPSHOT_BYTES = 8L shl 20
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun snapshot(operationId: String, snapshot: SnapshotResult.Complete): ByteArray {
        if (!canonicalUuid(operationId) || !canonicalUuid(snapshot.note.id)) {
            throw IOException("Invalid captured document identity")
        }
        val bytes = ExportCapturedDocumentCodec.snapshot(operationId, snapshot)
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_CAPTURED_SNAPSHOT_BYTES) {
            throw IOException("Captured source exceeds bounded binding budget")
        }
        return bytes
    }

    fun assetSetDigest(images: List<SnapshotAssetMetadata>): String {
        val seen = HashSet<String>()
        val md = MessageDigest.getInstance("SHA-256")
        for (asset in images.sortedBy { it.id }) {
            if (!canonicalUuid(asset.id) || !seen.add(asset.id) ||
                !digestPattern.matches(asset.sha256) ||
                asset.encodedBytes <= 0L || asset.encodedBytes > BoundedImageDecodePolicy.MAX_ENCODED_BYTES
            ) throw IOException("Malformed retained source asset identity")
            val entry = asset.id + ":" + asset.sha256 + ":" +
                asset.encodedBytes.toString() + ":" + asset.mimeType + "\n"
            md.update(entry.toByteArray(Charsets.UTF_8))
        }
        return hex(md.digest())
    }

    fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun canonicalUuid(value: String): Boolean =
        try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
}
