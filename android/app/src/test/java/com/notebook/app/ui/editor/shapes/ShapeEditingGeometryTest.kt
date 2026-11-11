package com.notebook.app.ui.editor.shapes

import com.notebook.app.data.content.model.StoredElement
import com.notebook.app.ui.editor.ink.CanonicalElementAffine
import com.notebook.app.ui.editor.viewport.DuPoint
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

internal fun editingShape(
    kind: CanonicalShapeKind = CanonicalShapeKind.RECTANGLE,
    geometry: CanonicalShapeGeometry = CanonicalShapeGeometry.Frame(20.0, 20.0, 40.0, 30.0),
    affine: List<Double> = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    stroke: CanonicalShapeStroke = CanonicalShapeStroke("#2255AA", 2.0, 0.7),
    fill: CanonicalShapeFill? = null,
): StoredCanonicalShape {
    val id = UUID.randomUUID().toString()
    val page = "00000000-0000-4000-8000-000000000001"
    val raw = CanonicalShapeAdapter.create(id, kind, geometry, stroke, fill,
        CanonicalShapeArrowHead(12.0, 450.0).takeIf { kind == CanonicalShapeKind.ARROW })
    val source = CanonicalShapeAdapter.decode(page, StoredElement(id, 3, "shape", raw, 7))
    val transformed = CanonicalShapeAdapter.replace(source,
        elementTransform = requireNotNull(CanonicalElementAffine.fromCanonicalOrNull(affine)))
    return CanonicalShapeAdapter.decode(page, source.source.copy(envelopeJson = transformed))
}

class ShapeEditingGeometryTest {
    @Test fun bodyTranslationComposesOnLeftWithoutBakingGeometryOrChangingOtherFields() {
        val source = editingShape(affine = listOf(2.0, 1.0, -1.0, 3.0, 7.0, 8.0))
        val moved = ShapeEditingGeometry.drag(source, null, DuPoint(10.0, 20.0), DuPoint(25.0, 40.0))
        assertEquals(source.geometry, moved.geometry)
        assertEquals(listOf(2.0, 1.0, -1.0, 3.0, 22.0, 28.0), moved.transform.coefficients())
        val decoded = CanonicalShapeAdapter.decode(source.pageId, source.source.copy(envelopeJson = moved.envelope()))
        assertEquals(source.source.id, decoded.source.id)
        assertEquals(source.stroke, decoded.stroke)
        assertEquals(source.fill, decoded.fill)
        assertEquals(source.geometry, decoded.geometry)
        assertEquals(source.source.envelopeJson, ShapeEditCandidate(source).envelope())
    }

