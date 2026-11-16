package com.notebook.app.data.assets

import com.notebook.app.data.assets.ExportOwnerHoldProtocolModel as Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests a proposed fail-closed model; does not test persistence or grant deletion authority. */
class ExportOwnerHoldProtocolModelTest {
    private fun observed(phase: Model.Phase, event: Model.Event) = Model.observe(phase, event)

    @Test fun committedPinSetRequiresRetention() {
        assertTrue(observed(Model.Phase.COMMITTED_PIN_SET, Model.Event.BEGIN_COPY).committedPinsMustRemainHeld)
    }

    @Test fun beginCopyFromCommittedPinIsObserved() {
        val result = observed(Model.Phase.COMMITTED_PIN_SET, Model.Event.BEGIN_COPY)
        assertTrue(result.accepted)
        assertEquals(Model.Phase.COPYING, result.phase)
    }

    @Test fun duplicateBeginCopyIsRejectedWithoutAdvance() {
        val result = observed(Model.Phase.COPYING, Model.Event.BEGIN_COPY)
        assertFalse(result.accepted)
        assertEquals(Model.Phase.COPYING, result.phase)
    }

    @Test fun unsealedStageRequiresCopyPhase() {
        val wrong = observed(Model.Phase.COMMITTED_PIN_SET, Model.Event.OBSERVE_UNSEALED_STAGE)
        assertFalse(wrong.accepted)
        assertEquals(Model.Phase.COMMITTED_PIN_SET, wrong.phase)
        assertEquals(Model.Phase.UNSEALED_STAGE,
            observed(Model.Phase.COPYING, Model.Event.OBSERVE_UNSEALED_STAGE).phase)
    }

    @Test fun independentCopyObservationRequiresUnsealedStage() {
        assertFalse(observed(Model.Phase.COPYING, Model.Event.INDEPENDENT_COPY_BYTES_OBSERVED).accepted)
        assertEquals(Model.Phase.COPY_BYTES_OBSERVED,
            observed(Model.Phase.UNSEALED_STAGE, Model.Event.INDEPENDENT_COPY_BYTES_OBSERVED).phase)
    }

    @Test fun copyBytesObservedNeverReleasesHold() {
        val step = observed(Model.Phase.UNSEALED_STAGE, Model.Event.INDEPENDENT_COPY_BYTES_OBSERVED)
        assertTrue(step.committedPinsMustRemainHeld)
        assertEquals(Model.Phase.COPY_BYTES_OBSERVED, step.phase)
    }

    @Test fun cancellationFromEveryProgressPhaseRequiresReview() {
        for (phase in Model.Phase.values().filter { it != Model.Phase.NEEDS_REVIEW }) {
            val step = observed(phase, Model.Event.CANCELLED)
            assertEquals(Model.Phase.NEEDS_REVIEW, step.phase)
            assertTrue(step.committedPinsMustRemainHeld)
        }
    }

    @Test fun processDeathFromEveryProgressPhaseRequiresReview() {
        for (phase in Model.Phase.values().filter { it != Model.Phase.NEEDS_REVIEW }) {
            val step = observed(phase, Model.Event.PROCESS_DIED)
            assertEquals(Model.Phase.NEEDS_REVIEW, step.phase)
            assertTrue(step.committedPinsMustRemainHeld)
        }
    }

    @Test fun ioFailureFromEveryProgressPhaseRequiresReview() {
        for (phase in Model.Phase.values().filter { it != Model.Phase.NEEDS_REVIEW }) {
            assertEquals(Model.Phase.NEEDS_REVIEW, observed(phase, Model.Event.IO_FAILED).phase)
        }
    }

    @Test fun twoUnsealedFilesOnRecoveryAreNotAReceipt() {
        val step = observed(Model.Phase.COPY_BYTES_OBSERVED, Model.Event.RECOVERY_FOUND_UNSEALED_FILES)
        assertEquals(Model.Phase.NEEDS_REVIEW, step.phase)
        assertTrue(step.committedPinsMustRemainHeld)
    }

    @Test fun ambiguousRecoveryRetainsPinRequirement() {
        val step = observed(Model.Phase.COMMITTED_PIN_SET, Model.Event.RECOVERY_AMBIGUOUS)
        assertTrue(step.accepted)
        assertEquals(Model.Phase.NEEDS_REVIEW, step.phase)
        assertTrue(step.committedPinsMustRemainHeld)
    }

    @Test fun needsReviewIsAbsorbing() {
        for (event in Model.Event.values()) {
            val step = observed(Model.Phase.NEEDS_REVIEW, event)
            assertFalse(step.accepted)
            assertEquals(Model.Phase.NEEDS_REVIEW, step.phase)
        }
    }

    @Test fun everyPhaseAndEventRequiresRetentionWithoutRelease() {
        for (phase in Model.Phase.values()) {
            for (event in Model.Event.values()) {
                val step = observed(phase, event)
                assertTrue(step.committedPinsMustRemainHeld)
            }
        }
    }
}
