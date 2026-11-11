
package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository

private data class SidebarChildren(
    val folders: List<Folder>,
    val notebooks: List<Notebook>,
)

private const val MAX_SIDEBAR_DEPTH = 12

/**
 * Repository-backed wide-window library navigation.
 *
 * Folder expansion is local presentation state. Opening a destination
 * continues through the existing Navigation 3 callbacks.
 */
@Composable
internal fun LibraryHierarchySidebar(
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    activeFolderId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    contentVersion: Int = 0,
) {
    var root by remember(folderRepository, notebookRepository) {
        mutableStateOf<SidebarChildren?>(null)
    }
    var error by remember(folderRepository, notebookRepository) {
        mutableStateOf<String?>(null)
    }
    var retry by remember { mutableIntStateOf(0) }

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
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load library navigation"
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
                val rootContents = root!!

                rootContents.folders.forEach { folder ->
                    key("sidebar-folder:${folder.id}") {
                        SidebarFolderBranch(
                            folder = folder,
                            depth = 0,
                            folderRepository = folderRepository,
                            notebookRepository = notebookRepository,
                            activeFolderId = activeFolderId,
                            onOpenFolder = onOpenFolder,
                            onOpenNotebook = onOpenNotebook,
                            contentVersion = contentVersion,
                        )
                    }
                }

                rootContents.notebooks.forEach { notebook ->
                    key("sidebar-notebook:${notebook.id}") {
                        SidebarNotebookRow(
                            notebook = notebook,
                            depth = 0,
                            onOpenNotebook = onOpenNotebook,
                        )
                    }
                }

                if (
                    rootContents.folders.isEmpty() &&
                    rootContents.notebooks.isEmpty()
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
    onOpenFolder: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    contentVersion: Int,
) {
    var expanded by rememberSaveable(folder.id) {
        mutableStateOf(false)
    }
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
        Column(
            modifier = Modifier.padding(
                start = (depth * 10).coerceAtMost(70).dp
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("sidebar-expand-${folder.id}"),
                ) {
                    Text(
                        text = if (expanded) "⌄" else "›",
                    )
                    Text(
                        text = folder.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                TextButton(
                    onClick = { onOpenFolder(folder.id) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("sidebar-open-folder-${folder.id}"),
                ) {
                    Text("Open")
                }
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
                        onOpenFolder = onOpenFolder,
                        onOpenNotebook = onOpenNotebook,
                        contentVersion = contentVersion,
                    )
                }
            }

            loaded.notebooks.forEach { notebook ->
                key("sidebar-notebook:${notebook.id}") {
                    SidebarNotebookRow(
                        notebook = notebook,
                        depth = depth + 1,
                        onOpenNotebook = onOpenNotebook,
                    )
                }
            }

            if (loaded.folders.isEmpty() && loaded.notebooks.isEmpty()) {
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
) {
    TextButton(
        onClick = { onOpenNotebook(notebook.id) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 10).coerceAtMost(70).dp)
            .heightIn(min = 48.dp)
            .testTag("sidebar-notebook-${notebook.id}"),
    ) {
        Text(
            text = if (notebook.isQuickNotes) {
                "★ ${notebook.name}"
            } else {
                "▣ ${notebook.name}"
            },
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
            modifier = Modifier.testTag("sidebar-retry"),
        ) {
            Text("Retry")
        }
    }
}
