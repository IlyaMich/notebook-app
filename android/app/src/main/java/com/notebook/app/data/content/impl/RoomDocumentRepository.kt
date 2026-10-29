package com.notebook.app.data.content.impl

import androidx.room.withTransaction
import com.notebook.app.data.content.model.DocumentCommit
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.content.validation.CanonicalContentValidator
import com.notebook.app.data.library.LibraryErrorCode
import com.notebook.app.data.library.LibraryOperationException
import com.notebook.app.data.library.LibraryValidation
import com.notebook.app.data.local.AppDatabase
import com.notebook.app.data.local.entity.ElementEntity
import com.notebook.app.data.local.entity.PageEntity
import org.json.JSONObject

/** Each successful command is one SQLite transaction and at most one revision advance.
 * The two-phase ordering writes rely on the V2 unique indexes. No UI caller may use raw DAOs.
 */
class RoomDocumentRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : DocumentRepository {

    override suspend fun load(noteId: String): StoredNoteContent = db.withTransaction {
        val id = LibraryValidation.canonicalId(noteId)
        val note = db.noteDao().findById(id) ?: fail(LibraryErrorCode.NOT_FOUND, "Note not found")
        val pages = db.pageDao().listByNote(id).map { page ->
            StoredPage(
                id = page.id, orderIndex = page.orderIndex,
                width = page.width, height = page.height, backgroundJson = page.backgroundJson,
                revision = page.revision,
                elements = db.elementDao().listByPage(page.id).map { element ->
                    StoredElement(element.id, element.orderIndex, element.kind, element.elementJson, element.revision)
                },
            )
        }
        StoredNoteContent(note.id, note.revision, pages)
    }

