package com.notebook.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository

/** Read-only proof that the editor route resolves an actual, durable note and its pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String,
    noteRepository: NoteRepository,
    documentRepository: DocumentRepository,
    onBack: () -> Unit,
) {
    var note by remember(noteId) { mutableStateOf<Note?>(null) }
    var content by remember(noteId) { mutableStateOf<StoredNoteContent?>(null) }
    var error by remember(noteId) { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        try {
            note = noteRepository.findById(noteId)
                ?: throw IllegalStateException("Note no longer exists")
            content = documentRepository.load(noteId)
            noteRepository.recordOpened(noteId)
            error = null
        } catch (exception: Exception) {
            error = exception.message ?: "Failed to load note"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(note?.title?.ifBlank { "Untitled Note" } ?: "Note")
                        Text("Read-only storage preview — editing arrives in M2",
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Pen", "Pencil", "Highlighter", "Eraser", "Lasso", "Shapes", "Text", "Image")
                    .forEach { tool ->
                        Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("$tool (later)", modifier = Modifier.padding(12.dp))
                        }
                    }
            }
            when {
                error != null -> Text(error.orEmpty(),
                    modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                content == null -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                else -> {
                    val stored = content!!
                    Text("${stored.pages.size} persisted page(s) · revision ${stored.revision}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall)
                    // A single representative page: the real renderer and scroll/zoom arrive in M2.
                    val firstPage = stored.pages.firstOrNull()
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.padding(20.dp).widthIn(max = 580.dp)
                                    .fillMaxWidth()
                                    .background(Color.White),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text("Persisted document", color = Color.DarkGray)
                                    if (firstPage != null) {
                                        Text("Page ID: ${firstPage.id}", color = Color.DarkGray)
                                        Text("Size: ${firstPage.width} × ${firstPage.height} document units",
                                            color = Color.DarkGray)
                                        Text("Editable elements stored: ${firstPage.elements.size}",
                                            color = Color.DarkGray)
                                    }
                                    Text("Page and element rendering is not implemented here.",
                                        color = Color.DarkGray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
