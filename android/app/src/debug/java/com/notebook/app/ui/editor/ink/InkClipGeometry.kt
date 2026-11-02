package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.ContinuousPageLayout
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform
import kotlin.math.max
import kotlin.math.min

/**
 * CP3C: one page/viewport intersection for the debug native wet mask AND dry clip.
 * Pure Double geometry. No native brush or Room types; no stored-content mutation.
 */
internal object InkClipGeometry {
    internal data class RectPx(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        init { require(right > left && bottom > top) }
        fun contains(x: Double, y: Double): Boolean =
            x >= left && x <= right && y >= top && y <= bottom
    }

    fun visiblePageRects(
        layout: ContinuousPageLayout,
        camera: ViewportTransform?,
        viewport: PxSize,
    ): Map<String, RectPx> {
        if (camera == null || !camera.scalePxPerDu.isFinite() ||
            camera.scalePxPerDu <= 0.0 || !camera.translationXPx.isFinite() ||
            !camera.translationYPx.isFinite() || !viewport.width.isFinite() ||
            !viewport.height.isFinite() || viewport.width <= 0.0 || viewport.height <= 0.0
        ) return emptyMap()
        val result = linkedMapOf<String, RectPx>()
        layout.pages.forEach { page ->
            val origin = ViewportGeometry.worldToViewport(
                DuPoint(page.originXDu, page.originYDu), camera,
            )
            val right = origin.x + page.widthDu * camera.scalePxPerDu
            val bottom = origin.y + page.heightDu * camera.scalePxPerDu
            if (!origin.x.isFinite() || !origin.y.isFinite() ||
                !right.isFinite() || !bottom.isFinite()
            ) return@forEach
            val left = max(0.0, origin.x)
            val top = max(0.0, origin.y)
            val clippedRight = min(viewport.width, right)
            val clippedBottom = min(viewport.height, bottom)
            if (clippedRight > left && clippedBottom > top) {
                result[page.id] = RectPx(left, top, clippedRight, clippedBottom)
            }
        }
        return result
    }
}
