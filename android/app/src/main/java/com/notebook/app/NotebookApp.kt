
package com.notebook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.ui.editor.NoteEditorSessionViewModel
import com.notebook.app.ui.screens.LibraryHierarchySidebar
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NoteEditorScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import kotlinx.serialization.Serializable
import com.notebook.app.ui.screens.LibraryWindowLayout
import com.notebook.app.ui.screens.libraryWindowLayout

@Serializable
data object LibraryRoute : NavKey

/** Each folder lives on the back stack, preserving its parent context. */
@Serializable
data class FolderRoute(val folderId: String) : NavKey

@Serializable
data class NotebookRoute(val notebookId: String) : NavKey

@Serializable
data class EditorRoute(val noteId: String) : NavKey

@Composable
fun NotebookApp(
    repositoryOverride: LibraryRepositoryFactory? = null,
) {
    val context = LocalContext.current.applicationContext

    // Production uses its existing database. Tests may supply an
    // isolated factory while exercising the real navigation host.
    val repositories = remember(context, repositoryOverride) {
        repositoryOverride ?: LibraryRepositoryFactory.from(context)
    }
    val lifecycle = remember(repositories) {
        repositories.noteLifecycle()
    }

    val backStack = rememberNavBackStack(LibraryRoute)

    var editorBackRequests by remember {
        mutableIntStateOf(0)
    }

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    NavDisplay(
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        backStack = backStack,
        onBack = {
            if (backStack.lastOrNull() is EditorRoute) {
                // The editor decides when it is safe to leave.
                editorBackRequests++
            } else {
                navigateBack()
            }
        },
        entryProvider = entryProvider {

            entry<LibraryRoute> {
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = null,
                    onOpenFolder = { folderId ->
                        backStack.add(FolderRoute(folderId))
                    },
                    onOpenNotebook = { notebookId ->
                        backStack.add(NotebookRoute(notebookId))
                    },
                )
            }

            entry<FolderRoute> { route ->
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = route.folderId,
                    onBack = {
                        navigateBack()
                    },
                    onOpenFolder = { folderId ->
                        backStack.add(FolderRoute(folderId))
                    },
                    onOpenNotebook = { notebookId ->
                        backStack.add(NotebookRoute(notebookId))
                    },
                )
            }

            entry<NotebookRoute> { route ->
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (
                        libraryWindowLayout(maxWidth, maxHeight) ==
                        LibraryWindowLayout.EXPANDED
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Surface(
                                modifier = Modifier
                                    .width(220.dp)
                                    .fillMaxHeight(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                LibraryHierarchySidebar(
                                    folderRepository = repositories.folders,
                                    notebookRepository = repositories.notebooks,
                                    activeFolderId = null,
                                    activeNotebookId = route.notebookId,
                                    onOpenFolder = { folderId ->
                                        backStack.add(FolderRoute(folderId))
                                    },
                                    onOpenNotebook = { notebookId ->
                                        if (notebookId != route.notebookId) {
                                            // Switch notebook without adding a
                                            // separate Back entry for every
                                            // sidebar selection.
                                            backStack[backStack.lastIndex] =
                                                NotebookRoute(notebookId)
                                        }
                                    },
                                )
                            }

                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                                NotebookContentsScreen(
                                    notebookId = route.notebookId,
                                    notebookRepository = repositories.notebooks,
                                    noteRepository = repositories.notes,
                                    noteLifecycleRepository = lifecycle,
                                    onBack = {
                                        navigateBack()
                                    },
                                    onOpenNote = { noteId ->
                                        backStack.add(EditorRoute(noteId))
                                    },
                                )
                            }
                        }
                    } else {
                        NotebookContentsScreen(
                            notebookId = route.notebookId,
                            notebookRepository = repositories.notebooks,
                            noteRepository = repositories.notes,
                            noteLifecycleRepository = lifecycle,
                            onBack = {
                                navigateBack()
                            },
                            onOpenNote = { noteId ->
                                backStack.add(EditorRoute(noteId))
                            },
                        )
                    }
                }
            }

            entry<EditorRoute> { route ->
                val editorViewModel: NoteEditorSessionViewModel =
                    viewModel()

                NoteEditorScreen(
                    noteId = route.noteId,
                    noteRepository = repositories.notes,
                    documentRepository = repositories.documents,
                    sessionViewModel = editorViewModel,
                    navigationBackRequests = editorBackRequests,
                    onBack = {
                        // Never pop another destination accidentally.
                        if (backStack.lastOrNull() == route) {
                            navigateBack()
                        }
                    },
                )
            }
        },
    )
}
