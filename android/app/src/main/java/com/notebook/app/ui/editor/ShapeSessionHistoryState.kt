package com.notebook.app.ui.editor

/** Shape projection of the one shared history. Depth counts shape entries; only a matching top is actionable. */
internal data class ShapeSessionHistoryState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
)
