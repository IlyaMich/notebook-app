package com.notebook.app.data.assets

import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Bounded PNG IDAT zlib verification over the SAME verified encoded bytes.
 * Run StrictEncodedImageStructure first to check signature, chunk layout and CRCs.
 * Produces no image or full-size uncompressed buffer.
 */
internal object StrictPngInflateValidation {
    private data class Slice(val start: Int, val length: Int)
    private data class Pass(val rows: Int, val bytesPerRow: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun u32(b: ByteArray, i: Int) =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun span(size: Int, first: Int, step: Int) =
        if (size <= first) 0 else (size - first + step - 1) / step

    private fun passes(w: Int, h: Int, bitsPerPixel: Int, interlace: Int): List<Pass> {
        fun make(pw: Int, ph: Int): Pass? = if (pw == 0 || ph == 0) null else
            Pass(ph, ((pw.toLong() * bitsPerPixel + 7L) / 8L).toInt())
        if (interlace == 0) return listOf(requireNotNull(make(w, h)))
        if (interlace != 1) throw IOException("Invalid PNG interlace method")
        // Adam7 pass geometry (x start, y start, x step, y step).
        val geometry = arrayOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8),
            intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2),
            intArrayOf(0, 1, 1, 2),
        )
        return geometry.mapNotNull { p -> make(span(w, p[0], p[2]), span(h, p[1], p[3])) }
    }

    /** Cancellation callback is invoked before parsing and throughout zlib inflation. */
    fun validate(bytes: ByteArray, checkpoint: () -> Unit = {}) {
        checkpoint()
        if (bytes.size < 57 || u32(bytes, 8) != 13L ||
            String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR"
        ) throw IOException("Invalid PNG IHDR before zlib validation")
        val widthLong = u32(bytes, 16)
        val heightLong = u32(bytes, 20)
        if (widthLong > Int.MAX_VALUE || heightLong > Int.MAX_VALUE) {
            throw IOException("PNG dimensions overflow")
        }
        val width = widthLong.toInt()
        val height = heightLong.toInt()
        BoundedImageDecodePolicy.requireIntrinsicDimensions(width, height)
        val channels = when (u8(bytes, 25)) {
            0, 3 -> 1
            2 -> 3
            4 -> 2
            6 -> 4
            else -> throw IOException("Invalid PNG color type")
        }
        val rowPasses = passes(width, height, u8(bytes, 24) * channels, u8(bytes, 28))
        val expected = rowPasses.sumOf { (it.bytesPerRow.toLong() + 1L) * it.rows }
        val slices = ArrayList<Slice>()
        var pos = 8
        while (pos < bytes.size) {
            checkpoint()
            if (bytes.size - pos < 12) throw IOException("Truncated PNG chunk")
            val size = u32(bytes, pos)
            val end = pos.toLong() + size + 12L
            if (size > Int.MAX_VALUE || end > bytes.size) {
                throw IOException("Invalid PNG chunk during zlib validation")
            }
            if (String(bytes, pos + 4, 4, Charsets.US_ASCII) == "IDAT") {
                slices += Slice(pos + 8, size.toInt())
            }
            pos = end.toInt()
        }
        if (slices.isEmpty()) throw IOException("Missing PNG IDAT")

        val inflater = Inflater(false) // zlib-wrapped data, checks header and Adler-32
        val output = ByteArray(8192)
        var nextSlice = 0
        var inflated = 0L
        var passIndex = 0
        var rowsLeft = 0
        var rowDataLeft = 0
        var rowBytes = 0
        try {
            while (!inflater.finished()) {
                // The inflater is synchronous; this keeps long streams cooperative
                // between bounded output chunks rather than only before/after them.
                checkpoint()
                if (inflater.needsInput()) {
                    while (nextSlice < slices.size && slices[nextSlice].length == 0) nextSlice++
                    if (nextSlice == slices.size) throw IOException("Incomplete PNG zlib stream")
                    val slice = slices[nextSlice++]
                    inflater.setInput(bytes, slice.start, slice.length)
                }
                val count = try {
                    inflater.inflate(output)
                } catch (e: DataFormatException) {
                    throw IOException("Invalid PNG zlib data or checksum", e)
                }
                if (count == 0) {
                    if (inflater.finished()) break
                    if (inflater.needsDictionary()) throw IOException("PNG zlib dictionary forbidden")
                    if (!inflater.needsInput()) throw IOException("PNG zlib inflater stalled")
                    continue
                }
                inflated += count.toLong()
                if (inflated > expected) throw IOException("Excess PNG decoded scanline bytes")
                var offset = 0
                while (offset < count) {
                    if (rowDataLeft == 0) {
                        if (rowsLeft == 0) {
                            if (passIndex == rowPasses.size) throw IOException("Extra PNG scanline")
                            val p = rowPasses[passIndex++]
                            rowsLeft = p.rows
                            rowBytes = p.bytesPerRow
                        }
                        if (u8(output, offset) !in 0..4) {
                            throw IOException("Invalid PNG scanline filter")
                        }
                        offset++
                        rowsLeft--
                        rowDataLeft = rowBytes
                    } else {
                        val take = minOf(rowDataLeft, count - offset)
                        rowDataLeft -= take
                        offset += take
                    }
                }
            }
            while (nextSlice < slices.size && slices[nextSlice].length == 0) nextSlice++
            if (!inflater.finished() || inflater.remaining != 0 || nextSlice != slices.size ||
                inflated != expected
            ) throw IOException("PNG zlib stream incomplete or contains trailing bytes")
            checkpoint()
        } finally {
            inflater.end()
        }
    }
}
