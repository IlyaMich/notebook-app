
package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookContentsScreen(
    notebookTitle: String,
    onBack: () -> Unit,
    onOpenNote: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(notebookTitle) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Notes",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                "Prototype contents — sample data only",
                style = MaterialTheme.typography.bodySmall
            )

            Card(
                onClick = onOpenNote,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Sample Note",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        "Open editor",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
