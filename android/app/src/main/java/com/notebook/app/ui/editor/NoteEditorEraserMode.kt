package com.notebook.app.ui.editor

/** Session-local eraser behavior. Partial remains the V1 default. */
internal enum class NoteEditorEraserMode(val label: String) {
    PARTIAL("Partial eraser"),
    WHOLE_STROKE("Whole stroke"),
}
