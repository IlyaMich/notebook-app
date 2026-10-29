package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.NoteLifecycleRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.library.repository.NotebookRepository
import kotlinx.coroutines.launch

/** Uses actual notebook/note IDs; note creation delegates to the atomic LIB-03/STO-02 lifecycle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookContentsScreen(
    notebookId: String,
    notebookRepository: NotebookRepository,
    noteRepository: NoteRepository,
    noteLifecycleRepository: NoteLifecycleRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
) {
    var notebook by remember(notebookId) { mutableStateOf<Notebook?>(null) }
    var notes by remember(notebookId) { mutableStateOf<List<Note>?>(null) }
    var error by remember(notebookId) { mutableStateOf<String?>(null) }
    var creating by remember(notebookId) { mutableStateOf(false) }
    var refresh by remember(notebookId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(notebookId, refresh) {
        try {
            notebook = notebookRepository.findById(notebookId)
            if (notebook == null || notebook?.deletedAtMillis != null) {
                error = "Notebook is unavailable"
                notes = emptyList()
            } else {
                notes = noteRepository.listByNotebook(notebookId)
                error = null
            }
        } catch (exception: Exception) {
            error = exception.message ?: "Could not load notes"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(notebook?.name ?: "Notebook") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButton = {
            if (notebook != null && error == null) {
                FloatingActionButton(onClick = {
                    if (!creating) {
                        creating = true
                        scope.launch {
                            try {
                                val created = noteLifecycleRepository.createNote(notebookId = notebookId)
                                refresh++
                                error = null
                                onOpenNote(created.id)
                            } catch (exception: Exception) {
                                error = exception.message ?: "Could not create note"
                            } finally {
                                creating = false
                            }
                        }
                    }
                }) { Text(if (creating) "Creating…" else "New note") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Notes", style = MaterialTheme.typography.headlineSmall)
            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text("Retry") }
            }
            when {
                notes == null -> CircularProgressIndicator()
                notes!!.isEmpty() && error == null -> Text("No notes yet. Create your first note.")
                else -> notes.orEmpty().forEach { note ->
                    Card(
                        onClick = { onOpenNote(note.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(note.title.ifBlank { "Untitled Note" },
                                style = MaterialTheme.typography.titleMedium)
                            Text("Revision ${note.revision}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
