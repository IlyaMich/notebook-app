package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.NotebookRepository
import kotlinx.coroutines.launch

/** First data-connected M1 slice: root notebooks only. Folder navigation is a later UI checkpoint. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    notebookRepository: NotebookRepository,
    onOpenNotebook: (String) -> Unit,
) {
    var notebooks by remember { mutableStateOf<List<Notebook>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showingCreate by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(notebookRepository, refresh) {
        try {
            // Idempotent and structurally protected; never use a hardcoded Quick Notes ID.
            notebookRepository.ensureQuickNotes()
            notebooks = notebookRepository.listByParent(null)
            error = null
        } catch (exception: Exception) {
            error = exception.message ?: "Failed to load notebooks"
        }
    }

    if (showingCreate) {
        AlertDialog(
            onDismissRequest = { if (!creating) showingCreate = false },
            title = { Text("New notebook") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !creating && draftName.isNotBlank(),
                    onClick = {
                        creating = true
                        scope.launch {
                            try {
                                notebookRepository.create(draftName.trim())
                                refresh++
                                draftName = ""
                                showingCreate = false
                                error = null
                            } catch (exception: Exception) {
                                error = exception.message ?: "Could not create notebook"
                            } finally {
                                creating = false
                            }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = { showingCreate = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Notebook Library") }) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= 840.dp) {
                Row {
                    Surface(
                        modifier = Modifier.width(220.dp).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Library", style = MaterialTheme.typography.titleMedium)
                            Text("Notebooks")
                            Text("Folders, Recent, and Trash: coming in later checkpoints",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RootNotebookList(
                        modifier = Modifier.weight(1f),
                        notebooks = notebooks,
                        error = error,
                        onNew = { draftName = ""; showingCreate = true },
                        onRetry = { refresh++ },
                        onOpenNotebook = onOpenNotebook,
                    )
                }
            } else {
                RootNotebookList(
                    modifier = Modifier.fillMaxSize(),
                    notebooks = notebooks,
                    error = error,
                    onNew = { draftName = ""; showingCreate = true },
                    onRetry = { refresh++ },
                    onOpenNotebook = onOpenNotebook,
                )
            }
        }
    }
}

@Composable
private fun RootNotebookList(
    modifier: Modifier,
    notebooks: List<Notebook>?,
    error: String?,
    onNew: () -> Unit,
    onRetry: () -> Unit,
    onOpenNotebook: (String) -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("My Notebooks", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onNew) { Text("New notebook") }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        if (notebooks == null) {
            CircularProgressIndicator()
        } else if (notebooks.isEmpty()) {
            Text("No notebooks yet")
        } else {
            notebooks.forEach { notebook ->
                Card(
                    onClick = { onOpenNotebook(notebook.id) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(notebook.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (notebook.isQuickNotes) "Default notebook" else "Open notebook",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
