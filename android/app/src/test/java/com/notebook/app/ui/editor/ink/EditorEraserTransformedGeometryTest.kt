
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Affine
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorEraserTransformedGeometryTest {

    private fun p(x: Double, y: Double) = Point(x, y)

    private val stroke = listOf(
        p(0.0, 0.0),
        p(100.0, 0.0),
    )

    private fun hit(
        transform: Affine,
        eraser: List<Point>,
        width: Double = 2.0,
        radius: Double = 2.0,
    ) = EditorEraserTransformedGeometry.hitTest(
        strokePointsLocalDu = stroke,
        strokeWidthLocalDu = width,
        transform = transform,
        eraserPointsPageDu = eraser,
        eraserRadiusPageDu = radius,
    )

    @Test
    fun identityTransformDetectsIntersection() {
        assertEquals(
            Result.HIT,
            hit(
                Affine.Identity,
                listOf(p(50.0, -10.0), p(50.0, 10.0)),
            ),
        )
    }

    @Test
    fun translationMovesHitRegion() {
        val translated = Affine(
            a = 1.0, b = 0.0,
            c = 0.0, d = 1.0,
            tx = 200.0, ty = 100.0,
        )

        assertEquals(
            Result.HIT,
            hit(
                translated,
                listOf(p(250.0, 90.0), p(250.0, 110.0)),
            ),
        )

        assertEquals(
            Result.MISS,
            hit(
                translated,
                listOf(p(50.0, -10.0), p(50.0, 10.0)),
            ),
        )
    }

    @Test
    fun rotationIsAppliedBeforeHitTesting() {
        val rotated = Affine(
            a = 0.0, b = 1.0,
            c = -1.0, d = 0.0,
            tx = 50.0, ty = 0.0,
        )

        assertEquals(
            Result.HIT,
            hit(
                rotated,
                listOf(p(40.0, 50.0), p(60.0, 50.0)),
            ),
        )
    }

    @Test
    fun uniformScalingAlsoScalesBrushWidth() {
        val scaled = Affine(
            a = 2.0, b = 0.0,
            c = 0.0, d = 2.0,
            tx = 0.0, ty = 0.0,
        )

        // Local width 4 becomes page width 8.
        // Its half-width is 4; eraser radius is 1.
        assertEquals(
            Result.HIT,
            hit(
                scaled,
                eraser = listOf(p(100.0, 5.0)),
                width = 4.0,
                radius = 1.0,
            ),
        )
    }

    @Test
    fun reflectionIsSupported() {
        val reflected = Affine(
            a = -1.0, b = 0.0,
            c = 0.0, d = 1.0,
            tx = 100.0, ty = 0.0,
        )

        assertEquals(
            Result.HIT,
            hit(
                reflected,
                listOf(p(25.0, -5.0), p(25.0, 5.0)),
            ),
        )
    }

    @Test
    fun nonuniformScaleIsExplicitlyUnsupported() {
        val nonuniform = Affine(
            a = 2.0, b = 0.0,
            c = 0.0, d = 1.0,
            tx = 0.0, ty = 0.0,
        )

        assertEquals(
            Result.UNSUPPORTED_TRANSFORM,
            hit(nonuniform, listOf(p(50.0, 0.0))),
        )
    }

    @Test
    fun shearIsExplicitlyUnsupported() {
        val sheared = Affine(
            a = 1.0, b = 0.0,
            c = 0.5, d = 1.0,
            tx = 0.0, ty = 0.0,
        )

        assertEquals(
            Result.UNSUPPORTED_TRANSFORM,
            hit(sheared, listOf(p(50.0, 0.0))),
        )
    }

    @Test
    fun zeroScaleIsExplicitlyUnsupported() {
        val singular = Affine(
            a = 0.0, b = 0.0,
            c = 0.0, d = 0.0,
            tx = 0.0, ty = 0.0,
        )

        assertEquals(
            Result.UNSUPPORTED_TRANSFORM,
            hit(singular, listOf(p(0.0, 0.0))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteTransformIsRejected() {
        hit(
            Affine(
                a = Double.NaN,
                b = 0.0,
                c = 0.0,
                d = 1.0,
                tx = 0.0,
                ty = 0.0,
            ),
            listOf(p(50.0, 0.0)),
        )
    }

    @Test
    fun emptyStrokeDoesNotHit() {
        assertEquals(
            Result.MISS,
            EditorEraserTransformedGeometry.hitTest(
                strokePointsLocalDu = emptyList(),
                strokeWidthLocalDu = 2.0,
                transform = Affine.Identity,
                eraserPointsPageDu = listOf(p(0.0, 0.0)),
                eraserRadiusPageDu = 2.0,
            ),
        )
    }
}
