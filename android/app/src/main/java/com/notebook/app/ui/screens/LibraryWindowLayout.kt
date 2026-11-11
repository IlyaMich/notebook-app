
package com.notebook.app.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Presentation mode for library and notebook navigation.
 *
 * Uses available layout dimensions, not device identity or orientation.
 */
internal enum class LibraryWindowLayout {
    COMPACT,
    EXPANDED,
}

/**
 * A split library needs sufficient width for two panes and sufficient
 * height for usable navigation and content controls.
 *
 * The thresholds are LIB-06 UI policy, not persisted document state.
 */
internal fun libraryWindowLayout(
    availableWidth: Dp,
    availableHeight: Dp,
): LibraryWindowLayout {
    val hasSplitWidth = availableWidth >= 840.dp
    val hasUsableHeight = availableHeight >= 480.dp

    return if (hasSplitWidth && hasUsableHeight) {
        LibraryWindowLayout.EXPANDED
    } else {
        LibraryWindowLayout.COMPACT
    }
}
