
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorEraserGeometryTest {

    private fun p(x: Double, y: Double) =
        Point(x, y)

    @Test
    fun crossingBetweenStrokeSamplesIsDetected() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(0.0, 0.0),
                p(100.0, 0.0),
            ),
            strokeWidthDu = 2.0,
            eraserPoints = listOf(
                p(50.0, -10.0),
                p(50.0, 10.0),
            ),
            eraserRadiusDu = 3.0,
        )

        assertTrue(hit)
    }

    @Test
    fun distantSweepDoesNotHitStroke() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(0.0, 0.0),
                p(100.0, 0.0),
            ),
            strokeWidthDu = 2.0,
            eraserPoints = listOf(
                p(0.0, 10.0),
                p(100.0, 10.0),
            ),
            eraserRadiusDu = 3.0,
        )

        assertFalse(hit)
    }

    @Test
    fun brushWidthIsIncludedInHitTesting() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(0.0, 0.0),
                p(100.0, 0.0),
            ),
            strokeWidthDu = 6.0,
            eraserPoints = listOf(
                p(50.0, 4.0),
            ),
            eraserRadiusDu = 1.0,
        )

        // Distance = 4, stroke half-width = 3,
        // and eraser radius = 1.
        assertTrue(hit)
    }

    @Test
    fun singlePointStrokeCanBeHit() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(20.0, 30.0),
            ),
            strokeWidthDu = 4.0,
            eraserPoints = listOf(
                p(23.0, 30.0),
            ),
            eraserRadiusDu = 1.0,
        )

        assertTrue(hit)
    }

    @Test
    fun parallelSweepOutsideCombinedRadiusDoesNotHit() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(0.0, 0.0),
                p(100.0, 0.0),
            ),
            strokeWidthDu = 2.0,
            eraserPoints = listOf(
                p(0.0, 4.1),
                p(100.0, 4.1),
            ),
            eraserRadiusDu = 3.0,
        )

        assertFalse(hit)
    }

    @Test
    fun emptyGeometryCannotHit() {
        assertFalse(
            EditorEraserGeometry.intersectsStroke(
                strokePoints = emptyList(),
                strokeWidthDu = 2.0,
                eraserPoints = listOf(p(10.0, 10.0)),
                eraserRadiusDu = 3.0,
            )
        )
    }

    @Test
    fun multipleEraserSegmentsCanHitLaterInSweep() {
        val hit = EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(0.0, 0.0),
                p(100.0, 0.0),
            ),
            strokeWidthDu = 2.0,
            eraserPoints = listOf(
                p(150.0, -20.0),
                p(50.0, -20.0),
                p(50.0, 20.0),
            ),
            eraserRadiusDu = 2.0,
        )

        assertTrue(hit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteCoordinatesAreRejected() {
        EditorEraserGeometry.intersectsStroke(
            strokePoints = listOf(
                p(Double.NaN, 0.0),
            ),
            strokeWidthDu = 2.0,
            eraserPoints = listOf(
                p(0.0, 0.0),
            ),
            eraserRadiusDu = 3.0,
        )
    }
}
