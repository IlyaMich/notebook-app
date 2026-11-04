package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * STO-04 Stage 2A: bounded, read-only inventory of private staging and finalized files.
 *
 * A staging file may belong to a live importer. A finalized file may be referenced by a
 * note or by an in-progress export. This scanner NEVER deletes, renames or quarantines files,
 * and never infers that a file is safe to collect without database/export reference state.
 * The caller must handle every reported finding; exceeding the bound fails the scan closed.
 */
internal class ManagedAssetRecoveryInventory(
    private val privateFilesDir: File,
    private val maximumEntries: Int = 10_000,
    private val maximumHashBytesPerFile: Long = 64L * 1024L * 1024L,
) {
    init {
        require(maximumEntries > 0)
        require(maximumHashBytesPerFile > 0)
    }

    enum class Kind {
        STAGING_FILE,
        VALID_FINAL,
        HASH_MISMATCH,
        SIGNATURE_MISMATCH,
        OVERSIZED_FINAL,
        UNEXPECTED_ENTRY,
        SYMLINK,
        UNREADABLE,
    }

    data class Finding(val relativePath: String, val kind: Kind)

    private val filenamePattern = Regex("([0-9a-f]{64})\\.(png|jpg|pdf)")
    private val shardPattern = Regex("[0-9a-f]{2}")

    fun scan(): List<Finding> {
        if (!Files.isDirectory(privateFilesDir.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(privateFilesDir.toPath())
        ) throw IOException("Expected a real app-private files directory")

        val findings = mutableListOf<Finding>()
        var seen = 0
        fun record(file: File, kind: Kind) {
            seen++
            if (seen > maximumEntries) throw IOException("Asset inventory entry limit exceeded")
            findings.add(Finding(file.relativeTo(privateFilesDir).invariantSeparatorsPath, kind))
        }

        fun entries(directory: File): List<File> = directory.listFiles()?.sortedBy { it.name }
            ?: throw IOException("Unable to enumerate asset directory")

        val staging = File(privateFilesDir, "staging")
        if (Files.exists(staging.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(staging.toPath())) {
                record(staging, Kind.SYMLINK)
            } else if (!staging.isDirectory) {
                record(staging, Kind.UNEXPECTED_ENTRY)
            } else {
                for (entry in entries(staging)) {
                    val kind = when {
                        Files.isSymbolicLink(entry.toPath()) -> Kind.SYMLINK
                        !Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) -> Kind.UNEXPECTED_ENTRY
                        entry.name.startsWith("asset-") && entry.name.endsWith(".partial") -> Kind.STAGING_FILE
                        else -> Kind.UNEXPECTED_ENTRY
                    }
                    record(entry, kind)
                }
            }
        }

        val assets = File(privateFilesDir, "assets")
        if (Files.exists(assets.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(assets.toPath())) {
                record(assets, Kind.SYMLINK)
            } else if (!assets.isDirectory) {
                record(assets, Kind.UNEXPECTED_ENTRY)
            } else {
                fun inspect(directory: File, depth: Int, firstShard: String, secondShard: String) {
                    for (entry in entries(directory)) {
                        if (Files.isSymbolicLink(entry.toPath())) {
                            record(entry, Kind.SYMLINK)
                            continue
                        }
                        if (entry.isDirectory) {
                            if (depth < 2 && shardPattern.matches(entry.name)) {
                                // Count directories against the scan bound even if they are empty.
                                seen++
                                if (seen > maximumEntries) throw IOException("Asset inventory entry limit exceeded")
                                inspect(entry, depth + 1,
                                    if (depth == 0) entry.name else firstShard,
                                    if (depth == 1) entry.name else secondShard)
                            } else {
                                record(entry, Kind.UNEXPECTED_ENTRY)
                            }
                            continue
                        }
                        if (!Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            record(entry, Kind.UNEXPECTED_ENTRY)
                            continue
                        }
                        if (depth != 2) {
                            record(entry, Kind.UNEXPECTED_ENTRY)
                            continue
                        }
                        val match = filenamePattern.matchEntire(entry.name)
                        if (match == null) {
                            record(entry, Kind.UNEXPECTED_ENTRY)
                            continue
                        }
                        val hash = match.groupValues[1]
                        if (hash.substring(0, 2) != firstShard || hash.substring(2, 4) != secondShard) {
                            record(entry, Kind.UNEXPECTED_ENTRY)
                            continue
                        }
                        val kind = try {
                            validateFinal(entry, hash, match.groupValues[2])
                        } catch (_: IOException) {
                            Kind.UNREADABLE
                        } catch (_: SecurityException) {
                            Kind.UNREADABLE
                        }
                        record(entry, kind)
                    }
                }
                inspect(assets, 0, "", "")
            }
        }
        return findings
    }

    private fun validateFinal(file: File, expectedHash: String, extension: String): Kind {
        if (file.length() > maximumHashBytesPerFile) return Kind.OVERSIZED_FINAL
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        var headerCount = 0
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                total += n
                if (total > maximumHashBytesPerFile) return Kind.OVERSIZED_FINAL
                if (headerCount < header.size) {
                    val copy = minOf(n, header.size - headerCount)
                    buffer.copyInto(header, headerCount, 0, copy)
                    headerCount += copy
                }
                digest.update(buffer, 0, n)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actualHash != expectedHash) return Kind.HASH_MISMATCH
        val signatureMatches = when (extension) {
            "png" -> headerCount >= 8 && header.contentEquals(byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            "jpg" -> headerCount >= 3 && header[0] == 0xff.toByte() &&
                header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "pdf" -> headerCount >= 5 && header.copyOfRange(0, 5)
                .contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))
            else -> false
        }
        return if (signatureMatches) Kind.VALID_FINAL else Kind.SIGNATURE_MISMATCH
    }
}
