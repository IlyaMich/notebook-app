package com.notebook.app.ui.editor.viewport

import com.notebook.app.ui.editor.ink.EditorEraserGeometry.Point
import com.notebook.app.ui.editor.ink.EditorEraserGestureBuffer

/**
 * Pure, read-only conversion of an owned pointer gesture from viewport pixels
 * into a page-local eraser sweep. No Compose, Room, or document mutations.
 *
 * Intended caller contract (NOT wired to UI in this checkpoint):
 *  - Call begin only after input ownership has been granted to the eraser.
 *  - Supply the actual, current camera on EVERY pointer update.
 *  - Call cancel on consumed/platform-cancelled release, tool change, navigation,
 *    lifecycle pause, or pointerInput disposal (including its finally block).
 *  - Submit a [PendingCompletion] only after a genuine unconsumed owner release.
 *  - Never call the database or hit-test during pointer movement.
 *
 * A new buffer is created per gesture. A previous completion therefore cannot
 * be overwritten by a subsequent pointer-down while its commit is pending.
 * Not thread-safe: pointer lifecycle calls must be serialized by the caller.
 */
internal class EditorEraserViewportGestureRouter(
    private val bufferFactory: () -> EditorEraserGestureBuffer = { EditorEraserGestureBuffer() },
) {
    /**
     * A stable input/camera snapshot. [generation] must increment on lifecycle
     * interruption, and [documentKey] must identify the editor document.
     * Use the exact canvas-local PX dimensions, not DP or global screen pixels.
     */
    data class Frame(
        val documentKey: String,
        val generation: Int,
        val layout: ContinuousPageLayout,
        val viewport: PxSize,
        val transform: ViewportTransform,
    )

    /**
     * Ownership of a finished pointer path moves to its consumer. The buffer
     * remains active until the existing NoteEditorWholeStrokeEraser calls
     * finishAndSubmit(buffer, pageId); that call consumes it exactly once.
     * A second call returns Cancelled without reading or editing the document.
     */
    data class PendingCompletion(
        val buffer: EditorEraserGestureBuffer,
        val pageId: String,
    )

    private data class Active(
        val pointerId: Long,
        val pageId: String,
        val start: Frame,
        val buffer: EditorEraserGestureBuffer,
        var lastPoint: Point,
    )

    private var active: Active? = null

    val isActive: Boolean get() = active != null
    val activePageId: String? get() = active?.pageId

    /** Fails closed: invalid starts discard any unfinished gesture. */
    fun begin(
        pointerId: Long,
        pointPx: PxPoint,
        radiusPx: Double,
        frame: Frame,
    ): Boolean {
        cancel()
        if (frame.documentKey.isBlank() || !radiusPx.isFinite() || radiusPx < 0.0) {
            return false
        }
        val hit = hitAt(pointPx, frame) ?: return false
        val radiusDu = radiusPx / frame.transform.scalePxPerDu
        if (!radiusDu.isFinite() || radiusDu < 0.0) return false
        val point = Point(hit.localPointDu.x, hit.localPointDu.y)
        return try {
            val buffer = bufferFactory()
            buffer.begin(hit.pageId, point, radiusDu)
            active = Active(pointerId, hit.pageId, frame, buffer, point)
            true
        } catch (_: IllegalArgumentException) {
            cancel()
            false
        }
    }

    /**
     * Ignore a foreign pointer without stealing or cancelling the owner.
     * Any change to the owner's camera/document/lifecycle or owning page
     * invalidates the entire gesture; no clipped or shortened sweep escapes.
     */
    fun append(pointerId: Long, pointPx: PxPoint, frame: Frame): Boolean {
        val current = active ?: return false
        if (current.pointerId != pointerId) return false
        val point = validatedPoint(current, pointPx, frame) ?: run {
            cancel()
            return false
        }
        if (point == current.lastPoint) return true
        return try {
            current.buffer.append(current.pageId, point)
            current.lastPoint = point
            true
        } catch (_: IllegalArgumentException) {
            cancel()
            false
        }
    }

    /**
     * On the owner's unconsumed pointer-up, validate the final point and hand
     * off a one-shot buffer to the existing commit-confirmed eraser boundary.
     * No database work occurs in this function. No unsafe partial completion.
     */
    fun finish(
        pointerId: Long,
        pointPx: PxPoint,
        frame: Frame,
    ): PendingCompletion? {
        val current = active ?: return null
        if (current.pointerId != pointerId) return null
        if (!append(pointerId, pointPx, frame)) return null
        active = null
        return PendingCompletion(current.buffer, current.pageId)
    }

    /** Discard active data on cancellation, pause, navigation, or disposal. */
    fun cancel() {
        active?.buffer?.cancel()
        active = null
    }

    private fun validatedPoint(
        current: Active,
        pointPx: PxPoint,
        frame: Frame,
    ): Point? {
        if (current.start.documentKey != frame.documentKey ||
            current.start.generation != frame.generation ||
            current.start.layout !== frame.layout ||
            current.start.viewport != frame.viewport ||
            current.start.transform != frame.transform
        ) return null
        val hit = hitAt(pointPx, frame) ?: return null
        if (hit.pageId != current.pageId) return null
        return Point(hit.localPointDu.x, hit.localPointDu.y)
    }

    private fun hitAt(pointPx: PxPoint, frame: Frame): PageHit? {
        if (frame.documentKey.isBlank()) return null
        return ViewportInteraction.hitTestVisiblePage(
            pointPx = pointPx,
            layout = frame.layout,
            transform = frame.transform,
            viewport = frame.viewport,
        )
    }
}
