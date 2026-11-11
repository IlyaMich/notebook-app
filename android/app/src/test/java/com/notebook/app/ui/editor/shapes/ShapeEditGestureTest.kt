package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.*
import org.junit.Assert.*
import org.junit.Test

class ShapeEditGestureTest {
    private val source = editingShape()
    private val layout = ViewportGeometry.layoutOrNull(listOf(
        PageSizeDu(source.pageId, 100.0, 100.0), PageSizeDu("page2", 100.0, 100.0),
    ))!!
    private fun frame() = ShapeCreationGesture.Frame("note", 0, 7, layout,
        PxSize(300.0, 300.0), ViewportTransform(1.0, 10.0, 20.0), EditorShapeTool.LINE, 4.0)
    private fun target(shape: StoredCanonicalShape = source, handle: Int? = null) =
        ShapeEditGesture.Target(EditorShapeSelection(shape, 7), handle)
    private val down = PxPoint(50.0, 50.0)

    @Test fun bodyGestureIsOneShotAndCarriesOriginalIdentityPageAndRevision() {
        val g = ShapeEditGesture(); val f = frame()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        assertTrue(g.append(1, PxPoint(60.0, 60.0), f))
        val result = g.finish(1, PxPoint(65.0, 65.0), f) as ShapeEditGesture.Result.Changed
        assertEquals(7L, result.edit.expectedRevision)
        assertEquals(source.source.id, result.edit.elementId)
        assertEquals(source.pageId, result.edit.pageId)
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 15.0, 15.0), result.edit.candidate.transform.coefficients())
        assertNull(g.preview)
        assertNull(g.finish(1, PxPoint(65.0, 65.0), f))
    }

    @Test fun invalidCandidateRetainsLastValidPreviewAndReleaseCommitsOnlyThatCandidate() {
        val g = ShapeEditGesture(); val f = frame()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        assertTrue(g.append(1, PxPoint(60.0, 60.0), f))
        val valid = g.preview!!.candidate
        assertTrue(g.append(1, PxPoint(108.0, 60.0), f)) // Pointer on page; paint would leave it.
        assertEquals(valid, g.preview!!.candidate)
        val result = g.finish(1, PxPoint(108.0, 60.0), f) as ShapeEditGesture.Result.Changed
        assertEquals(valid, result.edit.candidate)
    }

    @Test fun unrenderableCandidateCannotReplaceLastValidPreview() {
        val g = ShapeEditGesture { candidate, _ -> candidate.transform.coefficients()[4] <= 10.0 }
        val f = frame()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        assertTrue(g.append(1, PxPoint(60.0, 60.0), f))
        val valid = g.preview!!.candidate
        assertTrue(g.append(1, PxPoint(70.0, 60.0), f))
        assertEquals(valid, g.preview!!.candidate)
        assertEquals(valid, (g.finish(1, PxPoint(70.0, 60.0), f) as ShapeEditGesture.Result.Changed).edit.candidate)
    }

    @Test fun initialOutsideShapeRequiresFullyContainedCandidateWithoutAutomaticNormalization() {
        val outside = editingShape(geometry = CanonicalShapeGeometry.Frame(-10.0, 20.0, 40.0, 30.0))
        val g = ShapeEditGesture(); val f = frame()
        val start = PxPoint(20.0, 50.0)
        assertTrue(g.begin(1, start, f, target(outside), 4.0))
        assertEquals(ShapeEditGesture.Result.NoChange, g.finish(1, start, f))
        assertTrue(g.begin(1, start, f, target(outside), 4.0))
        assertEquals(outside.geometry, g.preview!!.candidate.geometry)
        assertTrue(g.finish(1, PxPoint(25.0, 50.0), f) is ShapeEditGesture.Result.Rejected)
        assertTrue(g.begin(1, start, f, target(outside), 4.0))
        val result = g.finish(1, PxPoint(35.0, 50.0), f) as ShapeEditGesture.Result.Changed
        assertEquals(outside.geometry, result.edit.candidate.geometry)
        assertEquals(15.0, result.edit.candidate.transform.coefficients()[4], 0.0)
        assertEquals(-10.0, (outside.geometry as CanonicalShapeGeometry.Frame).x, 0.0)
    }

    @Test fun leavingPageOrViewportCancelsEvenAfterValidCandidateAndCannotResume() {
        listOf(PxPoint(115.0, 60.0), PxPoint(50.0, 160.0),
            PxPoint(-1.0, 60.0), PxPoint(300.0, 60.0), PxPoint(Double.NaN, 60.0)).forEach { outside ->
            val g = ShapeEditGesture(); val f = frame()
            assertTrue(g.begin(1, down, f, target(), 4.0))
            assertTrue(g.append(1, PxPoint(60.0, 60.0), f))
            assertFalse(g.append(1, outside, f))
            assertNull(g.preview)
            assertNull(g.finish(1, PxPoint(60.0, 60.0), f))
        }
    }

    @Test fun changedFrameAndExplicitCancellationRequireFreshDown() {
        val f = frame()
        listOf(f.copy(revision = 8), f.copy(generation = 1), f.copy(documentKey = "other"),
            f.copy(transform = f.transform.copy(scalePxPerDu = 2.0)),
            f.copy(viewport = PxSize(299.0, 300.0)), f.copy(tool = EditorShapeTool.ARROW),
            f.copy(layout = layout.copy())).forEach { changed ->
            val g = ShapeEditGesture()
            assertTrue(g.begin(1, down, f, target(), 4.0))
            assertFalse(g.append(1, PxPoint(60.0, 60.0), changed))
            assertNull(g.finish(1, PxPoint(60.0, 60.0), f))
        }
        val g = ShapeEditGesture()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        g.cancel()
        assertNull(g.finish(1, PxPoint(60.0, 60.0), f))
    }

    @Test fun selectionJitterAndExactReturnToOriginAreNoOps() {
        val g = ShapeEditGesture(); val f = frame()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        assertEquals(ShapeEditGesture.Result.NoChange, g.finish(1, PxPoint(52.0, 51.0), f))
        assertTrue(g.begin(1, down, f, target(), 4.0))
        g.append(1, PxPoint(60.0, 60.0), f)
        assertEquals(ShapeEditGesture.Result.NoChange, g.finish(1, down, f))
    }

    @Test fun foreignPointerCannotStealOrFinishAndStaleSelectionCannotBegin() {
        val g = ShapeEditGesture(); val f = frame()
        assertTrue(g.begin(1, down, f, target(), 4.0))
        assertFalse(g.append(2, PxPoint(-1.0, 0.0), f))
        assertNull(g.finish(2, PxPoint(60.0, 60.0), f))
        assertTrue(g.finish(1, PxPoint(60.0, 60.0), f) is ShapeEditGesture.Result.Changed)
        assertFalse(g.begin(1, down, f.copy(revision = 8), target(), 4.0))
        assertFalse(g.begin(1, down, f, target(), Double.NaN))
    }

    @Test fun mixedSizeSecondPageMovementNeverBakesStackOriginOrCameraIntoTheAffine() {
        val mixed = ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("other", 140.0, 100.0), PageSizeDu(source.pageId, 100.0, 100.0),
        ))!!
        val f = frame().copy(layout = mixed, viewport = PxSize(500.0, 900.0),
            transform = ViewportTransform(3.0, 40.0, 30.0))
        val page = mixed.pages[1]
        val start = ViewportGeometry.pageToViewport(DuPoint(40.0, 30.0), page, f.transform)
        val end = ViewportGeometry.pageToViewport(DuPoint(50.0, 35.0), page, f.transform)
        val g = ShapeEditGesture()
        assertTrue(g.begin(1, start, f, target(), 4.0))
        val result = g.finish(1, end, f) as ShapeEditGesture.Result.Changed
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 10.0, 5.0), result.edit.candidate.transform.coefficients())
        assertEquals(source.pageId, result.edit.pageId)
        assertEquals(source.geometry, result.edit.candidate.geometry)
    }

    @Test fun handleDragKeepsTinyOriginalAndRejectsZeroAreaCandidate() {
        val tiny = editingShape(geometry = CanonicalShapeGeometry.Frame(20.0, 20.0, 0.01, 0.01))
        val g = ShapeEditGesture(); val f = frame()
        assertTrue(g.begin(1, PxPoint(30.0, 40.0), f, target(tiny, 0), 4.0))
        assertEquals(ShapeEditGesture.Result.NoChange, g.finish(1, PxPoint(30.0, 40.0), f))
        assertEquals(tiny.geometry, ShapeEditCandidate(tiny).geometry)
        assertTrue(g.begin(1, PxPoint(30.0, 40.0), f, target(source, 0), 4.0))
        assertEquals(ShapeEditGesture.Result.NoChange, g.finish(1, PxPoint(70.0, 70.0), f))
    }
}
