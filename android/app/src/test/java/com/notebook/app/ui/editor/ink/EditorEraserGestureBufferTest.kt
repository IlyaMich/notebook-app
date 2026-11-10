package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import org.junit.Assert.*
import org.junit.Test

class EditorEraserGestureBufferTest {
    private fun p(x: Double) = Point(x, 20.0)

    @Test fun completedGestureProducesOneImmutableSweep() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        buffer.append("page-a", p(2.0))
        val sweep = requireNotNull(buffer.finish("page-a", p(3.0)))
        assertEquals("page-a", sweep.pageId)
        assertEquals(listOf(p(1.0), p(2.0), p(3.0)), sweep.pointsPageDu)
        assertEquals(5.0, sweep.radiusPageDu, 0.0)
        assertFalse(buffer.isActive)
        buffer.begin("page-a", p(9.0), 2.0)
        assertEquals(3, sweep.pointsPageDu.size)
    }

    @Test fun cancelNeverCompletesGesture() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        buffer.cancel()
        assertNull(buffer.finish("page-a"))
    }

    @Test fun wrongPageAppendInvalidatesWholeGesture() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        try { buffer.append("page-b", p(2.0)); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertNull(buffer.finish("page-a"))
    }

    @Test fun wrongPageCompletionDoesNotProduceSweep() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        assertNull(buffer.finish("page-b"))
        assertFalse(buffer.isActive)
    }

    @Test fun invalidInputFailsClosed() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        try { buffer.append("page-a", Point(Double.NaN, 0.0)); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertNull(buffer.finish("page-a"))
    }

    @Test fun sampleLimitInvalidatesInsteadOfTruncatingPath() {
        val buffer = EditorEraserGestureBuffer(maxSamples = 2)
        buffer.begin("page-a", p(1.0), 5.0)
        buffer.append("page-a", p(2.0))
        try { buffer.append("page-a", p(3.0)); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertNull(buffer.finish("page-a"))
    }

    @Test fun beginReplacesAnUnfinishedGesture() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        buffer.begin("page-b", p(7.0), 2.0)
        assertEquals(listOf(p(7.0)), buffer.finish("page-b")!!.pointsPageDu)
    }

    @Test fun invalidBeginDiscardsPreviousGesture() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        try { buffer.begin("page-b", p(2.0), -1.0); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertNull(buffer.finish("page-a"))
    }

    @Test fun inactiveFinishIsNoOp() {
        assertNull(EditorEraserGestureBuffer().finish("page-a"))
    }

    @Test fun singlePointTapIsAValidCompletedSweep() {
        val buffer = EditorEraserGestureBuffer()
        buffer.begin("page-a", p(1.0), 5.0)
        assertEquals(listOf(p(1.0)), buffer.finish("page-a")!!.pointsPageDu)
    }
}
