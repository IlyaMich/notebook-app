package com.notebook.app.ui.editor.shapes

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset

/**
 * A stationary final stylus position with an out-of-page historical sample.
 * A moving palm keeps the event observable: Compose filters moves when all contacts are stationary.
 * Coordinates are in the Activity window; dispatch must run on the UI thread.
 */
internal fun batchedShapeStylusPageExit(
    start: Offset,
    end: Offset,
    outside: Offset,
    palm: Offset,
    dispatch: (MotionEvent) -> Unit,
) {
    val downTime = SystemClock.uptimeMillis()
    val stylus = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_STYLUS
    }
    val finger = MotionEvent.PointerProperties().apply {
        id = 1
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    fun coordinates(p: Offset) = MotionEvent.PointerCoords().apply {
        x = p.x; y = p.y; pressure = 1f
    }
    fun send(action: Int, time: Long, position: Offset, withPalm: Boolean = false, batch: Boolean = false) {
        val properties = if (withPalm) arrayOf(stylus, finger) else arrayOf(stylus)
        val coords = if (withPalm) arrayOf(coordinates(position), coordinates(palm))
            else arrayOf(coordinates(position))
        val event = MotionEvent.obtain(downTime, downTime + time, action, properties.size,
            properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try {
            if (batch) {
                event.addBatch(downTime + time + 8,
                    arrayOf(coordinates(end), coordinates(palm + Offset(1f, 0f))), 0)
                check(event.historySize == 1 && event.getHistoricalX(0, 0) == outside.x)
            }
            dispatch(event)
        } finally { event.recycle() }
    }
    send(MotionEvent.ACTION_DOWN, 0, start)
    send(MotionEvent.ACTION_MOVE, 16, end)
    send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        32, end, withPalm = true)
    send(MotionEvent.ACTION_MOVE, 48, outside, withPalm = true, batch = true)
    send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        64, end, withPalm = true)
    send(MotionEvent.ACTION_UP, 80, end)
}
