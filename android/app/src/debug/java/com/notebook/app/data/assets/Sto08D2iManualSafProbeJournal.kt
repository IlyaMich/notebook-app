package com.notebook.app.data.assets

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * DEBUG ONLY, test-fixture bookkeeping for a manually approved SAF picker run.
 * Never use as an export receipt, Room substitute, permission to resume,
 * release/GC decision, or evidence of external crash durability.
 *
 * Stores NO URI. Separate one-shot ledgers prevent automatic replay after
 * app process death. Each SharedPreferences.commit() completes before the
 * test-only provider writer is invoked.
 */
internal class Sto08D2iManualSafProbeJournal(
    context: Context,
    private val preferencesName: String,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    sealed interface Status {
        data object NeverAttempted : Status
        data object InvalidUntrusted : Status
        data class AttemptPendingUntrusted(
            val attempt: Attempt,
        ) : Status
        data class ReadbackObservedUntrusted(
            val attempt: Attempt,
        ) : Status
    }

    data class Attempt(
        val operationId: String,
        val expectedSha256: String,
        val expectedBytes: Long,
        val destinationIdentitySha256: String,
    )

    @Synchronized fun inspect(): Status {
        return try {
        val all = prefs.all
        if (all.isEmpty()) return Status.NeverAttempted
        if (all.keys != REQUIRED_KEYS) return Status.InvalidUntrusted
        val version = prefs.getInt("version", -1)
        val marker = prefs.getString("state", null)
        val attempt = Attempt(
            operationId = prefs.getString("operationId", "") ?: "",
            expectedSha256 = prefs.getString("sha256", "") ?: "",
            expectedBytes = prefs.getLong("bytes", -1L),
            destinationIdentitySha256 = prefs.getString("destinationSha256", "") ?: "",
        )
        if (version != 1 || !valid(attempt)) return Status.InvalidUntrusted
        when (marker) {
            "PENDING_UNTRUSTED" -> Status.AttemptPendingUntrusted(attempt)
            "READBACK_ONCE_UNTRUSTED" -> Status.ReadbackObservedUntrusted(attempt)
            else -> Status.InvalidUntrusted
        }
        } catch (_: Exception) {
            Status.InvalidUntrusted
        }
    }

    /** Fail closed on any existing/corrupt fixture row; never overwrite. */
    @Synchronized fun beginOnce(attempt: Attempt): Boolean {
        if (!valid(attempt) || inspect() != Status.NeverAttempted) return false
        if (prefs.all.isNotEmpty()) return false
        return prefs.edit()
            .putInt("version", 1)
            .putString("state", "PENDING_UNTRUSTED")
            .putString("operationId", attempt.operationId)
            .putString("sha256", attempt.expectedSha256)
            .putLong("bytes", attempt.expectedBytes)
            .putString("destinationSha256", attempt.destinationIdentitySha256)
            .commit()
    }

    /** Only a historical exact readback claim; never changes provider or Room. */
    @Synchronized fun recordReadbackOnce(
        operationId: String,
        sha256: String,
        bytes: Long,
    ): Boolean {
        val current = inspect()
        if (current !is Status.AttemptPendingUntrusted) return false
        if (current.attempt.operationId != operationId ||
            current.attempt.expectedSha256 != sha256 ||
            current.attempt.expectedBytes != bytes
        ) return false
        return prefs.edit().putString("state", "READBACK_ONCE_UNTRUSTED").commit()
    }

    private fun valid(attempt: Attempt): Boolean =
        runCatching { UUID.fromString(attempt.operationId).toString() == attempt.operationId }
            .getOrDefault(false) &&
            HEX.matches(attempt.expectedSha256) &&
            HEX.matches(attempt.destinationIdentitySha256) &&
            attempt.expectedBytes in 1L..MAX_PROBE_BYTES

    internal companion object {
        const val MAX_PROBE_BYTES = 256L * 1024L
        private val HEX = Regex("[a-f0-9]{64}")
        private val REQUIRED_KEYS = setOf(
            "version", "state", "operationId", "sha256", "bytes", "destinationSha256",
        )
        const val SUCCESS_LEDGER = "sto08_d2i_manual_picker_success"
        const val DEATH_LEDGER = "sto08_d2i_manual_picker_process_death"
    }
}
