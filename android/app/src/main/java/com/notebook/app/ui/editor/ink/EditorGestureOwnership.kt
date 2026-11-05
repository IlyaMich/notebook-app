
package com.notebook.app.ui.editor.ink

/**
 * Tracks pointer ownership across a gesture.
 *
 * Pure Kotlin: no Compose, Android SDK, or persistence.
 *
 * The caller must deliver pointer-down/up events and explicitly
 * cancel ownership when the platform cancels the gesture.
 */
internal class EditorGestureOwnership {

    data class Stroke(
        val pointerId: Long,
        val contact: EditorContact,
        val tool: EditorInkTool,
        val pageId: String,
    )

    sealed interface Effect {
        data class Started(val stroke: Stroke) : Effect
        data class Finished(val stroke: Stroke) : Effect
        data class Cancelled(val stroke: Stroke) : Effect
    }

    private val contacts = mutableMapOf<Long, EditorContact>()
    private val navigationPointers = mutableSetOf<Long>()
    private var activeStroke: Stroke? = null

    /**
     * fingerCount is the number of currently pressed finger
     * contacts, including this contact when it is a finger.
     *
     * pageId is the visible page hit at pointer-down,
     * or null when the pointer starts outside a page.
     */
    fun onDown(
        pointerId: Long,
        contact: EditorContact,
        selectedTool: EditorInkTool?,
        fingerMode: EditorFingerMode,
        fingerCount: Int,
        pageId: String?,
    ): List<Effect> {
        if (pointerId in contacts || fingerCount < 0) {
            return emptyList()
        }
        if (contact == EditorContact.FINGER && fingerCount == 0) {
            return emptyList()
        }

        contacts[pointerId] = contact
        val effects = mutableListOf<Effect>()

        // A second finger takes a finger-writing gesture
        // away from the drawing layer.
        val cancelFingerStroke =
            activeStroke?.contact == EditorContact.FINGER &&
                (
                    (contact == EditorContact.FINGER && fingerCount > 1) ||
                        contact == EditorContact.STYLUS
                    )

        if (cancelFingerStroke) {
            val previousStroke = activeStroke

            if (previousStroke != null) {
                effects += Effect.Cancelled(previousStroke)

                // Two-finger navigation takes over both contacts,
                // not just the newly pressed finger.
                if (
                    contact == EditorContact.FINGER &&
                    fingerCount > 1
                ) {
                    navigationPointers += previousStroke.pointerId
                }
            }

            activeStroke = null
        }

        val pointerCount = if (contact == EditorContact.FINGER) {
            fingerCount
        } else {
            contacts.size
        }

        when (
            EditorInputRouting.onPointerDown(
                contact = contact,
                selectedTool = selectedTool,
                fingerMode = fingerMode,
                pointerCount = pointerCount,
            )
        ) {
            EditorPointerAction.DRAW -> {
                if (
                    activeStroke == null &&
                    selectedTool != null &&
                    !pageId.isNullOrBlank()
                ) {
                    val stroke = Stroke(
                        pointerId = pointerId,
                        contact = contact,
                        tool = selectedTool,
                        pageId = pageId,
                    )
                    activeStroke = stroke
                    effects += Effect.Started(stroke)
                }
            }

            EditorPointerAction.NAVIGATE -> {
                // A finger that lands while the stylus is drawing
                // may be a palm-rest contact. It must not acquire
                // navigation ownership after the stylus releases.
                //
                // Finger navigation established before the stylus
                // started remains registered and may resume later.
                if (
                    contact == EditorContact.FINGER &&
                    activeStroke?.contact != EditorContact.STYLUS
                ) {
                    navigationPointers += pointerId
                }
            }

            EditorPointerAction.IGNORE -> Unit
        }

        return effects
    }

    /**
     * Only the owner of the active stroke can finish it.
     * Calling this repeatedly cannot finish the stroke twice.
     */
    fun onUp(pointerId: Long): List<Effect> {
        if (contacts.remove(pointerId) == null) {
            return emptyList()
        }
        navigationPointers.remove(pointerId)

        val stroke = activeStroke
        return if (stroke?.pointerId == pointerId) {
            activeStroke = null
            listOf(Effect.Finished(stroke))
        } else {
            emptyList()
        }
    }

    /**
     * A tool/mode change invalidates an active stroke.
     * Already-pressed contacts cannot start new strokes
     * without a fresh pointer-down.
     */
    fun onConfigurationChanged(): List<Effect> {
        val stroke = activeStroke
        activeStroke = null
        navigationPointers.clear()
        return listOfNotNull(stroke?.let(Effect::Cancelled))
    }

    /**
     * Called for pointer cancellation, disposal or
     * another interruption of the gesture stream.
     */
    fun cancelAll(): List<Effect> {
        val stroke = activeStroke
        activeStroke = null
        contacts.clear()
        navigationPointers.clear()
        return listOfNotNull(stroke?.let(Effect::Cancelled))
    }

    /**
     * True only for a finger that explicitly acquired navigation
     * ownership on pointer-down or through finger-writing takeover.
     *
     * Contacts rejected during stylus drawing are excluded, even
     * when some other finger is allowed to navigate.
     */
    fun isNavigationPointer(pointerId: Long): Boolean =
        pointerId in navigationPointers &&
            contacts[pointerId] == EditorContact.FINGER

    /**
     * Navigation is suspended while a stroke is active.
     * The future Compose adapter owns pan/zoom calculations.
     */
    fun canNavigate(): Boolean =
        activeStroke == null &&
            navigationPointers.any {
                contacts[it] == EditorContact.FINGER
            }
}
