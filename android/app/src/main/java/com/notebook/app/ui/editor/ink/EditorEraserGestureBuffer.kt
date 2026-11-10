package com.notebook.app.ui.editor.ink

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point

/**
 * Gesture lifecycle boundary, independent of Compose, Room and the autosave queue.
 * A completed gesture is only a read-only sweep; callers must obtain a fresh
 * document snapshot, run canonical hit-testing and plan a guarded removal.
 * No pointer move can ever trigger a document edit through this component.
 */
internal class EditorEraserGestureBuffer(
    private val maxSamples: Int = 8192,
) {
    init { require(maxSamples > 0) }

    data class CompletedSweep(
        val pageId: String,
        val pointsPageDu: List<Point>,
        val radiusPageDu: Double,
    )

    private data class Active(
        val pageId: String,
        val radiusPageDu: Double,
        val points: MutableList<Point>,
    )

    private var active: Active? = null

    val isActive: Boolean get() = active != null

    /** Starting a new gesture first discards an unfinished one. */
    fun begin(pageId: String, point: Point, radiusPageDu: Double) {
        active = null
        require(pageId.isNotBlank()) { "Missing eraser page" }
        require(radiusPageDu.isFinite() && radiusPageDu >= 0.0) {
            "Invalid eraser radius"
        }
        validatePoint(point)
        active = Active(pageId, radiusPageDu, mutableListOf(point))
    }

    /** Wrong-page, invalid or excessive input invalidates the entire gesture. */
    fun append(pageId: String, point: Point) {
        val gesture = active ?: throw IllegalStateException("No eraser gesture")
        if (pageId != gesture.pageId || !point.x.isFinite() || !point.y.isFinite() ||
            gesture.points.size >= maxSamples
        ) {
            active = null
            throw IllegalArgumentException("Eraser gesture crossed page or became invalid")
        }
        gesture.points.add(point)
    }

    /** Returns null for cancellation/inactive gestures; completion never writes. */
    fun finish(pageId: String, finalPoint: Point? = null): CompletedSweep? {
        val gesture = active ?: return null
        if (pageId != gesture.pageId ||
            (finalPoint != null && (!finalPoint.x.isFinite() || !finalPoint.y.isFinite())) ||
            (finalPoint != null && gesture.points.size >= maxSamples)
        ) {
            active = null
            return null
        }
        if (finalPoint != null) gesture.points.add(finalPoint)
        active = null
        return CompletedSweep(gesture.pageId, gesture.points.toList(), gesture.radiusPageDu)
    }

    /** Call on pointer cancellation, navigation, disposal or lifecycle interruption. */
    fun cancel() { active = null }

    private fun validatePoint(point: Point) {
        require(point.x.isFinite() && point.y.isFinite()) { "Invalid eraser point" }
    }
}
