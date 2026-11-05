package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import org.junit.Assert.*
import org.junit.Test

class EditorEraserWholeStrokeRemovalPlannerTest {
    private val pageId = "page-1"
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"
    private val c = "00000000-0000-4000-8000-000000000003"
    private val d = "00000000-0000-4000-8000-000000000004"

    private fun stroke(id: String, order: Long, locked: Boolean = false): StoredElement {
        // Non-normalized spacing is intentional: undo must retain the original bytes.
        val raw = """ {"id":"$id", "type":"stroke", "transform":[1,0,0,1,0,0],"locked":$locked,"payload":{"encoding":"sampledPointsV1","brush":{"kind":"pen","width":2.5,"color":"#112233","opacity":1.0},"points":[{"x":10.0,"y":20.0,"tMs":0}]}} """
        return StoredElement(id, order, "stroke", raw, 3L)
    }

    private fun shape(id: String, order: Long) = StoredElement(
        id = id,
        orderIndex = order,
        kind = "shape",
        envelopeJson = """{"id":"$id","type":"shape"}""",
        revision = 3L,
    )

    private fun page(
        id: String = pageId,
        elements: List<StoredElement>,
        revision: Long = 3L,
    ) = StoredPage(
        id = id,
        orderIndex = 0L,
        width = 600.0,
        height = 800.0,
        backgroundJson = """{"type":"blank","color":"#FFFFFF"}""",
        revision = revision,
        elements = elements,
    )

    private fun note(vararg pages: StoredPage, revision: Long = 9L) =
        StoredNoteContent("note-1", revision, pages.toList())

    private fun plan(
        content: StoredNoteContent,
        hits: List<String>? = listOf(a),
        id: String = pageId,
        scanNoteRevision: Long = content.revision,
        scanPageRevision: Long = 3L,
    ) = EditorEraserWholeStrokeRemovalPlanner.plan(
        content = content,
        pageId = id,
        scanNoteRevision = scanNoteRevision,
        scanPageRevision = scanPageRevision,
        confirmedHitsIfUnambiguous = hits,
    )

    @Test
    fun multipleNonAdjacentHitsBecomeOneEditAndPreserveExactUndoSnapshots() {
        val elements = listOf(stroke(c, 30L), stroke(a, 10L), shape(d, 40L), stroke(b, 20L))
        val content = note(page(elements = elements))
        val before = content.copy()

        val result = requireNotNull(plan(content, hits = listOf(c, a)))

        assertEquals(DocumentEdit.RemoveElements(pageId, listOf(a, c)), result.forwardEdit)
        assertEquals(
            DocumentEdit.RestoreRemovedStrokes(
                pageId = pageId,
                envelopes = listOf(elements[1].envelopeJson, elements[0].envelopeJson),
                idsInOrder = listOf(a, b, c, d),
            ),
            result.inverseEdit,
        )
        assertEquals(listOf(a, b, c, d), result.originalElementIdsInOrder)
        assertEquals(listOf(0, 2), result.removed.map { it.originalPosition })
        assertEquals(listOf(elements[1], elements[0]), result.removed.map { it.element })
        assertEquals(elements[1].envelopeJson, result.removed[0].element.envelopeJson)
        assertEquals(elements[0].envelopeJson, result.removed[1].element.envelopeJson)
        assertEquals(9L, result.expectedNoteRevision)
        assertEquals(3L, result.expectedPageRevision)
        assertEquals(content, before) // planning never alters the input
    }

    @Test
    fun emptyUnambiguousHitSetHasNoCommand() {
        assertNull(plan(note(page(elements = listOf(stroke(a, 0L)))), hits = emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun uncertainScanCannotProduceDeletionCandidates() {
        plan(note(page(elements = listOf(stroke(a, 0L)))), hits = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleNoteRevisionIsRejected() {
        plan(note(page(elements = listOf(stroke(a, 0L)))), scanNoteRevision = 8L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stalePageRevisionIsRejected() {
        plan(note(page(elements = listOf(stroke(a, 0L)))), scanPageRevision = 2L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateHitIdsAreRejectedRatherThanSilentlyDeduplicated() {
        plan(note(page(elements = listOf(stroke(a, 0L)))), hits = listOf(a, a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun lockedStrokeIsNotPlannedForDeletion() {
        plan(note(page(elements = listOf(stroke(a, 0L, locked = true)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherPageHitCannotDeleteAnotherPagesStroke() {
        val content = note(
            page(elements = listOf(stroke(b, 0L))),
            page(id = "page-2", elements = listOf(stroke(a, 0L))),
        )
        plan(content, hits = listOf(a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingHitIsRejected() {
        plan(note(page(elements = listOf(stroke(b, 0L)))), hits = listOf(a))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonStrokeHitIsRejected() {
        plan(note(page(elements = listOf(shape(a, 0L)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateDocumentElementIdIsRejected() {
        val content = note(
            page(elements = listOf(stroke(a, 0L))),
            page(id = "page-2", elements = listOf(stroke(a, 0L))),
        )
        plan(content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ambiguousPageOrderingIsRejected() {
        plan(note(page(elements = listOf(stroke(a, 0L), stroke(b, 0L)))))
    }
}
