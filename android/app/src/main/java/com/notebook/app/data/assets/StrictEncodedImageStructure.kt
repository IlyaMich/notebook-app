package com.notebook.app.data.assets

import java.io.IOException
import java.util.zip.CRC32

/**
 * C2C-3B-I3: bounded structural envelope checks on the *same verified bytes*
 * supplied to BitmapFactory. This rejects missing/trailing end markers and bad
 * PNG chunk CRCs / JPEG marker lengths that BitmapFactory may otherwise ignore.
 *
 * This is deliberately not a claim to validate every compressed scan stream.
 * I3 still requires adversarial compressed-body and API 26 tests before approval.
 */
internal object StrictEncodedImageStructure {
    fun validate(mimeType: String, bytes: ByteArray) {
        BoundedImageDecodePolicy.requireEncoded(mimeType, bytes.size.toLong())
        when (mimeType) {
            "image/png" -> checkPng(bytes)
            "image/jpeg" -> checkJpeg(bytes)
            else -> throw IOException("Unsupported image encoding")
        }
    }

    private fun octet(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xff

    private fun uint32(bytes: ByteArray, index: Int): Long =
        (octet(bytes, index).toLong() shl 24) or
            (octet(bytes, index + 1).toLong() shl 16) or
            (octet(bytes, index + 2).toLong() shl 8) or
            octet(bytes, index + 3).toLong()

    private fun checkPng(bytes: ByteArray) {
        val signature = intArrayOf(137, 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 8 || signature.indices.any { octet(bytes, it) != signature[it] }) {
            throw IOException("Invalid PNG signature")
        }
        var position = 8
        var chunks = 0
        var seenHeader = false
        var seenPalette = false
        var paletteEntries = 0
        var seenGamma = false
        var seenTransparency = false
        var seenData = false
        var dataEnded = false
        var colorType = -1
        var bitDepth = -1
        while (position < bytes.size) {
            // Use Long arithmetic so an attacker-controlled unsigned length cannot wrap.
            if (bytes.size - position < 12) throw IOException("Incomplete PNG chunk header")
            val length = uint32(bytes, position)
            val end = position.toLong() + 12L + length
            if (end > bytes.size.toLong()) throw IOException("PNG chunk extends past EOF")
            val typeOffset = position + 4
            val dataStart = position + 8
            val crcOffset = (end - 4).toInt()
            val type = String(bytes, typeOffset, 4, Charsets.US_ASCII)
            if (type.length != 4 || type.any { it !in 'A'..'Z' && it !in 'a'..'z' } ||
                type[2] !in 'A'..'Z'
            ) throw IOException("Invalid PNG chunk type")

            val crc = CRC32()
            crc.update(bytes, typeOffset, 4 + length.toInt())
            if (crc.value != uint32(bytes, crcOffset)) {
                throw IOException("PNG chunk CRC mismatch")
            }
            chunks++
            if (chunks > 65_536) throw IOException("Too many PNG chunks")
            if (!seenHeader && type != "IHDR") throw IOException("PNG must start with IHDR")
            if (seenData && type != "IDAT") dataEnded = true
            when (type) {
                "IHDR" -> {
                    if (seenHeader || position != 8 || length != 13L) {
                        throw IOException("Invalid or duplicate PNG IHDR")
                    }
                    val width = uint32(bytes, dataStart)
                    val height = uint32(bytes, dataStart + 4)
                    if (width == 0L || height == 0L ||
                        width > Int.MAX_VALUE.toLong() || height > Int.MAX_VALUE.toLong()
                    ) throw IOException("Invalid PNG IHDR dimensions")
                    bitDepth = octet(bytes, dataStart + 8)
                    colorType = octet(bytes, dataStart + 9)
                    val permitted = when (colorType) {
                        0 -> bitDepth in setOf(1, 2, 4, 8, 16)
                        2, 4, 6 -> bitDepth == 8 || bitDepth == 16
                        3 -> bitDepth in setOf(1, 2, 4, 8)
                        else -> false
                    }
                    if (!permitted || octet(bytes, dataStart + 10) != 0 ||
                        octet(bytes, dataStart + 11) != 0 ||
                        octet(bytes, dataStart + 12) !in 0..1
                    ) throw IOException("Unsupported or invalid PNG IHDR")
                    seenHeader = true
                }
                "PLTE" -> {
                    if (seenPalette || seenTransparency || seenData ||
                        colorType == 0 || colorType == 4 ||
                        length == 0L || length > 768L || length % 3L != 0L ||
                        (colorType == 3 && length / 3L > (1 shl bitDepth).toLong())
                    ) throw IOException("Invalid PNG PLTE")
                    seenPalette = true
                    paletteEntries = (length / 3L).toInt()
                }
                "gAMA" -> {
                    // A recognized ancillary chunk is not validated by its CRC alone.
                    // gAMA must be unique, precede PLTE/IDAT, and hold one uint32.
                    if (seenGamma || seenPalette || seenData || length != 4L) {
                        throw IOException("Invalid PNG gAMA")
                    }
                    seenGamma = true
                }
                "tRNS" -> {
                    // Transparency is forbidden for PNGs that already have alpha.
                    // Indexed alpha counts must fit the preceding palette.
                    if (seenTransparency || seenData) {
                        throw IOException("Invalid or misplaced PNG tRNS")
                    }
                    val valid = when (colorType) {
                        0 -> length == 2L
                        2 -> length == 6L
                        3 -> seenPalette && length in 1L..paletteEntries.toLong()
                        else -> false
                    }
                    if (!valid) throw IOException("Invalid PNG tRNS for color type")
                    seenTransparency = true
                }
                "IDAT" -> {
                    if (dataEnded || (colorType == 3 && !seenPalette)) {
                        throw IOException("PNG IDAT out of order")
                    }
                    seenData = true
                }
                "IEND" -> {
                    if (!seenData || length != 0L || end != bytes.size.toLong()) {
                        throw IOException("Invalid PNG IEND or trailing bytes")
                    }
                    return
                }
                else -> {
                    if (type[0] in 'A'..'Z') {
                        throw IOException("Unknown critical PNG chunk")
                    }
                }
            }
            position = end.toInt()
        }
        throw IOException("PNG missing IEND")
    }

    private fun checkJpeg(bytes: ByteArray) {
        if (bytes.size < 4 || octet(bytes, 0) != 0xff || octet(bytes, 1) != 0xd8) {
            throw IOException("Invalid JPEG SOI")
        }
        var position = 2
        var inScan = false
        var seenFrame = false
        var seenScan = false
        var scanHasData = false
        var restartInterval = 0
        var expectedRestart = 0
        var marker: Int
        while (true) {
            if (inScan) {
                // Entropy bytes may contain FF00 stuffing, restart markers, or
                // FF fill. The first other FF marker ends this scan.
                while (true) {
                    if (position >= bytes.size) throw IOException("JPEG missing EOI")
                    if (octet(bytes, position++) != 0xff) {
                        scanHasData = true
                        continue
                    }
                    while (position < bytes.size && octet(bytes, position) == 0xff) position++
                    if (position >= bytes.size) throw IOException("JPEG truncated entropy marker")
                    val following = octet(bytes, position++)
                    when (following) {
                        0x00 -> scanHasData = true
                        in 0xd0..0xd7 -> {
                            if (restartInterval == 0 || following != 0xd0 + expectedRestart ||
                                !scanHasData
                            ) throw IOException("Unannounced or out-of-order JPEG restart")
                            expectedRestart = (expectedRestart + 1) % 8
                            scanHasData = false
                        }
                        else -> {
                            if (!scanHasData) throw IOException("Empty JPEG scan")
                            marker = following
                            inScan = false
                            break
                        }
                    }
                }
            } else {
                if (position >= bytes.size || octet(bytes, position++) != 0xff) {
                    throw IOException("JPEG missing marker or EOI")
                }
                while (position < bytes.size && octet(bytes, position) == 0xff) position++
                if (position >= bytes.size) throw IOException("Truncated JPEG marker")
                marker = octet(bytes, position++)
            }

            when (marker) {
                0xd9 -> {
                    if (!seenFrame || !seenScan || position != bytes.size) {
                        throw IOException("JPEG invalid EOI or trailing bytes")
                    }
                    return
                }
                0xd8, 0x00, in 0xd0..0xd7 -> throw IOException("Unexpected JPEG marker")
                0x01 -> Unit // TEM is stand-alone; it has no length field.
                else -> {
                    // Do not interpret reserved/unassigned marker IDs as arbitrary
                    // length-bearing segments. Accept only known JPEG segment kinds.
                    val isFrame = marker in setOf(
                        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
                    )
                    val isOtherSegment = marker in 0xe0..0xef || marker == 0xfe ||
                        marker in setOf(0xc4, 0xcc, 0xdb, 0xdd, 0xde, 0xdf)
                    if (!isFrame && !isOtherSegment && marker != 0xda) {
                        throw IOException("Unsupported JPEG marker")
                    }
                    if (position + 2 > bytes.size) throw IOException("JPEG truncated segment length")
                    val length = (octet(bytes, position) shl 8) or octet(bytes, position + 1)
                    if (length < 2 || length.toLong() > bytes.size.toLong() - position) {
                        throw IOException("JPEG invalid segment length")
                    }
                    val payload = position + 2
                    val end = position + length
                    when (marker) {
                        // SOF markers (excluding DHT C4, JPG C8 and DAC CC).
                        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
                        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf -> {
                            if (seenFrame || length < 11) throw IOException("Invalid JPEG frame")
                            val components = octet(bytes, payload + 5)
                            val width = (octet(bytes, payload + 3) shl 8) or
                                octet(bytes, payload + 4)
                            val height = (octet(bytes, payload + 1) shl 8) or
                                octet(bytes, payload + 2)
                            if (components !in 1..4 || width == 0 || height == 0 ||
                                length != 8 + 3 * components
                            ) throw IOException("Invalid JPEG frame dimensions/components")
                            seenFrame = true
                        }
                        0xda -> {
                            if (!seenFrame || length < 8) throw IOException("Invalid JPEG SOS")
                            val components = octet(bytes, payload)
                            if (components !in 1..4 || length != 6 + 2 * components) {
                                throw IOException("Invalid JPEG SOS component count")
                            }
                            seenScan = true
                            inScan = true
                            scanHasData = false
                            expectedRestart = 0
                        }
                        0xdd -> {
                            if (length != 4) throw IOException("Invalid JPEG DRI length")
                            restartInterval = (octet(bytes, payload) shl 8) or
                                octet(bytes, payload + 1)
                        }
                        0xc8 -> throw IOException("Unsupported JPEG extension marker")
                        else -> Unit // Other length-bearing JPEG marker segments.
                    }
                    position = end
                }
            }
        }
    }
}
