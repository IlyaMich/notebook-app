package com.notebook.app.ui.editor.viewport

import kotlin.math.max

/**
 * EDT-03 checkpoint 4: viewport-only hit targets and restoration after a window/layout resize.
 * All dimensions for hit testing are viewport-local physical pixels (PX); page data stays in DU.
 * No persistent state or editor command is created by these functions.
 */
/** Small, document-unit camera bookmark suitable for restoring after Activity recreation. */
data class ViewportAnchor(
    val pageId: String,
    val localXDu: Double,
    val localYDu: Double,
    val zoomRelativeToFit: Double,
)

object ViewportInteraction {
    /**
     * Save only a stable page ID, a page-local viewport center, and relative zoom.
     * Do not save raw pixel translation across Activity recreation: the viewport may change size.
     */
    fun captureAnchorOrNull(
        layout: ContinuousPageLayout,
        viewport: PxSize,
        gutterPx: Double,
        transform: ViewportTransform,
    ): ViewportAnchor? {
        val fit = ViewportGeometry.fitToWidthOrNull(layout, viewport, gutterPx) ?: return null
        if (!transform.scalePxPerDu.isFinite() || transform.scalePxPerDu <= 0.0) return null
        val relativeZoom = transform.scalePxPerDu / fit.scalePxPerDu
        if (!relativeZoom.isFinite() || relativeZoom <= 0.0) return null
        val center = ViewportGeometry.viewportToWorldOrNull(
            PxPoint(viewport.width / 2.0, viewport.height / 2.0), transform,
        ) ?: return null
        val pageIndex = nearestPageIndex(center, layout) ?: return null
        val page = layout.pages[pageIndex]
        return ViewportAnchor(
            pageId = page.id,
            localXDu = (center.x - page.originXDu).coerceIn(0.0, page.widthDu),
            localYDu = (center.y - page.originYDu).coerceIn(0.0, page.heightDu),
            zoomRelativeToFit = relativeZoom,
        )
    }

    /** Reconstruct a camera from saveable document coordinates after Activity recreation. */
    fun restoreAnchorOrNull(
        anchor: ViewportAnchor,
        layout: ContinuousPageLayout,
        viewport: PxSize,
        gutterPx: Double,
    ): ViewportTransform? {
        val fit = ViewportGeometry.fitToWidthOrNull(layout, viewport, gutterPx) ?: return null
        val page = layout.pages.firstOrNull { it.id == anchor.pageId } ?: return fit
        if (!anchor.localXDu.isFinite() || !anchor.localYDu.isFinite() ||
            !anchor.zoomRelativeToFit.isFinite() || anchor.zoomRelativeToFit <= 0.0
        ) return fit
        val zoom = anchor.zoomRelativeToFit.coerceIn(
            ViewportNavigation.MIN_ZOOM_FACTOR, ViewportNavigation.MAX_ZOOM_FACTOR,
        )
        val scale = fit.scalePxPerDu * zoom
        if (!scale.isFinite() || scale <= 0.0) return fit
        val target = ViewportTransform(
            scalePxPerDu = scale,
            translationXPx = viewport.width / 2.0 - scale *
                (page.originXDu + anchor.localXDu.coerceIn(0.0, page.widthDu)),
            translationYPx = viewport.height / 2.0 - scale *
                (page.originYDu + anchor.localYDu.coerceIn(0.0, page.heightDu)),
        )
        return ViewportGeometry.clampOrNull(layout, viewport, gutterPx, target) ?: fit
    }

    /** Reject touches outside the viewport, even if an unclamped transform maps them to a page. */
    fun hitTestVisiblePage(
        pointPx: PxPoint,
        layout: ContinuousPageLayout,
        transform: ViewportTransform,
        viewport: PxSize,
    ): PageHit? {
        if (!validSize(viewport) || !pointPx.x.isFinite() || !pointPx.y.isFinite() ||
            pointPx.x < 0.0 || pointPx.y < 0.0 ||
            pointPx.x >= viewport.width || pointPx.y >= viewport.height
        ) return null
        return ViewportGeometry.hitTestPage(pointPx, layout, transform)
    }

