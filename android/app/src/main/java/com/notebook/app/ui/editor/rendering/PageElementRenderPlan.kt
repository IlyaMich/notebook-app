package com.notebook.app.ui.editor.rendering

import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.ink.*
import com.notebook.app.ui.editor.shapes.CanonicalShapeAdapter
import com.notebook.app.ui.editor.shapes.StoredCanonicalShape
import com.notebook.app.ui.editor.shapes.CanonicalShapeGeometry
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.ViewportTransform

internal sealed interface PageElementCommand {
    val elementId: String
    data class Ink(val value: StrokeRenderCommand) : PageElementCommand {
        override val elementId: String get() = value.stroke.elementId
    }
    data class Shape(
        val shape: StoredCanonicalShape,
        val localToViewport: StrokeViewportAffine,
        val geometryOverride: CanonicalShapeGeometry? = null,
    ) : PageElementCommand {
        val geometry: CanonicalShapeGeometry get() = geometryOverride ?: shape.geometry
        override val elementId: String get() = shape.source.id
    }
}

internal data class PageElementPlan(
    val pageId: String,
    val clipPx: PageStrokeClipPx,
    val commands: List<PageElementCommand>,
    val unavailableElementIds: Set<String>,
)

/** Read-only render projection of the existing snapshot; not a second editable document model. */
internal object PageElementRenderPlan {
    class Decoded internal constructor(
        internal val source: StoredPage,
        internal val strokes: List<StoredCanonicalStroke>,
        internal val shapes: Map<String, StoredCanonicalShape>,
        internal val unavailableElementIds: Set<String>,
    )

    /** Cache only canonical decoding, independently of camera changes. */
    fun decode(page: StoredPage): Decoded {
        require(page.elements.map { it.id }.distinct().size == page.elements.size) { "Duplicate element IDs" }
        require(page.elements.all { it.orderIndex >= 0 } &&
            page.elements.map { it.orderIndex }.distinct().size == page.elements.size) { "Invalid element order" }
        val strokes = StoredCanonicalStrokeDecoder.fromPage(page)
        val shapes = mutableMapOf<String, StoredCanonicalShape>()
        val unavailable = mutableSetOf<String>()
        page.elements.filter { it.kind == "shape" }.forEach { element ->
            try {
                shapes[element.id] = CanonicalShapeAdapter.decode(page.id, element)
            } catch (_: RuntimeException) {
                // Preserve valid neighboring ink and report this element; never rewrite its bytes.
                unavailable += element.id
            }
        }
        return Decoded(page, strokes, shapes.toMap(), unavailable.toSet())
    }

    fun build(
        page: StoredPage,
        positionedPage: PositionedPage,
        viewport: ViewportTransform,
        decoded: Decoded = decode(page),
    ): PageElementPlan {
        require(decoded.source == page) { "Stale or wrong-page decoded elements" }
        // Reuse the existing page/clip validation and the same affine composition as ink.
        val ink = CanonicalStrokeRenderPlan.build(page, positionedPage, viewport, decoded.strokes)
        val strokesById = ink.strokes.associateBy { it.stroke.elementId }
        val unavailable = decoded.unavailableElementIds.toMutableSet()
        val commands = page.elements.sortedBy { it.orderIndex }.mapNotNull { element ->
            strokesById[element.id]?.let { return@mapNotNull PageElementCommand.Ink(it) }
            val shape = decoded.shapes[element.id] ?: return@mapNotNull null
            try {
                PageElementCommand.Shape(shape,
                    elementToViewportAffine(shape.elementTransform, positionedPage, viewport))
            } catch (_: IllegalArgumentException) {
                unavailable += element.id
                null
            }
        }
        return PageElementPlan(page.id, ink.clipPx, commands, unavailable.toSet())
    }
}
