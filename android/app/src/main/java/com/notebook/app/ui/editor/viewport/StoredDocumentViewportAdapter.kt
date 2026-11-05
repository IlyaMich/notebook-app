
package com.notebook.app.ui.editor.viewport

import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.model.StoredPage

/**
 * Read-only bridge from the existing Room document snapshot
 * to the EDT-03/EDT-07 viewport.
 *
 * Preserves repository page order and the original StoredPage
 * objects. Does not create a second document representation.
 */
internal data class StoredViewportSnapshot(
    val pages: List<PageViewportItem>,
    val storedPages: Map<String, StoredPage>,
)

internal fun StoredNoteContent.toViewportSnapshot(): StoredViewportSnapshot {
    val items = pages.map { page ->
        PageViewportItem(
            id = page.id,
            widthDu = page.width,
            heightDu = page.height,
            elementCount = page.elements.size,
        )
    }

    return StoredViewportSnapshot(
        pages = items,
        storedPages = pages.associateBy { it.id },
    )
}