    override suspend fun commit(noteId: String, edit: DocumentEdit): DocumentCommit = db.withTransaction {
        val id = LibraryValidation.canonicalId(noteId)
        val note = db.noteDao().findById(id) ?: fail(LibraryErrorCode.NOT_FOUND, "Note not found")
        if (note.deletedAtMillis != null) fail(LibraryErrorCode.INACTIVE_OWNER, "Note is in Trash")
        val notebook = db.notebookDao().findById(note.notebookId)
            ?: fail(LibraryErrorCode.INACTIVE_OWNER, "Notebook is missing")
        if (notebook.deletedAtMillis != null) fail(LibraryErrorCode.INACTIVE_OWNER, "Notebook is in Trash")
        val pages = db.pageDao().listByNote(id)
        if (pages.isEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Note has no first page")
        requireContiguous(pages.map { it.orderIndex })
        if (note.revision == Long.MAX_VALUE) fail(LibraryErrorCode.INVALID_INPUT, "Note revision exhausted")
        val nextRevision = note.revision + 1L
        val changed = perform(id, pages, edit, nextRevision)
        val result = if (changed) {
            val now = maxOf(clock(), note.updatedAtMillis)
            val updated = note.copy(revision = nextRevision, updatedAtMillis = now)
            check(db.noteDao().update(updated) == 1)
            updated
        } else note
        DocumentCommit(result.id, result.revision, result.updatedAtMillis)
    }

    private suspend fun perform(
        noteId: String, pages: List<PageEntity>, edit: DocumentEdit, revision: Long,
    ): Boolean = when (edit) {
        is DocumentEdit.InsertPage -> {
            CanonicalContentValidator.page(edit.spec)
            val id = LibraryValidation.canonicalId(edit.id)
            val at = insertionAt(edit.at, pages.size)
            db.pageDao().insert(PageEntity(
                id = id, noteId = noteId, orderIndex = pages.size.toLong(),
                width = edit.spec.width, height = edit.spec.height,
                backgroundJson = edit.spec.backgroundJson, revision = revision,
            ))
            if (at != pages.size) {
                val order = pages.map { it.id }.toMutableList().also { it.add(at, id) }
                setPageOrder(db.pageDao().listByNote(noteId), order, revision)
            }
            true
        }
        is DocumentEdit.RemovePage -> {
            val page = ownedPage(pages, edit.id)
            if (pages.size == 1) fail(LibraryErrorCode.INVALID_INPUT, "Cannot delete the last page")
            // Asset-aware deletes require a later reference cleanup protocol.
            if (JSONObject(page.backgroundJson).optString("type") == "pdfPage") {
                fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "PDF asset deletion is not supported")
            }
            val elements = db.elementDao().listByPage(page.id)
            if (elements.any { it.kind == "image" }) {
                fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image asset deletion is not supported")
            }
            elements.forEach { check(db.elementDao().deleteById(it.id) == 1) }
            check(db.pageDao().deleteById(page.id) == 1)
            val remaining = db.pageDao().listByNote(noteId)
            setPageOrder(remaining, remaining.map { it.id }, revision)
            true
        }
        is DocumentEdit.ReorderPages -> {
            setPageOrder(pages, edit.idsInOrder, revision)
        }
        is DocumentEdit.ChangeBackground -> {
            val page = ownedPage(pages, edit.pageId)
            CanonicalContentValidator.background(edit.backgroundJson)
            if (page.backgroundJson == edit.backgroundJson) false else {
                check(db.pageDao().update(page.copy(backgroundJson = edit.backgroundJson, revision = revision)) == 1)
                true
            }
        }
        is DocumentEdit.InsertElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val at = insertionAt(edit.at, existing.size)
            if (edit.envelopes.isEmpty()) false else {
                val parsed = edit.envelopes.map { raw ->
                    val (id, kind) = CanonicalContentValidator.element(raw)
                    ElementEntity(id, page.id, 0, kind, raw, revision)
                }
                uniqueIds(parsed.map { it.id })
                parsed.forEachIndexed { index, element ->
                    db.elementDao().insert(element.copy(orderIndex = existing.size.toLong() + index))
                }
                if (at != existing.size) {
                    val desired = existing.map { it.id }.toMutableList().also {
                        it.addAll(at, parsed.map { item -> item.id })
                    }
                    setElementOrder(db.elementDao().listByPage(page.id), desired, revision)
                }
                true
            }
        }
        is DocumentEdit.ReplaceElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id).associateBy { it.id }
            val parsed = edit.envelopes.map { raw ->
                val (id, kind) = CanonicalContentValidator.element(raw)
                val old = existing[id] ?: fail(LibraryErrorCode.NOT_FOUND, "Element not on specified page")
                if (isLocked(old)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be modified")
                old.copy(kind = kind, elementJson = raw, revision = revision)
            }
            uniqueIds(parsed.map { it.id })
            val changed = parsed.filter { it.elementJson != existing.getValue(it.id).elementJson }
            changed.forEach { check(db.elementDao().update(it) == 1) }
            changed.isNotEmpty()
        }
        is DocumentEdit.RemoveElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val ids = edit.ids.map(LibraryValidation::canonicalId)
            uniqueIds(ids)
            if (ids.isEmpty()) false else {
                val selected = requireSelected(existing, ids)
                if (selected.any(::isLocked)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be removed")
                if (selected.any { it.kind == "image" }) {
                    fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image asset deletion is not supported")
                }
                ids.forEach { check(db.elementDao().deleteById(it) == 1) }
                val remaining = db.elementDao().listByPage(page.id)
                setElementOrder(remaining, remaining.map { it.id }, revision)
                true
            }
        }
        is DocumentEdit.ReorderElements -> {
            val page = ownedPage(pages, edit.pageId)
            val existing = elementsFor(page.id)
            val requested = edit.idsInOrder.map(LibraryValidation::canonicalId)
            if (existing.map { it.id } != requested && existing.any(::isLocked)) {
                fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot be reordered")
            }
            setElementOrder(existing, requested, revision)
        }
        is DocumentEdit.TransferElements -> {
            val from = ownedPage(pages, edit.fromPageId)
            val to = ownedPage(pages, edit.toPageId)
            if (from.id == to.id) fail(LibraryErrorCode.INVALID_INPUT, "Transfer requires different pages")
            val source = elementsFor(from.id)
            val destination = elementsFor(to.id)
            val ids = edit.ids.map(LibraryValidation::canonicalId)
            uniqueIds(ids)
            val at = insertionAt(edit.at, destination.size)
            if (ids.isEmpty()) false else {
                val selected = requireSelected(source, ids)
                if (selected.any(::isLocked)) fail(LibraryErrorCode.PROTECTED_ENTITY, "Locked element cannot move")
                // Image/PDF asset-backed content is never moved until references are implemented.
                if (selected.any { it.kind == "image" }) {
                    fail(LibraryErrorCode.UNSUPPORTED_CONTENT, "Image transfer requires asset management")
                }
                val movingInRenderOrder = source.filter { it.id in ids.toSet() }
                movingInRenderOrder.forEachIndexed { index, row ->
                    check(db.elementDao().update(row.copy(
                        pageId = to.id,
                        orderIndex = destination.size.toLong() + index,
                        revision = revision,
                    )) == 1)
                }
                val remainingSource = db.elementDao().listByPage(from.id)
                setElementOrder(remainingSource, remainingSource.map { it.id }, revision)
                val finalTarget = destination.map { it.id }.toMutableList().also {
                    it.addAll(at, movingInRenderOrder.map { row -> row.id })
                }
                setElementOrder(db.elementDao().listByPage(to.id), finalTarget, revision)
                true
            }
        }
    }

    private fun ownedPage(pages: List<PageEntity>, id: String): PageEntity {
        val canonical = LibraryValidation.canonicalId(id)
        return pages.firstOrNull { it.id == canonical }
            ?: fail(LibraryErrorCode.NOT_FOUND, "Page does not belong to this note")
    }

    private suspend fun elementsFor(pageId: String): List<ElementEntity> =
        db.elementDao().listByPage(pageId).also { requireContiguous(it.map(ElementEntity::orderIndex)) }

    private fun requireSelected(rows: List<ElementEntity>, ids: List<String>): List<ElementEntity> {
        val byId = rows.associateBy { it.id }
        return ids.map { byId[it] ?: fail(LibraryErrorCode.NOT_FOUND, "Element not on specified page") }
    }

    private fun isLocked(row: ElementEntity): Boolean = JSONObject(row.elementJson).optBoolean("locked", false)

    private suspend fun setPageOrder(rows: List<PageEntity>, ids: List<String>, revision: Long): Boolean {
        validatePermutation(rows.map { it.id }, ids)
        if (rows.map { it.id } == ids && rows.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
            return false
        }
        val byId = rows.associateBy { it.id }
        val start = freeIndex(rows.map { it.orderIndex })
        ids.forEachIndexed { i, id ->
            check(db.pageDao().update(byId.getValue(id).copy(orderIndex = start + i, revision = revision)) == 1)
        }
        ids.forEachIndexed { i, id ->
            check(db.pageDao().update(byId.getValue(id).copy(orderIndex = i.toLong(), revision = revision)) == 1)
        }
        return true
    }

    private suspend fun setElementOrder(rows: List<ElementEntity>, ids: List<String>, revision: Long): Boolean {
        validatePermutation(rows.map { it.id }, ids)
        if (rows.map { it.id } == ids && rows.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
            return false
        }
        val byId = rows.associateBy { it.id }
        val start = freeIndex(rows.map { it.orderIndex })
        ids.forEachIndexed { i, id ->
            check(db.elementDao().update(byId.getValue(id).copy(orderIndex = start + i, revision = revision)) == 1)
        }
        ids.forEachIndexed { i, id ->
            check(db.elementDao().update(byId.getValue(id).copy(orderIndex = i.toLong(), revision = revision)) == 1)
        }
        return true
    }

    /** All temporary positions are > every existing position in this sibling group. */
    private fun freeIndex(indexes: List<Long>): Long {
        val max = indexes.maxOrNull() ?: -1L
        if (max < 0L && indexes.isNotEmpty()) fail(LibraryErrorCode.INVALID_INPUT, "Invalid order index")
        if (Long.MAX_VALUE - max <= indexes.size.toLong()) {
            fail(LibraryErrorCode.INVALID_INPUT, "Ordering space exhausted")
        }
        return max + 1L
    }

    private fun requireContiguous(indexes: List<Long>) {
        if (indexes.withIndex().any { it.value != it.index.toLong() }) {
            fail(LibraryErrorCode.INVALID_INPUT, "Noncontiguous stored ordering")
        }
    }

    private fun validatePermutation(existing: List<String>, requested: List<String>) {
        requested.forEach(LibraryValidation::canonicalId)
        if (existing.size != requested.size || existing.toSet() != requested.toSet()) {
            fail(LibraryErrorCode.INVALID_INPUT, "Reorder must include each sibling exactly once")
        }
    }

    private fun insertionAt(at: Int?, size: Int): Int {
        val position = at ?: size
        if (position < 0 || position > size) fail(LibraryErrorCode.INVALID_INPUT, "Insert index is out of range")
        return position
    }

    private fun uniqueIds(ids: List<String>) {
        if (ids.size != ids.toSet().size) fail(LibraryErrorCode.INVALID_INPUT, "Duplicate element IDs")
    }

    private fun fail(code: LibraryErrorCode, message: String): Nothing =
        throw LibraryOperationException(code, message)
}
