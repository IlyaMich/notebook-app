package com.notebook.app.data.assets

import java.io.IOException

/**
 * STO-08 C2C-3B-I1: resource policy ONLY. No image decoding or attachment permission.
 * Both bounds-pass and full-decode results must be checked again in C2C-3B-I2.
 */
internal object BoundedImageDecodePolicy {
    const val MAX_ENCODED_BYTES: Long = 16L * 1024L * 1024L
    const val MAX_DIMENSION: Int = 4_096
    const val MAX_PIXELS: Long = 8_388_608L
    const val MAX_BITMAP_BYTES: Long = 32L * 1024L * 1024L

    data class PixelBudget(val width: Int, val height: Int, val pixels: Long, val argbBytes: Long)

    fun requireEncoded(mimeType: String, encodedBytes: Long) {
        if (mimeType != "image/png" && mimeType != "image/jpeg") {
            throw IOException("Only registered PNG/JPEG image originals are supported")
        }
        if (encodedBytes <= 0L || encodedBytes > MAX_ENCODED_BYTES) {
            throw IOException("Encoded image exceeds the approved size limit")
        }
    }

    fun requireIntrinsicDimensions(width: Int, height: Int): PixelBudget {
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) {
            throw IOException("Intrinsic image dimension is outside the approved limit")
        }
        val pixels = Math.multiplyExact(width.toLong(), height.toLong())
        if (pixels > MAX_PIXELS) throw IOException("Too many intrinsic image pixels")
        val argbBytes = Math.multiplyExact(pixels, 4L)
        if (argbBytes > MAX_BITMAP_BYTES) throw IOException("ARGB_8888 bitmap exceeds budget")
        return PixelBudget(width, height, pixels, argbBytes)
    }

    /** Recheck both counts on the *actual* bitmap in I2; preference is not a guarantee. */
    fun requireBitmapBytes(byteCount: Long, allocationBytes: Long) {
        if (byteCount <= 0L || allocationBytes < byteCount ||
            byteCount > MAX_BITMAP_BYTES || allocationBytes > MAX_BITMAP_BYTES
        ) {
            throw IOException("Decoded bitmap allocation exceeds approved budget")
        }
    }
}
