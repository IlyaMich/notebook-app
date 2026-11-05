package com.notebook.app.data.assets

import androidx.room.withTransaction
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.assets.DocumentAssetReferenceInspector.Finding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * STO-08 C1A-2. A read-only diagnostic snapshot, never a GC eligibility decision.
 * No stored content, metadata, references or original bytes are modified here.
 */
internal class RoomAssetOwnerInventory(private val database: AppDatabase) {
    companion object {
        private const val MAX_NOTES = 2_000
        private const val MAX_PAGES = 10_000
        private const val MAX_ELEMENTS = 50_000
        private const val MAX_ASSETS = 10_000
        private const val ASSET_BATCH_SIZE = 256
    }

    sealed interface Scan {
        data class Completed(val report: AssetOwnerInventory.Report) : Scan
        /** A failed or bounded scan is NEVER evidence of no owners. */
        data class Incomplete(val reason: String) : Scan
    }

    suspend fun scan(): Scan {
        return try {
            database.withTransaction {
                val noteDao = database.noteDao()
                val pageDao = database.pageDao()
                val elementDao = database.elementDao()
                val assetDao = database.assetDao()
                val noteCount = noteDao.countAll()
                if (noteCount < 0 || noteCount > MAX_NOTES) {
                    return@withTransaction Scan.Incomplete("note scan limit exceeded")
                }
                val notes = noteDao.listForAssetOwnerInventory(MAX_NOTES + 1)
                if (notes.size != noteCount) {
                    return@withTransaction Scan.Incomplete("note scan count mismatch")
                }

                val collector = AssetOwnerInventory.Collector()
                var pageTotal = 0L
                var elementTotal = 0L

                for (note in notes) {
                    val pageCount = pageDao.countByNote(note.id)
                    if (pageCount < 0 || pageTotal + pageCount > MAX_PAGES) {
                        return@withTransaction Scan.Incomplete("page scan limit exceeded")
                    }
                    val pages = pageDao.listByNote(note.id)
                    if (pages.size != pageCount) {
                        return@withTransaction Scan.Incomplete("page scan count mismatch")
                    }
                    pageTotal += pageCount
                    for (page in pages) {
                        collector.addBackground(note.id, note.deletedAtMillis != null,
                            page.id, page.noteId, page.backgroundJson)
                        val elementCount = elementDao.countByPage(page.id)
                        if (elementCount < 0 || elementTotal + elementCount > MAX_ELEMENTS) {
                            return@withTransaction Scan.Incomplete("element scan limit exceeded")
                        }
                        val elements = elementDao.listByPage(page.id)
                        if (elements.size != elementCount) {
                            return@withTransaction Scan.Incomplete("element scan count mismatch")
                        }
                        elementTotal += elementCount
                        for (element in elements) {
                            collector.addElement(note.id, note.deletedAtMillis != null,
                                page.id, element.id, element.pageId, element.kind, element.elementJson)
                        }
                    }
                }

                // Detect rows that were not reachable through the note/page hierarchy.
                // Normal foreign keys prevent these, but diagnostics must not assume that.
                if (pageTotal != pageDao.countAll().toLong() ||
                    elementTotal != elementDao.countAll().toLong()
                ) {
                    return@withTransaction Scan.Incomplete("unreachable page or element rows")
                }

                val assetCount = assetDao.countAll()
                if (assetCount < 0 || assetCount > MAX_ASSETS) {
                    return@withTransaction Scan.Incomplete("asset metadata scan limit exceeded")
                }
                val assetIds = mutableSetOf<String>()
                var after: String? = null
                while (assetIds.size < assetCount) {
                    val batch = assetDao.listAfterId(after, ASSET_BATCH_SIZE)
                    if (batch.isEmpty()) {
                        return@withTransaction Scan.Incomplete("asset metadata scan count mismatch")
                    }
                    for (asset in batch) {
                        if (after != null && asset.id <= after) {
                            return@withTransaction Scan.Incomplete("asset metadata ordering failure")
                        }
                        if (!assetIds.add(asset.id)) {
                            return@withTransaction Scan.Incomplete("duplicate asset metadata ID")
                        }
                        after = asset.id
                    }
                    if (assetIds.size > assetCount) {
                        return@withTransaction Scan.Incomplete("asset metadata scan count mismatch")
                    }
                }
                Scan.Completed(collector.finish(assetIds))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Do not expose exception messages: some may include note text or file paths.
            Scan.Incomplete("database snapshot failed")
        }
    }
}

/** Deterministic aggregation, separately unit-testable without a Room database. */
internal object AssetOwnerInventory {
    enum class Kind { PDF_BACKGROUND, IMAGE_ELEMENT }

