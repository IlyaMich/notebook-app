package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.*
import kotlin.math.abs
import kotlin.math.hypot

/** Owned pointer -> transient canonical geometry. No JSON, database work or ink samples. */
internal class ShapeCreationGesture {
    data class Frame(
        val documentKey: String,
        val generation: Int,
        val revision: Long,
        val layout: ContinuousPageLayout,
        val viewport: PxSize,
        val transform: ViewportTransform,
        val tool: EditorShapeTool,
        val minimumSizePx: Double,
    )

    data class Preview(
        val pageId: String,
        val geometry: CanonicalShapeGeometry,
        val frame: Frame,
    )

    private data class Active(
        val pointerId: Long,
        val pageId: String,
        val startPx: PxPoint,
        val startDu: DuPoint,
        val frame: Frame,
    )

    private var active: Active? = null
    var preview: Preview? = null
        private set

    fun begin(pointerId: Long, pageId: String, point: PxPoint, frame: Frame): Boolean {
        cancel()
        if (frame.documentKey.isBlank() || frame.revision < 0 || frame.revision == Long.MAX_VALUE ||
            !frame.minimumSizePx.isFinite() || frame.minimumSizePx <= 0.0) return false
        val hit = hit(point, frame) ?: return false
        if (hit.pageId != pageId) return false
        active = Active(pointerId, pageId, point, hit.localPointDu, frame)
        return append(pointerId, point, frame)
    }

    /** A foreign pointer cannot steal/cancel the owner. Any owner-frame/page change cancels. */
    fun append(pointerId: Long, point: PxPoint, frame: Frame): Boolean {
        val current = active ?: return false
        if (current.pointerId != pointerId) return false
        if (frame != current.frame || frame.layout !== current.frame.layout) {
            cancel()
            return false
        }
        val hit = hit(point, frame)
        if (hit?.pageId != current.pageId) {
            cancel()
            return false
        }
        preview = try {
            Preview(current.pageId,
                CanonicalShapeGeometry.fromDrag(frame.tool.kind, current.startDu, hit.localPointDu), frame)
        } catch (_: IllegalArgumentException) {
            // A zero-width/height frame has no preview yet; it may become valid on the next move.
            null
        }
        return true
    }

    /** Release position participates; completion is one-shot and below-threshold drags are no-ops. */
    fun finish(pointerId: Long, point: PxPoint, frame: Frame): CompletedShapeCreation? {
        val current = active ?: return null
        if (current.pointerId != pointerId) return null
        if (!append(pointerId, point, frame)) return null
        val geometry = preview?.geometry
        val dx = abs(point.x - current.startPx.x)
        val dy = abs(point.y - current.startPx.y)
        val largeEnough = when (frame.tool) {
            EditorShapeTool.LINE, EditorShapeTool.ARROW -> hypot(dx, dy) >= frame.minimumSizePx
            EditorShapeTool.RECTANGLE, EditorShapeTool.ELLIPSE ->
                dx >= frame.minimumSizePx && dy >= frame.minimumSizePx
        }
        cancel()
        return if (largeEnough && geometry != null)
            CompletedShapeCreation(current.pageId, frame.revision, frame.tool, geometry) else null
    }

    fun cancel() {
        active = null
        preview = null
    }

    private fun hit(point: PxPoint, frame: Frame): PageHit? =
        ViewportInteraction.hitTestVisiblePage(point, frame.layout, frame.transform, frame.viewport)
}