    @Test fun endpointDeltaUsesInverseAffineAndPreservesOffCenterGrab() {
        val source = editingShape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(10.0, 20.0, 30.0, 40.0),
            listOf(0.0, 2.0, -3.0, 0.0, 100.0, 50.0))
        val start = DuPoint(45.0, 75.0)
        val end = DuPoint(39.0, 83.0) // local displacement (4,2)
        val changed = ShapeEditingGeometry.drag(source, 0, start, end)
        assertEquals(CanonicalShapeGeometry.Endpoints(14.0, 22.0, 30.0, 40.0), changed.geometry)
        assertEquals(source.elementTransform.coefficients(), changed.transform.coefficients())
        assertTrue(ShapeEditingGeometry.drag(source, 0, start, start).unchanged)
    }

    @Test fun inverseHandlesShearReflectionAndTranslation() {
        val source = editingShape(affine = listOf(-2.0, 1.0, 0.5, 3.0, 100.0, -25.0))
        val local = DuPoint(12.0, -9.0)
        val restored = ShapeEditingGeometry.inverse(source.elementTransform, source.elementTransform.mapOrNull(local)!!)
        assertEquals(local.x, restored.x, 1e-12)
        assertEquals(local.y, restored.y, 1e-12)
    }

    @Test fun frameCornerCrossingNormalizesAroundFixedOppositeCorner() {
        val source = editingShape(geometry = CanonicalShapeGeometry.Frame(20.0, 30.0, 40.0, 20.0))
        val result = ShapeEditingGeometry.drag(source, 0, DuPoint(22.0, 32.0), DuPoint(72.0, 82.0))
        assertEquals(CanonicalShapeGeometry.Frame(60.0, 50.0, 10.0, 30.0), result.geometry)
        assertEquals(source.elementTransform.coefficients(), result.transform.coefficients())
    }

    @Test fun eachFrameCornerHasAStableOpposite() {
        val source = editingShape()
        ShapeEditingGeometry.handles(source.geometry).forEachIndexed { index, point ->
            val edit = ShapeEditingGeometry.drag(source, index, point, DuPoint(point.x + 2, point.y + 3))
            val opposite = ShapeEditingGeometry.handles(source.geometry)[(index + 2) % 4]
            assertTrue(ShapeEditingGeometry.handles(edit.geometry).contains(opposite))
        }
    }

    @Test fun tinyAndZeroLengthImportedGeometryAreNotEnlargedBySelectionOrNoOp() {
        val tiny = editingShape(geometry = CanonicalShapeGeometry.Frame(10.0, 10.0, 0.001, 0.002))
        assertTrue(ShapeEditingGeometry.drag(tiny, 0, DuPoint(9.0, 9.0), DuPoint(9.0, 9.0)).unchanged)
        val zero = editingShape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(20.0, 20.0, 20.0, 20.0))
        assertTrue(ShapeEditingGeometry.drag(zero, 1, DuPoint(20.0, 20.0), DuPoint(20.0, 20.0)).unchanged)
        assertEquals(ShapeEditingGeometry.Bounds(19.0, 19.0, 21.0, 21.0),
            ShapeEditingGeometry.paintedBounds(ShapeEditCandidate(zero)))
    }

    @Test fun outlineBoundsIncludeWholeWidthAndBoundaryIsInclusive() {
        val source = editingShape(geometry = CanonicalShapeGeometry.Frame(10.0, 10.0, 80.0, 80.0))
        assertTrue(ShapeEditingGeometry.contained(ShapeEditCandidate(source,
            stroke = source.stroke.copy(widthDu = 20.0)), 100.0, 100.0))
        assertFalse(ShapeEditingGeometry.contained(ShapeEditCandidate(source,
            stroke = source.stroke.copy(widthDu = 20.0001)), 100.0, 100.0))
    }

    @Test fun transformedEllipseBoundsUseAnalyticSupportInsteadOfBoundingCorners() {
        val source = editingShape(CanonicalShapeKind.ELLIPSE,
            CanonicalShapeGeometry.Frame(-10.0, -20.0, 20.0, 40.0),
            listOf(2.0, 1.0, -1.0, 3.0, 100.0, 100.0))
        val b = ShapeEditingGeometry.paintedBounds(ShapeEditCandidate(source))!!
        assertEquals(100.0 + kotlin.math.hypot(20.0, 20.0) + kotlin.math.sqrt(5.0), b.right, 1e-12)
        assertEquals(100.0 + kotlin.math.hypot(10.0, 60.0) + kotlin.math.sqrt(10.0), b.bottom, 1e-12)
    }

    @Test fun arrowBoundsIncludeUnshortenedHeadAndRoundCaps() {
        val shape = editingShape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(20.0, 20.0, 21.0, 20.0))
        val b = ShapeEditingGeometry.paintedBounds(ShapeEditCandidate(shape))!!
        assertEquals(7.0, b.top, 1e-12) // angle 450 -> 90 degrees, head legs 12 DU
        assertEquals(33.0, b.bottom, 1e-12)
        assertFalse(ShapeEditingGeometry.contained(ShapeEditCandidate(shape), 100.0, 30.0))
        assertEquals(CanonicalShapeArrowHead(12.0, 450.0), shape.arrowHead)
    }

    @Test fun invisiblePaintAndNonClosedFillDoNotInventFootprints() {
        val line = editingShape(CanonicalShapeKind.LINE,
            CanonicalShapeGeometry.Endpoints(-20.0, -20.0, -10.0, -10.0),
            stroke = CanonicalShapeStroke("#2255AA", 2.0, 0.0),
            fill = CanonicalShapeFill("#CC3333", 1.0))
        assertNull(ShapeEditingGeometry.paintedBounds(ShapeEditCandidate(line)))
        val filled = editingShape(stroke = CanonicalShapeStroke("#2255AA", 100.0, 0.0),
            fill = CanonicalShapeFill("#CC3333", 0.5))
        assertEquals(ShapeEditingGeometry.Bounds(20.0, 20.0, 60.0, 50.0),
            ShapeEditingGeometry.paintedBounds(ShapeEditCandidate(filled)))
    }

    @Test fun styleEditsPreserveOpacityHeadAffineAndSourceBytes() {
        val original = editingShape(CanonicalShapeKind.ARROW,
            CanonicalShapeGeometry.Endpoints(20.0, 30.0, 80.0, 30.0),
            affine = listOf(1.0, 0.2, -0.3, 2.0, 7.0, 9.0),
            fill = CanonicalShapeFill("#ABCDEF", 0.35))
        val before = original.source.envelopeJson
        val edit = EditorShapeSelection(original, 8).style("#CC3333", 6.0)
        val after = CanonicalShapeAdapter.decode(original.pageId, original.source.copy(envelopeJson = edit.envelope()))
        assertEquals(before, original.source.envelopeJson)
        assertEquals(0.7, after.stroke.opacity, 0.0)
        assertEquals(original.fill, after.fill)
        assertEquals(original.arrowHead, after.arrowHead)
        assertEquals(original.elementTransform.coefficients(), after.elementTransform.coefficients())
        assertEquals(original.source.id, after.source.id)
    }

    @Test fun alreadySelectedPaletteColorPreservesHexSpellingAndDoesNotCreateAnEdit() {
        val original = editingShape(stroke = CanonicalShapeStroke("#2255aa", 3.0, 0.7),
            fill = CanonicalShapeFill("#abcdef", 0.35))
        val edit = EditorShapeSelection(original, 8).style(
            outlineColor = "#2255AA", fill = CanonicalShapeFill("#ABCDEF", 0.35))
        assertTrue(edit.candidate.unchanged)
        assertEquals(original.source.envelopeJson, edit.envelope())
    }

    @Test fun noOpReplacementRetainsExactJsonAndInvalidEditsFail() {
        val original = editingShape()
        assertEquals(original.source.envelopeJson, EditorShapeSelection(original, 8).style().envelope())
        assertThrows(IllegalArgumentException::class.java) {
            EditorShapeSelection(original, 8).style(outlineWidth = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShapeEditingGeometry.drag(original, 99, DuPoint(0.0, 0.0), DuPoint(1.0, 1.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShapeEditingGeometry.drag(original, 0, DuPoint(20.0, 20.0), DuPoint(60.0, 50.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShapeEditingGeometry.drag(original, null, DuPoint(0.0, 0.0), DuPoint(Double.NaN, 0.0))
        }
    }
}
