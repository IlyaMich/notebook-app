package com.notebook.app.ui.editor.ink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPointerStartGateTest {
    @Test fun startedInsidePageIsAllowed() {
        val gate = InkPointerStartGate()
        assertFalse(gate.shouldConsume(7, down = true, pressed = true, validStart = true))
        assertFalse(gate.shouldConsume(7, down = false, pressed = true, validStart = false))
        assertFalse(gate.shouldConsume(7, down = false, pressed = false, validStart = false))
    }

    @Test fun startedInGapStaysBlockedWhenEnteringPage() {
        val gate = InkPointerStartGate()
        assertTrue(gate.shouldConsume(9, down = true, pressed = true, validStart = false))
        assertTrue(gate.shouldConsume(9, down = false, pressed = true, validStart = true))
        assertTrue(gate.shouldConsume(9, down = false, pressed = false, validStart = true))
    }

    @Test fun blockedPointerIsReleasedAndCanStartOnPageLater() {
        val gate = InkPointerStartGate()
        assertTrue(gate.shouldConsume(5, down = true, pressed = true, validStart = false))
        assertTrue(gate.shouldConsume(5, down = false, pressed = false, validStart = false))
        assertFalse(gate.shouldConsume(5, down = true, pressed = true, validStart = true))
    }

    @Test fun onePointerBlockedDoesNotBlockAnother() {
        val gate = InkPointerStartGate()
        assertTrue(gate.shouldConsume(1, down = true, pressed = true, validStart = false))
        assertFalse(gate.shouldConsume(2, down = true, pressed = true, validStart = true))
        assertTrue(gate.shouldConsume(1, down = false, pressed = true, validStart = true))
        assertFalse(gate.shouldConsume(2, down = false, pressed = true, validStart = false))
    }

    @Test fun resetReleasesAllBlockedPointers() {
        val gate = InkPointerStartGate()
        assertTrue(gate.shouldConsume(1, down = true, pressed = true, validStart = false))
        gate.reset()
        assertFalse(gate.shouldConsume(1, down = false, pressed = true, validStart = true))
    }
}
