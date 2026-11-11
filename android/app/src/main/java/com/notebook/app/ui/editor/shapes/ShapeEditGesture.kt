package com.notebook.app.ui.editor.shapes

import com.notebook.app.ui.editor.viewport.*
import kotlin.math.hypot

/** Owned shape-only edit. Source rows remain immutable until a guarded replacement commits. */
internal class ShapeEditGesture(
    private val renderable: (ShapeEditCandidate, ShapeCreationGesture.Frame) -> Boolean = { _, _ -> true },
) {
    data class Target(val selection: EditorShapeSelection, val handle: Int? = null)
    data class Preview(val candidate: ShapeEditCandidate, val frame: ShapeCreationGesture.Frame)
    sealed interface Result {
        data class Changed(val edit: CompletedShapeEdit) : Result
        data object NoChange : Result
        data class Rejected(val reason: String) : Result
    }
    private data class Active(
        val pointerId: Long, val target: Target, val frame: ShapeCreationGesture.Frame,
        val startPx: PxPoint, val startDu: DuPoint, val slopPx: Double,
        var lastValid: ShapeEditCandidate?,
    )
    private var active: Active? = null
    var preview: Preview? = null
        private set

    fun begin(
        pointerId: Long, point: PxPoint, frame: ShapeCreationGesture.Frame,
        target: Target, slopPx: Double,
    ): Boolean {
        cancel()
        val selection = target.selection
        if (selection.shape.locked || selection.expectedRevision != frame.revision ||
            frame.documentKey.isBlank() || frame.revision < 0 || frame.revision == Long.MAX_VALUE ||
            !slopPx.isFinite() || slopPx < 0.0) return false
        val hit = hit(point, frame) ?: return false
        if (hit.pageId != selection.pageId) return false
        val page = frame.layout.pages.singleOrNull { it.id == hit.pageId } ?: return false
        val original = ShapeEditCandidate(selection.shape)
        val valid = try { ShapeEditingGeometry.contained(original, page.widthDu, page.heightDu) }
            catch (_: IllegalArgumentException) { return false }
        active = Active(pointerId, target, frame, point, hit.localPointDu, slopPx,
            original.takeIf { valid })
        preview = Preview(original, frame)
        return true
    }

    fun append(pointerId: Long, point: PxPoint, frame: ShapeCreationGesture.Frame): Boolean {
        val current = active ?: return false
        if (current.pointerId != pointerId) return false
        val hit = hit(point, frame)
        if (frame != current.frame || frame.layout !== current.frame.layout ||
            hit?.pageId != current.target.selection.pageId) {
            cancel()
            return false
        }
        val original = current.target.selection.shape
        // Movement below slop is selection only and must not rewrite tiny imported geometry.
        if (hypot(point.x - current.startPx.x, point.y - current.startPx.y) < current.slopPx) {
            val originalCandidate = ShapeEditCandidate(original)
            val page = frame.layout.pages.single { it.id == original.pageId }
            current.lastValid = originalCandidate.takeIf {
                ShapeEditingGeometry.contained(it, page.widthDu, page.heightDu)
            }
            preview = Preview(originalCandidate, frame)
            return true
        }
        val page = frame.layout.pages.single { it.id == original.pageId }
        val candidate = try {
            ShapeEditingGeometry.drag(original, current.target.handle, current.startDu, hit.localPointDu)
                .takeIf { ShapeEditingGeometry.contained(it, page.widthDu, page.heightDu) && renderable(it, frame) }
        } catch (_: IllegalArgumentException) { null }
        if (candidate != null) current.lastValid = candidate
        preview = Preview(current.lastValid ?: ShapeEditCandidate(original), frame)
        return true
    }

    fun finish(pointerId: Long, point: PxPoint, frame: ShapeCreationGesture.Frame): Result? {
        val current = active ?: return null
        if (current.pointerId != pointerId || !append(pointerId, point, frame)) return null
        val valid = current.lastValid
        val moved = point != current.startPx &&
            hypot(point.x - current.startPx.x, point.y - current.startPx.y) >= current.slopPx
        cancel()
        return when {
            !moved -> Result.NoChange
            valid == null -> Result.Rejected("Bring the entire painted shape inside its page before applying this edit")
            valid.unchanged -> Result.NoChange
            else -> Result.Changed(CompletedShapeEdit(valid, frame.revision))
        }
    }

    fun cancel() { active = null; preview = null }

    private fun hit(point: PxPoint, frame: ShapeCreationGesture.Frame): PageHit? =
        ViewportInteraction.hitTestVisiblePage(point, frame.layout, frame.transform, frame.viewport)
}
