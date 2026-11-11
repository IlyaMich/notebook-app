package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.*
import org.junit.Assert.*
import org.junit.Test

class ShapeCreationGestureTest {
    private val layout = ViewportGeometry.layoutOrNull(listOf(
        PageSizeDu("p1", 100.0, 100.0), PageSizeDu("p2", 80.0, 100.0),
    ))!!
    private fun frame(tool: EditorShapeTool = EditorShapeTool.LINE) = ShapeCreationGesture.Frame(
        "note", 0, 7, layout, PxSize(400.0, 500.0),
        ViewportTransform(2.0, 10.0, 20.0), tool, 8.0,
    )
    private val start = PxPoint(30.0, 40.0)

    @Test fun fourKindsPreserveDirectionOrNormalizeFramesAndCompleteOnlyOnce() {
        EditorShapeTool.entries.forEach { tool ->
            val gesture = ShapeCreationGesture()
            val f = frame(tool)
            assertTrue(gesture.begin(1, "p1", PxPoint(90.0, 100.0), f))
            val completed = gesture.finish(1, start, f)!!
            assertEquals("p1", completed.pageId)
            assertEquals(7L, completed.expectedRevision)
            assertEquals(tool, completed.tool)
            val expected = if (tool == EditorShapeTool.LINE || tool == EditorShapeTool.ARROW)
                CanonicalShapeGeometry.Endpoints(40.0, 40.0, 10.0, 10.0)
            else CanonicalShapeGeometry.Frame(10.0, 10.0, 30.0, 30.0)
            assertEquals(expected, completed.geometry)
            assertNull(gesture.preview)
            assertNull(gesture.finish(1, start, f))
        }
    }

    @Test fun thresholdIsInclusiveAndImmediatelyBelowIsRejectedForEveryKind() {
        EditorShapeTool.entries.forEach { tool ->
            listOf(8.0 to true, 7.9999 to false).forEach { (distance, accepted) ->
                val gesture = ShapeCreationGesture()
                val f = frame(tool)
                assertTrue(gesture.begin(1, "p1", start, f))
                val dy = if (tool == EditorShapeTool.RECTANGLE || tool == EditorShapeTool.ELLIPSE) 8.0 else 0.0
                assertEquals(accepted, gesture.finish(1, PxPoint(start.x + distance, start.y + dy), f) != null)
            }
        }
    }

    @Test fun frameRequiresBothDimensionsAndEndpointUsesEuclideanDistance() {
        val g = ShapeCreationGesture()
        assertTrue(g.begin(1, "p1", start, frame(EditorShapeTool.ELLIPSE)))
        assertNull(g.finish(1, PxPoint(80.0, 47.999), frame(EditorShapeTool.ELLIPSE)))
        assertTrue(g.begin(1, "p1", start, frame()))
        assertNotNull(g.finish(1, PxPoint(36.0, 46.0), frame()))
    }

    @Test fun zoomPanAndDensityNeverBecomeCanonicalGeometry() {
        val expected = CanonicalShapeGeometry.Endpoints(10.0, 10.0, 40.0, 40.0)
        listOf(0.5, 1.0, 3.0).forEach { scale ->
            listOf(1.0, 2.5).forEach { density ->
                val f = frame().copy(transform = ViewportTransform(scale, 25.0, 30.0),
                    minimumSizePx = 4.0 * density)
                val page = layout.pages.first()
                val g = ShapeCreationGesture()
                assertTrue(g.begin(1, "p1", ViewportGeometry.pageToViewport(DuPoint(10.0, 10.0), page, f.transform), f))
                assertEquals(expected, g.finish(1,
                    ViewportGeometry.pageToViewport(DuPoint(40.0, 40.0), page, f.transform), f)!!.geometry)
            }
        }
    }

    @Test fun releasePositionIsValidatedEvenWithoutMoveAndNoDragCreatesNothing() {
        val g = ShapeCreationGesture(); val f = frame()
        assertTrue(g.begin(1, "p1", start, f))
        assertNotNull(g.finish(1, PxPoint(70.0, 80.0), f))
        assertTrue(g.begin(1, "p1", start, f))
        assertNull(g.finish(1, start, f))
        assertTrue(g.begin(1, "p1", start, f))
        assertNull(g.finish(1, PxPoint(-1.0, 80.0), f))
    }

