package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import org.junit.Assert.*
import org.junit.Test

class EditorEraserPartialStrokeGeometryTest {
    private val identity = requireNotNull(
        CanonicalElementAffine.fromCanonicalOrNull(listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    )

    private fun sample(
        x: Double, y: Double = 100.0, t: Long = 0L,
        pressure: Double? = null, tilt: Double? = null, orientation: Double? = null,
    ) = StoredStrokeSample(x, y, t, pressure, tilt, orientation)

    private fun stroke(
        points: List<StoredStrokeSample> = listOf(sample(10.0, t = 0L), sample(90.0, t = 80L)),
        locked: Boolean = false,
        opacity: Double = 1.0,
        transform: CanonicalElementAffine = identity,
    ) = StoredCanonicalStroke(
        pageId = "page-1", elementId = "stroke-1", orderIndex = 0L,
        locked = locked, elementTransform = transform,
        brush = StoredStrokeBrush("pen", 2.0, "#112233", opacity),
        samples = points,
    )

    private fun erase(
        stroke: StoredCanonicalStroke = stroke(),
        sweep: List<Point> = listOf(Point(50.0, 100.0)),
        radius: Double = 4.0,
        pageWidth: Double = 600.0,
        pageHeight: Double = 800.0,
    ) = EditorEraserPartialStrokeGeometry.split(stroke, pageWidth, pageHeight, sweep, radius)

    private fun pieces(result: EditorEraserPartialStrokeGeometry.Result): List<List<StoredStrokeSample>> {
        assertTrue("Expected fragments, got $result", result is EditorEraserPartialStrokeGeometry.Result.Fragments)
        return (result as EditorEraserPartialStrokeGeometry.Result.Fragments).pieces
    }

    @Test fun singlePointSweepSplitsBetweenSparseSamples() {
        val result = pieces(erase())
        assertEquals(2, result.size)
        assertEquals(listOf(10.0, 45.0), result[0].map { it.xDu })
        assertEquals(listOf(55.0, 90.0), result[1].map { it.xDu })
        assertEquals(listOf(0L, 35L), result[0].map { it.tMs })
        assertEquals(listOf(45L, 80L), result[1].map { it.tMs })
    }

    @Test fun samplesPreserveInterpolatedPressureTiltOrientation() {
        val s = stroke(points = listOf(
            sample(10.0, t = 0L, pressure = 0.0, tilt = 0.0, orientation = -1.0),
            sample(90.0, t = 160L, pressure = 1.0, tilt = 1.0, orientation = 1.0),
        ))
        val cuts = pieces(erase(stroke = s))
        val left = cuts[0].last()
        val right = cuts[1].first()
        assertEquals(70L, left.tMs)
        assertEquals(90L, right.tMs)
        assertEquals(0.4375, left.pressure!!, 0.0000001)
        assertEquals(0.5625, right.pressure!!, 0.0000001)
        assertEquals(0.4375, left.tiltRad!!, 0.0000001)
        assertEquals(-0.125, left.orientationRad!!, 0.0000001)
        assertEquals(0.125, right.orientationRad!!, 0.0000001)
    }

    @Test fun missingOptionalMetadataDoesNotInventInterpolatedValues() {
        val s = stroke(points = listOf(
            sample(10.0, t = 0L, pressure = 0.4),
            sample(90.0, t = 80L),
        ))
        val cuts = pieces(erase(stroke = s))
        assertNull(cuts[0].last().pressure)
        assertNull(cuts[1].first().pressure)
        assertEquals(0.4, cuts[0].first().pressure!!, 0.0)
    }

    @Test fun strokeSamplesOutsideSweptRegionRemainUnchanged() {
        assertEquals(
            EditorEraserPartialStrokeGeometry.Result.Unchanged,
            erase(sweep = listOf(Point(50.0, 150.0)))
        )
    }

    @Test fun erasingFirstEndpointRetainsOnlyTail() {
        val fragments = pieces(erase(sweep = listOf(Point(12.0, 100.0))))
        assertEquals(1, fragments.size)
        assertEquals(17.0, fragments.single().first().xDu, 0.0000001)
        assertEquals(90.0, fragments.single().last().xDu, 0.0)
    }

    @Test fun erasingLastEndpointRetainsOnlyHead() {
        val fragments = pieces(erase(sweep = listOf(Point(88.0, 100.0))))
        assertEquals(1, fragments.size)
        assertEquals(10.0, fragments.single().first().xDu, 0.0)
        assertEquals(83.0, fragments.single().last().xDu, 0.0000001)
    }

    @Test fun fullCoverageRemovesStroke() {
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Removed, erase(radius = 100.0))
    }

