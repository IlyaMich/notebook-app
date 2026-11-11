package com.notebook.app.ui.editor.shapes

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.data.library.LibraryValidation
import kotlinx.serialization.json.*

/**
 * One validated, immutable shape action on the existing document snapshot.
 * Factories freeze source rows before asynchronous admission. No writes or gesture state.
 */
internal class ShapeEditPlan private constructor(
    val noteId: String,
    val pageId: String,
    val expectedNoteRevision: Long,
    val elementId: String,
    val afterEnvelope: String,
    val beforeEnvelope: String?,
    val position: Int,
    private val originalPage: StoredPage,
) {
    val forwardEdit: DocumentEdit get() = if (beforeEnvelope == null)
        DocumentEdit.InsertElements(pageId, listOf(afterEnvelope), at = position)
    else DocumentEdit.ReplaceElements(pageId, listOf(afterEnvelope))

    val inverseEdit: DocumentEdit get() = if (beforeEnvelope == null)
        DocumentEdit.RemoveElements(pageId, listOf(elementId))
    else DocumentEdit.ReplaceElements(pageId, listOf(beforeEnvelope))

    /** Compare the displayed target against a fresh consistent read; the commit still checks revision. */
    fun matchesSnapshot(content: StoredNoteContent): Boolean =
        content.noteId == noteId && content.revision == expectedNoteRevision &&
            content.pages.singleOrNull { it.id == pageId } == originalPage &&
            (beforeEnvelope != null || content.pages.none { p -> p.elements.any { it.id == elementId } })

    companion object {
        fun insert(content: StoredNoteContent, pageId: String, envelope: String): ShapeEditPlan {
            val page = checkedPage(content, pageId)
            val candidate = decode(pageId, envelope, page.elements.size)
            require(!candidate.locked) { "New shape must be unlocked" }
            require(candidate.elementTransform.coefficients() == listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)) {
                "New shapes require identity geometry"
            }
            require(content.pages.none { p -> p.elements.any { it.id == candidate.source.id } }) {
                "Shape ID already exists in this note"
            }
            return ShapeEditPlan(content.noteId, pageId, content.revision, candidate.source.id,
                envelope, null, page.elements.size, page)
        }

        /** Null means semantic no-op, including numeric spelling/JSON formatting changes only. */
        fun replace(content: StoredNoteContent, pageId: String, envelope: String): ShapeEditPlan? {
            val page = checkedPage(content, pageId)
            val candidate = decode(pageId, envelope, 0)
            val position = page.elements.indexOfFirst { it.id == candidate.source.id }
            require(position >= 0) { "Shape is not on the specified page" }
            val original = CanonicalShapeAdapter.decode(pageId, page.elements[position])
            require(!original.locked && !candidate.locked) { "Locked shape cannot be replaced" }
            require(original.kind == candidate.kind) { "Shape replacement cannot change its kind" }
            if (original.geometry == candidate.geometry && original.stroke == candidate.stroke &&
                original.fill == candidate.fill && original.arrowHead == candidate.arrowHead &&
                original.elementTransform.coefficients() == candidate.elementTransform.coefficients()) return null
            return ShapeEditPlan(content.noteId, pageId, content.revision, original.source.id,
                envelope, original.source.envelopeJson, position, page)
        }

        private fun checkedPage(content: StoredNoteContent, pageId: String): StoredPage {
            LibraryValidation.canonicalId(content.noteId)
            LibraryValidation.canonicalId(pageId)
            require(content.revision >= 0 && content.revision < Long.MAX_VALUE) { "Invalid shape revision" }
            val page = requireNotNull(content.pages.singleOrNull { it.id == pageId }) { "Missing shape page" }
            require(page.width.isFinite() && page.height.isFinite() && page.width > 0.0 && page.height > 0.0)
            require(page.elements.withIndex().all { it.value.orderIndex == it.index.toLong() }) {
                "Shape page order is not contiguous"
            }
            val ids = content.pages.flatMap { it.elements.map { row -> row.id } }
            require(ids.distinct().size == ids.size) { "Duplicate note-wide element IDs" }
            return page.copy(elements = page.elements.toList())
        }

        private fun decode(pageId: String, raw: String, position: Int): StoredCanonicalShape {
            val envelope = Json.parseToJsonElement(raw).jsonObject
            val id = envelope.getValue("id").jsonPrimitive
            require(id.isString) { "Expected shape ID" }
            return CanonicalShapeAdapter.decode(pageId,
                StoredElement(id.content, position.toLong(), "shape", raw, 0))
        }
    }
}
