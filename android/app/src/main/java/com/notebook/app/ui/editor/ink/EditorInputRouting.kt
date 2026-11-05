
package com.notebook.app.ui.editor.ink

enum class EditorInkTool {
    PEN,
    PENCIL,
    HIGHLIGHTER,
}

internal enum class EditorContact {
    STYLUS,
    FINGER,
    ERASER,
    MOUSE,
    UNKNOWN,
}

internal enum class EditorFingerMode {
    NAVIGATE,
    WRITE,
}

internal enum class EditorPointerAction {
    DRAW,
    NAVIGATE,
    IGNORE,
}

/**
 * Determines the permitted action when a pointer goes down.
 *
 * This is a gesture-start policy only. It does not:
 * - Acquire or release pointer ownership.
 * - Handle ongoing multi-pointer gestures.
 * - Cancel active strokes.
 * - Consume Compose pointer events.
 * - Persist drawing data.
 */
internal object EditorInputRouting {

    fun onPointerDown(
        contact: EditorContact,
        selectedTool: EditorInkTool?,
        fingerMode: EditorFingerMode,
        pointerCount: Int,
    ): EditorPointerAction {
        if (pointerCount < 1) {
            return EditorPointerAction.IGNORE
        }

        return when (contact) {
            EditorContact.STYLUS -> {
                if (selectedTool != null) {
                    EditorPointerAction.DRAW
                } else {
                    EditorPointerAction.IGNORE
                }
            }

            EditorContact.FINGER -> {
                when {
                    fingerMode == EditorFingerMode.NAVIGATE ->
                        EditorPointerAction.NAVIGATE

                    pointerCount > 1 ->
                        EditorPointerAction.NAVIGATE

                    selectedTool != null ->
                        EditorPointerAction.DRAW

                    else ->
                        EditorPointerAction.IGNORE
                }
            }

            EditorContact.ERASER,
            EditorContact.MOUSE,
            EditorContact.UNKNOWN ->
                EditorPointerAction.IGNORE
        }
    }
}
