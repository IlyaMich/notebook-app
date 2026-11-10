
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
import com.notebook.app.ui.editor.NoteEditorPartialStrokeEraser
import com.notebook.app.ui.editor.PartialStrokeEraserGestureResult
import com.notebook.app.ui.editor.SavePhase
import com.notebook.app.ui.editor.SaveRetryResult
import kotlinx.coroutines.launch
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.notebook.app.ui.editor.viewport.ContinuousPageViewport
import com.notebook.app.ui.editor.viewport.toViewportSnapshot
import androidx.compose.runtime.saveable.rememberSaveable
import com.notebook.app.ui.editor.ink.EditorInkTool

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
    var previewWarning by remember(noteId) {
        mutableStateOf<String?>(null)
    }

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
    var fingerWritingPreview by rememberSaveable(noteId) {
        mutableStateOf(false)
    }
    var selectedTool by rememberSaveable(noteId) {
        mutableStateOf(EditorInkTool.PEN)
    }
    var penInputError by remember(noteId) {
        mutableStateOf<String?>(null)
    }
    var eraserInputError by remember(noteId) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(noteId) {
        // Essential initialization: these operations must succeed
        // before the editor can work with this document.
        try {
            val loadedNote = noteRepository.findById(noteId)
                ?: throw IllegalStateException("Note no longer exists")

            val loadedContent = documentRepository.load(noteId)

            val activeCoordinator =
                sessionViewModel.getOrCreateCoordinator(
                    noteId = noteId,
                    repository = documentRepository,
                    initialRevision = loadedContent.revision,
                )

            note = loadedNote
            content = loadedContent
            coordinator = activeCoordinator
            error = null

        } catch (exception: CancellationException) {
            throw exception

        } catch (exception: Exception) {
            error = exception.message ?: "Failed to load note"
            return@LaunchedEffect
        }

        // Nonessential metadata update.
        //
        // Failure here must not hide the editor, discard the
        // retained coordinator, or prevent autosave retry.
        try {
            noteRepository.recordOpened(noteId)

        } catch (exception: CancellationException) {
            throw exception

        } catch (exception: Exception) {
            Log.w(
                "NoteEditorScreen",
                "Could not update recently-opened metadata",
                exception,
            )
        }
    }


    val saveState = coordinator?.state?.collectAsState()?.value

    suspend fun reloadPreview() {
        try {
            val refreshed = documentRepository.load(noteId)
            content = refreshed

            val confirmedRevision =
                coordinator?.state?.value?.committedRevision

            previewWarning =
                if (
                    confirmedRevision != null &&
                    refreshed.revision != confirmedRevision
                ) {
                    "Preview revision ${refreshed.revision} " +
                        "does not match confirmed revision " +
                        "$confirmedRevision."
                } else {
                    null
                }

        } catch (exception: CancellationException) {
            throw exception

        } catch (exception: Exception) {
            previewWarning =
                "Could not refresh saved preview: " +
                    (exception.message ?: "Unknown error")
        }
    }


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
            reloadPreview()
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
                // ERASER is not yet a user-facing control: partial erase and
                // failure-recovery UI are still outstanding in EDT-16.
                EditorInkTool.entries.filter { it != EditorInkTool.ERASER }.forEach { tool ->
                    val label = when (tool) {
                        EditorInkTool.PEN -> "Pen"
                        EditorInkTool.PENCIL -> "Pencil"
                        EditorInkTool.HIGHLIGHTER -> "Highlighter"
                        EditorInkTool.ERASER -> "Eraser"
                    }

                    FilterChip(
                        selected = selectedTool == tool,
                        onClick = {
                            selectedTool = tool
                        },
                        label = {
                            Text(label)
                        },
                    )
                }

                // The remaining editor tools are not implemented yet.
                listOf(
                    "Eraser",
                    "Lasso",
                    "Shapes",
                    "Text",
                    "Image",
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
            TextButton(
                onClick = {
                    fingerWritingPreview = !fingerWritingPreview
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    if (fingerWritingPreview) {
                        "Finger Pen mode: ON (autosaved)"
                    } else {
                        "Enable finger Pen writing"
                    }
                )
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
                    val viewportSnapshot = remember(stored) {
                        stored.toViewportSnapshot()
                    }
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

                        if (penInputError != null) {
                            Text(
                                text = penInputError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (eraserInputError != null) {
                            Text(
                                text = eraserInputError.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (previewWarning != null) {
                            Text(
                                previewWarning.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                            )

                            TextButton(
                                onClick = {
                                    scope.launch {
                                        reloadPreview()
                                    }
                                },
                            ) {
                                Text("Reload preview")
                            }
                        }


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

                        val previewIsCurrent =
                            saveState?.phase == SavePhase.SAVED &&
                                saveState.pendingCommandCount == 0 &&
                                stored.revision == saveState.committedRevision

                        TextButton(
                            enabled = nextBackground != null && previewIsCurrent,
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

                        // Retain STO-06 diagnostic information
                        // while the editor rendering integration
                        // is being verified.
                        Text(
                            "Persisted document",
                            style = MaterialTheme.typography.labelMedium,
                        )

                        firstPage?.let { page ->
                            Text(
                                "Page ID: ${page.id}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Size: ${page.width} x ${page.height}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Editable elements stored: ${page.elements.size}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                "Background: ${page.backgroundJson}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                    }

                    // EDT-07: Render the same confirmed Room
                    // snapshot used by the existing editor.
                    //
                    // Zoom, pan and page selection remain
                    // transient. No drawing-input or document
                    // mutation is introduced here.

                    ContinuousPageViewport(
                        pages = viewportSnapshot.pages,
                        storedPages = viewportSnapshot.storedPages,
                        documentKey = noteId,
                        fingerWritingPreview = fingerWritingPreview,
                        selectedTool = selectedTool,
                        onPenStrokeCompleted = { pageId, envelopeJson ->
                            // Keep the existing 2C-2 autosave callback unchanged.
                            val activeCoordinator = coordinator

                            if (activeCoordinator == null) {
                                penInputError =
                                    "Cannot save stroke: editor session unavailable"
                            } else {
                                activeCoordinator.enqueue(
                                    DocumentEdit.InsertElements(
                                        pageId = pageId,
                                        envelopes = listOf(envelopeJson),
                                    )
                                )
                                penInputError = null
                            }
                        },
                        onPenStrokeError = { message ->
                            penInputError = message
                        },
                        // EDT-16 1E-6: completion-only PARTIAL eraser pipeline. This callback
                        // is unreachable from the regular toolbar until the
                        // eraser has passed failure/undo/partial-mode gates.
                        onEraserSweepCaptured = { pageId, pointsPageDu, radiusPageDu ->
                            val activeCoordinator = coordinator
                            val renderedAtRelease = content
                            if (activeCoordinator == null || renderedAtRelease == null) {
                                eraserInputError = "Eraser unavailable: editor session not ready"
                            } else {
                                // Freeze page coordinates and the visible note revision
                                // before launching any asynchronous work.
                                val finishedPoints = pointsPageDu.toList()
                                scope.launch {
                                    try {
                                        val result = NoteEditorPartialStrokeEraser(
                                            noteId = noteId,
                                            repository = documentRepository,
                                            coordinator = activeCoordinator,
                                        ).submitViewportSweep(
                                            renderedSnapshot = renderedAtRelease,
                                            pageId = pageId,
                                            pointsPageDu = finishedPoints,
                                            radiusPageDu = radiusPageDu,
                                        )
                                        eraserInputError = when (result) {
                                            is PartialStrokeEraserGestureResult.Committed,
                                            PartialStrokeEraserGestureResult.NoHit -> null
                                            is PartialStrokeEraserGestureResult.Blocked ->
                                                "Eraser blocked: ${result.reason}"
                                            is PartialStrokeEraserGestureResult.Rejected ->
                                                "Eraser not applied: ${result.reason}"
                                            is PartialStrokeEraserGestureResult.Unconfirmed ->
                                                "Eraser save outcome uncertain. Reload the note " +
                                                    "before performing more edits."
                                        }
                                        // Existing save-state observer reloads the Room
                                        // snapshot only after a confirmed revision.
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        eraserInputError =
                                            "Could not confirm eraser outcome. Reload the note " +
                                                "before performing more edits."
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}
