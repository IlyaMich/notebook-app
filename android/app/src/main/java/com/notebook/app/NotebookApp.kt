package com.notebook.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.notebook.app.data.library.impl.LibraryRepositoryFactory
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NoteEditorScreen
import com.notebook.app.ui.screens.NotebookContentsScreen
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute : NavKey

/** Each folder lives on the back stack, so Back returns to the exact parent context. */
@Serializable
data class FolderRoute(val folderId: String) : NavKey

@Serializable
data class NotebookRoute(val notebookId: String) : NavKey

@Serializable
data class EditorRoute(val noteId: String) : NavKey

@Composable
fun NotebookApp() {
    val context = LocalContext.current.applicationContext
    // All routes use repositories backed by the same production AppDatabase.
    val repositories = remember(context) { LibraryRepositoryFactory.from(context) }
    val lifecycle = remember(repositories) { repositories.noteLifecycle() }
    val backStack = rememberNavBackStack(LibraryRoute)

    fun navigateBack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigateBack() },
        entryProvider = entryProvider {
            entry<LibraryRoute> {
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = null,
                    onOpenFolder = { folderId -> backStack.add(FolderRoute(folderId)) },
                    onOpenNotebook = { notebookId -> backStack.add(NotebookRoute(notebookId)) },
                )
            }

            entry<FolderRoute> { route ->
                LibraryScreen(
                    folderRepository = repositories.folders,
                    notebookRepository = repositories.notebooks,
                    parentFolderId = route.folderId,
                    onBack = { navigateBack() },
                    onOpenFolder = { folderId -> backStack.add(FolderRoute(folderId)) },
                    onOpenNotebook = { notebookId -> backStack.add(NotebookRoute(notebookId)) },
                )
            }

            entry<NotebookRoute> { route ->
                NotebookContentsScreen(
                    notebookId = route.notebookId,
                    notebookRepository = repositories.notebooks,
                    noteRepository = repositories.notes,
                    noteLifecycleRepository = lifecycle,
                    onBack = { navigateBack() },
                    onOpenNote = { noteId -> backStack.add(EditorRoute(noteId)) },
                )
            }

            entry<EditorRoute> { route ->
                NoteEditorScreen(
                    noteId = route.noteId,
                    noteRepository = repositories.notes,
                    documentRepository = repositories.documents,
                    onBack = { navigateBack() },
                )
            }
        },
    )
}
