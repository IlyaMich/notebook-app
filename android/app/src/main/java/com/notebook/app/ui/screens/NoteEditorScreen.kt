
package com.notebook.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.notebook.app.data.content.model.DocumentEdit
import com.notebook.app.data.content.model.StoredNoteContent
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.model.Note
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.ui.editor.NoteAutosaveCoordinator
import com.notebook.app.ui.editor.SavePhase
import com.notebook.app.ui.editor.SaveRetryResult
import kotlinx.coroutines.launch
import com.notebook.app.ui.editor.NoteEditorSessionViewModel

/**
 * STO-06 vertical slice.
 *
 * Provides a test document-edit action with real Room persistence.
 * Full document rendering and handwriting arrive in M2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: String,
    noteRepository: NoteRepository,
    documentRepository: DocumentRepository,
    sessionViewModel: NoteEditorSessionViewModel,
    navigationBackRequests: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var note by remember(noteId) { mutableStateOf<Note?>(null) }
    var content by remember(noteId) {
        mutableStateOf<StoredNoteContent?>(null)
    }
    var error by remember(noteId) { mutableStateOf<String?>(null) }

    var coordinator by remember(noteId) {
        mutableStateOf<NoteAutosaveCoordinator?>(null)
    }

    var navigationWarning by remember(noteId) {
        mutableStateOf<String?>(null)
    }
    var retryWarning by remember(noteId) {
        mutableStateOf<String?>(null)
    }
    var showLeaveConfirmation by remember(noteId) {
        mutableStateOf(false)
    }

    LaunchedEffect(noteId) {
        try {
            val loadedNote = noteRepository.findById(noteId)
                ?: throw IllegalStateException("Note no longer exists")

            val loadedContent = documentRepository.load(noteId)

            note = loadedNote
            content = loadedContent

            coordinator = sessionViewModel.getOrCreateCoordinator(
                noteId = noteId,
                repository = documentRepository,
                initialRevision = loadedContent.revision,
            )

            noteRepository.recordOpened(noteId)
            error = null
        } catch (exception: Exception) {
            error = exception.message ?: "Failed to load note"
        }
    }

    val saveState = coordinator?.state?.collectAsState()?.value

    // Refresh the preview after the confirmed committed revision changes.
    LaunchedEffect(
        noteId,
        saveState?.phase,
        saveState?.committedRevision,
    ) {
        if (
            saveState?.phase == SavePhase.SAVED &&
            content != null &&
            content?.revision != saveState.committedRevision
        ) {
            try {
                content = documentRepository.load(noteId)
                error = null
            } catch (exception: Exception) {
                error = exception.message
                    ?: "Saved, but failed to reload document"
            }
        }

        if (saveState?.phase == SavePhase.SAVED) {
            navigationWarning = null
            retryWarning = null
        }
    }

    fun handleBack() {
        val activeCoordinator =
            coordinator ?: sessionViewModel.existingCoordinator(noteId)

        when (activeCoordinator?.state?.value?.phase) {
            SavePhase.PENDING, SavePhase.SAVING -> {
                navigationWarning =
                    "A save is still pending. Wait for confirmation."
            }
            SavePhase.FAILED -> {
                showLeaveConfirmation = true
            }
            else -> onBack()
        }
    }


    var handledBackRequests by remember(noteId) {
        mutableIntStateOf(navigationBackRequests)
    }

    LaunchedEffect(navigationBackRequests) {
        if (navigationBackRequests != handledBackRequests) {
            handledBackRequests = navigationBackRequests
            handleBack()
        }
    }


    // Protect the system Back gesture as well as the toolbar button.
    BackHandler { handleBack() }

    if (showLeaveConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showLeaveConfirmation = false
            },
            title = { Text("Unconfirmed edits") },
            text = {
                Text(
                    "Some edits have not been confirmed as saved. " +
                        "Leaving now abandons the in-memory retry queue. " +
                        "The last confirmed database revision is preserved."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmation = false
                        onBack()
                    }
                ) {
                    Text("Leave without retrying")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirmation = false
                    }
                ) {
                    Text("Stay")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            note?.title?.ifBlank { "Untitled Note" }
                                ?: "Note"
                        )
                        Text(
                            "Local document editor prototype",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { handleBack() }) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Pen", "Pencil", "Highlighter",
                    "Eraser", "Lasso", "Shapes", "Text", "Image",
                ).forEach { tool ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "$tool (later)",
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            when {
                error != null -> {
                    Text(
                        error.orEmpty(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                content == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp)
                    )
                }

                else -> {
                    val stored = content!!
                    val firstPage = stored.pages.firstOrNull()

                    val statusText = when (saveState?.phase) {
                        SavePhase.PENDING -> "Pending save"
                        SavePhase.SAVING -> "Saving..."
                        SavePhase.SAVED -> "Saved locally"
                        SavePhase.FAILED -> "Save failed"
                        null -> "Preparing save status"
                    }

                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "$statusText - revision " +
                                "${saveState?.committedRevision ?: stored.revision}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (saveState?.phase == SavePhase.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )

                        if (saveState?.phase == SavePhase.FAILED) {
                            Text(
                                saveState.failureMessage
                                    ?: "Unable to confirm save",
                                color = MaterialTheme.colorScheme.error,
                            )

                            TextButton(
                                onClick = {
                                    val active = coordinator
                                        ?: return@TextButton

                                    scope.launch {
                                        when (active.retry()) {
                                            SaveRetryResult.STARTED -> {
                                                retryWarning = null
                                            }
                                            SaveRetryResult.RECONCILIATION_REQUIRED -> {
                                                retryWarning =
                                                    "Cannot safely retry automatically. " +
                                                        "Database reconciliation is required."
                                            }
                                            SaveRetryResult.NOT_NEEDED -> Unit
                                        }
                                    }
                                },
                            ) {
                                Text("Retry save")
                            }
                        }

                        if (navigationWarning != null) {
                            Text(navigationWarning.orEmpty())
                        }

                        if (retryWarning != null) {
                            Text(
                                retryWarning.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Text(
                            "${stored.pages.size} persisted page(s)",
                            style = MaterialTheme.typography.bodySmall,
                        )

                        // A safe, limited editing action for STO-06.
                        // Only recognize the two demo blank-page formats.
                        val white =
                            """{"type":"blank","color":"#FFFFFF"}"""
                        val warm =
                            """{"type":"blank","color":"#FFF9E9"}"""

                        val nextBackground = when (
                            firstPage?.backgroundJson
                        ) {
                            white -> warm
                            warm -> white
                            else -> null
                        }

                        TextButton(
                            enabled = (
                                nextBackground != null &&
                                    saveState?.phase == SavePhase.SAVED
                                ),
                            onClick = {
                                val page = firstPage
                                    ?: return@TextButton
                                val next = nextBackground
                                    ?: return@TextButton

                                coordinator?.enqueue(
                                    DocumentEdit.ChangeBackground(
                                        pageId = page.id,
                                        backgroundJson = next,
                                    )
                                )
                            },
                        ) {
                            Text("Test edit: change blank-page color")
                        }

                        Text(
                            "Test action modifies real note data. " +
                                "Use a disposable note.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .widthIn(max = 580.dp)
                                    .fillMaxWidth()
                                    .background(
                                        if (firstPage?.backgroundJson ==
                                            """{"type":"blank","color":"#FFF9E9"}"""
                                        ) {
                                            Color(0xFFFFF9E9)
                                        } else {
                                            Color.White
                                        }
                                    ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        "Persisted document",
                                        color = Color.DarkGray,
                                    )

                                    if (firstPage != null) {
                                        Text(
                                            "Page ID: ${firstPage.id}",
                                            color = Color.DarkGray,
                                        )
                                        Text(
                                            "Size: ${firstPage.width} x " +
                                                "${firstPage.height}",
                                            color = Color.DarkGray,
                                        )
                                        Text(
                                            "Editable elements stored: " +
                                                "${firstPage.elements.size}",
                                            color = Color.DarkGray,
                                        )
                                        Text(
                                            "Background: " +
                                                firstPage.backgroundJson,
                                            color = Color.DarkGray,
                                        )
                                    }

                                    Text(
                                        "Full page rendering arrives in M2.",
                                        color = Color.DarkGray,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