    /**
     * Keep the page-local point nearest the old viewport center under the new viewport center.
     * Preserve zoom *relative to fit* rather than the old physical PX/DU scale.
     *
     * This is intentionally best-effort: an edge clamp can shift the anchor. If the focused page
     * was removed, use its former index's nearest surviving page, then the first page.
     * No common page IDs (opening another document) starts at the new fit instead.
     */
    fun restoreAfterChangeOrNull(
        previousLayout: ContinuousPageLayout,
        previousViewport: PxSize,
        previousGutterPx: Double,
        current: ViewportTransform,
        newLayout: ContinuousPageLayout,
        newViewport: PxSize,
        newGutterPx: Double,
    ): ViewportTransform? {
        val newFit = ViewportGeometry.fitToWidthOrNull(newLayout, newViewport, newGutterPx)
            ?: return null
        val oldFit = ViewportGeometry.fitToWidthOrNull(
            previousLayout, previousViewport, previousGutterPx,
        ) ?: return newFit
        if (newLayout.pages.none { candidate -> previousLayout.pages.any { it.id == candidate.id } }) {
            return newFit
        }
        if (!current.scalePxPerDu.isFinite() || current.scalePxPerDu <= 0.0) return newFit
        val factor = current.scalePxPerDu / oldFit.scalePxPerDu
        if (!factor.isFinite() || factor <= 0.0) return newFit

        val oldCenter = PxPoint(previousViewport.width / 2.0, previousViewport.height / 2.0)
        val world = ViewportGeometry.viewportToWorldOrNull(oldCenter, current) ?: return newFit
        val oldIndex = nearestPageIndex(world, previousLayout) ?: return newFit
        val oldPage = previousLayout.pages[oldIndex]
        val newPage = newLayout.pages.firstOrNull { it.id == oldPage.id }
            ?: newLayout.pages[oldIndex.coerceIn(0, newLayout.pages.lastIndex)]
        val localX = (world.x - oldPage.originXDu).coerceIn(0.0, oldPage.widthDu)
        val localY = (world.y - oldPage.originYDu).coerceIn(0.0, oldPage.heightDu)
        val scale = newFit.scalePxPerDu * factor.coerceIn(
            ViewportNavigation.MIN_ZOOM_FACTOR, ViewportNavigation.MAX_ZOOM_FACTOR,
        )
        if (!scale.isFinite() || scale <= 0.0) return newFit
        val anchor = ViewportTransform(
            scalePxPerDu = scale,
            translationXPx = newViewport.width / 2.0 - scale *
                (newPage.originXDu + localX.coerceAtMost(newPage.widthDu)),
            translationYPx = newViewport.height / 2.0 - scale *
                (newPage.originYDu + localY.coerceAtMost(newPage.heightDu)),
        )
        return ViewportGeometry.clampOrNull(newLayout, newViewport, newGutterPx, anchor) ?: newFit
    }

    private fun nearestPageIndex(world: DuPoint, layout: ContinuousPageLayout): Int? {
        if (!world.x.isFinite() || !world.y.isFinite() || layout.pages.isEmpty()) return null
        return layout.pages.indices.minByOrNull { index ->
            val page = layout.pages[index]
            val dx = max(max(page.originXDu - world.x, world.x - page.originXDu - page.widthDu), 0.0)
            val dy = max(max(page.originYDu - world.y, world.y - page.originYDu - page.heightDu), 0.0)
            // Comparing distances need not square them, avoiding overflow for huge valid doubles.
            kotlin.math.hypot(dx, dy)
        }
    }

    private fun validSize(size: PxSize): Boolean = size.width.isFinite() &&
        size.height.isFinite() && size.width > 0.0 && size.height > 0.0
}
