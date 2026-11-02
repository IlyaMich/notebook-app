
package com.notebook.app.ui.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.notebook.app.EditorRoute
import com.notebook.app.NotebookRoute
import com.notebook.app.data.content.repository.DocumentRepository
import com.notebook.app.data.library.repository.NoteRepository
import com.notebook.app.ui.screens.NoteEditorScreen
import com.notebook.app.ui.theme.NotebookTheme

/**
 * Debug-only inputs. The instrumentation test owns
 * database creation, failure injection and cleanup.
 */
data class Nav3EditorTestConfig(
    val notebookId: String,
    val noteId: String,
    val noteRepository: NoteRepository,
    val documentRepository: DocumentRepository,
)

/**
 * Shared between the debug Activity and its instrumentation
 * test. State changes trigger Compose recomposition.
 */
object Nav3EditorTestBridge {
    var current by mutableStateOf<Nav3EditorTestConfig?>(null)
}

class Nav3EditorAutosaveTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NotebookTheme {
                val config = Nav3EditorTestBridge.current

                if (config == null) {
                    Text("Test fixture not ready")
                } else {
                    EditorNavigationTestHost(config)
                }
            }
        }
    }
}

/**
 * Same Navigation 3 entry decorators and editor
 * destination wiring as the production application.
 */
@Composable
private fun EditorNavigationTestHost(
    config: Nav3EditorTestConfig,
) {
    val backStack = rememberNavBackStack(
        NotebookRoute(config.notebookId),
        EditorRoute(config.noteId),
    )

    var backRequests by remember {
        mutableIntStateOf(0)
    }

    fun popIfPossible() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        onBack = {
            if (backStack.lastOrNull() is EditorRoute) {
                backRequests++
            } else {
                popIfPossible()
            }
        },
        entryProvider = entryProvider {
            entry<NotebookRoute> {
                Text("Notebook destination")
            }

            entry<EditorRoute> { route ->
                val editorViewModel:
                    NoteEditorSessionViewModel = viewModel()

                NoteEditorScreen(
                    noteId = route.noteId,
                    noteRepository = config.noteRepository,
                    documentRepository = config.documentRepository,
                    sessionViewModel = editorViewModel,
                    navigationBackRequests = backRequests,
                    onBack = {
                        if (backStack.lastOrNull() == route) {
                            popIfPossible()
                        }
                    },
                )
            }
        },
    )
}
