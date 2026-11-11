
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
import com.notebook.app.ui.editor.EraserSessionCommitResult
import com.notebook.app.ui.editor.NoteEditorEraserToolbarPolicy
import com.notebook.app.ui.editor.NoteEditorEraserMode
import com.notebook.app.ui.editor.NoteEditorWholeStrokeEraser
import com.notebook.app.ui.editor.WholeStrokeEraserGestureResult
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
import com.notebook.app.ui.editor.NoteEditorShapeCommands
import com.notebook.app.ui.editor.ShapeCommandResult
import com.notebook.app.ui.editor.shapes.EditorShapeTool
import com.notebook.app.ui.editor.shapes.EditorShapeSelection
import com.notebook.app.ui.editor.shapes.CompletedShapeEdit
import com.notebook.app.ui.editor.shapes.ShapeEditingGeometry
import com.notebook.app.ui.editor.shapes.ShapeStyleControls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var selectedShapeTool by rememberSaveable(noteId) {
        mutableStateOf<EditorShapeTool?>(null)
    }
    var shapeInputError by remember(noteId) { mutableStateOf<String?>(null) }
    var shapeEditMode by rememberSaveable(noteId) { mutableStateOf(false) }
    var selectedShape by remember(noteId) { mutableStateOf<EditorShapeSelection?>(null) }
    var showShapeStyle by remember(noteId) { mutableStateOf(false) }
    var eraserMode by rememberSaveable(noteId) {
        mutableStateOf(NoteEditorEraserMode.PARTIAL)
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
    val eraserHistoryState = coordinator?.eraserHistory?.collectAsState()?.value
    val eraserUiReady = NoteEditorEraserToolbarPolicy.canErase(
        saveState, content?.revision,
    )

    val shapeHistoryState = coordinator?.shapeHistory?.collectAsState()?.value
    val shapeUiReady = saveState?.phase == SavePhase.SAVED &&
        saveState.pendingCommandCount == 0 && content?.revision == saveState.committedRevision &&
        saveState.committedRevision < Long.MAX_VALUE

    fun submitShapeEdit(completed: CompletedShapeEdit) {
        val active = coordinator
        val rendered = content
        if (active == null || rendered == null || rendered.revision != completed.expectedRevision) {
            shapeInputError = "Shape edit not applied: preview changed before completion"
            return
        }
        scope.launch {
            try {
                val page = rendered.pages.singleOrNull { it.id == completed.pageId }
                val envelope = withContext(Dispatchers.Default) {
                    requireNotNull(page) { "Shape page is unavailable" }
                    require(ShapeEditingGeometry.contained(completed.candidate, page.width, page.height)) {
                        "Bring the entire painted shape inside its page before applying this edit"
                    }
                    completed.envelope()
                }
                shapeInputError = when (val result = NoteEditorShapeCommands(
                    noteId, documentRepository, active,
                ).replace(rendered, completed.pageId, envelope)) {
                    is ShapeCommandResult.Committed, ShapeCommandResult.NoChange -> null
                    is ShapeCommandResult.Rejected -> "Shape edit not applied: ${result.reason}"
                    is ShapeCommandResult.Unconfirmed ->
                        "Shape save outcome uncertain. Reload the note before performing more edits."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                shapeInputError = "Shape edit not applied: ${invalid.message}"
            } catch (_: Exception) {
                shapeInputError = "Could not confirm shape save outcome. Reload the note before performing more edits."
            }
        }
    }

    fun performShapeHistoryAction(undo: Boolean) {
        val active = coordinator ?: return
        if (!shapeUiReady || !(if (undo) active.shapeHistory.value.canUndo
                else active.shapeHistory.value.canRedo)) return
        scope.launch {
            try {
                val result = if (undo) active.undoShapeEdit().await() else active.redoShapeEdit().await()
                shapeInputError = when (result) {
                    is EraserSessionCommitResult.Committed -> null
                    is EraserSessionCommitResult.Rejected -> "Shape history not applied: ${result.reason}"
                    is EraserSessionCommitResult.Unconfirmed ->
                        "Shape history save outcome uncertain. Reload the note before performing more edits."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                shapeInputError = "Could not confirm shape history outcome. Reload the note before performing more edits."
            }
        }
    }

    fun performEraserHistoryAction(undo: Boolean) {
        val active = coordinator ?: return
        val isEligible = if (undo) {
            NoteEditorEraserToolbarPolicy.canUndo(
                active.state.value, content?.revision, active.eraserHistory.value,
            )
        } else {
            NoteEditorEraserToolbarPolicy.canRedo(
                active.state.value, content?.revision, active.eraserHistory.value,
            )
        }
        if (!isEligible) {
            eraserInputError = "Eraser history unavailable until the preview is current and saved"
            return
        }
        scope.launch {
            try {
                val outcome = if (undo) {
                    active.undoEraserEdit().await()
                } else {
                    active.redoEraserEdit().await()
                }
                eraserInputError = when (outcome) {
                    is EraserSessionCommitResult.Committed -> null
                    is EraserSessionCommitResult.Rejected ->
                        "Eraser history not applied: ${outcome.reason}"
                    is EraserSessionCommitResult.Unconfirmed ->
                        "Eraser history save outcome uncertain. Reload the note " +
                            "before performing more edits."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                eraserInputError =
                    "Could not confirm eraser history outcome. Reload the note " +
                        "before performing more edits."
            }
        }
    }

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

    if (showShapeStyle && selectedShape != null && selectedShapeTool != null && shapeEditMode) {
        val selection = requireNotNull(selectedShape)
        AlertDialog(
            onDismissRequest = { showShapeStyle = false },
            title = { Text("Shape style") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (shapeUiReady) "Changes save automatically." else "Waiting for saved preview...")
                    ShapeStyleControls(selection,
                        enabled = shapeUiReady && selection.expectedRevision == content?.revision,
                        onEdit = ::submitShapeEdit)
                    shapeInputError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { showShapeStyle = false }) { Text("Done") } },
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
                // EDT-16 1E-7A: partial-stroke erase is the default eraser mode.
                // The coordinator and Room still enforce admission and revision guards.
                EditorInkTool.entries.forEach { tool ->
                    val label = when (tool) {
                        EditorInkTool.PEN -> "Pen"
                        EditorInkTool.PENCIL -> "Pencil"
                        EditorInkTool.HIGHLIGHTER -> "Highlighter"
                        EditorInkTool.ERASER -> "Eraser"
                    }

                    FilterChip(
                        selected = selectedShapeTool == null && selectedTool == tool,
                        enabled = tool != EditorInkTool.ERASER || eraserUiReady,
                        onClick = {
                            selectedShapeTool = null
                            selectedShape = null
                            showShapeStyle = false
                            selectedTool = tool
                        },
                        label = {
                            Text(label)
                        },
                    )
                }

                FilterChip(
                    selected = selectedShapeTool != null,
                    enabled = shapeUiReady,
                    onClick = { selectedShapeTool = selectedShapeTool ?: EditorShapeTool.LINE },
                    label = { Text("Shapes") },
                )

                // The remaining editor tools are not implemented yet.
                listOf(
                    "Lasso",
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
            if (selectedShapeTool != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorShapeTool.entries.forEach { tool ->
                        FilterChip(
                            selected = selectedShapeTool == tool && !shapeEditMode,
                            enabled = shapeUiReady,
                            onClick = {
                                shapeEditMode = false
                                showShapeStyle = false
                                selectedShapeTool = tool
                            },
                            label = { Text(tool.label) },
                        )
                    }
                    FilterChip(
                        selected = shapeEditMode,
                        enabled = shapeUiReady,
                        onClick = { shapeEditMode = true },
                        label = { Text("Edit shapes") },
                    )
                }
            }
            TextButton(
                onClick = {
                    fingerWritingPreview = !fingerWritingPreview
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    if (selectedShapeTool != null) {
                        if (shapeEditMode) {
                            if (fingerWritingPreview) "Finger shape editing: ON"
                            else "Enable finger shape editing"
                        } else {
                            if (fingerWritingPreview) "Finger shape drawing: ON"
                            else "Enable finger shape drawing"
                        }
                    } else if (fingerWritingPreview) {
                        if (selectedTool == EditorInkTool.ERASER) {
                            "Finger eraser mode: ON"
                        } else {
                            "Finger Pen mode: ON (autosaved)"
                        }
                    } else {
                        if (selectedTool == EditorInkTool.ERASER) {
                            "Enable finger erasing"
                        } else {
                            "Enable finger Pen writing"
                        }
                    }
                )
            }

            if (selectedShapeTool == null && selectedTool == EditorInkTool.ERASER) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NoteEditorEraserMode.entries.forEach { mode ->
                        FilterChip(
                            selected = eraserMode == mode,
                            enabled = eraserUiReady,
                            onClick = { eraserMode = mode },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    "${eraserMode.label} selected. Stylus erases; enable finger input " +
                        "to erase with touch. Changes can be undone while this " +
                        "editing session remains open.",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selectedShapeTool != null) {
                    TextButton(
                        enabled = shapeUiReady && shapeHistoryState?.canUndo == true,
                        onClick = { performShapeHistoryAction(undo = true) },
                    ) { Text("Undo shape") }
                    TextButton(
                        enabled = shapeUiReady && shapeHistoryState?.canRedo == true,
                        onClick = { performShapeHistoryAction(undo = false) },
                    ) { Text("Redo shape") }
                    if (shapeEditMode) {
                        TextButton(
                            enabled = shapeUiReady && selectedShape?.expectedRevision == content?.revision,
                            onClick = { showShapeStyle = true },
                        ) { Text("Shape style") }
                    }
                } else {
                    TextButton(
                        enabled = NoteEditorEraserToolbarPolicy.canUndo(
                            saveState, content?.revision, eraserHistoryState,
                        ),
                        onClick = { performEraserHistoryAction(undo = true) },
                    ) { Text("Undo eraser") }
                    TextButton(
                        enabled = NoteEditorEraserToolbarPolicy.canRedo(
                            saveState, content?.revision, eraserHistoryState,
                        ),
                        onClick = { performEraserHistoryAction(undo = false) },
                    ) { Text("Redo eraser") }
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

                        if (shapeInputError != null) {
                            Text(
                                text = shapeInputError.orEmpty(),
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
                        eraserModeKey = eraserMode.name,
                        shapeTool = selectedShapeTool,
                        shapeEditing = shapeEditMode,
                        shapeInputBlocked = showShapeStyle,
                        onShapeSelectionChanged = {
                            selectedShape = it
                            if (it == null) showShapeStyle = false
                        },
                        onShapeEdited = ::submitShapeEdit,
                        shapeInputRevision = content?.revision.takeIf { shapeUiReady },
                        onShapeInputError = { shapeInputError = it },
                        onShapeCreated = { completed ->
                            val active = coordinator
                            val rendered = content
                            if (active == null || rendered == null ||
                                rendered.revision != completed.expectedRevision) {
                                shapeInputError = "Shape not applied: preview changed before completion"
                            } else {
                                scope.launch {
                                    try {
                                        val envelope = withContext(Dispatchers.Default) { completed.envelope() }
                                        shapeInputError = when (val result = NoteEditorShapeCommands(
                                            noteId, documentRepository, active,
                                        ).insert(rendered, completed.pageId, envelope)) {
                                            is ShapeCommandResult.Committed, ShapeCommandResult.NoChange -> null
                                            is ShapeCommandResult.Rejected -> "Shape not applied: ${result.reason}"
                                            is ShapeCommandResult.Unconfirmed ->
                                                "Shape save outcome uncertain. Reload the note before performing more edits."
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        shapeInputError = "Could not confirm shape save outcome. Reload the note before performing more edits."
                                    }
                                }
                            }
                        },
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
                        // EDT-16 1E-7A: the toolbar now exposes PARTIAL erasing.
                        // The service and coordinator still reject stale/unsafe actions.
                        onEraserSweepCaptured = { pageId, pointsPageDu, radiusPageDu ->
                            val activeCoordinator = coordinator
                            val renderedAtRelease = content
                            val modeAtRelease = eraserMode
                            if (activeCoordinator == null || renderedAtRelease == null) {
                                eraserInputError = "Eraser unavailable: editor session not ready"
                            } else {
                                // Freeze page coordinates and the visible note revision
                                // before launching any asynchronous work.
                                val finishedPoints = pointsPageDu.toList()
                                scope.launch {
                                    try {
                                        eraserInputError = when (modeAtRelease) {
                                            NoteEditorEraserMode.PARTIAL -> {
                                                when (val result = NoteEditorPartialStrokeEraser(
                                                    noteId = noteId,
                                                    repository = documentRepository,
                                                    coordinator = activeCoordinator,
                                                ).submitViewportSweep(
                                                    renderedSnapshot = renderedAtRelease,
                                                    pageId = pageId,
                                                    pointsPageDu = finishedPoints,
                                                    radiusPageDu = radiusPageDu,
                                                )) {
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
                                            }
                                            NoteEditorEraserMode.WHOLE_STROKE -> {
                                                when (val result = NoteEditorWholeStrokeEraser(
                                                    noteId = noteId,
                                                    repository = documentRepository,
                                                    coordinator = activeCoordinator,
                                                ).submitViewportSweep(
                                                    renderedSnapshot = renderedAtRelease,
                                                    pageId = pageId,
                                                    pointsPageDu = finishedPoints,
                                                    radiusPageDu = radiusPageDu,
                                                )) {
                                                    is WholeStrokeEraserGestureResult.Committed,
                                                    WholeStrokeEraserGestureResult.NoHit,
                                                    WholeStrokeEraserGestureResult.Cancelled -> null
                                                    is WholeStrokeEraserGestureResult.Blocked ->
                                                        "Eraser blocked: ${result.reason}"
                                                    is WholeStrokeEraserGestureResult.Rejected ->
                                                        "Eraser not applied: ${result.reason}"
                                                    is WholeStrokeEraserGestureResult.Unconfirmed ->
                                                        "Eraser save outcome uncertain. Reload the note " +
                                                            "before performing more edits."
                                                }
                                            }
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
