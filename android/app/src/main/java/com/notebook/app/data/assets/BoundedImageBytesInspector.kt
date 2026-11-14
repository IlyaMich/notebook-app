package com.notebook.app.data.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caller-supplied expectations are not an asset-provenance or export-protection certificate. */
internal data class ExpectedEncodedImage(
    val assetId: String,
    val mimeType: String,
    val sha256: String,
    val encodedBytes: Long,
)

/**
 * I4-B-2B: strict content inspection of a caller-owned, exclusively borrowed byte array.
 *
 * Does not read Room, access files, acquire an asset-publication lease, copy or clear bytes,
 * stage exports, authorize ownership, or prove the provenance of expected metadata.
 * The caller must keep [exactBytes] stable for the entire call and zero its owned temporary
 * buffer after use. Never invoke a lease-acquiring wrapper from an already-held lease.
 */
internal object BoundedImageBytesInspector {
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    suspend fun inspect(
        expected: ExpectedEncodedImage,
        exactBytes: ByteArray,
    ): VerifiedDecodedImageInfo {
        val inspectionContext = currentCoroutineContext()
        inspectionContext.ensureActive()
        if (expected.assetId.isBlank()) throw IOException("Missing expected image asset ID")
        BoundedImageDecodePolicy.requireEncoded(expected.mimeType, expected.encodedBytes)
        if (!SHA256_PATTERN.matches(expected.sha256) ||
            expected.encodedBytes != exactBytes.size.toLong()
        ) {
            throw IOException("Encoded image expectations are invalid or inconsistent")
        }

        // Verify the borrowed exact bytes before parsing or native decoding. No array clone.
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < exactBytes.size) {
            inspectionContext.ensureActive()
            val count = minOf(8_192, exactBytes.size - offset)
            digest.update(exactBytes, offset, count)
            offset += count
        }
        inspectionContext.ensureActive()
        val actualSha256 = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        if (actualSha256 != expected.sha256) throw IOException("Encoded image digest mismatch")

        // Preserve the existing strict structure, compressed-body and native decode gates.
        StrictEncodedImageStructure.validate(expected.mimeType, exactBytes)
        when (expected.mimeType) {
            "image/png" -> StrictPngInflateValidation.validate(exactBytes) {
                inspectionContext.ensureActive()
            }
            "image/jpeg" -> StrictBaselineJpegEntropy.validate(exactBytes) {
                inspectionContext.ensureActive()
            }
        }
        inspectionContext.ensureActive()

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        val unexpectedBoundsBitmap = BitmapFactory.decodeByteArray(
            exactBytes, 0, exactBytes.size, boundsOptions,
        )
        if (unexpectedBoundsBitmap != null) {
            unexpectedBoundsBitmap.recycle()
            throw IOException("Bounds-only image pass unexpectedly allocated a bitmap")
        }
        if (boundsOptions.outMimeType != expected.mimeType) {
            throw IOException("Decoder MIME differs from the expected image MIME")
        }
        val budget = BoundedImageDecodePolicy.requireIntrinsicDimensions(
            boundsOptions.outWidth, boundsOptions.outHeight,
        )
        inspectionContext.ensureActive()

        val decodeOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = 1
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inBitmap = null
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(
                exactBytes, 0, exactBytes.size, decodeOptions,
            ) ?: throw IOException("Full image decode failed")
            // Native decode is synchronous; cancellation is checked immediately afterward.
            inspectionContext.ensureActive()
            if (bitmap.width != budget.width || bitmap.height != budget.height ||
                decodeOptions.outWidth != budget.width || decodeOptions.outHeight != budget.height
            ) {
                throw IOException("Decoded bitmap dimensions differ from intrinsic dimensions")
            }
            if (decodeOptions.outMimeType != expected.mimeType) {
                throw IOException("Full decoder MIME differs from expected image MIME")
            }
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                throw IOException("Decoded bitmap did not use ARGB_8888")
            }
            BoundedImageDecodePolicy.requireBitmapBytes(
                bitmap.byteCount.toLong(), bitmap.allocationByteCount.toLong(),
            )
            if (bitmap.byteCount.toLong() != budget.argbBytes) {
                throw IOException("Decoded pixel storage differs from expected ARGB_8888 size")
            }
            inspectionContext.ensureActive()
            return VerifiedDecodedImageInfo(
                assetId = expected.assetId,
                mimeType = expected.mimeType,
                sha256 = expected.sha256,
                encodedBytes = expected.encodedBytes,
                width = budget.width,
                height = budget.height,
                pixels = budget.pixels,
            )
        } finally {
            bitmap?.recycle()
        }
    }
}
