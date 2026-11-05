
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserPageClipping.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorEraserPageClippingTest {

    private fun p(x: Double, y: Double) = Point(x, y)

    private fun classify(
        stroke: List<Point>,
        eraser: List<Point>,
        strokeRadius: Double = 2.0,
        eraserRadius: Double = 3.0,
    ) = EditorEraserPageClipping.classify(
        strokePointsPageDu = stroke,
        strokeRadiusPageDu = strokeRadius,
        eraserPointsPageDu = eraser,
        eraserRadiusPageDu = eraserRadius,
        pageWidthDu = 100.0,
        pageHeightDu = 100.0,
    )

    @Test
    fun crossingWithinPageIsConfirmed() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(20.0, 50.0),
                    p(80.0, 50.0),
                ),
                eraser = listOf(
                    p(50.0, 30.0),
                    p(50.0, 70.0),
                ),
            ),
        )
    }

    @Test
    fun crossingAtTopBoundaryIsVisible() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(0.0, 0.0),
                    p(100.0, 0.0),
                ),
                eraser = listOf(
                    p(50.0, -10.0),
                    p(50.0, 10.0),
                ),
            ),
        )
    }

    @Test
    fun overlapOnlyOutsidePageIsRejected() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(-30.0, 50.0),
                    p(-10.0, 50.0),
                ),
                eraser = listOf(
                    p(-20.0, 40.0),
                    p(-20.0, 60.0),
                ),
            ),
        )
    }

    @Test
    fun nonIntersectingPathsAreRejected() {
        assertEquals(
            Verdict.NO_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(20.0, 20.0),
                    p(80.0, 20.0),
                ),
                eraser = listOf(
                    p(20.0, 50.0),
                    p(80.0, 50.0),
                ),
            ),
        )
    }

    @Test
    fun strokeInsidePageProvesVisibleHit() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(20.0, 50.0),
                    p(80.0, 50.0),
                ),
                eraser = listOf(
                    p(-10.0, 50.0),
                    p(110.0, 50.0),
                ),
            ),
        )
    }

    @Test
    fun eraserInsidePageProvesVisibleHit() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(
                    p(-10.0, 50.0),
                    p(110.0, 50.0),
                ),
                eraser = listOf(
                    p(50.0, 45.0),
                    p(50.0, 55.0),
                ),
            ),
        )
    }

    @Test
    fun uncertainBoundaryOverlapIsNotAuthorized() {
        // The un-clipped paths overlap outside the page.
        // Their bounds both reach the page, but the
        // current checks cannot establish visible overlap.
        assertEquals(
            Verdict.UNRESOLVED_BOUNDARY,
            classify(
                stroke = listOf(
                    p(-5.0, 10.0),
                    p(0.5, 10.0),
                ),
                eraser = listOf(
                    p(-5.0, 10.0),
                    p(20.0, 40.0),
                ),
                strokeRadius = 1.0,
                eraserRadius = 1.0,
            ),
        )
    }

    @Test
    fun singlePointStrokeCanHitInsidePage() {
        assertEquals(
            Verdict.CONFIRMED_VISIBLE_HIT,
            classify(
                stroke = listOf(p(50.0, 50.0)),
                eraser = listOf(p(53.0, 50.0)),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPageDimensionsAreRejected() {
        EditorEraserPageClipping.classify(
            strokePointsPageDu =
                listOf(p(50.0, 50.0)),
            strokeRadiusPageDu = 2.0,
            eraserPointsPageDu =
                listOf(p(50.0, 50.0)),
            eraserRadiusPageDu = 3.0,
            pageWidthDu = Double.NaN,
            pageHeightDu = 100.0,
        )
    }
}
