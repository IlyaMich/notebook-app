package com.notebook.app.data.assets

import java.io.IOException

/**
 * An additional bounded check of baseline sequential JPEG entropy completeness.
 * Counts every 8x8 block using canonical DC/AC Huffman tables, not a marker-only
 * scan. Never treats a native BitmapFactory partial image as proof of validity.
 *
 * Deliberately fail-closed on JPEG modes/scans not yet implemented here (including
 * progressive and multi-scan JPEG). They require separate positive controls and
 * review before the C2C-3B approval gate can be completed.
 */
internal object StrictBaselineJpegEntropy {
    private class Malformed(message: String) : IOException(message)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun fail(message: String): Nothing = throw Malformed(message)

    private data class Component(val id: Int, val h: Int, val v: Int, val quantTable: Int)
    private data class ScanComponent(val component: Component, val dc: Huffman, val ac: Huffman)

    /** Entries are keyed by (bit length, canonical code). */
    private class Huffman(counts: IntArray, symbols: ByteArray) {
        private val values = HashMap<Int, Int>(symbols.size * 2)
        init {
            var code = 0
            var offset = 0
            for (bits in 1..16) {
                val count = counts[bits - 1]
                // JPEG reserves the all-ones code so fill bits cannot be a code.
                if (count > 0 && code + count >= (1 shl bits)) {
                    fail("Overfull or reserved JPEG Huffman code")
                }
                repeat(count) { index ->
                    values[(bits shl 16) or (code + index)] = u8(symbols, offset + index)
                }
                offset += count
                code = (code + count) shl 1
            }
        }
        fun read(reader: Bits): Int {
            var code = 0
            for (length in 1..16) {
                code = (code shl 1) or reader.bit()
                val value = values[(length shl 16) or code]
                if (value != null) return value
            }
            fail("Invalid JPEG Huffman code")
        }
    }

    private class Bits(private val bytes: ByteArray, start: Int) {
        var position = start
            private set
        private var current = 0
        private var remaining = 0

        fun bit(): Int {
            if (remaining == 0) {
                if (position >= bytes.size) fail("Truncated JPEG entropy")
                current = u8(bytes, position++)
                if (current == 255) {
                    if (position >= bytes.size || u8(bytes, position++) != 0) {
                        fail("JPEG scan ended before required blocks")
                    }
                }
                remaining = 8
            }
            remaining--
            return (current ushr remaining) and 1
        }

        fun bits(count: Int) {
            repeat(count) { bit() }
        }

        fun align() {
            if (remaining > 0 && (current and ((1 shl remaining) - 1)) != (1 shl remaining) - 1) {
                fail("Non-padding bits at end of JPEG scan")
            }
            remaining = 0
        }

        fun marker(): Int {
            align()
            if (position >= bytes.size || u8(bytes, position++) != 255) {
                fail("Missing JPEG marker after entropy")
            }
            while (position < bytes.size && u8(bytes, position) == 255) position++
            if (position >= bytes.size) fail("Truncated JPEG marker")
            return u8(bytes, position++)
        }
    }

    private fun block(reader: Bits, dc: Huffman, ac: Huffman) {
        val dcSize = dc.read(reader)
        if (dcSize !in 0..11) fail("Invalid baseline JPEG DC category")
        reader.bits(dcSize)
        var k = 1
        while (k < 64) {
            val symbol = ac.read(reader)
            if (symbol == 0) break // EOB
            if (symbol == 0xf0) {
                k += 16 // ZRL
                if (k > 64) fail("JPEG AC zero run exceeds block")
                continue
            }
            val run = symbol ushr 4
            val size = symbol and 15
            if (size !in 1..10) fail("Invalid baseline JPEG AC category")
            k += run
            if (k >= 64) fail("JPEG AC coefficient outside block")
            reader.bits(size)
            k++
        }
    }

