package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.notebook.app.data.library.repository.LibraryTrashRepository
import com.notebook.app.data.library.repository.PurgePreview
import com.notebook.app.data.library.repository.TrashedBatch
import com.notebook.app.data.library.repository.TrashedItemKind
import kotlinx.coroutines.launch

/** One card per deletion batch. A whole subtree is restored as a single action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    trashRepository: LibraryTrashRepository,
    onBack: () -> Unit,
    onRestored: () -> Unit = {},
) {
    var batches by remember(trashRepository) { mutableStateOf<List<TrashedBatch>?>(null) }
    var loadError by remember(trashRepository) { mutableStateOf<String?>(null) }
    var operationError by remember(trashRepository) { mutableStateOf<String?>(null) }
    var restoringId by remember(trashRepository) { mutableStateOf<String?>(null) }
    var preview by remember(trashRepository) { mutableStateOf<PurgePreview?>(null) }
    var loadingPreviewId by remember(trashRepository) { mutableStateOf<String?>(null) }
    var deletingId by remember(trashRepository) { mutableStateOf<String?>(null) }
    var refresh by remember(trashRepository) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(trashRepository, refresh) {
        batches = null
        loadError = null
        try {
            batches = trashRepository.listTrashedBatches()
        } catch (exception: Exception) {
            loadError = exception.message ?: "Could not load Trash"
        }
    }

    preview?.let { selected ->
        AlertDialog(
            modifier = Modifier.testTag("trash-purge-dialog"),
            onDismissRequest = { if (deletingId == null) preview = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    "${selected.title} and its deletion batch will be permanently deleted. " +
                        "${selected.folderCount} folders, ${selected.notebookCount} notebooks, " +
                        "${selected.noteCount} notes, ${selected.pageCount} pages and " +
                        "${selected.elementCount} elements will be removed. " +
                        "${selected.survivingFolderCount + selected.survivingNotebookCount + selected.survivingNoteCount} " +
                        "independently trashed items will be preserved. " +
                        "This cannot be undone."
                )
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("trash-purge-cancel"),
                    enabled = deletingId == null,
                    onClick = { preview = null },
                ) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("trash-purge-confirm"),
                    enabled = deletingId == null,
                    onClick = {
                        if (deletingId != null) return@TextButton
                        deletingId = selected.deletionBatchId
                        operationError = null
                        scope.launch {
                            try {
                                trashRepository.purgeBatch(selected)
                                preview = null
                                refresh++
                                onRestored() // also notify parent library that content changed
                            } catch (exception: Exception) {
                                preview = null
                                operationError = exception.message ?: "Permanent deletion failed"
                            } finally {
                                deletingId = null
                            }
                        }
                    },
                ) { Text(if (deletingId == null) "Delete permanently" else "Deleting…") }
            },
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Trash") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Restore items or permanently delete a supported asset-free batch. " +
                "Permanent deletion cannot be undone.",
                style = MaterialTheme.typography.bodyMedium)
            if (loadError != null) {
                Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text("Retry") }
            } else if (batches == null) {
                CircularProgressIndicator()
            } else if (batches!!.isEmpty()) {
                Text("Trash is empty", modifier = Modifier.testTag("trash-empty"))
            } else {
                if (operationError != null) {
                    Text(operationError.orEmpty(), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("trash-error"))
                }
                batches.orEmpty().forEach { batch ->
                    Card(Modifier.fillMaxWidth().testTag("trash-batch-${batch.id}")) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(batch.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (batch.kind) {
                                        TrashedItemKind.FOLDER -> "Folder"
                                        TrashedItemKind.NOTEBOOK -> "Notebook"
                                        TrashedItemKind.NOTE -> "Note"
                                    } + " · ${batch.folderCount} folders, " +
                                        "${batch.notebookCount} notebooks, ${batch.noteCount} notes",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Column {
                            TextButton(
                                modifier = Modifier.testTag("trash-restore-${batch.id}"),
                                enabled = restoringId == null && deletingId == null && loadingPreviewId == null,
                                onClick = {
                                    if (restoringId != null) return@TextButton
                                    restoringId = batch.id
                                    operationError = null
                                    scope.launch {
                                        try {
                                            trashRepository.restoreBatch(batch.id)
                                            refresh++
                                            onRestored()
                                        } catch (exception: Exception) {
                                            operationError = exception.message ?: "Restore failed"
                                        } finally {
                                            restoringId = null
                                        }
                                    }
                                },
                            ) { Text(if (restoringId == batch.id) "Restoring…" else "Restore") }
                            TextButton(
                                modifier = Modifier.testTag("trash-delete-${batch.id}"),
                                enabled = restoringId == null && deletingId == null && loadingPreviewId == null,
                                onClick = {
                                    loadingPreviewId = batch.id
                                    operationError = null
                                    scope.launch {
                                        try {
                                            preview = trashRepository.previewPurge(batch.id)
                                        } catch (exception: Exception) {
                                            operationError = exception.message ?: "This item cannot be permanently deleted yet"
                                        } finally {
                                            loadingPreviewId = null
                                        }
                                    }
                                },
                            ) { Text(if (loadingPreviewId == batch.id) "Checking…" else "Delete permanently") }
                            }
                        }
                    }
                }
            }
        }
    }
}
