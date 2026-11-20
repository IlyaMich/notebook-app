package com.notebook.app.data.assets

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Builds an isolated, PRIVATE portable-format DRAFT from a copied source snapshot.
 * This is not a published note or a durable export completion receipt. A validated
 * ZIP file does NOT authorize hold release, original deletion, GC or stage adoption.
 * No source-original paths or live Room note state are ever read by this writer.
 *
 * Any failure leaves a possible partial draft in place and refuses retries.
 */
internal class ExportPrivatePortableDraftWriter(
    privateRoot: File,
    private val limits: ExportPrivateStageLimits,
    private val syncDirectory: (File) -> Unit,
    private val onEntry: (String) -> Unit = {},
) {
    internal data class DraftUntrusted(
        val operationId: String,
        val noteId: String,
        val noteRevision: Long,
        val fileName: String,
        val byteCount: Long,
        val sha256: String,
    )

    private val root = privateRoot.toPath().toAbsolutePath().normalize()
    private val stageRoot = root.resolve("pending-export-copies")

    suspend fun writeDraft(
        expected: ExportUntrustedPrivateStageObservation,
    ): DraftUntrusted = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val verifier = ExportPrivateStageReadOnlyVerifier(root.toFile(), limits)
        val result = verifier.inspect(expected)
        if (result !is ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted) {
            throw IOException("Untrusted stage did not pass independent reread")
        }
        if (UUID.fromString(expected.operationId).toString() != expected.operationId) {
            throw IOException("Noncanonical draft operation ID")
        }
        val stage = stageRoot.resolve(expected.operationId)
        val snapshotFile = stage.resolve("snapshot.json")
        if (!Files.isRegularFile(snapshotFile, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(snapshotFile) > limits.maxMetadataBytes
        ) throw IOException("Unsafe source snapshot")
        val snapshotBytes = Files.readAllBytes(snapshotFile)
        if (shaBytes(snapshotBytes) != expected.snapshotSha256) {
            throw IOException("Captured snapshot changed since stage validation")
        }
        val snapshot = Json.parseToJsonElement(
            snapshotBytes.toString(Charsets.UTF_8),
        ).jsonObject
        val model = modelFor(expected, snapshot)

        val draft = stage.resolve("draft.notebook")
        // CREATE_NEW: no replacement, adoption, resume, or cleanup on collision.
        Files.newOutputStream(draft, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
            ZipOutputStream(output).use { zip ->
                suspend fun add(name: String, bytes: ByteArray) {
                    currentCoroutineContext().ensureActive()
                    val entry = ZipEntry(name)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                    onEntry(name)
                }
                add("manifest.json", model.manifest)
                for ((path, bytes) in model.pages) add(path, bytes)
                for ((path, source) in model.assets) {
                    currentCoroutineContext().ensureActive()
                    val sourceFile = stage.resolve(source.fileName)
                    if (!Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS) ||
                        Files.size(sourceFile) != source.byteCount
                    ) throw IOException("Private copied asset changed")
                    val entry = ZipEntry(path)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    FileChannel.open(sourceFile, StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS).use { input ->
                        val buffer = ByteBuffer.allocate(8192)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            buffer.clear()
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (n == 0 || n.toLong() > source.byteCount - count) {
                                throw IOException("Private copied asset truncated or expanded")
                            }
                            zip.write(buffer.array(), 0, n)
                            digest.update(buffer.array(), 0, n)
                            count += n
                        }
                        if (count != source.byteCount ||
                            hex(digest.digest()) != source.sha256
                        ) throw IOException("Private copied asset digest mismatch")
                    }
                    zip.closeEntry()
                    onEntry(path)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val maxArchiveBytes = limits.maxTotalAssetBytes + 2L * limits.maxMetadataBytes +
            1024L * (limits.maxAssets + 128L)
        val size = Files.size(draft)
        if (!Files.isRegularFile(draft, LinkOption.NOFOLLOW_LINKS) ||
            size <= 0L || size > maxArchiveBytes
        ) throw IOException("Draft exceeds archive budget")
        FileChannel.open(draft, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        // Validate the archive independently after writing. No acceptance by writer flags.
        validateDraft(draft.toFile(), model)
        syncDirectory(stage.toFile())
        if (verifier.inspect(expected) !is
            ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted
        ) throw IOException("Private source stage changed during ZIP generation")
        val digest = shaFile(draft)
        currentCoroutineContext().ensureActive()
        DraftUntrusted(expected.operationId, expected.noteId, expected.noteRevision,
            "draft.notebook", size, digest)
    }

    /**
     * Diagnostic-only restart inspection. No writes, stage adoption, receipt,
     * release or GC decision. The caller's expected snapshot hashes are still
     * untrusted unless independently persisted and authenticated elsewhere.
     */
    suspend fun inspectExistingDraftReadOnly(
        expected: ExportUntrustedPrivateStageObservation,
    ): DraftUntrusted = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val stageResult = ExportPrivateStageReadOnlyVerifier(
            root.toFile(), limits,
        ).inspect(expected)
        if (stageResult !is ExportPrivateStageReadOnlyVerifier.Assessment.MatchedUntrusted) {
            throw IOException("Private source stage failed read-only inspection")
        }
        val stage = stageRoot.resolve(expected.operationId)
        val snapshotFile = stage.resolve("snapshot.json")
        if (!Files.isRegularFile(snapshotFile, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(snapshotFile) > limits.maxMetadataBytes
        ) throw IOException("Unsafe private snapshot")
        val snapshotBytes = Files.readAllBytes(snapshotFile)
        if (shaBytes(snapshotBytes) != expected.snapshotSha256) {
            throw IOException("Private snapshot digest changed during restart inspection")
        }
        val model = modelFor(expected, Json.parseToJsonElement(
            snapshotBytes.toString(Charsets.UTF_8),
        ).jsonObject)
        val draft = stage.resolve("draft.notebook")
        if (!Files.isRegularFile(draft, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Missing or unsafe private archive draft")
        }
        val maxArchiveBytes = limits.maxTotalAssetBytes + 2L * limits.maxMetadataBytes +
            1024L * (limits.maxAssets + 128L)
        val bytes = Files.size(draft)
        if (bytes <= 0L || bytes > maxArchiveBytes) {
            throw IOException("Private draft exceeds read-only budget")
        }
        validateDraft(draft.toFile(), model)
        val hash = shaFile(draft)
        currentCoroutineContext().ensureActive()
        DraftUntrusted(expected.operationId, expected.noteId, expected.noteRevision,
            "draft.notebook", bytes, hash)
    }

    private data class Model(
        val manifest: ByteArray,
        val pages: List<Pair<String, ByteArray>>,
        val assets: List<Pair<String, ExportPrivateCopyObservation>>,
    )

    private fun modelFor(
        expected: ExportUntrustedPrivateStageObservation,
        source: JsonObject,
    ): Model {
        require(source.str("format") == "internal-untrusted-captured-note-v1")
        require(source.str("operationId") == expected.operationId)
        val note = source.getValue("note").jsonObject
        require(note.str("id") == expected.noteId)
        require(note.getValue("revision").jsonPrimitive.long == expected.noteRevision)
        val title = note.str("title")
        require(title.isNotBlank())
        val created = note.getValue("createdAtMillis").jsonPrimitive.long
        val updated = note.getValue("updatedAtMillis").jsonPrimitive.long
        require(created >= 0L && updated >= created)
        val pages = source.getValue("pages").jsonArray.map { it.jsonObject }
        require(pages.isNotEmpty())
        val seenPageIds = HashSet<String>()
        val pageFiles = pages.mapIndexed { index, page ->
            val id = page.str("id")
            require(canonicalId(id) && seenPageIds.add(id) &&
                page.str("noteId") == expected.noteId &&
                page.getValue("orderIndex").jsonPrimitive.long == index.toLong())
            val background = Json.parseToJsonElement(page.str("canonicalBackground")).jsonObject
            val elements = page.getValue("elements").jsonArray.map { it.jsonObject }
            val document = buildJsonObject {
                put("id", id)
                put("size", buildJsonObject {
                    put("width", page.getValue("width").jsonPrimitive.double)
                    put("height", page.getValue("height").jsonPrimitive.double)
                })
                put("background", background)
                put("elements", buildJsonArray {
                    for (element in elements) {
                        add(Json.parseToJsonElement(element.str("canonicalEnvelope")).jsonObject)
                    }
                })
            }
            ("pages/" + id + ".json") to document.toString().toByteArray(Charsets.UTF_8)
        }
        val sourceAssets = source.getValue("assets").jsonArray.map { it.jsonObject }
        val byId = expected.copies.associateBy { it.assetId }
        require(byId.size == sourceAssets.size)
        val seenAssetIds = HashSet<String>()
        val archivePaths = linkedMapOf<String, ExportPrivateCopyObservation>()
        val assetRecords = sourceAssets.map { asset ->
            val id = asset.str("id")
            val registered = byId[id] ?: error("Unlisted stage asset")
            require(seenAssetIds.add(id) && asset.str("sha256") == registered.sha256 &&
                asset.getValue("encodedBytes").jsonPrimitive.long == registered.byteCount)
            val ext = when (asset.str("mimeType")) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> throw IOException("Unsupported stage asset type")
            }
            val name = "assets/" + registered.sha256 + "." + ext
            archivePaths.putIfAbsent(name, registered)
            buildJsonObject {
                put("id", id)
                put("path", name)
                put("mimeType", asset.str("mimeType"))
                put("sha256", registered.sha256)
                put("byteLength", registered.byteCount)
            }
        }
        val manifest = buildJsonObject {
            put("schemaVersion", 1)
            put("id", expected.noteId)
            put("title", title)
            put("createdAt", Instant.ofEpochMilli(created).toString())
            put("updatedAt", Instant.ofEpochMilli(updated).toString())
            put("pages", buildJsonArray {
                for (p in pageFiles) add(buildJsonObject {
                    put("id", p.first.removePrefix("pages/").removeSuffix(".json"))
                    put("path", p.first)
                })
            })
            put("assets", buildJsonArray { assetRecords.forEach { add(it) } })
        }.toString().toByteArray(Charsets.UTF_8)
        if (manifest.size.toLong() > limits.maxMetadataBytes ||
            pageFiles.any { it.second.size.toLong() > limits.maxMetadataBytes }
        ) throw IOException("Portable draft metadata exceeds budget")
        return Model(manifest, pageFiles, archivePaths.toList())
    }

    private suspend fun validateDraft(file: File, model: Model) {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            val exact = listOf("manifest.json") + model.pages.map { it.first } +
                model.assets.map { it.first }
            if (names != exact || names.distinct().size != names.size) {
                throw IOException("Draft ZIP entry set differs from captured snapshot")
            }
            suspend fun check(name: String, sha: String, size: Long) {
                currentCoroutineContext().ensureActive()
                val entry = zip.getEntry(name) ?: throw IOException("Missing portable entry")
                if (entry.size != size) throw IOException("Portable entry size mismatch")
                val digest = MessageDigest.getInstance("SHA-256")
                var observed = 0L
                val buffer = ByteArray(8192)
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        if (n == 0 || n.toLong() > size - observed) {
                            throw IOException("Portable entry expanded beyond expected size")
                        }
                        digest.update(buffer, 0, n)
                        observed += n
                    }
                }
                buffer.fill(0)
                if (observed != size || hex(digest.digest()) != sha) {
                    throw IOException("Portable archive content mismatch")
                }
            }
            check("manifest.json", shaBytes(model.manifest), model.manifest.size.toLong())
            for ((path, bytes) in model.pages) check(path, shaBytes(bytes), bytes.size.toLong())
            for ((path, copy) in model.assets) check(path, copy.sha256, copy.byteCount)
        }
    }

    private suspend fun shaFile(path: java.nio.file.Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val n = ch.read(buffer)
                if (n == -1) break
                if (n <= 0) throw IOException("Draft digest read stalled")
                md.update(buffer.array(), 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun canonicalId(value: String) =
        try { UUID.fromString(value).toString() == value }
        catch (_: IllegalArgumentException) { false }

    private companion object {
        fun hex(bytes: ByteArray) = bytes.joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        fun shaBytes(bytes: ByteArray) =
            hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