    @Test fun gapSurroundViewportAndOtherPageStartsHaveNoOwner() {
        val g = ShapeCreationGesture(); val f = frame()
        listOf(PxPoint(5.0, 40.0), PxPoint(30.0, 240.0),
            PxPoint(400.0, 40.0), PxPoint(Double.NaN, 40.0),
            PxPoint(30.0, 280.0)).forEach { point ->
            assertFalse(g.begin(1, "p1", point, f))
            assertNull(g.preview)
        }
    }

    @Test fun leavingPageOrViewportCancelsPermanentlyEvenAfterReentry() {
        listOf(PxPoint(30.0, 240.0), PxPoint(40.0, 280.0),
            PxPoint(400.0, 50.0), PxPoint(Double.POSITIVE_INFINITY, 50.0)).forEach { outside ->
            val g = ShapeCreationGesture(); val f = frame()
            assertTrue(g.begin(1, "p1", start, f))
            assertTrue(g.append(1, PxPoint(70.0, 80.0), f))
            assertFalse(g.append(1, outside, f))
            assertNull(g.preview)
            assertFalse(g.append(1, start, f))
            assertNull(g.finish(1, PxPoint(70.0, 80.0), f))
        }
    }

    @Test fun foreignPointerCannotStealOrCancelOwner() {
        val g = ShapeCreationGesture(); val f = frame()
        assertTrue(g.begin(1, "p1", start, f))
        assertFalse(g.append(2, PxPoint(-1.0, 40.0), f))
        assertNull(g.finish(2, PxPoint(70.0, 80.0), f))
        assertNotNull(g.finish(1, PxPoint(70.0, 80.0), f))
    }

    @Test fun cameraDocumentRevisionToolDensityLayoutViewportAndGenerationChangesCancel() {
        val f = frame()
        val changes = listOf(
            f.copy(transform = f.transform.copy(translationXPx = 11.0)),
            f.copy(transform = f.transform.copy(scalePxPerDu = 2.1)),
            f.copy(documentKey = "other"), f.copy(revision = 8),
            f.copy(tool = EditorShapeTool.ARROW), f.copy(minimumSizePx = 9.0),
            f.copy(generation = 1), f.copy(viewport = PxSize(400.0, 499.0)),
            f.copy(layout = layout.copy(pages = layout.pages.toList())),
        )
        changes.forEach { changed ->
            val g = ShapeCreationGesture()
            assertTrue(g.begin(1, "p1", start, f))
            assertFalse(g.append(1, PxPoint(70.0, 80.0), changed))
            assertNull(g.finish(1, PxPoint(70.0, 80.0), f))
        }
    }

    @Test fun invalidFramesFailClosed() {
        val f = frame()
        listOf(f.copy(documentKey = ""), f.copy(revision = -1), f.copy(revision = Long.MAX_VALUE),
            f.copy(minimumSizePx = Double.NaN), f.copy(minimumSizePx = 0.0),
            f.copy(transform = f.transform.copy(scalePxPerDu = 0.0)),
            f.copy(viewport = PxSize(Double.NaN, 500.0))).forEach { invalid ->
            val g = ShapeCreationGesture()
            assertFalse(g.begin(1, "p1", start, invalid))
            assertNull(g.finish(1, PxPoint(70.0, 80.0), f))
        }
    }

    @Test fun cancelDiscardsPreviewAndRequiresFreshDown() {
        val g = ShapeCreationGesture(); val f = frame()
        assertTrue(g.begin(1, "p1", start, f))
        assertTrue(g.append(1, PxPoint(70.0, 80.0), f))
        assertNotNull(g.preview)
        g.cancel()
        assertNull(g.preview)
        assertNull(g.finish(1, PxPoint(70.0, 80.0), f))
        assertTrue(g.begin(1, "p1", start, f))
        assertNotNull(g.finish(1, PxPoint(70.0, 80.0), f))
    }

    @Test fun secondPageGeometryIsLocalAndNotContinuousStackCoordinates() {
        val g = ShapeCreationGesture(); val f = frame()
        assertTrue(g.begin(1, "p2", PxPoint(50.0, 288.0), f))
        assertEquals(CanonicalShapeGeometry.Endpoints(10.0, 10.0, 30.0, 30.0),
            g.finish(1, PxPoint(90.0, 328.0), f)!!.geometry)
    }
}
