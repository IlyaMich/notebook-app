package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
            Text("Items remain here until you restore them. Permanent deletion is not available yet.",
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
                            TextButton(
                                modifier = Modifier.testTag("trash-restore-${batch.id}"),
                                enabled = restoringId == null,
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
                        }
                    }
                }
            }
        }
    }
}
