package com.notebook.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.notebook.app.data.library.repository.LibraryTrashRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlin.coroutines.cancellation.CancellationException

private enum class NewLibraryItem { FOLDER, NOTEBOOK }

private data class LibraryListing(
    val currentFolder: Folder?,
    val folders: List<Folder>,
    val notebooks: List<Notebook>,
)

/** Root and nested folders share a view. Folder ownership is always resolved in the repository. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    folderRepository: FolderRepository,
    notebookRepository: NotebookRepository,
    onOpenNotebook: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    parentFolderId: String? = null,
    onBack: () -> Unit = {},
    trashRepository: LibraryTrashRepository? = null,
    onOpenTrash: () -> Unit = {},
    onLibraryChanged: () -> Unit = {},
    contentVersion: Int = 0,
    sidebarExpansionState: LibrarySidebarExpansionState? = null,
) {
    // Key navigation state by folder to prevent a prior folder's rows flashing in a new folder.
    var listing by remember(parentFolderId, folderRepository, notebookRepository) {
        mutableStateOf<LibraryListing?>(null)
    }
    var loadError by remember(parentFolderId) { mutableStateOf<String?>(null) }
    var refresh by remember(parentFolderId) { mutableIntStateOf(0) }
    var newItem by remember(parentFolderId) { mutableStateOf<NewLibraryItem?>(null) }
    var draftName by remember(parentFolderId) { mutableStateOf("") }
    var createError by remember(parentFolderId) { mutableStateOf<String?>(null) }
    var creating by remember(parentFolderId) { mutableStateOf(false) }
    var management by remember(parentFolderId) { mutableStateOf<LibraryManagementRequest?>(null) }
    var trashRequest by remember(parentFolderId) { mutableStateOf<TrashRequest?>(null) }
    var trashError by remember(parentFolderId) { mutableStateOf<String?>(null) }
    var trashBusy by remember(parentFolderId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderRepository, notebookRepository, parentFolderId, refresh, contentVersion) {
        listing = null
        loadError = null
        try {
            val folder = if (parentFolderId == null) {
                // Provision only when viewing root; Quick Notes must never appear inside folders.
                notebookRepository.ensureQuickNotes()
                null
            } else {
                val found = folderRepository.findById(parentFolderId)
                check(found != null && found.deletedAtMillis == null) { "Folder is unavailable" }
                found
            }
            listing = LibraryListing(
                currentFolder = folder,
                folders = folderRepository.listByParent(parentFolderId),
                notebooks = notebookRepository.listByParent(parentFolderId),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            loadError = exception.message ?: "Could not load library"
        }
    }

    if (newItem != null) {
        val kind = newItem!!
        AlertDialog(
            onDismissRequest = { if (!creating) newItem = null },
            title = { Text(if (kind == NewLibraryItem.FOLDER) "New folder" else "New notebook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it; createError = null },
                        modifier = Modifier.testTag("library-name-input"),
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    if (createError != null) {
                        Text(createError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("library-create-confirm"),
                    enabled = !creating && draftName.isNotBlank(),
                    onClick = {
                        if (creating) return@TextButton
                        creating = true
                        val name = draftName.trim()
                        scope.launch {
                            try {
                                when (kind) {
                                    NewLibraryItem.FOLDER -> folderRepository.create(name, parentFolderId)
                                    NewLibraryItem.NOTEBOOK -> notebookRepository.create(name, parentFolderId)
                                }
                                newItem = null
                                draftName = ""
                                createError = null
                                refresh++
                            } catch (exception: Exception) {
                                createError = exception.message ?: "Could not create item"
                            } finally {
                                creating = false
                            }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = { newItem = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    management?.let { request ->
        LibraryManagementDialog(
            request = request,
            folderRepository = folderRepository,
            notebookRepository = notebookRepository,
            onDismiss = { management = null },
            onSaved = { management = null; refresh++ },
        )
    }

    trashRequest?.let { request ->
        TrashConfirmationDialog(
            request = request, busy = trashBusy, error = trashError,
            onCancel = { trashRequest = null; trashError = null },
            onConfirm = {
                if (!trashBusy && trashRepository != null) {
                    trashBusy = true
                    trashError = null
                    scope.launch {
                        try {
                            when (request.kind) {
                                TrashTargetKind.FOLDER -> trashRepository.trashFolder(request.id)
                                TrashTargetKind.NOTEBOOK -> trashRepository.trashNotebook(request.id)
                                TrashTargetKind.NOTE -> trashRepository.trashNote(request.id)
                            }
                            trashRequest = null
                            refresh++
                            onLibraryChanged()
                        } catch (exception: Exception) {
                            trashError = exception.message ?: "Move to Trash failed"
                        } finally {
                            trashBusy = false
                        }
                    }
                }
            },
        )
    }

    val folderName = listing?.currentFolder?.name
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName ?: if (parentFolderId == null) "Notebook Library" else "Folder") },
                actions = {
                    if (parentFolderId == null && trashRepository != null) {
                        TextButton(
                            onClick = onOpenTrash,
                            modifier = Modifier.testTag("library-open-trash"),
                        ) { Text("Trash") }
                    }
                },
                navigationIcon = {
                    if (parentFolderId != null) {
                        TextButton(onClick = onBack, modifier = Modifier.testTag("library-back")) {
                            Text("Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (
                libraryWindowLayout(maxWidth, maxHeight) ==
                LibraryWindowLayout.EXPANDED
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        LibraryHierarchySidebar(
                            folderRepository = folderRepository,
                            notebookRepository = notebookRepository,
                            activeFolderId = parentFolderId,
                            onOpenFolder = onOpenFolder,
                            onOpenNotebook = onOpenNotebook,
                            contentVersion = refresh + contentVersion,
                            expansionState = sidebarExpansionState,
                        )
                    }

                    LibraryFolderContents(
                        modifier = Modifier.weight(1f),
                        listing = listing,
                        error = loadError,
                        onNew = { kind ->
                            newItem = kind
                            draftName = ""
                            createError = null
                        },
                        onRetry = { refresh++ },
                        onOpenFolder = onOpenFolder,
                        onOpenNotebook = onOpenNotebook,
                        onManage = { management = it },
                        onTrash = { trashRequest = it; trashError = null },
                        canTrash = trashRepository != null,
                    )
                }
            } else {
                LibraryFolderContents(
                    modifier = Modifier.fillMaxSize(),
                    listing = listing,
                    error = loadError,
                    onNew = { kind ->
                        newItem = kind
                        draftName = ""
                        createError = null
                    },
                    onRetry = { refresh++ },
                    onOpenFolder = onOpenFolder,
                    onOpenNotebook = onOpenNotebook,
                    onManage = { management = it },
                    onTrash = { trashRequest = it; trashError = null },
                    canTrash = trashRepository != null,
                )
            }
        }
    }
}


@Composable
private fun LibraryFolderContents(
    modifier: Modifier,
    listing: LibraryListing?,
    error: String?,
    onNew: (NewLibraryItem) -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onManage: (LibraryManagementRequest) -> Unit,
    onTrash: (TrashRequest) -> Unit,
    canTrash: Boolean,
) {
    LazyColumn(
        modifier = modifier
            .testTag("library-contents")
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "library-header", contentType = "header") {
            Text(
                text = if (listing?.currentFolder == null) {
                    "My Library"
                } else {
                    "Inside this folder"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        item(key = "library-create-actions", contentType = "actions") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { onNew(NewLibraryItem.FOLDER) },
                    enabled = listing != null && error == null,
                    modifier = Modifier.testTag("library-new-folder"),
                ) {
                    Text("New folder")
                }

                Button(
                    onClick = { onNew(NewLibraryItem.NOTEBOOK) },
                    enabled = listing != null && error == null,
                    modifier = Modifier.testTag("library-new-notebook"),
                ) {
                    Text("New notebook")
                }
            }
        }

        when {
            error != null -> {
                item(key = "library-error", contentType = "error") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                        )

                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }

            listing == null -> {
                item(key = "library-loading", contentType = "loading") {
                    CircularProgressIndicator()
                }
            }

            else -> {
                item(
                    key = "library-folders-heading",
                    contentType = "section-heading",
                ) {
                    Text(
                        text = "Folders",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                if (listing.folders.isEmpty()) {
                    item(
                        key = "library-folders-empty",
                        contentType = "empty-state",
                    ) {
                        Text("No folders here")
                    }
                } else {
                    items(
                        items = listing.folders,
                        key = { folder -> "folder:${folder.id}" },
                        contentType = { "folder-card" },
                    ) { folder ->
                        Card(
                            onClick = { onOpenFolder(folder.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp)
                                .testTag("library-folder-${folder.id}"),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                Text(
                                    text = "Open folder",
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Row(
                                    modifier = Modifier.horizontalScroll(
                                        rememberScrollState()
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            onManage(
                                                LibraryManagementRequest(
                                                    folder.forManagement(),
                                                    LibraryManagementAction.RENAME,
                                                )
                                            )
                                        },
                                        modifier = Modifier.testTag(
                                            "library-rename-folder-${folder.id}"
                                        ),
                                    ) {
                                        Text("Rename")
                                    }

                                    TextButton(
                                        onClick = {
                                            onManage(
                                                LibraryManagementRequest(
                                                    folder.forManagement(),
                                                    LibraryManagementAction.MOVE,
                                                )
                                            )
                                        },
                                        modifier = Modifier.testTag(
                                            "library-move-folder-${folder.id}"
                                        ),
                                    ) {
                                        Text("Move")
                                    }

                                    if (canTrash) {
                                        TextButton(
                                            onClick = {
                                                onTrash(
                                                    TrashRequest(
                                                        TrashTargetKind.FOLDER,
                                                        folder.id,
                                                        folder.name,
                                                    )
                                                )
                                            },
                                            modifier = Modifier.testTag(
                                                "library-trash-folder-${folder.id}"
                                            ),
                                        ) {
                                            Text("Move to Trash")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(
                    key = "library-notebooks-heading",
                    contentType = "section-heading",
                ) {
                    Text(
                        text = "Notebooks",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                if (listing.notebooks.isEmpty()) {
                    item(
                        key = "library-notebooks-empty",
                        contentType = "empty-state",
                    ) {
                        Text("No notebooks here")
                    }
                } else {
                    items(
                        items = listing.notebooks,
                        key = { notebook -> "notebook:${notebook.id}" },
                        contentType = { "notebook-card" },
                    ) { notebook ->
                        Card(
                            onClick = { onOpenNotebook(notebook.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp)
                                .testTag("library-notebook-${notebook.id}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = notebook.name,
                                    style = MaterialTheme.typography.titleMedium,
                                )

                                Text(
                                    text = if (notebook.isQuickNotes) {
                                        "Default notebook"
                                    } else {
                                        "Open notebook"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )

                                Row(
                                    modifier = Modifier.horizontalScroll(
                                        rememberScrollState()
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(
                                        onClick = {
                                            onManage(
                                                LibraryManagementRequest(
                                                    notebook.forManagement(),
                                                    LibraryManagementAction.RENAME,
                                                )
                                            )
                                        },
                                        modifier = Modifier.testTag(
                                            "library-rename-notebook-${notebook.id}"
                                        ),
                                    ) {
                                        Text("Rename")
                                    }

                                    if (!notebook.isQuickNotes) {
                                        TextButton(
                                            onClick = {
                                                onManage(
                                                    LibraryManagementRequest(
                                                        notebook.forManagement(),
                                                        LibraryManagementAction.MOVE,
                                                    )
                                                )
                                            },
                                            modifier = Modifier.testTag(
                                                "library-move-notebook-${notebook.id}"
                                            ),
                                        ) {
                                            Text("Move")
                                        }

                                        if (canTrash) {
                                            TextButton(
                                                onClick = {
                                                    onTrash(
                                                        TrashRequest(
                                                            TrashTargetKind.NOTEBOOK,
                                                            notebook.id,
                                                            notebook.name,
                                                        )
                                                    )
                                                },
                                                modifier = Modifier.testTag(
                                                    "library-trash-notebook-${notebook.id}"
                                                ),
                                            ) {
                                                Text("Move to Trash")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
