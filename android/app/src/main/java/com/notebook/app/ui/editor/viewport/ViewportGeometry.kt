package com.notebook.app.ui.editor.viewport

/**
 * EDT-03: platform-independent viewport math.
 *
 * DU = canonical document unit (1/72 inch). PX = Compose viewport-local physical pixel.
 * This module never reads or writes persisted geometry or document revisions.
 */
data class DuPoint(val x: Double, val y: Double)
data class PxPoint(val x: Double, val y: Double)
data class PxSize(val width: Double, val height: Double)

data class PageSizeDu(val id: String, val width: Double, val height: Double)

data class PositionedPage(
    val id: String,
    val widthDu: Double,
    val heightDu: Double,
    val originXDu: Double,
    val originYDu: Double,
)

data class ContinuousPageLayout(
    val pages: List<PositionedPage>,
    val columnWidthDu: Double,
    val totalHeightDu: Double,
)

data class PageHit(val pageId: String, val localPointDu: DuPoint)

/** Viewport translation is measured in physical PX; scale is PX per DU. */
data class ViewportTransform(
    val scalePxPerDu: Double,
    val translationXPx: Double,
    val translationYPx: Double,
)

object ViewportGeometry {
    const val DEFAULT_GAP_DU: Double = 24.0

    /**
     * Returns null for invalid page input, instead of propagating NaN/invalid geometry into UI.
     * Layout is derived: never store these origins in Room or the .notebook format.
     */
    fun layoutOrNull(
        pages: List<PageSizeDu>,
        gapDu: Double = DEFAULT_GAP_DU,
    ): ContinuousPageLayout? {
        if (!gapDu.isFinite() || gapDu < 0.0) return null
        if (pages.isEmpty()) return ContinuousPageLayout(emptyList(), 0.0, 0.0)
        if (pages.any { it.id.isBlank() || !it.width.isFinite() || !it.height.isFinite() ||
                    it.width <= 0.0 || it.height <= 0.0 }) return null
        if (pages.map { it.id }.toSet().size != pages.size) return null

        val columnWidth = pages.maxOf { it.width }
        var y = 0.0
        val positioned = ArrayList<PositionedPage>(pages.size)
        pages.forEachIndexed { index, page ->
            if (index > 0) y += gapDu
            if (!y.isFinite()) return null
            positioned.add(
                PositionedPage(
                    id = page.id,
                    widthDu = page.width,
                    heightDu = page.height,
                    originXDu = (columnWidth - page.width) / 2.0,
                    originYDu = y,
                ),
            )
            y += page.height
            if (!y.isFinite()) return null
        }
        return ContinuousPageLayout(positioned, columnWidth, y)
    }

    /**
     * Initial fit-to-width, starting at top of the document. Gutter is converted from dp to px
     * by the Compose caller. Returns null for empty content or an unusably small viewport.
     */
    fun fitToWidthOrNull(
        layout: ContinuousPageLayout,
        viewport: PxSize,
        gutterPx: Double,
    ): ViewportTransform? {
        if (!validViewport(viewport, gutterPx) || layout.pages.isEmpty()) return null
        if (layout.columnWidthDu <= 0.0 || !layout.columnWidthDu.isFinite()) return null
        val scale = (viewport.width - 2.0 * gutterPx) / layout.columnWidthDu
        if (!scale.isFinite() || scale <= 0.0) return null
        return clampOrNull(
            layout,
            viewport,
            gutterPx,
            ViewportTransform(scale, (viewport.width - scale * layout.columnWidthDu) / 2.0, gutterPx),
        )
    }

    fun worldToViewport(world: DuPoint, transform: ViewportTransform): PxPoint = PxPoint(
        world.x * transform.scalePxPerDu + transform.translationXPx,
        world.y * transform.scalePxPerDu + transform.translationYPx,
    )

    fun viewportToWorldOrNull(viewportPoint: PxPoint, transform: ViewportTransform): DuPoint? {
        if (!validTransform(transform) || !viewportPoint.x.isFinite() || !viewportPoint.y.isFinite()) {
            return null
        }
        val x = (viewportPoint.x - transform.translationXPx) / transform.scalePxPerDu
        val y = (viewportPoint.y - transform.translationYPx) / transform.scalePxPerDu
        if (!x.isFinite() || !y.isFinite()) return null
        return DuPoint(x, y)
    }

