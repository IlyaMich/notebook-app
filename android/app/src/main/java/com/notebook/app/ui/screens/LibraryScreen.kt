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
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    LaunchedEffect(folderRepository, notebookRepository, parentFolderId, refresh) {
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

    val folderName = listing?.currentFolder?.name
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName ?: if (parentFolderId == null) "Notebook Library" else "Folder") },
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
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.width(220.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Library", style = MaterialTheme.typography.titleMedium)
                            Text(if (parentFolderId == null) "Root" else "Current folder")
                            Text(folderName ?: "Folders and notebooks",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Recent and Trash: later checkpoints",
                                style = MaterialTheme.typography.bodySmall)
                        }
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
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (listing?.currentFolder == null) "My Library" else "Inside this folder",
            style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { onNew(NewLibraryItem.FOLDER) },
                enabled = listing != null && error == null,
                modifier = Modifier.testTag("library-new-folder"),
            ) { Text("New folder") }
            Button(
                onClick = { onNew(NewLibraryItem.NOTEBOOK) },
                enabled = listing != null && error == null,
                modifier = Modifier.testTag("library-new-notebook"),
            ) { Text("New notebook") }
        }

        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            listing == null -> CircularProgressIndicator()
            else -> {
                Text("Folders", style = MaterialTheme.typography.titleLarge)
                if (listing.folders.isEmpty()) {
                    Text("No folders here")
                } else {
                    listing.folders.forEach { folder ->
                        Card(
                            onClick = { onOpenFolder(folder.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                                .testTag("library-folder-${folder.id}"),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                                Text("Open folder", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = { onManage(LibraryManagementRequest(
                                            folder.forManagement(), LibraryManagementAction.RENAME,
                                        )) },
                                        modifier = Modifier.testTag("library-rename-folder-${folder.id}"),
                                    ) { Text("Rename") }
                                    TextButton(
                                        onClick = { onManage(LibraryManagementRequest(
                                            folder.forManagement(), LibraryManagementAction.MOVE,
                                        )) },
                                        modifier = Modifier.testTag("library-move-folder-${folder.id}"),
                                    ) { Text("Move") }
                                }
                            }
                        }
                    }
                }

                Text("Notebooks", style = MaterialTheme.typography.titleLarge)
                if (listing.notebooks.isEmpty()) {
                    Text("No notebooks here")
                } else {
                    listing.notebooks.forEach { notebook ->
                        Card(
                            onClick = { onOpenNotebook(notebook.id) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                                .testTag("library-notebook-${notebook.id}"),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(notebook.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (notebook.isQuickNotes) "Default notebook" else "Open notebook",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = { onManage(LibraryManagementRequest(
                                            notebook.forManagement(), LibraryManagementAction.RENAME,
                                        )) },
                                        modifier = Modifier.testTag("library-rename-notebook-${notebook.id}"),
                                    ) { Text("Rename") }
                                    if (!notebook.isQuickNotes) {
                                        TextButton(
                                            onClick = { onManage(LibraryManagementRequest(
                                                notebook.forManagement(), LibraryManagementAction.MOVE,
                                            )) },
                                            modifier = Modifier.testTag("library-move-notebook-${notebook.id}"),
                                        ) { Text("Move") }
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
