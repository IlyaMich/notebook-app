package com.notebook.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.PageViewportItem

/** Read-only editor backed by the actual saved note and all its ordered pages. */
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
                        Text("Read-only page viewport — drawing arrives later",
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
                    ContinuousPageViewport(
                        documentKey = noteId,
                        pages = stored.pages.map { page ->
                            PageViewportItem(
                                id = page.id,
                                widthDu = page.width.toDouble(),
                                heightDu = page.height.toDouble(),
                                elementCount = page.elements.size,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