    fun pageToViewport(local: DuPoint, page: PositionedPage, transform: ViewportTransform): PxPoint =
        worldToViewport(DuPoint(page.originXDu + local.x, page.originYDu + local.y), transform)

    fun viewportToPageOrNull(
        viewportPoint: PxPoint,
        page: PositionedPage,
        transform: ViewportTransform,
    ): DuPoint? = viewportToWorldOrNull(viewportPoint, transform)?.let {
        DuPoint(it.x - page.originXDu, it.y - page.originYDu)
    }

    /** Inter-page gaps and margins intentionally return no page target. */
    fun hitTestPage(
        viewportPoint: PxPoint,
        layout: ContinuousPageLayout,
        transform: ViewportTransform,
    ): PageHit? {
        val world = viewportToWorldOrNull(viewportPoint, transform) ?: return null
        val page = layout.pages.firstOrNull {
            world.x >= it.originXDu && world.x <= it.originXDu + it.widthDu &&
                world.y >= it.originYDu && world.y <= it.originYDu + it.heightDu
        } ?: return null
        return PageHit(page.id, DuPoint(world.x - page.originXDu, world.y - page.originYDu))
    }

    /** Does not clamp: callers may combine this with clampOrNull after gesture updates. */
    fun pan(transform: ViewportTransform, delta: PxPoint): ViewportTransform = transform.copy(
        translationXPx = transform.translationXPx + delta.x,
        translationYPx = transform.translationYPx + delta.y,
    )

    /**
     * Pinch about a viewport-local focal point. The caller must supply the actual scale after
     * any zoom-limit handling; keeping a focal world point fixed is then mathematically exact.
     */
    fun zoomAroundOrNull(
        transform: ViewportTransform,
        focal: PxPoint,
        newScalePxPerDu: Double,
    ): ViewportTransform? {
        if (!validTransform(transform) || !newScalePxPerDu.isFinite() ||
            newScalePxPerDu <= 0.0 || !focal.x.isFinite() || !focal.y.isFinite()) return null
        val k = newScalePxPerDu / transform.scalePxPerDu
        if (!k.isFinite() || k <= 0.0) return null
        val updated = ViewportTransform(
            newScalePxPerDu,
            focal.x - k * (focal.x - transform.translationXPx),
            focal.y - k * (focal.y - transform.translationYPx),
        )
        return updated.takeIf(::validTransform)
    }

    /** Hard clamping as specified by EDT-01. Short content remains top-aligned. */
    fun clampOrNull(
        layout: ContinuousPageLayout,
        viewport: PxSize,
        gutterPx: Double,
        transform: ViewportTransform,
    ): ViewportTransform? {
        if (!validViewport(viewport, gutterPx) || !validTransform(transform) ||
            layout.pages.isEmpty()) return null
        val widthPx = layout.columnWidthDu * transform.scalePxPerDu
        val heightPx = layout.totalHeightDu * transform.scalePxPerDu
        if (!widthPx.isFinite() || !heightPx.isFinite()) return null
        val usableWidthPx = viewport.width - 2.0 * gutterPx
        val usableHeightPx = viewport.height - 2.0 * gutterPx
        val x = if (widthPx <= usableWidthPx) {
            (viewport.width - widthPx) / 2.0
        } else {
            transform.translationXPx.coerceIn(viewport.width - gutterPx - widthPx, gutterPx)
        }
        val y = if (heightPx <= usableHeightPx) {
            gutterPx
        } else {
            transform.translationYPx.coerceIn(viewport.height - gutterPx - heightPx, gutterPx)
        }
        return ViewportTransform(transform.scalePxPerDu, x, y).takeIf(::validTransform)
    }

    private fun validViewport(viewport: PxSize, gutterPx: Double): Boolean =
        viewport.width.isFinite() && viewport.height.isFinite() &&
            gutterPx.isFinite() && gutterPx >= 0.0 &&
            viewport.width > 2.0 * gutterPx && viewport.height > 2.0 * gutterPx

    private fun validTransform(transform: ViewportTransform): Boolean =
        transform.scalePxPerDu.isFinite() && transform.scalePxPerDu > 0.0 &&
            transform.translationXPx.isFinite() && transform.translationYPx.isFinite()
}
