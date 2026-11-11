
package com.notebook.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Transient UI state shared by library destinations.
 *
 * Only folder IDs are saved. Folder hierarchy and notebook content
 * are always reloaded from the repositories.
 */
@Stable
class LibrarySidebarExpansionState(
    initialExpandedFolderIds: Set<String> = emptySet(),
) {
    private var expandedFolderIds by mutableStateOf(
        initialExpandedFolderIds.toSet()
    )

    fun isExpanded(folderId: String): Boolean =
        folderId in expandedFolderIds

    fun setExpanded(
        folderId: String,
        expanded: Boolean,
    ) {
        expandedFolderIds = if (expanded) {
            expandedFolderIds + folderId
        } else {
            expandedFolderIds - folderId
        }
    }

    fun expandAll(folderIds: Collection<String>) {
        if (folderIds.isNotEmpty()) {
            expandedFolderIds = expandedFolderIds + folderIds
        }
    }

    companion object {
        val Saver: Saver<
            LibrarySidebarExpansionState,
            ArrayList<String>
            > = Saver(
            save = { state ->
                ArrayList(state.expandedFolderIds)
            },
            restore = { savedIds ->
                LibrarySidebarExpansionState(savedIds.toSet())
            },
        )
    }
}

@Composable
internal fun rememberLibrarySidebarExpansionState():
    LibrarySidebarExpansionState =
    rememberSaveable(
        saver = LibrarySidebarExpansionState.Saver,
    ) {
        LibrarySidebarExpansionState()
    }
