package com.notebook.app.ui.editor.viewport

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserGestureBuffer
import org.junit.Assert.*
import org.junit.Test

class EditorEraserViewportGestureRouterTest {
    private val layout = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
        PageSizeDu("a", 300.0, 250.0),
        PageSizeDu("b", 400.0, 200.0),
    )))
    private val camera = EditorEraserViewportGestureRouter.Frame(
        documentKey = "note-1", generation = 1, layout = layout,
        viewport = PxSize(1100.0, 1100.0),
        transform = ViewportTransform(2.0, 10.0, 20.0),
    )
    private fun a(x: Double, y: Double) = PxPoint((50.0 + x) * 2.0 + 10.0, y * 2.0 + 20.0)
    private fun b(x: Double, y: Double) = PxPoint(x * 2.0 + 10.0, (274.0 + y) * 2.0 + 20.0)
    private fun start(router: EditorEraserViewportGestureRouter): Boolean =
        router.begin(100L, a(20.0, 40.0), 10.0, camera)
    private fun take(completion: EditorEraserViewportGestureRouter.PendingCompletion?) =
        requireNotNull(completion).buffer.finish(completion.pageId)

    @Test fun pixelPointsBecomePageLocalDocumentUnitsAndRadiusScalesWithZoom() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertTrue(router.append(100L, a(30.0, 55.0), camera))
        val completed = requireNotNull(take(router.finish(100L, a(40.0, 60.0), camera)))
        assertEquals("a", completed.pageId)
        assertEquals(listOf(Point(20.0, 40.0), Point(30.0, 55.0), Point(40.0, 60.0)), completed.pointsPageDu)
        assertEquals(5.0, completed.radiusPageDu, 0.0)
        assertFalse(router.isActive)
    }

    @Test fun tapCompletesOnceAndSecondConsumptionIsImpossible() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        val handoff = requireNotNull(router.finish(100L, a(20.0, 40.0), camera))
        val first = requireNotNull(take(handoff))
        assertEquals(listOf(Point(20.0, 40.0)), first.pointsPageDu)
        assertNull(handoff.buffer.finish(handoff.pageId))
        assertNull(router.finish(100L, a(20.0, 40.0), camera))
    }

    @Test fun identicalSamplesDoNotExhaustSampleBudget() {
        val router = EditorEraserViewportGestureRouter {
            EditorEraserGestureBuffer(maxSamples = 2)
        }
        assertTrue(start(router))
        repeat(20) { assertTrue(router.append(100L, a(20.0, 40.0), camera)) }
        assertTrue(router.append(100L, a(30.0, 40.0), camera))
        val sweep = requireNotNull(take(router.finish(100L, a(30.0, 40.0), camera)))
        assertEquals(2, sweep.pointsPageDu.size)
    }

    @Test fun distinctSampleOverflowInvalidatesWholePath() {
        val router = EditorEraserViewportGestureRouter {
            EditorEraserGestureBuffer(maxSamples = 2)
        }
        assertTrue(start(router))
        assertTrue(router.append(100L, a(30.0, 40.0), camera))
        assertFalse(router.append(100L, a(40.0, 40.0), camera))
        assertFalse(router.isActive)
        assertNull(router.finish(100L, a(40.0, 40.0), camera))
    }

    @Test fun crossingIntoAnotherPageInvalidatesRatherThanShorteningTheStroke() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(100L, b(20.0, 10.0), camera))
        assertNull(router.finish(100L, a(25.0, 45.0), camera))
    }

    @Test fun crossingPageGapInvalidatesRatherThanSkippingPoints() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(100L, PxPoint(150.0, 2.0 * 262.0 + 20.0), camera))
        assertNull(router.finish(100L, a(25.0, 45.0), camera))
    }

    @Test fun outsideViewportAndPaperCannotStartOrFinish() {
        val router = EditorEraserViewportGestureRouter()
        assertFalse(router.begin(100L, PxPoint(-1.0, 100.0), 10.0, camera))
        assertFalse(router.begin(100L, PxPoint(20.0, 100.0), 10.0, camera)) // paper margin
        assertTrue(start(router))
        assertNull(router.finish(100L, PxPoint(1200.0, 100.0), camera))
        assertFalse(router.isActive)
    }

    @Test fun cameraPanOrZoomMidGestureInvalidatesWholePath() {
        val pan = camera.copy(transform = camera.transform.copy(translationXPx = 11.0))
        val zoom = camera.copy(transform = camera.transform.copy(scalePxPerDu = 3.0))
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(100L, a(30.0, 45.0), pan))
        assertNull(router.finish(100L, a(30.0, 45.0), camera))
        assertTrue(start(router))
        assertNull(router.finish(100L, a(30.0, 45.0), zoom))
    }

    @Test fun resizeOrLayoutReplacementInvalidates() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(100L, a(20.0, 40.0), camera.copy(viewport = PxSize(1000.0, 1100.0))))
        assertTrue(start(router))
        val newLayout = requireNotNull(ViewportGeometry.layoutOrNull(listOf(
            PageSizeDu("a", 300.0, 250.0), PageSizeDu("b", 400.0, 200.0)
        )))
        assertFalse(router.append(100L, a(20.0, 40.0), camera.copy(layout = newLayout)))
    }

    @Test fun navigationOrLifecycleEpochChangeInvalidates() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(100L, a(20.0, 40.0), camera.copy(documentKey = "note-2")))
        assertTrue(start(router))
        assertNull(router.finish(100L, a(20.0, 40.0), camera.copy(generation = 2)))
    }

    @Test fun foreignPointerCannotAppendOrFinishOwnersSweep() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertFalse(router.append(200L, b(10.0, 10.0), camera))
        assertNull(router.finish(200L, b(10.0, 10.0), camera))
        assertTrue(router.isActive)
        assertNotNull(take(router.finish(100L, a(20.0, 40.0), camera)))
    }

    @Test fun cancelDisposesUnfinishedBufferAndCompletionIsImpossible() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        assertTrue(router.append(100L, a(40.0, 40.0), camera))
        router.cancel()
        assertNull(router.finish(100L, a(40.0, 40.0), camera))
        assertFalse(router.isActive)
    }

    @Test fun aNewGestureCannotMutateAnOlderPendingCompletion() {
        val router = EditorEraserViewportGestureRouter()
        assertTrue(start(router))
        val old = requireNotNull(router.finish(100L, a(20.0, 40.0), camera))
        assertTrue(router.begin(200L, b(30.0, 20.0), 16.0, camera))
        val newer = requireNotNull(router.finish(200L, b(40.0, 20.0), camera))
        val originalSweep = requireNotNull(take(old))
        val newSweep = requireNotNull(take(newer))
        assertEquals("a", originalSweep.pageId)
        assertEquals(listOf(Point(20.0, 40.0)), originalSweep.pointsPageDu)
        assertEquals("b", newSweep.pageId)
        assertEquals(listOf(Point(30.0, 20.0), Point(40.0, 20.0)), newSweep.pointsPageDu)
        assertEquals(8.0, newSweep.radiusPageDu, 0.0)
    }

    @Test fun impossiblePixelRadiusAndInvalidInputsFailClosed() {
        val router = EditorEraserViewportGestureRouter()
        assertFalse(router.begin(100L, a(20.0, 40.0), -1.0, camera))
        assertFalse(router.begin(100L, a(20.0, 40.0), Double.NaN, camera))
        assertFalse(router.begin(100L, a(20.0, 40.0), 5.0, camera.copy(documentKey = "")))
        assertFalse(router.begin(100L, PxPoint(Double.POSITIVE_INFINITY, 100.0), 5.0, camera))
        val tinyScale = camera.copy(transform = ViewportTransform(Double.MIN_VALUE, 10.0, 20.0))
        assertFalse(router.begin(100L, a(20.0, 40.0), Double.MAX_VALUE, tinyScale))
        assertFalse(router.isActive)
    }
}