    data class Owner(
        val assetId: String,
        val noteId: String,
        val pageId: String,
        val elementId: String?,
        val kind: Kind,
        val inTrash: Boolean,
    )

    data class Issue(val noteId: String, val pageId: String, val elementId: String?, val reason: String)

    data class Report(
        val owners: List<Owner>,
        val issues: List<Issue>,
        val metadataWithoutRecognizedOwner: Set<String>,
    ) {
        /** Diagnostics only. Even an empty issues list does not authorize asset deletion. */
        val hasUnresolved: Boolean get() = issues.isNotEmpty()
    }

    class Collector {
        private val owners = mutableListOf<Owner>()
        private val issues = mutableListOf<Issue>()

        fun addBackground(noteId: String, inTrash: Boolean, pageId: String,
                          rowNoteId: String, json: String) {
            if (noteId != rowNoteId) issues += Issue(noteId, pageId, null, "page ownership mismatch")
            when (val finding = DocumentAssetReferenceInspector.pageBackground(json)) {
                is Finding.Reference -> owners += Owner(finding.assetId, noteId, pageId,
                    null, Kind.PDF_BACKGROUND, inTrash)
                is Finding.Unresolved -> issues += Issue(noteId, pageId, null, finding.reason.name)
                Finding.NoDirectReference -> Unit
            }
        }

        fun addElement(noteId: String, inTrash: Boolean, pageId: String,
                       elementId: String, rowPageId: String, rowKind: String, json: String) {
            if (pageId != rowPageId) issues += Issue(noteId, pageId, elementId, "element ownership mismatch")
            val finding = DocumentAssetReferenceInspector.element(json)
            when (finding) {
                is Finding.Reference -> owners += Owner(finding.assetId, noteId, pageId,
                    elementId, Kind.IMAGE_ELEMENT, inTrash)
                is Finding.Unresolved -> issues += Issue(noteId, pageId, elementId, finding.reason.name)
                Finding.NoDirectReference -> Unit
            }
            if (finding is Finding.Unresolved) return
            val envelope = try { Json.parseToJsonElement(json) as? JsonObject } catch (_: Exception) { null }
            if (envelope != null) {
                val jsonId = (envelope["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val jsonType = (envelope["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (jsonId != elementId || jsonType != rowKind) {
                    issues += Issue(noteId, pageId, elementId, "element envelope/index mismatch")
                }
            }
        }

        fun finish(registeredAssetIds: Set<String>): Report {
            for (owner in owners) {
                if (owner.assetId !in registeredAssetIds) {
                    issues += Issue(owner.noteId, owner.pageId, owner.elementId,
                        "referenced asset absent from metadata")
                }
            }
            val recognizedIds = owners.mapTo(mutableSetOf()) { it.assetId }
            return Report(
                owners = owners.sortedWith(compareBy<Owner>(
                    { it.noteId }, { it.pageId }, { it.elementId ?: "" }, { it.assetId }
                )),
                issues = issues.toList(),
                metadataWithoutRecognizedOwner = registeredAssetIds - recognizedIds,
            )
        }
    }
}
