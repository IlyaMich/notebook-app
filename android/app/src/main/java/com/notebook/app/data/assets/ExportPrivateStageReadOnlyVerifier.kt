package com.notebook.app.data.assets

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Read-only verification of an abandoned or current PRIVATE UNTRUSTED stage.
 * Caller-supplied expectations are not a durable independently signed receipt.
 * MatchedUntrusted NEVER authorizes adoption, export publication, hold release,
 * source retirement, ownership promotion, or GC. No stage files are changed.
 */
internal class ExportPrivateStageReadOnlyVerifier(
    privateRoot: File,
    private val limits: ExportPrivateStageLimits,
    private val afterReadChunk: (String, Long) -> Unit = { _, _ -> },
) {
    internal enum class Reason {
        INVALID_EXPECTATION, MISSING_STAGE, UNSAFE_DIRECTORY, UNEXPECTED_ENTRY,
        MISSING_FILE, UNSAFE_FILE, OVER_BUDGET, BYTES_MISMATCH,
        INVALID_SNAPSHOT, INVALID_MANIFEST, PATH_CHANGED, READ_FAILED,
    }

    internal sealed interface Assessment {
        data class MatchedUntrusted(val observation: ExportUntrustedPrivateStageObservation) :
            Assessment
        data class Rejected(val reason: Reason) : Assessment
    }

    private val root = privateRoot.toPath().toAbsolutePath().normalize()
    private val parent = root.resolve("pending-export-copies")

    suspend fun inspect(
        expected: ExportUntrustedPrivateStageObservation,
    ): Assessment = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!validExpectation(expected)) {
            return@withContext Assessment.Rejected(Reason.INVALID_EXPECTATION)
        }
        val stage = parent.resolve(expected.operationId)
        try {
            val rootBefore = directory(root) ?: return@withContext reject(Reason.UNSAFE_DIRECTORY)
            val parentBefore = directory(parent) ?: return@withContext reject(Reason.MISSING_STAGE)
            val stageBefore = directory(stage) ?: return@withContext reject(Reason.MISSING_STAGE)
            val expectedNames = (expected.copies.map { it.fileName } +
                listOf("snapshot.json", "manifest.json")).toSet()
            val found = mutableSetOf<String>()
            Files.newDirectoryStream(stage).use { entries ->
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    val name = entry.fileName.toString()
                    if (!expectedNames.contains(name) || !found.add(name)) {
                        return@withContext reject(Reason.UNEXPECTED_ENTRY)
                    }
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        return@withContext reject(Reason.UNSAFE_FILE)
                    }
                }
            }
            if (found != expectedNames) return@withContext reject(Reason.MISSING_FILE)

            val snapshot = readFile(stage.resolve("snapshot.json"), limits.maxMetadataBytes,
                expected.snapshotSha256, captureBytes = true)
                ?: return@withContext reject(Reason.BYTES_MISMATCH)
            val manifest = readFile(stage.resolve("manifest.json"), limits.maxMetadataBytes,
                expected.manifestSha256, captureBytes = true)
                ?: return@withContext reject(Reason.BYTES_MISMATCH)
            if (!checkSnapshot(requireNotNull(snapshot.bytes), expected)) {
                return@withContext reject(Reason.INVALID_SNAPSHOT)
            }
            if (!checkManifest(requireNotNull(manifest.bytes), expected)) {
                return@withContext reject(Reason.INVALID_MANIFEST)
            }
            for (copy in expected.copies) {
                currentCoroutineContext().ensureActive()
                val verified = readFile(stage.resolve(copy.fileName), limits.maxAssetBytes,
                    copy.sha256, captureBytes = false)
                    ?: return@withContext reject(Reason.BYTES_MISMATCH)
                if (verified.size != copy.byteCount) {
                    return@withContext reject(Reason.BYTES_MISMATCH)
                }
            }
            val rootAfter = directory(root)
            val parentAfter = directory(parent)
            val stageAfter = directory(stage)
            if (rootAfter == null || parentAfter == null || stageAfter == null ||
                !stable(rootBefore, rootAfter) || !stable(parentBefore, parentAfter) ||
                !stable(stageBefore, stageAfter)
            ) return@withContext reject(Reason.PATH_CHANGED)
            currentCoroutineContext().ensureActive()
            Assessment.MatchedUntrusted(expected)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Assessment.Rejected(Reason.READ_FAILED)
        } catch (e: SecurityException) {
            currentCoroutineContext().ensureActive()
            Assessment.Rejected(Reason.READ_FAILED)
        }
    }

    private data class ReadFile(val size: Long, val bytes: ByteArray?)

    private suspend fun readFile(
        path: Path, maxBytes: Long, expectedSha256: String, captureBytes: Boolean,
    ): ReadFile? {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.isSymbolicLink ||
            before.size() < 0L || before.size() > maxBytes
        ) throw IOException("Unsafe or over-budget private stage member")
        val digest = MessageDigest.getInstance("SHA-256")
        val memory = if (captureBytes) ByteArrayOutputStream() else null
        val chunkBytes = ByteArray(8_192)
        var size = 0L
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                if (input.size() != before.size()) return null
                val chunk = ByteBuffer.wrap(chunkBytes)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    chunk.clear()
                    val read = input.read(chunk)
                    if (read == -1) break
                    if (read == 0 || read.toLong() > maxBytes - size) return null
                    digest.update(chunkBytes, 0, read)
                    memory?.write(chunkBytes, 0, read)
                    size += read
                    afterReadChunk(path.fileName.toString(), size)
                    currentCoroutineContext().ensureActive()
                }
                if (input.size() != size || size != before.size()) return null
            }
        } finally {
            chunkBytes.fill(0)
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || !stable(before, after)) return null
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != expectedSha256) return null
        return ReadFile(size, memory?.toByteArray())
    }

    private fun checkSnapshot(bytes: ByteArray, expected: ExportUntrustedPrivateStageObservation): Boolean {
        return try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (json.str("format") != "internal-untrusted-captured-note-v1" ||
                json.str("operationId") != expected.operationId
            ) return false
            val note = json.getValue("note").jsonObject
            if (note.str("id") != expected.noteId ||
                note.getValue("revision").jsonPrimitive.long != expected.noteRevision
            ) return false
            val assets = json.getValue("assets").jsonArray.map { it.jsonObject }
            if (assets.size != expected.copies.size) return false
            val byId = expected.copies.associateBy { it.assetId }
            if (byId.size != assets.size) return false
            if (assets.any { asset ->
                val copy = byId[asset.str("id")] ?: return@any true
                asset.str("sha256") != copy.sha256 ||
                    asset.getValue("encodedBytes").jsonPrimitive.long != copy.byteCount ||
                    asset.str("state") != "finalized"
            }) return false
            val pages = json.getValue("pages").jsonArray.map { it.jsonObject }
            if (pages.isEmpty() || pages.any { it.str("noteId") != expected.noteId }) return false
            val owners = json.getValue("imageOwners").jsonArray.map { it.jsonObject }
            if (owners.any { it.str("noteId") != expected.noteId ||
                    it.str("assetId") !in byId.keys
            }) return false
            owners.map { it.str("assetId") }.toSet() == byId.keys
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: NoSuchElementException) {
            false
        }
    }

    private fun checkManifest(bytes: ByteArray, expected: ExportUntrustedPrivateStageObservation): Boolean {
        return try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (json.str("format") != "internal-untrusted-copy-manifest-v1" ||
                json.str("operationId") != expected.operationId ||
                json.str("noteId") != expected.noteId ||
                json.getValue("noteRevision").jsonPrimitive.long != expected.noteRevision ||
                json.str("snapshotSha256") != expected.snapshotSha256
            ) return false
            val copies = json.getValue("copies").jsonArray.map { it.jsonObject }
            if (copies.size != expected.copies.size) return false
            val byId = expected.copies.associateBy { it.assetId }
            if (byId.size != copies.size) return false
            copies.all { item ->
                val copy = byId[item.str("assetId")] ?: return@all false
                item.str("fileName") == copy.fileName &&
                    item.str("sha256") == copy.sha256 &&
                    item.getValue("byteCount").jsonPrimitive.long == copy.byteCount
            }
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: NoSuchElementException) {
            false
        }
    }

    private fun validExpectation(x: ExportUntrustedPrivateStageObservation): Boolean {
        if (!canonicalUuid(x.operationId) || !canonicalUuid(x.noteId) ||
            x.noteRevision < 0L || !shaPattern.matches(x.snapshotSha256) ||
            !shaPattern.matches(x.manifestSha256) || x.copies.size > limits.maxAssets
        ) return false
        val ids = HashSet<String>()
        var total = 0L
        for (copy in x.copies) {
            if (!canonicalUuid(copy.assetId) || !ids.add(copy.assetId) ||
                copy.fileName != "asset-" + copy.assetId + ".bin" ||
                !shaPattern.matches(copy.sha256) || copy.byteCount <= 0L ||
                copy.byteCount > limits.maxAssetBytes ||
                copy.byteCount > limits.maxTotalAssetBytes - total
            ) return false
            total += copy.byteCount
        }
        return true
    }

    private fun directory(path: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS).takeIf { it.isDirectory && !it.isSymbolicLink }
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }

    private fun stable(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.isDirectory == b.isDirectory && a.isRegularFile == b.isRegularFile &&
            a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime() &&
            (a.fileKey() == null && b.fileKey() == null ||
                a.fileKey() != null && a.fileKey() == b.fileKey())

    private fun canonicalUuid(value: String): Boolean =
        try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun reject(reason: Reason): Assessment = Assessment.Rejected(reason)

    private companion object {
        val shaPattern = Regex("[0-9a-f]{64}")
    }
}
