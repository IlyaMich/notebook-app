
package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private data class SidebarChildren(
    val folders: List<Folder>,
    val notebooks: List<Notebook>,
)

private const val MAX_SIDEBAR_DEPTH = 12

/**
 * Repository-backed wide-window library navigation.
 *
 * Folder expansion is independent of navigation. The active notebook
 * is supplied by the Navigation 3 route rather than duplicated here.
 */
@Composable
internal fun LibraryHierarchySidebar(
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    activeFolderId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    contentVersion: Int = 0,
    activeNotebookId: String? = null,
    expansionState: LibrarySidebarExpansionState? = null,
) {
    val sharedExpansionState =
        expansionState ?: rememberLibrarySidebarExpansionState()
    var root by remember(folderRepository, notebookRepository) {
        mutableStateOf<SidebarChildren?>(null)
    }
    var error by remember(folderRepository, notebookRepository) {
        mutableStateOf<String?>(null)
    }
    var retry by remember { mutableIntStateOf(0) }
    var activeAncestorIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }

    LaunchedEffect(
        folderRepository,
        notebookRepository,
        contentVersion,
        retry,
    ) {
        root = null
        error = null

        try {
            notebookRepository.ensureQuickNotes()

            root = SidebarChildren(
                folders = folderRepository.listByParent(null),
                notebooks = notebookRepository.listByParent(null),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message
                ?: "Could not load library navigation"
        }
    }

    // Expand the ancestor path of the currently selected notebook.
    LaunchedEffect(
        activeNotebookId,
        folderRepository,
        notebookRepository,
        contentVersion,
    ) {
        activeAncestorIds = emptySet()

        if (activeNotebookId != null) {
            try {
                val selected = notebookRepository.findById(
                    activeNotebookId
                )

                val ancestors = mutableSetOf<String>()
                var parentId = selected?.parentFolderId

                while (
                    parentId != null &&
                    ancestors.size < MAX_SIDEBAR_DEPTH
                ) {
                    if (parentId in ancestors) break

                    val folder = folderRepository.findById(parentId)
                        ?: break

                    if (folder.deletedAtMillis != null) break

                    ancestors.add(folder.id)
                    parentId = folder.parentFolderId
                }

                activeAncestorIds = ancestors
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // An unavailable selection must not crash the sidebar.
                activeAncestorIds = emptySet()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(8.dp)
            .testTag("library-sidebar"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp),
        )

        Text(
            text = "Folders and notebooks",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(8.dp),
        )

        when {
            error != null -> {
                SidebarError(
                    message = error.orEmpty(),
                    onRetry = { retry++ },
                )
            }

            root == null -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp),
                )
            }

            else -> {
                val contents = root!!

                contents.folders.forEach { folder ->
                    key("sidebar-folder:${folder.id}") {
                        SidebarFolderBranch(
                            folder = folder,
                            depth = 0,
                            folderRepository = folderRepository,
                            notebookRepository = notebookRepository,
                            activeFolderId = activeFolderId,
                            activeNotebookId = activeNotebookId,
                            autoExpandFolderIds = activeAncestorIds,
                            onOpenFolder = onOpenFolder,
                            onOpenNotebook = onOpenNotebook,
                            contentVersion = contentVersion,
                            expansionState = sharedExpansionState,
                        )
                    }
                }

                contents.notebooks.forEach { notebook ->
                    key("sidebar-notebook:${notebook.id}") {
                        SidebarNotebookRow(
                            notebook = notebook,
                            depth = 0,
                            onOpenNotebook = onOpenNotebook,
                            activeNotebookId = activeNotebookId,
                        )
                    }
                }

                if (
                    contents.folders.isEmpty() &&
                    contents.notebooks.isEmpty()
                ) {
                    Text(
                        text = "No library items",
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarFolderBranch(
    folder: Folder,
    depth: Int,
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    activeFolderId: String?,
    activeNotebookId: String?,
    autoExpandFolderIds: Set<String>,
    onOpenFolder: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    contentVersion: Int,
    expansionState: LibrarySidebarExpansionState,
) {
    val expanded = expansionState.isExpanded(folder.id)
    var children by remember(folder.id) {
        mutableStateOf<SidebarChildren?>(null)
    }
    var error by remember(folder.id) {
        mutableStateOf<String?>(null)
    }
    var retry by remember(folder.id) {
        mutableIntStateOf(0)
    }

    LaunchedEffect(
        folder.id,
        autoExpandFolderIds,
        expansionState,
    ) {
        if (folder.id in autoExpandFolderIds) {
            expansionState.setExpanded(folder.id, true)
        }
    }

    LaunchedEffect(
        expanded,
        folder.id,
        folderRepository,
        notebookRepository,
        contentVersion,
        retry,
    ) {
        if (!expanded || depth >= MAX_SIDEBAR_DEPTH) {
            return@LaunchedEffect
        }

        children = null
        error = null

        try {
            children = SidebarChildren(
                folders = folderRepository.listByParent(folder.id),
                notebooks = notebookRepository.listByParent(folder.id),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load folder"
        }
    }

    val isCurrentFolder = folder.id == activeFolderId

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isCurrentFolder) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = (depth * 10).coerceAtMost(70).dp
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // This button only expands or collapses the tree.
            IconButton(
                onClick = {
                    expansionState.setExpanded(
                        folder.id,
                        !expanded,
                    )
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("sidebar-expand-${folder.id}")
                    .semantics {
                        contentDescription = if (expanded) {
                            "Collapse folder ${folder.name}"
                        } else {
                            "Expand folder ${folder.name}"
                        }
                        stateDescription = if (expanded) {
                            "Expanded"
                        } else {
                            "Collapsed"
                        }
                    },
            ) {
                Text(
                    text = if (expanded) "⌄" else "›",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }

            // The folder name is the navigation action. The legacy
            // test tag is retained for existing instrumentation.
            TextButton(
                onClick = { onOpenFolder(folder.id) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("sidebar-open-folder-${folder.id}")
                    .semantics {
                        contentDescription = "Open folder ${folder.name}"
                        selected = isCurrentFolder
                    },
                contentPadding = PaddingValues(
                    start = 0.dp,
                    end = 4.dp,
                ),
            ) {
                Text(
                    text = folder.name,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (!expanded) return

    if (depth >= MAX_SIDEBAR_DEPTH) {
        Text(
            text = "Open this folder to browse deeper.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 12.dp),
        )
        return
    }

    when {
        error != null -> {
            SidebarError(
                message = error.orEmpty(),
                onRetry = { retry++ },
            )
        }

        children == null -> {
            CircularProgressIndicator(
                modifier = Modifier.padding(12.dp),
            )
        }

        else -> {
            val loaded = children!!

            loaded.folders.forEach { child ->
                key("sidebar-folder:${child.id}") {
                    SidebarFolderBranch(
                        folder = child,
                        depth = depth + 1,
                        folderRepository = folderRepository,
                        notebookRepository = notebookRepository,
                        activeFolderId = activeFolderId,
                        activeNotebookId = activeNotebookId,
                        autoExpandFolderIds = autoExpandFolderIds,
                        onOpenFolder = onOpenFolder,
                        onOpenNotebook = onOpenNotebook,
                        contentVersion = contentVersion,
                        expansionState = expansionState,
                    )
                }
            }

            loaded.notebooks.forEach { notebook ->
                key("sidebar-notebook:${notebook.id}") {
                    SidebarNotebookRow(
                        notebook = notebook,
                        depth = depth + 1,
                        onOpenNotebook = onOpenNotebook,
                        activeNotebookId = activeNotebookId,
                    )
                }
            }

            if (
                loaded.folders.isEmpty() &&
                loaded.notebooks.isEmpty()
            ) {
                Text(
                    text = "Empty folder",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SidebarNotebookRow(
    notebook: Notebook,
    depth: Int,
    onOpenNotebook: (String) -> Unit,
    activeNotebookId: String?,
) {
    val isSelected = notebook.id == activeNotebookId

    TextButton(
        onClick = { onOpenNotebook(notebook.id) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = (depth * 10).coerceAtMost(70).dp
            )
            .heightIn(min = 48.dp)
            .testTag("sidebar-notebook-${notebook.id}")
            .semantics {
                contentDescription = "Open notebook ${notebook.name}"
                selected = isSelected
            },
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        // The icon occupies the same width as a folder chevron,
        // aligning notebook names with folder names at each depth.
        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (notebook.isQuickNotes) "★" else "▣",
                modifier = Modifier.clearAndSetSemantics {},
            )
        }

        Text(
            text = notebook.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarError(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )

        TextButton(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("sidebar-retry"),
        ) {
            Text("Retry")
        }
    }
}
