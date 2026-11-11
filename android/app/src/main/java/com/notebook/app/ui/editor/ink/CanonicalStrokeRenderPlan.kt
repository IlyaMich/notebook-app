
package com.notebook.app.ui.editor.ink

import com.notebook.app.data.content.model.StoredPage
import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform

/**
 * Physical-pixel clip rectangle for one page.
 *
 * The drawing adapter must apply this clip to the complete
 * painted stroke, including its transformed brush footprint.
 */
internal data class PageStrokeClipPx(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(
            listOf(left, top, right, bottom).all {
                it.isFinite()
            }
        )
        require(right > left && bottom > top)
    }
}

/**
 * Complete element-local DU -> viewport-PX affine mapping.
 *
 * xPx = a*xDu + c*yDu + tx
 * yPx = b*xDu + d*yDu + ty
 *
 * A drawing adapter must transform the entire stroke geometry,
 * not just sample centers followed by a scalar brush width.
 */
internal data class StrokeViewportAffine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double,
) {
    init {
        require(
            listOf(a, b, c, d, tx, ty).all {
                it.isFinite()
            }
        ) {
            "Non-finite viewport affine"
        }
    }

    fun mapPointOrNull(local: DuPoint): PxPoint? {
        if (!local.x.isFinite() || !local.y.isFinite()) {
            return null
        }

        val x = a * local.x + c * local.y + tx
        val y = b * local.x + d * local.y + ty

        return if (x.isFinite() && y.isFinite()) {
            PxPoint(x, y)
        } else {
            null
        }
    }

    /**
     * Transform a local displacement without translation.
     * Useful for verifying stroke-width footprint behavior.
     */
    fun mapVectorOrNull(local: DuPoint): PxPoint? {
        if (!local.x.isFinite() || !local.y.isFinite()) {
            return null
        }

        val x = a * local.x + c * local.y
        val y = b * local.x + d * local.y

        return if (x.isFinite() && y.isFinite()) {
            PxPoint(x, y)
        } else {
            null
        }
    }
}



/** Shared element-local DU -> viewport-PX composition; never changes the canonical affine. */
internal fun elementToViewportAffine(
    element: CanonicalElementAffine,
    page: PositionedPage,
    viewport: ViewportTransform,
): StrokeViewportAffine {
    require(viewport.scalePxPerDu.isFinite() && viewport.scalePxPerDu > 0.0)
    require(viewport.translationXPx.isFinite() && viewport.translationYPx.isFinite())
    require(page.originXDu.isFinite() && page.originYDu.isFinite())
    val t = element.coefficients()
    val scale = viewport.scalePxPerDu
    return StrokeViewportAffine(
        a = t[0] * scale,
        b = t[1] * scale,
        c = t[2] * scale,
        d = t[3] * scale,
        tx = (t[4] + page.originXDu) * scale + viewport.translationXPx,
        ty = (t[5] + page.originYDu) * scale + viewport.translationYPx,
    )
}

/**
 * One saved stroke prepared for a future drawing adapter.
 *
 * The source stroke retains its original local samples,
 * brush width in DU, optional stylus values, and ordering.
 */
internal data class StrokeRenderCommand(
    val stroke: StoredCanonicalStroke,
    val localToViewport: StrokeViewportAffine,
)

/**
 * One page's immutable read-only drawing instructions.
 *
 * Clipping is a rendering operation, not a mutation of
 * canonical stroke samples.
 */
internal data class PageStrokeRenderPlan(
    val pageId: String,
    val clipPx: PageStrokeClipPx,
    val strokes: List<StrokeRenderCommand>,
)

/**
 * EDT-07: Builds draw plans from persisted canonical strokes.
 *
 * No native Ink objects, meshes, Room writes, or Compose drawing.
 */
internal object CanonicalStrokeRenderPlan {

    fun build(
        page: StoredPage,
        positionedPage: PositionedPage,
        viewport: ViewportTransform,
        decodedStrokes: List<StoredCanonicalStroke> =
            StoredCanonicalStrokeDecoder.fromPage(page),
    ): PageStrokeRenderPlan {
        require(page.id == positionedPage.id) {
            "Page identity mismatch"
        }

        require(
            page.width.isFinite() &&
                page.height.isFinite() &&
                page.width > 0.0 &&
                page.height > 0.0 &&
                page.width == positionedPage.widthDu &&
                page.height == positionedPage.heightDu
        ) {
            "Page geometry mismatch"
        }

        val scale = viewport.scalePxPerDu
        require(
            scale.isFinite() &&
                scale > 0.0 &&
                viewport.translationXPx.isFinite() &&
                viewport.translationYPx.isFinite() &&
                positionedPage.originXDu.isFinite() &&
                positionedPage.originYDu.isFinite()
        ) {
            "Invalid viewport geometry"
        }

        val origin = ViewportGeometry.pageToViewport(
            DuPoint(0.0, 0.0),
            positionedPage,
            viewport,
        )

        val clip = PageStrokeClipPx(
            left = origin.x,
            top = origin.y,
            right = origin.x + page.width * scale,
            bottom = origin.y + page.height * scale,
        )

        require(decodedStrokes.all { it.pageId == page.id }) {
            "Stroke ownership mismatch"
        }

        val commands = decodedStrokes.map { stroke ->
                val affine = elementToViewportAffine(stroke.elementTransform, positionedPage, viewport)

                StrokeRenderCommand(
                    stroke = stroke,
                    localToViewport = affine,
                )
            }

        return PageStrokeRenderPlan(
            pageId = page.id,
            clipPx = clip,
            strokes = commands,
        )
    }
}
