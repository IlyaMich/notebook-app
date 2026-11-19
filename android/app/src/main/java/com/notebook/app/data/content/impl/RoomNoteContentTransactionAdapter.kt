package com.notebook.app.data.content.impl

import com.notebook.app.data.assets.DocumentAssetReferenceInspector
import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ElementAssetReferenceEntity
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageAssetReferenceEntity
import com.notebook.app.data.local.entity.PageEntity
import java.util.UUID
import org.json.JSONObject

/**
 * STO-02's production adapter for the LIB-03 note lifecycle transaction.
 *
 * NEVER opens a database or starts its own transaction. The caller must be
 * RoomNoteLifecycleRepository, which encloses note metadata and these writes
 * in a single AppDatabase.withTransaction block. Errors propagate to that
 * transaction; no partial lifecycle operation can be acknowledged as saved.
 */
class RoomNoteContentTransactionAdapter(
    private val db: AppDatabase,
    private val createId: () -> String = { UUID.randomUUID().toString() },
) : NoteContentTransactionPort {

    override suspend fun insertInitialPage(noteId: String, spec: InitialPageSpec) {
        val id = LibraryValidation.canonicalId(noteId)
        // Validate *before* inserting anything; this also rejects asset-backed PDFs.
        CanonicalContentValidator.page(spec)
        val note = db.noteDao().findById(id)
            ?: fail(LibraryErrorCode.NOT_FOUND, "New note metadata does not exist")
        if (note.deletedAtMillis != null) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "New note is in Trash")
        }
        if (db.pageDao().countByNote(id) != 0) {
            fail(LibraryErrorCode.INVALID_INPUT, "New note already has content")
        }
        db.pageDao().insert(PageEntity(
            id = newId(), noteId = id, orderIndex = 0L,
            width = spec.width, height = spec.height,
            backgroundJson = spec.backgroundJson,
            revision = note.revision,
        ))
    }

    override suspend fun cloneContent(sourceNoteId: String, targetNoteId: String) {
        val sourceId = LibraryValidation.canonicalId(sourceNoteId)
        val targetId = LibraryValidation.canonicalId(targetNoteId)
        if (sourceId == targetId) {
            fail(LibraryErrorCode.INVALID_INPUT, "Cannot clone note content onto itself")
        }
        val source = db.noteDao().findById(sourceId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Source note does not exist")
        val target = db.noteDao().findById(targetId)
            ?: fail(LibraryErrorCode.NOT_FOUND, "Destination note does not exist")
        if (source.deletedAtMillis != null || target.deletedAtMillis != null) {
            fail(LibraryErrorCode.INACTIVE_OWNER, "Source or destination is in Trash")
        }
        if (db.pageDao().countByNote(targetId) != 0) {
            fail(LibraryErrorCode.INVALID_INPUT, "Destination note must be empty")
        }
        if (target.revision != 0L) {
            fail(LibraryErrorCode.INVALID_INPUT, "Destination note must have initial revision")
        }

        val pages = db.pageDao().listByNote(sourceId)
        if (pages.isEmpty()) {
            fail(LibraryErrorCode.INVALID_INPUT, "Source note has no pages")
        }
        requireContiguous(pages.map { it.orderIndex }, "Source page")

        // Snapshot every canonical owner and its matching typed projection before
        // any target content is inserted. A missing/mismatched/unknown projection
        // rejects the whole outer note-duplication transaction.
        // This only shares registered originals: it does not inspect file bytes,
        // certify the global ownership index, or authorize asset reclamation.
        val generatedIds = mutableSetOf<String>()
        val copies = pages.map { page ->
            if (!page.width.isFinite() || page.width <= 0.0 ||
                !page.height.isFinite() || page.height <= 0.0 ||
                page.revision < 0L || page.revision > source.revision
            ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid source page metadata")
            val pageAsset = when (val finding =
                DocumentAssetReferenceInspector.pageBackground(page.backgroundJson)) {
                is DocumentAssetReferenceInspector.Finding.Reference -> {
                    requireMatchedPageOwner(page.id, finding.assetId)
                    finding.assetId
                }
                DocumentAssetReferenceInspector.Finding.NoDirectReference -> {
                    CanonicalContentValidator.page(InitialPageSpec(
                        page.width, page.height, page.backgroundJson,
                    ))
                    if (db.assetOwnershipDao().findPageReference(page.id) != null) {
                        fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Unexpected page asset projection")
                    }
                    null
                }
                is DocumentAssetReferenceInspector.Finding.Unresolved ->
                    fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Unresolved source page background")
            }
            val elements = db.elementDao().listByPage(page.id)
            requireContiguous(elements.map { it.orderIndex }, "Source element")
            val copiedElements = elements.map { element ->
                if (element.pageId != page.id ||
                    element.revision < 0L || element.revision > source.revision
                ) fail(LibraryErrorCode.INVALID_INPUT, "Invalid source element metadata")
                val assetId = when (val finding =
                    DocumentAssetReferenceInspector.element(element.elementJson)) {
                    is DocumentAssetReferenceInspector.Finding.Reference -> {
                        val json = JSONObject(element.elementJson)
                        if (element.kind != "image" || json.optString("id") != element.id) {
                            fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Indexed image owner identity mismatch")
                        }
                        requireMatchedElementOwner(element.id, finding.assetId)
                        finding.assetId
                    }
                    DocumentAssetReferenceInspector.Finding.NoDirectReference -> {
                        val (canonicalId, canonicalKind) =
                            CanonicalContentValidator.element(element.elementJson)
                        if (canonicalId != element.id || canonicalKind != element.kind ||
                            db.assetOwnershipDao().findElementReference(element.id) != null
                        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Unexpected element projection")
                        null
                    }
                    is DocumentAssetReferenceInspector.Finding.Unresolved ->
                        fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Unresolved source element")
                }
                val newElementId = distinctId(generatedIds)
                val rewritten = JSONObject(element.elementJson)
                    .put("id", newElementId).toString()
                val rewrittenFinding = DocumentAssetReferenceInspector.element(rewritten)
                if (assetId != null) {
                    if (rewrittenFinding != DocumentAssetReferenceInspector.Finding.Reference(assetId)) {
                        fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Rewritten image owner is inconsistent")
                    }
                } else {
                    val (verifiedId, verifiedKind) = CanonicalContentValidator.element(rewritten)
                    check(verifiedId == newElementId && verifiedKind == element.kind)
                }
                ElementCopy(
                    ElementEntity(
                        id = newElementId, pageId = "", orderIndex = element.orderIndex,
                        kind = element.kind, elementJson = rewritten, revision = target.revision,
                    ),
                    assetId,
                )
            }
            PageCopy(page, distinctId(generatedIds), pageAsset, copiedElements)
        }

        // Parent-before-child and edge-after-owner writes are all enclosed by the
        // same outer Room transaction as the destination note. No physical bytes
        // or source asset metadata are changed.
        copies.forEach { copy ->
            db.pageDao().insert(copy.source.copy(
                id = copy.newId, noteId = targetId, revision = target.revision,
            ))
            copy.pageAssetId?.let { assetId ->
                db.assetOwnershipDao().insertPageReference(
                    PageAssetReferenceEntity(copy.newId, assetId))
            }
            copy.elements.forEach { element ->
                db.elementDao().insert(element.row.copy(pageId = copy.newId))
                element.assetId?.let { assetId ->
                    db.assetOwnershipDao().insertElementReference(
                        ElementAssetReferenceEntity(element.row.id, assetId))
                }
            }
        }
    }

    private suspend fun requireMatchedPageOwner(pageId: String, assetId: String) {
        val owner = db.assetOwnershipDao().findPageReference(pageId)
        val asset = db.assetDao().findById(assetId)
        if (owner?.assetId != assetId || asset?.state != "finalized" ||
            asset?.mimeType != "application/pdf"
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF source ownership mismatch")
    }

    private suspend fun requireMatchedElementOwner(elementId: String, assetId: String) {
        val owner = db.assetOwnershipDao().findElementReference(elementId)
        val asset = db.assetDao().findById(assetId)
        if (owner?.assetId != assetId || asset?.state != "finalized" ||
            asset?.mimeType !in setOf("image/png", "image/jpeg")
        ) fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image source ownership mismatch")
    }

    private data class ElementCopy(val row: ElementEntity, val assetId: String?)

    private data class PageCopy(
        val source: PageEntity,
        val newId: String,
        val pageAssetId: String?,
        val elements: List<ElementCopy>,
    )

    private fun newId(): String = LibraryValidation.canonicalId(createId())

    private fun distinctId(used: MutableSet<String>): String {
        val id = newId()
        if (!used.add(id)) {
            fail(LibraryErrorCode.INVALID_INPUT, "ID generator produced a duplicate content ID")
        }
        return id
    }

    private fun requireContiguous(positions: List<Long>, label: String) {
        if (positions.withIndex().any { it.value != it.index.toLong() }) {
            fail(LibraryErrorCode.INVALID_INPUT, "$label order is not contiguous")
        }
    }

    private fun fail(code: LibraryErrorCode, message: String): Nothing =
        throw LibraryOperationException(code, message)
}
