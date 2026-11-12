package com.notebook.app.data.assets

import android.database.SQLException
import androidx.room.withTransaction
import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.local.AppDatabase
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Read-only metadata/content capture. Never opens originals or acquires a lease. */
internal class ExportSourceSnapshotReader(private val db: AppDatabase) {
    suspend fun capture(request: SnapshotRequest): SnapshotResult = try {
        // Exactly one coordinator, even for rejected requests; no nested repository calls.
        db.withTransaction {
            currentCoroutineContext().ensureActive()
            val limits = request.limits
            checkRequest(request)
            val note = db.noteDao().findById(request.noteId)
                ?: reject(SnapshotRejection.NOTE_NOT_FOUND)
            structure(note.id == request.noteId && validId(note.notebookId) && note.revision >= 0)
            eligible(note.deletedAtMillis == null && note.deletionBatchId == null &&
                note.originalNotebookId == null)
            val book = db.notebookDao().findById(note.notebookId)
                ?: reject(SnapshotRejection.INELIGIBLE_NOTE)
            eligible(book.id == note.notebookId && book.deletedAtMillis == null &&
                book.deletionBatchId == null && book.originalParentFolderId == null &&
                book.originalSortOrder == null && book.sortOrder >= 0)
            val ancestors = HashSet<String>()
            var parent = book.parentFolderId
            while (parent != null) {
                currentCoroutineContext().ensureActive()
                eligible(validId(parent) && ancestors.add(parent))
                bound(ancestors.size <= limits.maxFolderDepth)
                val folder = db.folderDao().findById(parent)
                    ?: reject(SnapshotRejection.INELIGIBLE_NOTE)
                eligible(folder.id == parent && folder.deletedAtMillis == null &&
                    folder.deletionBatchId == null && folder.originalParentFolderId == null &&
                    folder.originalSortOrder == null && folder.sortOrder >= 0)
                parent = folder.parentFolderId
            }

            val pageCount = db.pageDao().countByNote(note.id)
            structure(pageCount > 0)
            bound(pageCount <= limits.maxPages)
            val rows = db.pageDao().listByNote(note.id)
            structure(rows.size == pageCount)
            val pages = ArrayList<SnapshotPage>()
            val owners = ArrayList<SnapshotImageOwner>()
            val assets = linkedMapOf<String, SnapshotAssetMetadata>()
            val pageIds = HashSet<String>()
            val elementIds = HashSet<String>()
            var elementCount = 0L
            var jsonBytes = 0L
            var sourceBytes = 0L
            fun account(raw: String) {
                // Count UTF-8 without an extra byte-array allocation.
                jsonBytes = add(jsonBytes, utf8Size(raw), limits.maxCanonicalJsonBytes)
            }
            for ((pageIndex, page) in rows.withIndex()) {
                currentCoroutineContext().ensureActive()
                structure(validId(page.id) && pageIds.add(page.id) && page.noteId == note.id &&
                    page.orderIndex == pageIndex.toLong() && page.width.isFinite() &&
                    page.height.isFinite() && page.width > 0 && page.height > 0 &&
                    page.revision in 0L..note.revision)
                account(page.backgroundJson)
                val background = parse(page.backgroundJson, limits.maxJsonDepth)
                if (string(background, "type") == "pdfPage") reject(SnapshotRejection.UNSUPPORTED_MEDIA)
                validateNonMedia { CanonicalContentValidator.background(page.backgroundJson) }
                if (db.assetOwnershipDao().findPageReference(page.id) != null) {
                    reject(SnapshotRejection.OWNER_EDGE_MISMATCH)
                }
                val count = db.elementDao().countByPage(page.id)
                structure(count >= 0)
                elementCount = add(elementCount, count.toLong(), limits.maxElements.toLong())
                val elements = db.elementDao().listByPage(page.id)
                structure(elements.size == count)
                val captured = ArrayList<SnapshotElement>()
                for ((index, element) in elements.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    structure(validId(element.id) && elementIds.add(element.id) &&
                        element.pageId == page.id && element.orderIndex == index.toLong() &&
                        element.revision in 0L..note.revision)
                    account(element.elementJson)
                    val envelope = parse(element.elementJson, limits.maxJsonDepth)
                    structure(string(envelope, "id") == element.id &&
                        string(envelope, "type") == element.kind)
                    val assetId = if (element.kind == "image") image(envelope) else {
                        validateNonMedia { CanonicalContentValidator.element(element.elementJson) }
                        null
                    }
                    val edge = db.assetOwnershipDao().findElementReference(element.id)
                    if (edge?.assetId != assetId ||
                        (edge != null && edge.elementId != element.id)) {
                        reject(SnapshotRejection.OWNER_EDGE_MISMATCH)
                    }
                    if (assetId != null) {
                        owners += SnapshotImageOwner(note.id, page.id, element.id, assetId)
                        if (assetId !in assets) {
                            bound(assets.size < limits.maxDistinctAssets)
                            val asset = db.assetDao().findById(assetId)
                                ?: reject(SnapshotRejection.ASSET_METADATA_MISSING)
                            val ext = when (asset.mimeType) {
                                "image/png" -> "png"
                                "image/jpeg" -> "jpg"
                                else -> reject(SnapshotRejection.UNSUPPORTED_MEDIA)
                            }
                            if (asset.id != assetId || asset.state != "finalized" ||
                                asset.byteSize <= 0 ||
                                !Regex("[0-9a-f]{64}").matches(asset.sha256)) {
                                reject(SnapshotRejection.ASSET_METADATA_INVALID)
                            }
                            bound(asset.byteSize <= limits.maxAssetBytes)
                            val hash = asset.sha256
                            if (asset.relativePath !=
                                "assets/${hash.take(2)}/${hash.substring(2, 4)}/$hash.$ext") {
                                reject(SnapshotRejection.ASSET_METADATA_INVALID)
                            }
                            sourceBytes = add(sourceBytes, asset.byteSize, limits.maxTotalAssetBytes)
                            assets[assetId] = SnapshotAssetMetadata(asset.id, hash, asset.mimeType,
                                asset.byteSize, asset.relativePath, asset.state)
                        }
                    }
                    captured += SnapshotElement(element.id, page.id, element.orderIndex,
                        element.kind, element.elementJson, element.revision)
                }
                pages += SnapshotPage(page.id, note.id, page.orderIndex, page.width,
                    page.height, page.backgroundJson, page.revision, frozen(captured))
            }
            currentCoroutineContext().ensureActive()
            SnapshotResult.Complete(
                SnapshotNote(note.id, note.notebookId, note.title, note.createdAtMillis,
                    note.updatedAtMillis, note.lastOpenedAtMillis, note.revision),
                frozen(pages), frozen(owners), frozen(assets.values), jsonBytes, sourceBytes,
            )
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Rejection) {
        SnapshotResult.Rejected(failure.reason)
    } catch (_: SQLException) {
        // Catch database failures only; fatal VM errors and programming failures propagate.
        currentCoroutineContext().ensureActive()
        SnapshotResult.Rejected(SnapshotRejection.DATABASE_READ_FAILED)
    }

    private fun checkRequest(request: SnapshotRequest) {
        val l = request.limits
        if (!validId(request.noteId) || l.maxPages <= 0 || l.maxElements < 0 ||
            l.maxDistinctAssets < 0 || l.maxAssetBytes <= 0 || l.maxTotalAssetBytes < 0 ||
            l.maxCanonicalJsonBytes <= 0 || l.maxFolderDepth < 0 ||
            l.maxJsonDepth !in 1..64) reject(SnapshotRejection.INVALID_REQUEST)
    }

    private fun image(o: JsonObject): String {
        keys(o, setOf("id", "type", "transform", "locked", "payload"))
        val locked = o["locked"] as? JsonPrimitive
        structure(locked != null && !locked.isString && locked.booleanOrNull != null)
        val transform = o["transform"] as? JsonArray ?: invalid()
        structure(transform.size == 6)
        val n = transform.map { number(it) }
        val det = n[0] * n[3] - n[1] * n[2]
        structure(det.isFinite() && det != 0.0)
        val p = o["payload"] as? JsonObject ?: invalid()
        keys(p, setOf("assetId", "frame", "opacity"), setOf("crop"))
        val assetId = string(p, "assetId")
        structure(validId(assetId))
        rectangle(p["frame"], false)
        structure(number(p["opacity"]) in 0.0..1.0)
        if ("crop" in p) rectangle(p["crop"], true)
        return assetId
    }

    private fun rectangle(value: JsonElement?, normalized: Boolean) {
        val o = value as? JsonObject ?: invalid()
        keys(o, setOf("x", "y", "width", "height"))
        val x = number(o["x"]); val y = number(o["y"])
        val w = number(o["width"]); val h = number(o["height"])
        structure(w > 0 && h > 0)
        if (normalized) structure(x >= 0 && y >= 0 && x + w <= 1 && y + h <= 1)
    }

    private fun number(value: JsonElement?): Double {
        val p = value as? JsonPrimitive ?: invalid()
        structure(!p.isString)
        return p.doubleOrNull?.takeIf { it.isFinite() } ?: invalid()
    }

    private fun string(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun keys(o: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        structure(o.keys.containsAll(required) && (required + optional).containsAll(o.keys))
    }

    private fun validateNonMedia(action: () -> Any) {
        try {
            action()
        } catch (_: LibraryOperationException) {
            invalid()
        }
    }

    private fun parse(raw: String, depth: Int): JsonObject {
        // Reject duplicate keys, trailing tokens, lenient syntax and excessive nesting
        // BEFORE semantic parsing. Exact original strings are returned, never rewritten.
        StrictJson(raw, depth).check()
        return try {
            Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        } catch (_: SerializationException) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun validId(id: String): Boolean =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)

    private fun add(a: Long, b: Long, max: Long): Long {
        bound(a >= 0 && b >= 0 && b <= max && a <= max - b)
        return a + b
    }

    private fun utf8Size(raw: String): Long {
        var bytes = 0L
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            val size = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> {
                    structure(i < raw.length && raw[i].isLowSurrogate())
                    i++
                    4
                }
                c.isLowSurrogate() -> invalid()
                else -> 3
            }
            bytes = add(bytes, size.toLong(), Long.MAX_VALUE)
        }
        return bytes
    }

