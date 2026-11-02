package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal enum class TrashTargetKind { FOLDER, NOTEBOOK, NOTE }

internal data class TrashRequest(
    val kind: TrashTargetKind,
    val id: String,
    val name: String,
)

/** Non-destructive deletion must be confirmed before calling the repository. */
@Composable
internal fun TrashConfirmationDialog(
    request: TrashRequest,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Move to Trash?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move ${request.kind.name.lowercase()} “${request.name}” to Trash? " +
                    "Its contents will be kept and can be restored later.")
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag("trash-confirm"),
            ) { Text(if (busy) "Moving…" else "Move to Trash") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy,
                modifier = Modifier.testTag("trash-cancel")) { Text("Cancel") }
        },
    )
}
