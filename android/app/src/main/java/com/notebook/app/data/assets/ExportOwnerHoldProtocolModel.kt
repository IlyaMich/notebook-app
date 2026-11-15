package com.notebook.app.data.assets

/**
 * STO-08 I5-B2. Pure, hypothetical export-owner hold protocol MODEL ONLY.
 * COMMITTED_PIN_SET is a precondition that future database code must prove;
 * constructing this model never persists a pin, inspects a file, or grants GC rights.
 * Every state demands retention of any real committed pins. There is NO release API.
 */
internal object ExportOwnerHoldProtocolModel {
    enum class Phase {
        COMMITTED_PIN_SET,
        COPYING,
        UNSEALED_STAGE,
        COPY_BYTES_OBSERVED,
        NEEDS_REVIEW,
    }

    enum class Event {
        BEGIN_COPY,
        OBSERVE_UNSEALED_STAGE,
        INDEPENDENT_COPY_BYTES_OBSERVED,
        CANCELLED,
        PROCESS_DIED,
        IO_FAILED,
        RECOVERY_AMBIGUOUS,
        RECOVERY_FOUND_UNSEALED_FILES,
    }

    data class Step(val phase: Phase, val accepted: Boolean) {
        /** A proof obligation, not a database inspection or authority to retain/release. */
        val committedPinsMustRemainHeld: Boolean get() = true
    }

    fun observe(current: Phase, event: Event): Step {
        if (current == Phase.NEEDS_REVIEW) return Step(current, false)
        if (event == Event.CANCELLED || event == Event.PROCESS_DIED ||
            event == Event.IO_FAILED || event == Event.RECOVERY_AMBIGUOUS ||
            event == Event.RECOVERY_FOUND_UNSEALED_FILES
        ) return Step(Phase.NEEDS_REVIEW, true)

        val next = when {
            current == Phase.COMMITTED_PIN_SET && event == Event.BEGIN_COPY -> Phase.COPYING
            current == Phase.COPYING && event == Event.OBSERVE_UNSEALED_STAGE -> Phase.UNSEALED_STAGE
            current == Phase.UNSEALED_STAGE && event == Event.INDEPENDENT_COPY_BYTES_OBSERVED ->
                Phase.COPY_BYTES_OBSERVED
            else -> return Step(current, false)
        }
        return Step(next, true)
    }
}
