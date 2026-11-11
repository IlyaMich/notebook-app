package com.notebook.app.ui.editor.rendering

import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import com.notebook.app.ui.editor.ink.CanonicalStrokeCanvasRenderer
import com.notebook.app.ui.editor.ink.StrokeViewportAffine
import com.notebook.app.ui.editor.shapes.CanonicalShapeCanvasRenderer

/** Dispatch supported element kinds in persisted order under one owning-page clip. */
internal object PageElementCanvasRenderer {
    /** Returns IDs that could not be painted, for explicit viewport diagnostics. */
    fun draw(canvas: Canvas, plan: PageElementPlan): Set<String> {
        val unavailable = plan.unavailableElementIds.toMutableSet()
        val clip = plan.clipPx
        val left = clip.left.renderFloat()
        val top = clip.top.renderFloat()
        val right = clip.right.renderFloat()
        val bottom = clip.bottom.renderFloat()
        require(right > left && bottom > top) { "Page clip cannot be represented" }
        val saved = canvas.save()
        try {
            if (!canvas.clipRect(left, top, right, bottom)) return unavailable
            val ink = CanonicalStrokeCanvasRenderer.CommandRenderer()
            val shapes = CanonicalShapeCanvasRenderer.CommandRenderer()
            for (command in plan.commands) {
                try {
                    when (command) {
                        is PageElementCommand.Ink -> ink.draw(canvas, command.value)
                        is PageElementCommand.Shape -> shapes.draw(canvas, command)
                    }
                } catch (failure: IllegalArgumentException) {
                    unavailable += command.elementId
                    Log.w("PageElementRenderer", "Unable to render element ${command.elementId}", failure)
                }
            }
        } finally {
            canvas.restoreToCount(saved)
        }
        return unavailable
    }
}

internal fun Double.renderFloat(): Float = toFloat().also {
    require(it.isFinite()) { "Canonical geometry exceeds Canvas float range" }
}

internal fun StrokeViewportAffine.renderMatrix(): Matrix {
    val values = floatArrayOf(a.renderFloat(), c.renderFloat(), tx.renderFloat(),
        b.renderFloat(), d.renderFloat(), ty.renderFloat(), 0f, 0f, 1f)
    val determinant = values[0].toDouble() * values[4] - values[1].toDouble() * values[3]
    require(determinant.isFinite() && determinant != 0.0) { "Canvas affine cannot be represented" }
    return Matrix().apply { setValues(values) }
}
