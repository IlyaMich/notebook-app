
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.DuPoint
import com.notebook.app.ui.editor.viewport.PositionedPage
import com.notebook.app.ui.editor.viewport.PxPoint
import com.notebook.app.ui.editor.viewport.ViewportGeometry
import com.notebook.app.ui.editor.viewport.ViewportTransform

/**
 * Canonical element transform:
 *
 * x' = a*x + c*y + tx
 * y' = b*x + d*y + ty
 *
 * Maps element-local DU into page-local DU.
 * Does not mutate the persisted geometry.
 */
internal class CanonicalElementAffine private constructor(
    private val a: Double,
    private val b: Double,
    private val c: Double,
    private val d: Double,
    private val tx: Double,
    private val ty: Double,
) {
    companion object {
        fun fromCanonicalOrNull(
            values: List<Double>
        ): CanonicalElementAffine? {
            if (values.size != 6 || values.any { !it.isFinite() }) {
                return null
            }

            val determinant =
                values[0] * values[3] -
                    values[1] * values[2]

            if (!determinant.isFinite() || determinant == 0.0) {
                return null
            }

            return CanonicalElementAffine(
                a = values[0],
                b = values[1],
                c = values[2],
                d = values[3],
                tx = values[4],
                ty = values[5],
            )
        }
    }

    fun mapOrNull(point: DuPoint): DuPoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) {
            return null
        }

        val x = a * point.x + c * point.y + tx
        val y = b * point.x + d * point.y + ty

        if (!x.isFinite() || !y.isFinite()) {
            return null
        }

        return DuPoint(x, y)
    }

    /**
     * Returns a fresh list containing the canonical affine
     * coefficients in [a, b, c, d, tx, ty] order.
     *
     * Does not expose mutable internal state.
     */
    fun coefficients(): List<Double> =
        listOf(a, b, c, d, tx, ty)
}

/**
 * EDT-07: Pure coordinate projection for canonical strokes.
 *
 * Element-local DU -> page-local DU -> viewport PX.
 *
 * This class does not decode JSON, generate native meshes,
 * perform clipping, or modify canonical samples.
 */
internal object CanonicalStrokeProjection {

    fun pagePointOrNull(
        localPoint: DuPoint,
        elementTransform: CanonicalElementAffine,
    ): DuPoint? =
        elementTransform.mapOrNull(localPoint)

    fun viewportPointOrNull(
        localPoint: DuPoint,
        elementTransform: CanonicalElementAffine,
        page: PositionedPage,
        viewport: ViewportTransform,
    ): PxPoint? {
        if (
            !viewport.scalePxPerDu.isFinite() ||
            viewport.scalePxPerDu <= 0.0 ||
            !viewport.translationXPx.isFinite() ||
            !viewport.translationYPx.isFinite()
        ) {
            return null
        }

        val pagePoint = pagePointOrNull(
            localPoint,
            elementTransform
        ) ?: return null

        val projected = ViewportGeometry.pageToViewport(
            pagePoint,
            page,
            viewport
        )

        if (!projected.x.isFinite() || !projected.y.isFinite()) {
            return null
        }

        return projected
    }
}
