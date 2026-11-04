
package com.notebook.app.ui.editor.viewport

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage
import org.junit.Assert.*
import org.junit.Test

class StoredDocumentViewportAdapterTest {

    private fun page(
        id: String,
        order: Long,
        elements: List<StoredElement> = emptyList(),
    ) = StoredPage(
        id = id,
        orderIndex = order,
        width = if (order == 0L) 612.0 else 792.0,
        height = if (order == 0L) 792.0 else 612.0,
        backgroundJson = """{"type":"blank","color":"#FFFFFF"}""",
        revision = 1L,
        elements = elements,
    )

    @Test
    fun preservesPageOrderDimensionsAndOriginalObjects() {
        val first = page("page-a", 0L)
        val second = page("page-b", 1L)

        val content = StoredNoteContent(
            noteId = "note-1",
            revision = 7L,
            pages = listOf(first, second),
        )

        val viewport = content.toViewportSnapshot()

        assertEquals(
            listOf("page-a", "page-b"),
            viewport.pages.map { it.id },
        )
        assertEquals(612.0, viewport.pages[0].widthDu, 0.0)
        assertEquals(792.0, viewport.pages[1].widthDu, 0.0)

        assertSame(first, viewport.storedPages["page-a"])
        assertSame(second, viewport.storedPages["page-b"])

        assertEquals(7L, content.revision)
        assertEquals(listOf(first, second), content.pages)
    }

    @Test
    fun refreshedCommittedSnapshotSuppliesUpdatedElements() {
        val originalPage = page("page-a", 0L)

        val original = StoredNoteContent(
            noteId = "note-1",
            revision = 1L,
            pages = listOf(originalPage),
        )

        val savedElement = StoredElement(
            id = "stroke-1",
            orderIndex = 0L,
            kind = "stroke",
            envelopeJson = """{"synthetic":"test"}""",
            revision = 2L,
        )

        val updatedPage = originalPage.copy(
            revision = 2L,
            elements = listOf(savedElement),
        )

        val updated = original.copy(
            revision = 2L,
            pages = listOf(updatedPage),
        )

        val before = original.toViewportSnapshot()
        val after = updated.toViewportSnapshot()

        assertEquals(0, before.pages.single().elementCount)
        assertEquals(1, after.pages.single().elementCount)

        assertTrue(before.storedPages.getValue("page-a").elements.isEmpty())

        assertEquals(
            savedElement,
            after.storedPages.getValue("page-a")
                .elements.single(),
        )

        // Preparing a viewport snapshot never changes
        // the original committed document.
        assertEquals(1L, original.revision)
        assertTrue(original.pages.single().elements.isEmpty())
    }
}