    @Test fun singlePointInkDotIsRemovedOrRetained() {
        val dot = stroke(points = listOf(sample(50.0)))
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Removed, erase(stroke = dot))
        assertEquals(
            EditorEraserPartialStrokeGeometry.Result.Unchanged,
            erase(stroke = dot, sweep = listOf(Point(150.0, 100.0)))
        )
    }

    @Test fun connectedSweepCanCreateThreeRetainedFragments() {
        val s = stroke(points = listOf(sample(10.0, t = 0L), sample(190.0, t = 180L)))
        val sweep = listOf(Point(50.0, 100.0), Point(50.0, 150.0),
            Point(150.0, 150.0), Point(150.0, 100.0))
        val fragments = pieces(erase(stroke = s, sweep = sweep))
        assertEquals(3, fragments.size)
        val expected = listOf(listOf(10.0, 45.0), listOf(55.0, 145.0), listOf(155.0, 190.0))
        fragments.zip(expected).forEach { (fragment, xs) ->
            assertEquals(xs.size, fragment.size)
            fragment.zip(xs).forEach { (point, x) ->
                assertEquals(x, point.xDu, 0.0000001)
            }
        }
    }

    @Test fun preservesMiddleVerticesInsideRetainedFragment() {
        val s = stroke(points = listOf(sample(10.0), sample(30.0), sample(70.0), sample(90.0)))
        val fragments = pieces(erase(stroke = s))
        assertEquals(listOf(10.0, 30.0, 45.0), fragments[0].map { it.xDu })
        assertEquals(listOf(55.0, 70.0, 90.0), fragments[1].map { it.xDu })
    }

    @Test fun overlappingEraserCapsulesMergeIntoSingleCut() {
        val fragments = pieces(erase(sweep = listOf(Point(46.0, 100.0), Point(54.0, 100.0))))
        assertEquals(2, fragments.size)
        assertEquals(41.0, fragments[0].last().xDu, 0.00001)
        assertEquals(59.0, fragments[1].first().xDu, 0.00001)
    }

    @Test fun tangentialContactFailsClosed() {
        assertTrue(
            erase(sweep = listOf(Point(50.0, 105.0))) is
                EditorEraserPartialStrokeGeometry.Result.Unresolved
        )
    }

    @Test fun numericallyNearTangentialMissFailsClosed() {
        assertTrue(
            erase(sweep = listOf(Point(50.0, 105.0000000001))) is
                EditorEraserPartialStrokeGeometry.Result.Unresolved
        )
    }

    @Test fun distantNearMissIsUnchanged() {
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Unchanged,
            erase(sweep = listOf(Point(50.0, 105.1))))
    }

    @Test fun lockedAndInvisibleInkAreUnaffected() {
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Unchanged, erase(stroke = stroke(locked = true)))
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Unchanged, erase(stroke = stroke(opacity = 0.0)))
        assertEquals(EditorEraserPartialStrokeGeometry.Result.Unchanged,
            erase(stroke = stroke(opacity = 0.001)))
    }

    @Test fun transformedStrokeIsExplicitlyUnresolved() {
        val translated = requireNotNull(
            CanonicalElementAffine.fromCanonicalOrNull(listOf(1.0, 0.0, 0.0, 1.0, 20.0, 0.0))
        )
        assertTrue(erase(stroke = stroke(transform = translated)) is
            EditorEraserPartialStrokeGeometry.Result.Unresolved)
    }

    @Test fun pageClippedStrokeFailsClosed() {
        assertTrue(erase(stroke = stroke(points = listOf(sample(0.5), sample(90.0)))) is
            EditorEraserPartialStrokeGeometry.Result.Unresolved)
    }

    @Test fun repeatedSamplesDoNotCreateInvalidFragments() {
        val s = stroke(points = listOf(
            sample(10.0, t = 0L), sample(10.0, t = 2L),
            sample(90.0, t = 82L),
        ))
        val fragments = pieces(erase(stroke = s))
        assertEquals(2, fragments.size)
        assertEquals(listOf(0L, 2L), fragments[0].take(2).map { it.tMs })
        assertEquals(45.0, fragments[0].last().xDu, 0.0000001)
    }

    @Test fun outOfPageSweepFailsClosed() {
        assertTrue(erase(sweep = listOf(Point(-1.0, 100.0))) is
            EditorEraserPartialStrokeGeometry.Result.Unresolved)
    }

    @Test fun boundedWorkloadFailsClosedBeforeQuadraticScan() {
        val longStroke = stroke(points = (0 until 600).map { sample(it.toDouble() + 10, t = it.toLong()) })
        val longSweep = (0 until 600).map { Point(it.toDouble() + 10, 150.0) }
        assertTrue(erase(stroke = longStroke, sweep = longSweep, pageWidth = 1000.0) is
            EditorEraserPartialStrokeGeometry.Result.Unresolved)
    }

    @Test fun vanishinglyShortRetainedFragmentFailsClosed() {
        assertTrue(erase(sweep = listOf(Point(15.00000005, 100.0))) is
            EditorEraserPartialStrokeGeometry.Result.Unresolved)
    }

    @Test fun inputValidationRejectsInvalidRadius() {
        try {
            erase(radius = Double.NaN)
            fail("Expected invalid radius rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun inputSamplesNeverMutate() {
        val original = stroke()
        val before = original.samples.toList()
        erase(stroke = original)
        assertEquals(before, original.samples)
    }
}
