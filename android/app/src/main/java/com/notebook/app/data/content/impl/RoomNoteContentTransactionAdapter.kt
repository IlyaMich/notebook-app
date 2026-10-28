package com.notebook.app.data.content.impl

import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.library.model.InitialPageSpec
import com.notebook.app.data.library.repository.NoteContentTransactionPort
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ElementEntity
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

        // Snapshot and validate ALL source content before the first clone insert.
        // Unsupported asset-backed pages or elements abort; nothing is flattened
        // or skipped. The outer transaction still protects against later IO/SQL errors.
        val generatedIds = mutableSetOf<String>()
        val copies = pages.map { page ->
            CanonicalContentValidator.page(InitialPageSpec(
                page.width, page.height, page.backgroundJson,
            ))
            val elements = db.elementDao().listByPage(page.id)
            requireContiguous(elements.map { it.orderIndex }, "Source element")
            val copiedElements = elements.map { element ->
                val (envelopeId, indexedKind) = CanonicalContentValidator.element(element.elementJson)
                if (envelopeId != element.id || indexedKind != element.kind) {
                    fail(LibraryErrorCode.INVALID_INPUT, "Stored element and canonical JSON disagree")
                }
                val newElementId = distinctId(generatedIds)
                val rewritten = JSONObject(element.elementJson)
                    .put("id", newElementId)
                    .toString()
                val (verifiedId, verifiedKind) = CanonicalContentValidator.element(rewritten)
                check(verifiedId == newElementId && verifiedKind == indexedKind)
                ElementEntity(
                    id = newElementId, pageId = "", orderIndex = element.orderIndex,
                    kind = indexedKind, elementJson = rewritten,
                    revision = target.revision,
                )
            }
            PageCopy(page, distinctId(generatedIds), copiedElements)
        }

        // Retain parent-before-child insertion order. The target note row already
        // exists in LIB-03's outer transaction. An exception here rolls it back.
        copies.forEach { copy ->
            db.pageDao().insert(copy.source.copy(
                id = copy.newId,
                noteId = targetId,
                revision = target.revision,
            ))
            copy.elements.forEach { element ->
                db.elementDao().insert(element.copy(pageId = copy.newId))
            }
        }
    }

    private data class PageCopy(
        val source: PageEntity,
        val newId: String,
        val elements: List<ElementEntity>,
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
