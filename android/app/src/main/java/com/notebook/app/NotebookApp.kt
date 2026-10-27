
package com.notebook.app

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.notebook.app.ui.screens.NotebookContentsScreen
import com.notebook.app.ui.screens.LibraryScreen
import com.notebook.app.ui.screens.NoteEditorScreen
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute : NavKey

@Serializable
data class NotebookRoute(
    val notebookId: String
) : NavKey

@Serializable
data class EditorRoute(
    val noteId: String
) : NavKey

@Composable
fun NotebookApp() {
    val backStack = rememberNavBackStack(LibraryRoute)

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { navigateBack() },
        entryProvider = entryProvider {

            entry<LibraryRoute> {
                LibraryScreen(
                    onOpenNotebook = { notebookId ->
                        backStack.add(
                            NotebookRoute(notebookId)
                        )
                    }
                )
            }

            entry<NotebookRoute> { route ->
                val title = when (route.notebookId) {
                    "quick-notes" -> "Quick Notes"
                    "mathematics" -> "Mathematics"
                    else -> "Notebook"
                }

                NotebookContentsScreen(
                    notebookTitle = title,
                    onBack = { navigateBack() },
                    onOpenNote = {
                        backStack.add(
                            EditorRoute("sample-note")
                        )
                    }
                )
            }

            entry<EditorRoute> { route ->
                NoteEditorScreen(
                    noteTitle = when (route.noteId) {
                        "sample-note" -> "Sample Note"
                        else -> "Untitled Note"
                    },
                    onBack = { navigateBack() }
                )
            }
        }
    )
}
