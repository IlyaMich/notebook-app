
package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.viewport.PxSize
import com.notebook.app.ui.editor.viewport.ViewportTransform
import org.junit.Assert.*
import org.junit.Test

class EditorPenCaptureTest {

    private val camera = ViewportTransform(
        scalePxPerDu = 2.0,
        translationXPx = 20.0,
        translationYPx = 30.0,
    )

    private val viewport = PxSize(1000.0, 800.0)

    private fun point(
        x: Double,
        y: Double,
        time: Long,
    ) = EditorPenPoint(x, y, time)

    private fun start(
        capture: EditorPenCapture,
    ) = capture.start(
        pointerId = 1L,
        pageId = "page-1",
        transform = camera,
        viewport = viewport,
        point = point(100.0, 200.0, 1000L),
    )

    @Test
    fun capturesMultiplePointsInOrder() {
        val capture = EditorPenCapture()

        assertNotNull(start(capture))
        capture.append(1L, point(110.0, 210.0, 1010L))
        capture.append(1L, point(120.0, 220.0, 1020L))

        val finished = capture.finish(1L)

        assertNotNull(finished)
        assertEquals(3, finished!!.samples.size)
        assertEquals("page-1", finished.pageId)
        assertEquals(camera, finished.startTransform)
        assertNull(capture.preview)
    }

    @Test
    fun rejectsAnotherPointersSamples() {
        val capture = EditorPenCapture()
        start(capture)

        assertNull(
            capture.append(2L, point(120.0, 220.0, 1020L))
        )

        assertEquals(1, capture.preview!!.samples.size)
        assertNull(capture.finish(2L))
        assertNotNull(capture.finish(1L))
    }

    @Test
    fun cancellationDiscardsStroke() {
        val capture = EditorPenCapture()
        start(capture)
        capture.append(1L, point(120.0, 220.0, 1020L))

        capture.cancel()

        assertNull(capture.preview)
        assertNull(capture.finish(1L))
    }

    @Test
    fun cannotStartTwoSimultaneousStrokes() {
        val capture = EditorPenCapture()
        start(capture)

        assertNull(
            capture.start(
                2L, "page-2", camera, viewport,
                point(100.0, 100.0, 1010L),
            )
        )
        assertEquals(1L, capture.preview!!.pointerId)
    }

    @Test
    fun invalidCoordinatesAndTimesAreRejected() {
        val capture = EditorPenCapture()

        assertNull(
            capture.start(
                1L, "page-1", camera, viewport,
                point(Double.NaN, 20.0, 1000L),
            )
        )

        start(capture)

        assertNull(
            capture.append(1L, point(110.0, 210.0, 999L))
        )
        assertNull(
            capture.append(1L, point(1200.0, 210.0, 1020L))
        )

        assertEquals(1, capture.preview!!.samples.size)
    }

    @Test
    fun duplicateCoordinatesAreNotAdded() {
        val capture = EditorPenCapture()
        start(capture)

        capture.append(1L, point(100.0, 200.0, 1010L))

        assertEquals(1, capture.preview!!.samples.size)
    }

    @Test
    fun sampleLimitRejectsFurtherInput() {
        val capture = EditorPenCapture(maxSamples = 2)
        start(capture)
        capture.append(1L, point(110.0, 210.0, 1010L))

        assertNull(
            capture.append(1L, point(120.0, 220.0, 1020L))
        )

        capture.cancel()
        assertNull(capture.finish(1L))
    }
}
