package com.notebook.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.notebook.app.data.assets.ExportNoteHistoryReport
import com.notebook.app.data.assets.ExportNoteHistoryState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

/**
 * D2J-B: read-only historic evidence, not an export action or completion UI.
 * Does not open a document picker, inspect external URI, retry, release or GC.
 */
@Composable
internal fun ExportNoteHistoryDialog(
    noteId: String,
    readHistory: suspend (String) -> ExportNoteHistoryReport,
    onDismiss: () -> Unit,
) {
    var report by remember(noteId) { mutableStateOf<ExportNoteHistoryReport?>(null) }
    LaunchedEffect(noteId, readHistory) {
        report = null
        report = try {
            readHistory(noteId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ExportNoteHistoryReport.IncompleteUntrusted
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export attempt history") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "This is an app-local record, not proof that a .notebook file is " +
                        "durable, available in cloud storage, or safe after app removal. " +
                        "No automatic retry or cleanup is performed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                when (val current = report) {
                    null -> CircularProgressIndicator()
                    ExportNoteHistoryReport.IncompleteUntrusted -> Text(
                        "History cannot be classified safely. Existing export holds " +
                            "and files remain protected.",
                    )
                    is ExportNoteHistoryReport.ObservedUntrusted -> {
                        if (current.entries.isEmpty()) {
                            Text(
                                "No locally recorded export operations for this note. " +
                                    "This does not prove that no external files exist.",
                            )
                        } else {
                            current.entries.forEach { item ->
                                HorizontalDivider()
                                Text(
                                    DateFormat.getDateTimeInstance(
                                        DateFormat.MEDIUM, DateFormat.SHORT,
                                    ).format(Date(item.createdAtMillis)),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    when (item.state) {
                                        ExportNoteHistoryState.NO_ATTEMPT_RECORDED_UNTRUSTED ->
                                            "No provider attempt recorded. Private stage not certified by this view."
                                        ExportNoteHistoryState.ATTEMPT_PENDING_UNTRUSTED ->
                                            "Provider write outcome unknown; never automatically retry this operation."
                                        ExportNoteHistoryState.READBACK_OBSERVED_UNTRUSTED ->
                                            "Matching bytes once read back. External durability not verified."
                                        ExportNoteHistoryState.INCOMPLETE_UNTRUSTED ->
                                            "Missing or conflicting evidence; retain all protected assets."
                                    },
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Text(
                                    "Operation ${item.operationId.take(8)}… (untrusted)",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
