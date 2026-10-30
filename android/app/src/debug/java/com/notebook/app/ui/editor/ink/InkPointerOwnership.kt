package com.notebook.app.ui.editor.ink

/**
 * CP3B: small platform-neutral policy for deciding who owns pointer events.
 * The Compose adapter is responsible for consuming TOUCH events on the Initial pass
 * before they reach InProgressStrokes. No input events or geometry are persisted.
 */
internal class InkPointerOwnership {
    internal enum class Kind { TOUCH, STYLUS, OTHER }
    internal data class Contact(val id: Long, val kind: Kind, val pressed: Boolean)
    internal data class Decision(val navigateTouch: Boolean, val consumeTouch: Boolean, val stylusActive: Boolean)

    private val stylusIds = mutableSetOf<Long>()

    fun route(contacts: List<Contact>, fingerDrawing: Boolean): Decision {
        for (contact in contacts) {
            if (contact.kind == Kind.STYLUS) {
                if (contact.pressed) stylusIds.add(contact.id) else stylusIds.remove(contact.id)
            }
        }
        // Navigation cannot modify the camera during a stylus gesture. This keeps
        // the callback's viewport camera valid for the complete stroke capture.
        val consumeTouch = !fingerDrawing && contacts.any { it.kind == Kind.TOUCH }
        return Decision(
            navigateTouch = consumeTouch && stylusIds.isEmpty() &&
                contacts.any { it.kind == Kind.TOUCH && it.pressed },
            consumeTouch = consumeTouch,
            stylusActive = stylusIds.isNotEmpty(),
        )
    }

    fun reset() = stylusIds.clear()
}
