package com.notebook.app.ui.editor.ink

/**
 * Prevents Jetpack Ink from starting a stroke in the page gaps or outside
 * the visible viewport. A rejected pointer stays blocked until it is released,
 * even if it subsequently moves over a page.
 *
 * Coordinates and page hit testing are supplied by the existing viewport APIs;
 * this class owns only pointer identity and does not mutate document geometry.
 */
internal class InkPointerStartGate {
    private val blocked = mutableSetOf<Long>()

    fun shouldConsume(id: Long, down: Boolean, pressed: Boolean, validStart: Boolean): Boolean {
        if (down && !validStart) blocked.add(id)
        val shouldBlock = id in blocked
        if (!pressed) blocked.remove(id)
        return shouldBlock
    }

    fun reset() = blocked.clear()
}