    /** Pure check on already verified and bounded encoded bytes. */
    fun validate(bytes: ByteArray, checkpoint: () -> Unit = {}) {
        if (bytes.size < 4 || u8(bytes, 0) != 255 || u8(bytes, 1) != 0xd8) {
            fail("Invalid JPEG SOI")
        }
        var p = 2
        var components: List<Component>? = null
        var width = 0
        var height = 0
        var restartInterval = 0
        val huffman = HashMap<Int, Huffman>()
        val definedQuantTables = BooleanArray(4)
        while (p < bytes.size) {
            checkpoint()
            if (u8(bytes, p++) != 255) fail("Expected JPEG marker")
            while (p < bytes.size && u8(bytes, p) == 255) p++
            if (p >= bytes.size) fail("Truncated JPEG marker")
            val marker = u8(bytes, p++)
            if (marker == 0xd9) fail("EOI before complete JPEG scan")
            if (marker == 0xd8 || marker == 0 || marker in 0xd0..0xd7 || marker == 0x01) {
                fail("Unsupported JPEG standalone marker")
            }
            if (p + 2 > bytes.size) fail("Incomplete JPEG segment length")
            val length = u16(bytes, p)
            if (length < 2 || length > bytes.size - p) fail("JPEG segment beyond EOF")
            val start = p + 2
            val end = p + length
            when (marker) {
                0xc0 -> { // SOF0: baseline, 8-bit, DCT Huffman
                    if (components != null || length < 11 || u8(bytes, start) != 8) {
                        fail("Unsupported JPEG frame")
                    }
                    height = u16(bytes, start + 1)
                    width = u16(bytes, start + 3)
                    BoundedImageDecodePolicy.requireIntrinsicDimensions(width, height)
                    val n = u8(bytes, start + 5)
                    if (n !in 1..4 || length != 8 + 3 * n) fail("Invalid baseline component count")
                    val seenIds = HashSet<Int>()
                    components = List(n) { index ->
                        val at = start + 6 + 3 * index
                        val id = u8(bytes, at)
                        val factors = u8(bytes, at + 1)
                        val h = factors ushr 4
                        val v = factors and 15
                        if (!seenIds.add(id) || h !in 1..4 || v !in 1..4) {
                            fail("Invalid baseline sampling factors")
                        }
                        val quantTable = u8(bytes, at + 2)
                        if (quantTable !in 0..3) fail("Invalid baseline quantization table ID")
                        Component(id, h, v, quantTable)
                    }
                    if (components.sumOf { it.h * it.v } > 10) {
                        fail("Excess JPEG MCU blocks")
                    }
                }
                0xdb -> { // DQT; baseline 8-bit JPEG requires 8-bit quantizers.
                    var q = start
                    while (q < end) {
                        val spec = u8(bytes, q++)
                        val precision = spec ushr 4
                        val tableId = spec and 15
                        if (precision != 0 || tableId !in 0..3 || end - q < 64) {
                            fail("Invalid baseline JPEG quantization table")
                        }
                        for (i in 0 until 64) {
                            if (u8(bytes, q + i) == 0) {
                                fail("Zero JPEG quantization coefficient")
                            }
                        }
                        q += 64
                        definedQuantTables[tableId] = true
                    }
                }
                0xc4 -> { // DHT
                    var q = start
                    while (q < end) {
                        if (end - q < 17) fail("Truncated JPEG Huffman table")
                        val kind = u8(bytes, q++)
                        val tableClass = kind ushr 4
                        val id = kind and 15
                        if (tableClass !in 0..1 || id !in 0..3) fail("Invalid JPEG Huffman table key")
                        val counts = IntArray(16) { u8(bytes, q + it) }
                        q += 16
                        val symbols = counts.sum()
                        if (symbols !in 1..256 || symbols > end - q) fail("Invalid JPEG Huffman symbols")
                        val key = (tableClass shl 4) or id
                        val tableSymbols = bytes.copyOfRange(q, q + symbols)
                        // Validate even unused entries: a native decoder can ignore
                        // invalid symbols that do not occur in the image entropy.
                        for (symbolByte in tableSymbols) {
                            val symbol = symbolByte.toInt() and 255
                            if (tableClass == 0) {
                                if (symbol > 11) fail("Invalid baseline JPEG DC Huffman symbol")
                            } else {
                                val size = symbol and 15
                                if (symbol != 0 && symbol != 0xf0 && size !in 1..10) {
                                    fail("Invalid baseline JPEG AC Huffman symbol")
                                }
                            }
                        }
                        huffman[key] = Huffman(counts, tableSymbols)
                        q += symbols
                    }
                }
                0xdd -> { // DRI
                    if (length != 4) fail("Invalid JPEG DRI")
                    restartInterval = u16(bytes, start)
                }
                0xda -> { // Single complete interleaved scan only, for now
                    val frame = components ?: fail("Missing baseline JPEG frame")
                    if (frame.any { !definedQuantTables[it.quantTable] }) {
                        fail("Missing baseline JPEG quantization table")
                    }
                    if (length != 6 + 2 * frame.size || u8(bytes, start) != frame.size) {
                        fail("Multi-scan JPEG not yet supported by strict entropy check")
                    }
                    val scan = ArrayList<ScanComponent>()
                    val seen = HashSet<Int>()
                    for (i in frame.indices) {
                        val at = start + 1 + 2 * i
                        val id = u8(bytes, at)
                        val component = frame.find { it.id == id } ?: fail("Unknown JPEG scan component")
                        if (!seen.add(id)) fail("Duplicate JPEG scan component")
                        val selector = u8(bytes, at + 1)
                        val dc = huffman[selector ushr 4]
                            ?: fail("Missing JPEG DC Huffman table")
                        val ac = huffman[0x10 or (selector and 15)]
                            ?: fail("Missing JPEG AC Huffman table")
                        scan += ScanComponent(component, dc, ac)
                    }
                    if (u8(bytes, end - 3) != 0 || u8(bytes, end - 2) != 63 ||
                        u8(bytes, end - 1) != 0
                    ) fail("Unsupported JPEG spectral selection")
                    val hMax = frame.maxOf { it.h }
                    val vMax = frame.maxOf { it.v }
                    val cols = (width + 8 * hMax - 1) / (8 * hMax)
                    val rows = (height + 8 * vMax - 1) / (8 * vMax)
                    val mcus = cols * rows
                    val reader = Bits(bytes, end)
                    var nextRestart = 0
                    for (mcu in 0 until mcus) {
                        if ((mcu and 127) == 0) checkpoint()
                        if (restartInterval != 0 && mcu > 0 && mcu % restartInterval == 0) {
                            val got = reader.marker()
                            if (got != 0xd0 + nextRestart) fail("Unexpected JPEG restart marker")
                            nextRestart = (nextRestart + 1) and 7
                        }
                        for (s in scan) repeat(s.component.h * s.component.v) {
                            block(reader, s.dc, s.ac)
                        }
                    }
                    val terminator = reader.marker()
                    if (terminator != 0xd9 || reader.position != bytes.size) {
                        fail("JPEG scan has trailing entropy or unexpected marker")
                    }
                    return
                }
                in 0xc1..0xc3, in 0xc5..0xc7, in 0xc9..0xcb, in 0xcd..0xcf -> {
                    fail("Unsupported JPEG mode requires separate strict entropy validation")
                }
                else -> {
                    if (marker !in 0xe0..0xef && marker != 0xfe && marker !in setOf(0xdb, 0xcc, 0xde, 0xdf)) {
                        fail("Unsupported JPEG segment")
                    }
                }
            }
            p = end
        }
        fail("JPEG has no complete entropy scan")
    }
}
