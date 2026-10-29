package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Folder
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.FolderRepository
import com.notebook.app.data.library.repository.NotebookRepository
import kotlinx.coroutines.launch

/** A display-only selection. Repository operations remain the authority for all validation. */
internal data class LibraryManagementTarget(
    val id: String,
    val name: String,
    val parentFolderId: String?,
    val isFolder: Boolean,
)

internal enum class LibraryManagementAction { RENAME, MOVE }

internal data class LibraryManagementRequest(
    val target: LibraryManagementTarget,
    val action: LibraryManagementAction,
)

internal fun Folder.forManagement() = LibraryManagementTarget(
    id = id, name = name, parentFolderId = parentFolderId, isFolder = true,
)

internal fun Notebook.forManagement() = LibraryManagementTarget(
    id = id, name = name, parentFolderId = parentFolderId, isFolder = false,
)

@Composable
internal fun LibraryManagementDialog(
    request: LibraryManagementRequest,
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    when (request.action) {
        LibraryManagementAction.RENAME -> RenameLibraryItemDialog(
            target = request.target,
            folderRepository = folderRepository,
            notebookRepository = notebookRepository,
            onDismiss = onDismiss,
            onSaved = onSaved,
        )
        LibraryManagementAction.MOVE -> MoveLibraryItemDialog(
            target = request.target,
            folderRepository = folderRepository,
            notebookRepository = notebookRepository,
            onDismiss = onDismiss,
            onSaved = onSaved,
        )
    }
}

@Composable
private fun RenameLibraryItemDialog(
    target: LibraryManagementTarget,
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember(target.id) { mutableStateOf(target.name) }
    var busy by remember(target.id) { mutableStateOf(false) }
    var error by remember(target.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (target.isFolder) "Rename folder" else "Rename notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier.testTag("library-rename-input"),
                    label = { Text("Name") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("library-rename-error"))
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("library-rename-confirm"),
                enabled = !busy && name.isNotBlank() && name.trim() != target.name,
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (target.isFolder) folderRepository.rename(target.id, name.trim())
                            else notebookRepository.rename(target.id, name.trim())
                            onSaved()
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not rename item"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

internal data class FolderDestination(val id: String?, val label: String, val depth: Int)

/**
 * Enumerate ACTIVE folder destinations from the root, omitting the moving folder and its
 * entire subtree. This is a UI convenience, NOT the authorization/anti-cycle check: the
 * transactional FolderRepository.move() independently validates the destination.
 */
internal suspend fun loadFolderDestinations(
    folderRepository: FolderRepository,
    movingFolderId: String?,
): List<FolderDestination> {
    val destinations = mutableListOf(FolderDestination(null, "Library root", 0))
    val visited = mutableSetOf<String>()

    suspend fun visit(parentId: String?, depth: Int) {
        check(depth <= 100) { "Folder hierarchy is too deep to choose a destination" }
        for (folder in folderRepository.listByParent(parentId)) {
            check(visited.add(folder.id)) { "Folder hierarchy contains a cycle" }
            check(visited.size <= 3_000) { "Too many folders to choose a destination" }
            if (folder.id == movingFolderId) continue // descendants stay hidden as well
            destinations.add(FolderDestination(folder.id, folder.name, depth))
            visit(folder.id, depth + 1)
        }
    }
    visit(null, 1)
    return destinations
}

@Composable
private fun MoveLibraryItemDialog(
    target: LibraryManagementTarget,
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var destinations by remember(target.id) { mutableStateOf<List<FolderDestination>?>(null) }
    var selectedId by remember(target.id) { mutableStateOf(target.parentFolderId) }
    var error by remember(target.id) { mutableStateOf<String?>(null) }
    var busy by remember(target.id) { mutableStateOf(false) }
    var reload by remember(target.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target.id, target.isFolder, folderRepository, reload) {
        destinations = null
        error = null
        try {
            destinations = loadFolderDestinations(
                folderRepository,
                if (target.isFolder) target.id else null,
            )
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load destinations"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (target.isFolder) "Move folder" else "Move notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move ${target.name} to:")
                when {
                    destinations == null && error == null -> CircularProgressIndicator()
                    destinations != null -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            destinations.orEmpty().forEach { destination ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .selectable(
                                            selected = selectedId == destination.id,
                                            enabled = !busy,
                                            onClick = { selectedId = destination.id },
                                        )
                                        .testTag("library-destination-${destination.id ?: "root"}")
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    RadioButton(selected = selectedId == destination.id, onClick = null)
                                    Text(
                                        text = "  ".repeat((destination.depth - 1).coerceAtLeast(0)
                                            .coerceAtMost(8)) + destination.label,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("library-move-error"))
                    if (destinations == null) {
                        TextButton(enabled = !busy, onClick = { reload++ }) { Text("Retry") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("library-move-confirm"),
                enabled = !busy && destinations != null && selectedId != target.parentFolderId,
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            if (target.isFolder) folderRepository.move(target.id, selectedId)
                            else notebookRepository.move(target.id, selectedId)
                            onSaved()
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not move item"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Move") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
