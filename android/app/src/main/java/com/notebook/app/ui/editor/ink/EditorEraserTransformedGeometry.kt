
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Converts stroke-local geometry to page-document
 * coordinates before whole-stroke eraser hit-testing.
 *
 * Supports translations, rotations, reflections and
 * uniform scaling. Nonuniform/sheared transforms are
 * explicitly unsupported until renderer-equivalent
 * hit-testing is available.
 *
 * This component never modifies document content.
 */
internal object EditorEraserTransformedGeometry {

    /**
     * Canonical affine transform [a, b, c, d, tx, ty].
     */
    data class Affine(
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
        val tx: Double,
        val ty: Double,
    ) {
        fun map(point: Point): Point = Point(
            x = a * point.x + c * point.y + tx,
            y = b * point.x + d * point.y + ty,
        )

        companion object {
            val Identity = Affine(
                a = 1.0,
                b = 0.0,
                c = 0.0,
                d = 1.0,
                tx = 0.0,
                ty = 0.0,
            )
        }
    }

    enum class Result {
        HIT,
        MISS,
        UNSUPPORTED_TRANSFORM,
    }

    fun hitTest(
        strokePointsLocalDu: List<Point>,
        strokeWidthLocalDu: Double,
        transform: Affine,
        eraserPointsPageDu: List<Point>,
        eraserRadiusPageDu: Double,
    ): Result {
        require(
            strokeWidthLocalDu.isFinite() &&
                strokeWidthLocalDu > 0.0
        )

        require(
            eraserRadiusPageDu.isFinite() &&
                eraserRadiusPageDu >= 0.0
        )

        require(
            listOf(
                transform.a,
                transform.b,
                transform.c,
                transform.d,
                transform.tx,
                transform.ty,
            ).all(Double::isFinite)
        )

        require(
            (strokePointsLocalDu + eraserPointsPageDu).all {
                it.x.isFinite() && it.y.isFinite()
            }
        )

        if (
            strokePointsLocalDu.isEmpty() ||
            eraserPointsPageDu.isEmpty()
        ) {
            return Result.MISS
        }

        val scaleX = hypot(transform.a, transform.b)
        val scaleY = hypot(transform.c, transform.d)

        if (
            !scaleX.isFinite() ||
            !scaleY.isFinite() ||
            scaleX == 0.0 ||
            scaleY == 0.0
        ) {
            return Result.UNSUPPORTED_TRANSFORM
        }

        val relativeScaleDifference =
            abs(scaleX - scaleY) / max(scaleX, scaleY)

        val normalizedDot =
            (transform.a / scaleX) *
                (transform.c / scaleY) +
                (transform.b / scaleX) *
                (transform.d / scaleY)

        // Reject nonuniform scaling and shear rather
        // than approximate their painted footprints.
        if (
            relativeScaleDifference > 1e-9 ||
            abs(normalizedDot) > 1e-9
        ) {
            return Result.UNSUPPORTED_TRANSFORM
        }

        val pagePoints = strokePointsLocalDu.map(
            transform::map
        )

        require(
            pagePoints.all {
                it.x.isFinite() && it.y.isFinite()
            }
        )

        val pageWidth = strokeWidthLocalDu * scaleX

        require(
            pageWidth.isFinite() && pageWidth > 0.0
        )

        return if (
            EditorEraserGeometry.intersectsStroke(
                strokePoints = pagePoints,
                strokeWidthDu = pageWidth,
                eraserPoints = eraserPointsPageDu,
                eraserRadiusDu = eraserRadiusPageDu,
            )
        ) {
            Result.HIT
        } else {
            Result.MISS
        }
    }
}
