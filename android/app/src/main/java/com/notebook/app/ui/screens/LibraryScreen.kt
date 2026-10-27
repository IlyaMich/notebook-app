
package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenNotebook: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notebook Library") }
            )
        }
    ) { padding ->

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (maxWidth >= 840.dp) {
                Row {
                    Surface(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "Library",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("All Notes")
                            Text("Recent")
                            Text("Folders")
                            Text("Trash")
                        }
                    }

                    NotebookList(
                        modifier = Modifier.weight(1f),
                        onOpenNotebook = onOpenNotebook
                    )
                }
            } else {
                NotebookList(
                    modifier = Modifier.fillMaxSize(),
                    onOpenNotebook = onOpenNotebook
                )
            }
        }
    }
}

@Composable
private fun NotebookList(
    modifier: Modifier,
    onOpenNotebook: (String) -> Unit
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "My Notebooks",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Prototype library — sample data only",
            style = MaterialTheme.typography.bodySmall
        )

        NotebookCard(
            title = "Quick Notes",
            onClick = { onOpenNotebook("quick-notes") }
        )

        NotebookCard(
            title = "Mathematics",
            onClick = { onOpenNotebook("mathematics") }
        )
    }
}

@Composable
private fun NotebookCard(
    title: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Open notebook",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
