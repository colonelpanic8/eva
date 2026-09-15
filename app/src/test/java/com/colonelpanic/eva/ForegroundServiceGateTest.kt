package com.colonelpanic.eva

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceGateTest {
    private val gate = ForegroundServiceGate()

    @Test
    fun `a stop arriving before the service is foreground is left for the service to apply`() {
        gate.starting()
        // The turn finished before onStartCommand ran; stopping the record here is what Android kills for.
        assertFalse(gate.stopping())
        assertTrue(gate.foregrounded())
    }

    @Test
    fun `a stop after the service is foreground is applied by the caller`() {
        gate.starting()
        assertFalse(gate.foregrounded())
        assertTrue(gate.stopping())
    }

    @Test
    fun `a restart before the service is foreground leaves it running`() {
        gate.starting()
        assertFalse(gate.stopping())
        gate.starting()
        assertFalse(gate.foregrounded())
    }

    @Test
    fun `a gate reused after teardown does not carry the earlier stop`() {
        gate.starting()
        assertFalse(gate.foregrounded())
        assertTrue(gate.stopping())
        gate.destroyed()

        gate.starting()
        assertFalse(gate.foregrounded())
    }
}
