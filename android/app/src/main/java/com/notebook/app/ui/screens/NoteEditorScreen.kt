package com.notebook.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteTitle: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(noteTitle)
                        Text(
                            "Prototype — no persistence",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
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
        ) {
            // Fixed, non-interactive tool placeholders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tools = listOf(
                    "Pen",
                    "Pencil",
                    "Highlighter",
                    "Eraser",
                    "Lasso",
                    "Shapes",
                    "Text",
                    "Image"
                )

                tools.forEach { tool ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = tool,
                            modifier = Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 12.dp
                            )
                        )
                    }
                }
            }

            HorizontalDivider()

            // Scrollable canvas surround
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // A4 aspect ratio placeholder
                    Box(
                        modifier = Modifier
                            .padding(20.dp)
                            .widthIn(max = 580.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f / 1.414f)
                            .background(Color.White),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            "A4 page placeholder\n\n" +
                                "Editable drawing will be implemented in M2.",
                            modifier = Modifier.padding(24.dp),
                            color = Color.DarkGray
                        )
                    }
                }
            }
        }
    }
}
