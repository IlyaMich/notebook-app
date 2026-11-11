package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.DuPoint
import org.junit.Assert.*
import org.junit.Test

class CanonicalShapeGeometryTest {
    @Test fun lineAndArrowPreserveDirectionAndFractionalDu() {
        for (kind in listOf(CanonicalShapeKind.LINE, CanonicalShapeKind.ARROW)) {
            assertEquals(CanonicalShapeGeometry.Endpoints(30.125, 50.5, -2.25, 4.75),
                CanonicalShapeGeometry.fromDrag(kind, DuPoint(30.125, 50.5), DuPoint(-2.25, 4.75)))
        }
    }

    @Test fun closedShapesNormalizeAllDragDirectionsWithoutQuantization() {
        for (kind in listOf(CanonicalShapeKind.RECTANGLE, CanonicalShapeKind.ELLIPSE)) {
            for ((start, end) in listOf(
                DuPoint(10.125, 20.25) to DuPoint(30.5, 50.75),
                DuPoint(30.5, 50.75) to DuPoint(10.125, 20.25),
                DuPoint(10.125, 50.75) to DuPoint(30.5, 20.25),
                DuPoint(30.5, 20.25) to DuPoint(10.125, 50.75),
            )) {
                assertEquals(CanonicalShapeGeometry.Frame(10.125, 20.25, 20.375, 30.5),
                    CanonicalShapeGeometry.fromDrag(kind, start, end))
            }
        }
    }

    @Test fun schemaValidZeroLengthAndTinyGeometryAreNotUiThresholded() {
        assertEquals(CanonicalShapeGeometry.Endpoints(2.0, 3.0, 2.0, 3.0),
            CanonicalShapeGeometry.fromDrag(CanonicalShapeKind.LINE, DuPoint(2.0, 3.0), DuPoint(2.0, 3.0)))
        assertEquals(1e-12, (CanonicalShapeGeometry.fromDrag(CanonicalShapeKind.ELLIPSE,
            DuPoint(0.0, 0.0), DuPoint(1e-12, 1e-12)) as CanonicalShapeGeometry.Frame).width, 0.0)
    }

    @Test fun horizontalAndVerticalEndpointsAreValidButZeroAreaFramesAreNot() {
        CanonicalShapeGeometry.Endpoints(0.0, 0.0, 10.0, 0.0)
        CanonicalShapeGeometry.Endpoints(0.0, 0.0, 0.0, 10.0)
        for (end in listOf(DuPoint(0.0, 10.0), DuPoint(10.0, 0.0), DuPoint(0.0, 0.0))) {
            assertThrows(IllegalArgumentException::class.java) {
                CanonicalShapeGeometry.fromDrag(CanonicalShapeKind.RECTANGLE, DuPoint(0.0, 0.0), end)
            }
        }
    }

    @Test fun invalidAndOverflowingDragGeometryFailsExplicitly() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (kind in CanonicalShapeKind.entries) {
                assertThrows(IllegalArgumentException::class.java) {
                    CanonicalShapeGeometry.fromDrag(kind, DuPoint(value, 0.0), DuPoint(1.0, 1.0))
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalShapeGeometry.fromDrag(CanonicalShapeKind.ELLIPSE,
                DuPoint(-Double.MAX_VALUE, 0.0), DuPoint(Double.MAX_VALUE, 1.0))
        }
        for (dimension in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                CanonicalShapeGeometry.Frame(0.0, 0.0, dimension, 1.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                CanonicalShapeGeometry.Frame(0.0, 0.0, 1.0, dimension)
            }
        }
    }
}
