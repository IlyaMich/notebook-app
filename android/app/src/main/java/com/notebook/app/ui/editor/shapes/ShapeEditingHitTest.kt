package com.notebook.app.ui.editor.shapes

import android.graphics.Path
import com.notebook.app.ui.editor.rendering.PageElementCommand
import com.notebook.app.ui.editor.rendering.PageElementPlan
import com.notebook.app.ui.editor.rendering.renderMatrix
import com.notebook.app.ui.editor.rendering.renderFloat
import com.notebook.app.ui.editor.viewport.*
import kotlin.math.hypot

/** Android's actual round-cap/join painted path, clipped before applying physical-pixel tolerance. */
internal class ShapeEditingHitTest {
    data class Hit(val shape: StoredCanonicalShape, val handle: Int? = null)
    private val painter = CanonicalShapeCanvasRenderer.CommandRenderer()

    fun pick(
        plan: PageElementPlan, point: PxPoint, viewport: PxSize,
        selectedId: String?, handleRadiusPx: Double, bodyTolerancePx: Double,
    ): Hit? {
        require(handleRadiusPx.isFinite() && handleRadiusPx > 0.0 &&
            bodyTolerancePx.isFinite() && bodyTolerancePx > 0.0)
        if (!inside(point, plan, viewport)) return null
        val commands = plan.commands.filterIsInstance<PageElementCommand.Shape>()
        commands.firstOrNull { it.elementId == selectedId && !it.shape.locked }?.let { selected ->
            handlePoints(selected, plan, viewport)
                .map { (index, p) -> index to hypot(p.x - point.x, p.y - point.y) }
                .filter { it.second <= handleRadiusPx }
                .minWithOrNull(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
                ?.let { return Hit(selected.shape, it.first) }
        }
        val clip = Path().apply {
            addRect(plan.clipPx.left.renderFloat(), plan.clipPx.top.renderFloat(),
                plan.clipPx.right.renderFloat(), plan.clipPx.bottom.renderFloat(), Path.Direction.CW)
        }
        val visibleWindow = Path().apply {
            addRect(0f, 0f, viewport.width.renderFloat(), viewport.height.renderFloat(), Path.Direction.CW)
        }
        require(clip.op(visibleWindow, Path.Op.INTERSECT))
        val probe = Path().apply {
            addCircle(point.x.renderFloat(), point.y.renderFloat(),
                bodyTolerancePx.renderFloat(), Path.Direction.CW)
        }
        for (command in commands.asReversed()) {
            if (command.shape.locked) continue
            // An unavailable shape must not hide independently editable visible neighbors.
            val painted = try { painter.paintedPath(command.shape).also {
                it.transform(command.localToViewport.renderMatrix())
            } } catch (_: IllegalArgumentException) { continue }
            if (painted.isEmpty) continue
            require(painted.op(clip, Path.Op.INTERSECT))
            if (painted.isEmpty) continue
            require(painted.op(probe, Path.Op.INTERSECT))
            if (!painted.isEmpty) return Hit(command.shape)
        }
        return null
    }

    /** Hidden off-page/off-window centers have neither a visible handle nor a hidden hit target. */
    fun handlePoints(
        command: PageElementCommand.Shape, plan: PageElementPlan, viewport: PxSize,
    ): List<Pair<Int, PxPoint>> = ShapeEditingGeometry.handles(command.geometry).mapIndexedNotNull { i, p ->
        command.localToViewport.mapPointOrNull(p)?.takeIf { inside(it, plan, viewport) }?.let { i to it }
    }

    private fun inside(point: PxPoint, plan: PageElementPlan, viewport: PxSize): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.x >= 0.0 && point.y >= 0.0 &&
            point.x < viewport.width && point.y < viewport.height &&
            point.x >= plan.clipPx.left && point.x <= plan.clipPx.right &&
            point.y >= plan.clipPx.top && point.y <= plan.clipPx.bottom
}
