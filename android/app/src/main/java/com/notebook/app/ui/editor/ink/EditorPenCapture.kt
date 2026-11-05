
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportTransform

internal data class EditorPenPoint(
    val xPx: Double,
    val yPx: Double,
    val uptimeMs: Long,
)

internal data class EditorPenPreview(
    val pointerId: Long,
    val pageId: String,
    val startTransform: ViewportTransform,
    val startViewport: PxSize,
    val samples: List<EditorPenPoint>,
)

/**
 * Temporary screen-space handwriting capture.
 *
 * No document edits, canonical serialization or persistence.
 * The caller validates page ownership before appending samples.
 */
internal class EditorPenCapture(
    private val maxSamples: Int = 8192,
) {
    init {
        require(maxSamples > 0)
    }

    var preview: EditorPenPreview? = null
        private set

    fun start(
        pointerId: Long,
        pageId: String,
        transform: ViewportTransform,
        viewport: PxSize,
        point: EditorPenPoint,
    ): EditorPenPreview? {
        if (preview != null || pageId.isBlank()) return null
        if (!validGeometry(transform, viewport)) return null
        if (!validPoint(point, viewport)) return null

        return EditorPenPreview(
            pointerId = pointerId,
            pageId = pageId,
            startTransform = transform,
            startViewport = viewport,
            samples = listOf(point),
        ).also { preview = it }
    }

    fun append(
        pointerId: Long,
        point: EditorPenPoint,
    ): EditorPenPreview? {
        val current = preview ?: return null
        if (pointerId != current.pointerId) return null
        if (!validPoint(point, current.startViewport)) return null

        val previous = current.samples.last()
        if (point.uptimeMs < previous.uptimeMs) return null

        // Ignore duplicate coordinates.
        if (point.xPx == previous.xPx &&
            point.yPx == previous.yPx
        ) {
            return current
        }

        if (current.samples.size >= maxSamples) return null

        return current.copy(
            samples = current.samples + point,
        ).also { preview = it }
    }

    fun finish(pointerId: Long): EditorPenPreview? {
        val current = preview ?: return null
        if (current.pointerId != pointerId) return null

        preview = null
        return current
    }

    fun cancel() {
        preview = null
    }

    private fun validGeometry(
        transform: ViewportTransform,
        viewport: PxSize,
    ): Boolean =
        transform.scalePxPerDu.isFinite() &&
            transform.scalePxPerDu > 0.0 &&
            transform.translationXPx.isFinite() &&
            transform.translationYPx.isFinite() &&
            viewport.width.isFinite() &&
            viewport.height.isFinite() &&
            viewport.width > 0.0 &&
            viewport.height > 0.0

    private fun validPoint(
        point: EditorPenPoint,
        viewport: PxSize,
    ): Boolean =
        point.xPx.isFinite() &&
            point.yPx.isFinite() &&
            point.xPx >= 0.0 &&
            point.yPx >= 0.0 &&
            point.xPx < viewport.width &&
            point.yPx < viewport.height &&
            point.uptimeMs >= 0L
}
