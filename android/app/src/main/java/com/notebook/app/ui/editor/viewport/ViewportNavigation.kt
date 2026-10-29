package com.notebook.app.ui.editor.viewport

/**
 * Checkpoint 3: immutable, view-only gesture updates shared by the Compose pointer adapter.
 * There are no Room dependencies or changes to canonical page/element coordinates.
 *
 * [centroidPreviousPx] is the previous-frame centroid from detectTransformGestures.
 * [panPx] moves that centroid to its new position. Apply focal zoom first, pan second,
 * then clamp at the document edges. Zoom bounds are relative to the current fit scale.
 */
object ViewportNavigation {
    const val MIN_ZOOM_FACTOR = 0.5
    const val MAX_ZOOM_FACTOR = 6.0

    fun updateOrNull(
        layout: ContinuousPageLayout,
        viewport: PxSize,
        gutterPx: Double,
        fitScalePxPerDu: Double,
        current: ViewportTransform,
        centroidPreviousPx: PxPoint,
        panPx: PxPoint,
        zoomChange: Double,
    ): ViewportTransform? {
        if (!fitScalePxPerDu.isFinite() || fitScalePxPerDu <= 0.0 ||
            !zoomChange.isFinite() || zoomChange <= 0.0 ||
            !panPx.x.isFinite() || !panPx.y.isFinite()
        ) return null

        val minScale = fitScalePxPerDu * MIN_ZOOM_FACTOR
        val maxScale = fitScalePxPerDu * MAX_ZOOM_FACTOR
        if (!minScale.isFinite() || !maxScale.isFinite() || minScale <= 0.0) return null

        val requestedScale = current.scalePxPerDu * zoomChange
        if (requestedScale.isNaN() || requestedScale <= 0.0) return null
        // A finite zoom change can overflow a Double multiplication. Saturate rather than
        // introducing an invalid transform (positive infinity becomes the maximum scale).
        val actualScale = requestedScale.coerceIn(minScale, maxScale)
        val zoomed = ViewportGeometry.zoomAroundOrNull(
            current, centroidPreviousPx, actualScale,
        ) ?: return null
        val moved = ViewportGeometry.pan(zoomed, panPx)
        return ViewportGeometry.clampOrNull(layout, viewport, gutterPx, moved)
    }
}
