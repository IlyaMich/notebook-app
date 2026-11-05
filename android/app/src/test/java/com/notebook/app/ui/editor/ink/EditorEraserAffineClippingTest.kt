package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserTransformedGeometry.Affine
import com.notebook.app.ui.editor.ink.EditorEraserAffineClipping.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorEraserAffineClippingTest {
    private fun p(x: Double, y: Double) = Point(x, y)

    private fun classify(
        stroke: List<Point>,
        eraser: List<Point>,
        transform: Affine = Affine.Identity,
        width: Double = 4.0,
        radius: Double = 2.0,
    ) = EditorEraserAffineClipping.classify(
        strokePointsLocalDu = stroke,
        strokeWidthLocalDu = width,
        transform = transform,
        eraserPointsPageDu = eraser,
        eraserRadiusPageDu = radius,
        pageWidthDu = 100.0,
        pageHeightDu = 100.0,
    )

    @Test
    fun identityCrossingIsVisible() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(20.0, 50.0), p(80.0, 50.0)),
                eraser = listOf(p(50.0, 40.0), p(50.0, 60.0)),
            ),
        )
    }

    @Test
    fun intersectionWhollyOutsidePageIsMiss() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(p(-30.0, 50.0), p(-10.0, 50.0)),
                eraser = listOf(p(-20.0, 40.0), p(-20.0, 60.0)),
            ),
        )
    }

    @Test
    fun shapesThatEnterPageButCollideOnlyOutsideAreMiss() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(p(-10.0, 10.0), p(10.0, 10.0)),
                eraser = listOf(p(-10.0, 10.0), p(10.0, 40.0)),
                width = 2.0,
                radius = 0.25,
            ),
        )
    }

    @Test
    fun ellipticalFootprintFromNonuniformScaleIsHit() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(30.0, 30.0)),
                eraser = listOf(p(63.0, 15.0)),
                transform = Affine(2.0, 0.0, 0.0, 0.5, 0.0, 0.0),
                radius = 0.25,
            ),
        )
    }

    @Test
    fun ellipticalFootprintFromNonuniformScaleCanMiss() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(p(30.0, 30.0)),
                eraser = listOf(p(60.0, 16.6)),
                transform = Affine(2.0, 0.0, 0.0, 0.5, 0.0, 0.0),
                radius = 0.25,
            ),
        )
    }

    @Test
    fun shearTransformsWholePaintedFootprint() {
        val shear = Affine(1.0, 0.0, 0.5, 1.0, 0.0, 0.0)
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(20.0, 20.0), p(20.0, 60.0)),
                eraser = listOf(p(40.0, 40.0)),
                transform = shear,
            ),
        )
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(p(20.0, 20.0), p(20.0, 60.0)),
                eraser = listOf(p(80.0, 80.0)),
                transform = shear,
            ),
        )
    }

    @Test
    fun reflectionRemainsSupported() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(30.0, 50.0)),
                eraser = listOf(p(70.0, 50.0)),
                transform = Affine(-1.0, 0.0, 0.0, 1.0, 100.0, 0.0),
            ),
        )
    }

    @Test
    fun tangencyIsUnresolvedRatherThanClaimedHitOrMiss() {
        assertEquals(
            Verdict.UNRESOLVED_GEOMETRY,
            classify(
                stroke = listOf(p(50.0, 50.0)),
                eraser = listOf(p(54.0, 50.0)),
            ),
        )
    }

    @Test
    fun visibleOverlapNearPageBoundaryIsHit() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(-0.5, 50.0)),
                eraser = listOf(p(0.25, 50.0)),
                radius = 0.4,
            ),
        )
    }

    @Test
    fun zeroRadiusEraserIsNotMistakenForProofOfNoContact() {
        assertEquals(
            Verdict.UNRESOLVED_GEOMETRY,
            classify(
                stroke = listOf(p(50.0, 50.0)),
                eraser = listOf(p(50.0, 50.0)),
                radius = 0.0,
            ),
        )
    }

    @Test
    fun singularTransformIsExplicitlyUnsupported() {
        assertEquals(
            Verdict.UNSUPPORTED_TRANSFORM,
            classify(
                stroke = listOf(p(50.0, 50.0)),
                eraser = listOf(p(50.0, 50.0)),
                transform = Affine(1.0, 0.0, 2.0, 0.0, 0.0, 0.0),
            ),
        )
    }

    @Test
    fun emptyInputIsMiss() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(stroke = emptyList(), eraser = listOf(p(50.0, 50.0))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteGeometryIsRejected() {
        classify(
            stroke = listOf(p(Double.NaN, 50.0)),
            eraser = listOf(p(50.0, 50.0)),
        )
    }
}
