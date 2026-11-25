package com.notebook.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notebook.app.data.assets.ExportNoteHistoryEntry
import com.notebook.app.data.assets.ExportNoteHistoryReport
import com.notebook.app.data.assets.ExportNoteHistoryState
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure opt-in read-only UI. No production Room, files, Android picker or export writes. */
@RunWith(AndroidJUnit4::class)
class ExportNoteHistoryDialogAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val noteId = UUID.randomUUID().toString()
    private val operation = UUID.randomUUID().toString()

    private fun show(state: ExportNoteHistoryState) {
        compose.setContent {
            MaterialTheme {
                ExportNoteHistoryDialog(
                    noteId,
                    readHistory = {
                        ExportNoteHistoryReport.ObservedUntrusted(listOf(
                            ExportNoteHistoryEntry(operation, 20_000L, state),
                        ))
                    },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Export attempt history").assertExists()
        compose.onNodeWithText("Close").assertExists()
    }

    @Test fun pendingProviderAttemptExplainsNoAutomaticRetry() {
        show(ExportNoteHistoryState.ATTEMPT_PENDING_UNTRUSTED)
        compose.onNodeWithText(
            "Provider write outcome unknown; never automatically retry this operation.",
        ).assertExists()
        compose.onNodeWithText("Start export").assertDoesNotExist()
    }

    @Test fun matchingReadbackDoesNotClaimExternalDurability() {
        show(ExportNoteHistoryState.READBACK_OBSERVED_UNTRUSTED)
        compose.onNodeWithText(
            "Matching bytes once read back. External durability not verified.",
        ).assertExists()
        compose.onNodeWithText("Export complete").assertDoesNotExist()
    }

    @Test fun noAttemptIsNotClaimedAsNoExternalFile() {
        show(ExportNoteHistoryState.NO_ATTEMPT_RECORDED_UNTRUSTED)
        compose.onNodeWithText(
            "No provider attempt recorded. Private stage not certified by this view.",
        ).assertExists()
    }

    @Test fun incompleteObservationRemainsRetained() {
        show(ExportNoteHistoryState.INCOMPLETE_UNTRUSTED)
        compose.onNodeWithText(
            "Missing or conflicting evidence; retain all protected assets.",
        ).assertExists()
    }

    @Test fun emptyHistoryDoesNotPromiseAbsenceOfExternalBytes() {
        compose.setContent {
            MaterialTheme {
                ExportNoteHistoryDialog(
                    noteId,
                    readHistory = { ExportNoteHistoryReport.ObservedUntrusted(emptyList()) },
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(
            "No locally recorded export operations for this note. " +
                "This does not prove that no external files exist.",
        ).assertExists()
    }

    @Test fun closingDialogDoesNotPerformAnyExportAction() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                ExportNoteHistoryDialog(
                    noteId,
                    readHistory = { ExportNoteHistoryReport.IncompleteUntrusted },
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
