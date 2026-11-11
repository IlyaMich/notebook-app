package com.notebook.app.data.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STO-08 C3-A2-I3-B B1. Deterministic model of hypothetical staging lifecycle.
 * No filesystem, real process death, OS synchronization or recovery is executed.
 */
class ExportSnapshotResearchStateMachineTest {
    private enum class Stage { NEW, COPYING, COPIES_CHECKED, MANIFEST_CHECKED, SYNC_RECORDED, CANDIDATE }
    private data class Member(val key: String, val expectedSha: String, val actualSha: String?, val present: Boolean)
    private data class Evidence(
        val manifestValid: Boolean,
        val manifestBindsCanonicalSnapshot: Boolean,
        val manifestWithinBound: Boolean,
        val privatePathOwnershipProven: Boolean,
        val filesForceSucceeded: Boolean,
        val directoryDurabilityProven: Boolean,
        val originalBytesUntouched: Boolean,
        val members: List<Member>,
    )

    private class ResearchStageMachine {
        var stage = Stage.NEW
            private set
        var trusted = false
            private set

        fun begin() { require(stage == Stage.NEW); stage = Stage.COPYING }
        fun copied() { require(stage == Stage.COPYING); stage = Stage.COPIES_CHECKED }
        fun checkedManifest() { require(stage == Stage.COPIES_CHECKED); stage = Stage.MANIFEST_CHECKED }
        fun recordedSync() { require(stage == Stage.MANIFEST_CHECKED); stage = Stage.SYNC_RECORDED }

        /** Model-only candidate; the model has no way to grant a real export capability. */
        fun candidate(evidence: Evidence): Boolean {
            if (stage != Stage.SYNC_RECORDED) return false
            if (!fullyReverified(evidence)) return false
            stage = Stage.CANDIDATE
            return true
        }

        fun cancelOrCrash() {
            stage = Stage.NEW
            trusted = false
        }

        fun restart(evidence: Evidence): Boolean {
            // Deliberately refuse trusting a persisted marker; recheck all inputs.
            return stage == Stage.CANDIDATE && fullyReverified(evidence)
        }

        private fun fullyReverified(e: Evidence): Boolean =
            e.manifestValid && e.manifestBindsCanonicalSnapshot && e.manifestWithinBound &&
                e.privatePathOwnershipProven && e.filesForceSucceeded &&
                e.directoryDurabilityProven && e.originalBytesUntouched &&
                e.members.map { it.key }.toSet().size == e.members.size &&
                e.members.all { it.present && it.actualSha == it.expectedSha }
    }

    private fun valid() = Evidence(
        manifestValid = true,
        manifestBindsCanonicalSnapshot = true,
        manifestWithinBound = true,
        privatePathOwnershipProven = true,
        filesForceSucceeded = true,
        directoryDurabilityProven = true, // Model input, NOT demonstrated Android behavior.
        originalBytesUntouched = true,
        members = listOf(Member("copy-a", "digest-a", "digest-a", true)),
    )

    private fun complete(machine: ResearchStageMachine, evidence: Evidence = valid()): Boolean {
        machine.begin()
        machine.copied()
        machine.checkedManifest()
        machine.recordedSync()
        return machine.candidate(evidence)
    }

    @Test fun completeModelCandidateRemainsUntrustedAsRealExport() {
        val machine = ResearchStageMachine()
        assertTrue(complete(machine))
        assertEquals(Stage.CANDIDATE, machine.stage)
        assertFalse(machine.trusted)
        assertTrue(machine.restart(valid()))
        assertFalse(machine.trusted)
    }

    @Test fun completionMarkerAloneNeverMakesPartialStageTrusted() {
        val machine = ResearchStageMachine()
        machine.begin()
        assertFalse(machine.restart(valid()))
        assertFalse(machine.candidate(valid()))
        assertFalse(machine.trusted)
    }

    @Test fun corruptMissingOrChangedMemberRejectsAtCompletionAndRestart() {
        val failures = listOf(
            valid().copy(members = listOf(Member("copy-a", "digest-a", null, false))),
            valid().copy(members = listOf(Member("copy-a", "digest-a", "tampered", true))),
            valid().copy(members = listOf(Member("copy-a", "digest-a", "digest-a", true), Member("copy-a", "digest-a", "digest-a", true))),
        )
        for (bad in failures) {
            val machine = ResearchStageMachine()
            assertFalse(complete(machine, bad))
            assertFalse(machine.restart(bad))
            assertFalse(machine.trusted)
        }
        val good = ResearchStageMachine()
        assertTrue(complete(good))
        failures.forEach { assertFalse(good.restart(it)) }
    }

    @Test fun manifestPathDurabilityAndSourcePreservationAreIndependentGates() {
        val bad = listOf(
            valid().copy(manifestValid = false),
            valid().copy(manifestBindsCanonicalSnapshot = false),
            valid().copy(manifestWithinBound = false),
            valid().copy(privatePathOwnershipProven = false),
            valid().copy(filesForceSucceeded = false),
            valid().copy(directoryDurabilityProven = false),
            valid().copy(originalBytesUntouched = false),
        )
        bad.forEach {
            val machine = ResearchStageMachine()
            assertFalse(complete(machine, it))
            assertFalse(machine.trusted)
        }
    }

    @Test fun cancellationAtEveryStageInvalidatesCandidate() {
        for (step in 0..4) {
            val machine = ResearchStageMachine()
            machine.begin()
            if (step >= 1) machine.copied()
            if (step >= 2) machine.checkedManifest()
            if (step >= 3) machine.recordedSync()
            if (step >= 4) assertTrue(machine.candidate(valid()))
            machine.cancelOrCrash()
            assertEquals(Stage.NEW, machine.stage)
            assertFalse(machine.restart(valid()))
            assertFalse(machine.trusted)
        }
    }
}
