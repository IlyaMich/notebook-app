
package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.model.Notebook
import com.notebook.app.data.library.repository.LibraryTrashRepository
import com.notebook.app.data.library.repository.NoteLifecycleRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.data.library.repository.NotebookRepository
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** Repository-backed notebook screen. UI presentation never mutates note content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookContentsScreen(
    notebookId: String,
    notebookRepository: NotebookRepository,
    noteRepository: NoteRepository,
    noteLifecycleRepository: NoteLifecycleRepository,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    trashRepository: LibraryTrashRepository? = null,
    onLibraryChanged: () -> Unit = {},
    contentVersion: Int = 0,
) {
    var notebook by remember(notebookId) { mutableStateOf<Notebook?>(null) }
    var notes by remember(notebookId) { mutableStateOf<List<Note>?>(null) }
    var error by remember(notebookId) { mutableStateOf<String?>(null) }
    var creating by remember(notebookId) { mutableStateOf(false) }
    var refresh by remember(notebookId) { mutableIntStateOf(0) }
    var trashRequest by remember(notebookId) {
        mutableStateOf<TrashRequest?>(null)
    }
    var trashBusy by remember(notebookId) { mutableStateOf(false) }
    var trashError by remember(notebookId) { mutableStateOf<String?>(null) }

    // Presentation preference only, not canonical notebook data.
    var gridView by rememberSaveable(notebookId) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(notebookId, refresh, contentVersion) {
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

    trashRequest?.let { request ->
        TrashConfirmationDialog(
            request = request,
            busy = trashBusy,
            error = trashError,
            onCancel = {
                trashRequest = null
                trashError = null
            },
            onConfirm = {
                if (!trashBusy && trashRepository != null) {
                    trashBusy = true
                    trashError = null
                    scope.launch {
                        try {
                            trashRepository.trashNote(request.id)
                            trashRequest = null
                            refresh++
                            onLibraryChanged()
                        } catch (exception: Exception) {
                            trashError = exception.message
                                ?: "Move to Trash failed"
                        } finally {
                            trashBusy = false
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(notebook?.name ?: "Notebook") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (notebook != null && error == null) {
                FloatingActionButton(
                    onClick = {
                        if (!creating) {
                            creating = true
                            scope.launch {
                                try {
                                    val created = noteLifecycleRepository.createNote(
                                        notebookId = notebookId
                                    )
                                    refresh++
                                    error = null
                                    onOpenNote(created.id)
                                } catch (exception: Exception) {
                                    error = exception.message
                                        ?: "Could not create note"
                                } finally {
                                    creating = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("notebook-new-note"),
                ) {
                    Text(if (creating) "Creating…" else "New note")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (error == null && notes != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { gridView = false },
                        modifier = Modifier.testTag("notebook-view-list"),
                    ) {
                        Text(if (!gridView) "List ✓" else "List")
                    }

                    TextButton(
                        onClick = { gridView = true },
                        modifier = Modifier.testTag("notebook-view-grid"),
                    ) {
                        Text(if (gridView) "Grid ✓" else "Grid")
                    }
                }
            }

            when {
                error != null -> {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { refresh++ }) {
                        Text("Retry")
                    }
                }

                notes == null -> {
                    CircularProgressIndicator()
                }

                notes!!.isEmpty() -> {
                    Text("No notes yet. Create your first note.")
                }


                else -> {
                    val displayNotes = notes.orEmpty()

                    if (gridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 180.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("notebook-notes-grid"),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            gridItems(
                                items = displayNotes,
                                key = { note -> note.id },
                                contentType = { "note-card" },
                            ) { note ->
                                NotebookNoteCard(
                                    note = note,
                                    gridView = true,
                                    canTrash = trashRepository != null,
                                    onOpenNote = onOpenNote,
                                    onTrash = { trashRequest = it },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("notebook-notes-list"),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = displayNotes,
                                key = { note -> note.id },
                                contentType = { "note-card" },
                            ) { note ->
                                NotebookNoteCard(
                                    note = note,
                                    gridView = false,
                                    canTrash = trashRepository != null,
                                    onOpenNote = onOpenNote,
                                    onTrash = { trashRequest = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookNoteCard(
    note: Note,
    gridView: Boolean,
    canTrash: Boolean,
    onOpenNote: (String) -> Unit,
    onTrash: (TrashRequest) -> Unit,
) {
    val title = note.title.ifBlank { "Untitled Note" }

    // The local metadata model supplies timestamps, but not a thumbnail
    // or page count. Do not manufacture either value.
    val modifiedLabel = remember(note.updatedAtMillis) {
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
        ).format(Date(note.updatedAtMillis))
    }

    Card(
        onClick = { onOpenNote(note.id) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (gridView) 144.dp else 72.dp)
            .testTag("notebook-note-${note.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = "Modified $modifiedLabel",
                style = MaterialTheme.typography.bodySmall,
            )

            if (canTrash) {
                TextButton(
                    onClick = {
                        onTrash(
                            TrashRequest(
                                TrashTargetKind.NOTE,
                                note.id,
                                title,
                            )
                        )
                    },
                    modifier = Modifier.testTag(
                        "notebook-trash-note-${note.id}"
                    ),
                ) {
                    Text("Move to Trash")
                }
            }
        }
    }
}
