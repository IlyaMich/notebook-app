package com.notebook.app.data.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.notebook.app.data.local.dao.AssetDao
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * C2C-3B-I2 result: observed decoded dimensions and verified source identity only.
 * NOT a media-validity certificate or permission to insert an image element.
 */
internal data class VerifiedDecodedImageInfo(
    val assetId: String,
    val mimeType: String,
    val sha256: String,
    val encodedBytes: Long,
    val width: Int,
    val height: Int,
    val pixels: Long,
)

/**
 * C2C-3B-I2: actual full-resolution Android decode from the I1-verified byte array.
 *
 * This standalone inspector obtains the publication lease through the I1 reader.
 * Do NOT invoke it from inside an already-held acquisition lease: the OS lock is
 * non-reentrant. C2C-3C will require a separately reviewed, lease-aware integration.
 * No Bitmap, byte array, file handle, or mutable path escapes this method.
 *
 * SECURITY GATE: BitmapFactory does NOT promise strict rejection of every malformed
 * PNG/JPEG. I3 structural/adversarial validation must pass before writer integration.
 */
internal class RegisteredFinalizedImageDecoder(
    privateFilesDir: File,
    assetDao: AssetDao,
) {
    private val source = RegisteredFinalizedImageBytesReader(privateFilesDir, assetDao)

    suspend fun inspect(assetId: String): VerifiedDecodedImageInfo = withContext(Dispatchers.IO) {
        source.withVerifiedBytes(assetId) { snapshot, bytes ->
            currentCoroutineContext().ensureActive()
            BoundedImageDecodePolicy.requireEncoded(snapshot.mimeType, bytes.size.toLong())

            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
            // Bounds-only should never return an allocated pixel buffer. If it does,
            // release it and reject rather than silently accepting unexpected behavior.
            val unexpectedBoundsBitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, boundsOptions,
            )
            if (unexpectedBoundsBitmap != null) {
                unexpectedBoundsBitmap.recycle()
                throw IOException("Bounds-only image pass unexpectedly allocated a bitmap")
            }
            if (boundsOptions.outMimeType != snapshot.mimeType) {
                throw IOException("Decoder MIME differs from the registered image MIME")
            }
            val budget = BoundedImageDecodePolicy.requireIntrinsicDimensions(
                boundsOptions.outWidth, boundsOptions.outHeight,
            )
            currentCoroutineContext().ensureActive()

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
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    ?: throw IOException("Full image decode failed")
                // Full decode is synchronous and cannot be assumed to stop mid-call.
                // Cancellation is checked immediately once native decoding returns.
                currentCoroutineContext().ensureActive()
                if (bitmap.width != budget.width || bitmap.height != budget.height ||
                    decodeOptions.outWidth != budget.width ||
                    decodeOptions.outHeight != budget.height
                ) {
                    throw IOException("Decoded bitmap dimensions differ from intrinsic dimensions")
                }
                if (decodeOptions.outMimeType != snapshot.mimeType) {
                    throw IOException("Full decoder MIME differs from registered image MIME")
                }
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    throw IOException("Decoded bitmap did not use ARGB_8888")
                }
                BoundedImageDecodePolicy.requireBitmapBytes(
                    bitmap.byteCount.toLong(), bitmap.allocationByteCount.toLong(),
                )
                if (bitmap.byteCount.toLong() != budget.argbBytes) {
                    throw IOException("Decoded pixel storage differs from the expected ARGB_8888 size")
                }
                currentCoroutineContext().ensureActive()
                VerifiedDecodedImageInfo(
                    assetId = snapshot.assetId,
                    mimeType = snapshot.mimeType,
                    sha256 = snapshot.sha256,
                    encodedBytes = snapshot.byteSize,
                    width = budget.width,
                    height = budget.height,
                    pixels = budget.pixels,
                )
            } finally {
                bitmap?.recycle()
            }
        }
    }
}
