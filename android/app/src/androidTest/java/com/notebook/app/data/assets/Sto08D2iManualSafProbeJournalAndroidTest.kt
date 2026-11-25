package com.notebook.app.data.assets

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Only disposable debug preferences; no picker, external file, Room or hold mutation. */
@RunWith(AndroidJUnit4::class)
class Sto08D2iManualSafProbeJournalAndroidTest {
    private lateinit var context: Context
    private lateinit var prefName: String

    @Before fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefName = "sto08-d2i-test-" + UUID.randomUUID()
        assertTrue(context.getSharedPreferences(prefName, Context.MODE_PRIVATE).all.isEmpty())
    }

    @After fun after() {
        if (::context.isInitialized && ::prefName.isInitialized) {
            // Test's own private diagnostic fixture ONLY.
            assertTrue(context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit().clear().commit())
        }
    }

    private fun journal() = Sto08D2iManualSafProbeJournal(context, prefName)

    private fun attempt() = Sto08D2iManualSafProbeJournal.Attempt(
        UUID.randomUUID().toString(), "a".repeat(64), 98304L, "b".repeat(64),
    )

    @Test fun committedAttemptSurvivesNewJournalInstanceWithoutProviderReplay() {
        val one = journal()
        val a = attempt()
        assertEquals(Sto08D2iManualSafProbeJournal.Status.NeverAttempted, one.inspect())
        assertTrue(one.beginOnce(a))
        assertEquals(Sto08D2iManualSafProbeJournal.Status.AttemptPendingUntrusted(a),
            journal().inspect())
        assertFalse(journal().beginOnce(a))
        assertEquals(Sto08D2iManualSafProbeJournal.Status.AttemptPendingUntrusted(a),
            journal().inspect())
    }

    @Test fun matchingReadbackIsHistoricalOnlyAndCannotRepeat() {
        val a = attempt()
        assertTrue(journal().beginOnce(a))
        assertTrue(journal().recordReadbackOnce(
            a.operationId, a.expectedSha256, a.expectedBytes))
        assertEquals(Sto08D2iManualSafProbeJournal.Status.ReadbackObservedUntrusted(a),
            journal().inspect())
        assertFalse(journal().recordReadbackOnce(
            a.operationId, a.expectedSha256, a.expectedBytes))
        assertFalse(journal().beginOnce(attempt()))
    }

    @Test fun wrongDigestOrSizeCanNeverBecomePositiveObservation() {
        val a = attempt()
        assertTrue(journal().beginOnce(a))
        assertFalse(journal().recordReadbackOnce(
            a.operationId, "c".repeat(64), a.expectedBytes))
        assertFalse(journal().recordReadbackOnce(
            a.operationId, a.expectedSha256, a.expectedBytes + 1))
        assertFalse(journal().recordReadbackOnce(
            UUID.randomUUID().toString(), a.expectedSha256, a.expectedBytes))
        assertEquals(Sto08D2iManualSafProbeJournal.Status.AttemptPendingUntrusted(a),
            journal().inspect())
    }

    @Test fun corruptOrPartialLedgerFailsClosedWithoutReplacingEvidence() {
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("state", "PENDING_UNTRUSTED").commit())
        assertEquals(Sto08D2iManualSafProbeJournal.Status.InvalidUntrusted,
            journal().inspect())
        assertFalse(journal().beginOnce(attempt()))
        assertEquals(1, prefs.all.size)
    }

    @Test fun invalidIdentitiesAndOutOfBudgetBytesNeverCommit() {
        val good = attempt()
        assertFalse(journal().beginOnce(good.copy(operationId = "not-uuid")))
        assertFalse(journal().beginOnce(good.copy(expectedSha256 = "BAD")))
        assertFalse(journal().beginOnce(good.copy(destinationIdentitySha256 = "BAD")))
        assertFalse(journal().beginOnce(good.copy(expectedBytes = -1)))
        assertFalse(journal().beginOnce(good.copy(
            expectedBytes = Sto08D2iManualSafProbeJournal.MAX_PROBE_BYTES + 1,
        )))
        assertEquals(Sto08D2iManualSafProbeJournal.Status.NeverAttempted,
            journal().inspect())
    }
}