    private fun <T> frozen(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private class Rejection(val reason: SnapshotRejection) : RuntimeException()
    private fun reject(reason: SnapshotRejection): Nothing = throw Rejection(reason)
    private fun invalid(): Nothing = reject(SnapshotRejection.INVALID_DOCUMENT_STRUCTURE)
    private fun structure(ok: Boolean) { if (!ok) invalid() }
    private fun eligible(ok: Boolean) { if (!ok) reject(SnapshotRejection.INELIGIBLE_NOTE) }
    private fun bound(ok: Boolean) { if (!ok) reject(SnapshotRejection.RESOURCE_LIMIT_EXCEEDED) }

    /** Bounded strict syntax walk; object key sets are scoped to each object. */
    private inner class StrictJson(private val raw: String, private val maxDepth: Int) {
        private var at = 0
        fun check() {
            value(0)
            space()
            structure(at == raw.length)
        }
        private fun space() {
            while (at < raw.length && raw[at] in " \t\r\n") at++
        }
        private fun take(c: Char): Boolean {
            space()
            return if (at < raw.length && raw[at] == c) { at++; true } else false
        }
        private fun need(c: Char) { structure(take(c)) }
        private fun value(depth: Int) {
            space()
            structure(at < raw.length)
            bound(depth <= maxDepth)
            when (raw[at]) {
                '{' -> {
                    at++
                    val seen = HashSet<String>()
                    if (take('}')) return
                    do {
                        space()
                        val key = quoted()
                        structure(seen.add(key))
                        need(':')
                        value(depth + 1)
                    } while (take(','))
                    need('}')
                }
                '[' -> {
                    at++
                    if (take(']')) return
                    do { value(depth + 1) } while (take(','))
                    need(']')
                }
                '"' -> quoted()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val start = at
                    if (at < raw.length && raw[at] == '-') at++
                    structure(at < raw.length)
                    if (raw[at] == '0') at++ else digits(true)
                    if (at < raw.length && raw[at] == '.') { at++; digits(false) }
                    if (at < raw.length && raw[at] in "eE") {
                        at++
                        if (at < raw.length && raw[at] in "+-") at++
                        digits(false)
                    }
                    structure(at > start)
                }
            }
        }
        private fun digits(nonzero: Boolean) {
            val start = at
            if (nonzero) structure(at < raw.length && raw[at] in '1'..'9')
            while (at < raw.length && raw[at] in '0'..'9') at++
            structure(at > start)
        }
        private fun literal(word: String) {
            structure(raw.startsWith(word, at))
            at += word.length
        }
        private fun quoted(): String {
            val start = at
            structure(at < raw.length && raw[at++] == '"')
            var closed = false
            while (at < raw.length) {
                val c = raw[at++]
                if (c == '"') { closed = true; break }
                structure(c.code >= 0x20)
                if (c == '\\') {
                    structure(at < raw.length)
                    val escaped = raw[at++]
                    if (escaped == 'u') {
                        repeat(4) {
                            structure(at < raw.length && raw[at++] in "0123456789abcdefABCDEF")
                        }
                    } else structure(escaped in "\"\\/bfnrt")
                }
            }
            structure(closed)
            return try {
                (Json.parseToJsonElement(raw.substring(start, at)) as JsonPrimitive).content
                    .also { utf8Size(it) }
            } catch (_: SerializationException) {
                invalid()
            } catch (_: IllegalArgumentException) {
                invalid()
            }
        }
    }
}
