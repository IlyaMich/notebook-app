package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.ContinuousPageLayout
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportInteraction
import com.notebook.app.ui.editor.viewport.ViewportTransform

/**
 * EDT-05 CP3A — debug-only, platform-neutral boundary between the EDT-03 page
 * viewport and the FMT-04 sampledPointsV1 model.
 *
 * This is deliberately NOT an input event handler or production renderer.
 * The entire stroke is accepted only when every confirmed sample belongs to
 * its starting page and lies inside the visible viewport. Rejection is explicit
 * until wet-ink masking and boundary clipping are tested in CP3B/CP3C.
 * No document revision, Room row or viewport state is changed here.
 */
internal object InkViewportPageAdapter {
    internal data class Captured(
        val pageId: String,
        val canonical: CanonicalInkStroke,
    )

    internal data class Projected(
        val pageId: String,
        val samples: List<InkPixelSample>,
        val brushWidthPx: Double,
    )

    fun captureOrNull(
        samples: List<InkPixelSample>,
        layout: ContinuousPageLayout,
        viewportTransform: ViewportTransform,
        viewportSize: PxSize,
        brushKind: String,
        brushWidthPx: Double,
        brushColorHex: String,
        brushOpacity: Double,
    ): Captured? {
        if (samples.isEmpty()) return null
        val scale = viewportTransform.scalePxPerDu
        if (!scale.isFinite() || scale <= 0.0) return null
        if (!brushWidthPx.isFinite() || brushWidthPx <= 0.0) return null

        val starting = ViewportInteraction.hitTestVisiblePage(
            PxPoint(samples.first().xPx, samples.first().yPx),
            layout, viewportTransform, viewportSize,
        ) ?: return null

        // Lock ownership for the whole gesture to the *stable* page ID found
        // at pointer-down. Samples from any other page or gutter are rejected.
        val pageId = starting.pageId
        val localPx = ArrayList<InkPixelSample>(samples.size)
        for (sample in samples) {
            val hit = ViewportInteraction.hitTestVisiblePage(
                PxPoint(sample.xPx, sample.yPx), layout, viewportTransform, viewportSize,
            ) ?: return null
            if (hit.pageId != pageId) return null

            // CanonicalInkConverter remains responsible for brush/time/optional
            // characteristic validation. Here we only translate page-local DU
            // back to a local pixel sample so that it can use a single PX/DU scale.
            localPx.add(
                sample.copy(
                    xPx = hit.localPointDu.x * scale,
                    yPx = hit.localPointDu.y * scale,
                ),
            )
        }
        return Captured(
            pageId = pageId,
            canonical = CanonicalInkConverter.capture(
                samples = localPx,
                scale = InkPixelScale(scale),
                brushKind = brushKind,
                brushWidthPx = brushWidthPx,
                brushColorHex = brushColorHex,
                brushOpacity = brushOpacity,
            ),
        )
    }

    /**
     * Projects a saved page-local stroke into viewport-local pixels for the
     * *current* camera. No mutation of canonical coordinates or clipping.
     * The caller must apply the page's clipping rectangle when rendering.
     */
    fun projectOrNull(
        pageId: String,
        canonical: CanonicalInkStroke,
        layout: ContinuousPageLayout,
        viewportTransform: ViewportTransform,
    ): Projected? {
        val page = layout.pages.firstOrNull { it.id == pageId } ?: return null
        val scale = viewportTransform.scalePxPerDu
        if (!scale.isFinite() || scale <= 0.0 ||
            !viewportTransform.translationXPx.isFinite() ||
            !viewportTransform.translationYPx.isFinite()
        ) return null
        val widthPx = canonical.brush.widthDu * scale
        if (!widthPx.isFinite() || widthPx <= 0.0) return null
        val projected = canonical.points.map { point ->
            val px = ViewportGeometry.pageToViewport(
                DuPoint(point.x, point.y), page, viewportTransform,
            )
            if (!px.x.isFinite() || !px.y.isFinite()) return null
            InkPixelSample(
                xPx = px.x,
                yPx = px.y,
                elapsedTimeMs = point.tMs,
                pressure = point.pressure,
                tiltRad = point.tiltRad,
                orientationRad = point.orientationRad,
            )
        }
        return Projected(pageId, projected, widthPx)
    }
}
