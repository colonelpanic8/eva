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
    fun `a stop waits for every accepted foreground start`() {
        gate.starting()
        gate.starting()
        assertFalse(gate.stopping())
        assertFalse(gate.foregrounded())
        assertTrue(gate.foregrounded())
    }

    @Test
    fun `a restart before the service is foreground leaves it running`() {
        gate.starting()
        assertFalse(gate.stopping())
        gate.starting()
        assertFalse(gate.foregrounded())
        assertFalse(gate.foregrounded())
    }

    @Test
    fun `a quick stop after restarting a foreground service waits for the new start`() {
        gate.starting()
        assertFalse(gate.foregrounded())
        assertTrue(gate.stopping())

        gate.starting()
        assertFalse(gate.stopping())
        assertTrue(gate.foregrounded())
    }

    @Test
    fun `destruction of the old service preserves a stop waiting on its replacement`() {
        gate.starting()
        assertFalse(gate.foregrounded())
        assertTrue(gate.stopping())

        gate.starting()
        assertFalse(gate.stopping())
        gate.destroyed()
        assertTrue(gate.foregrounded())
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

    @Test
    fun `rejected start does not strand a later foreground stop`() {
        assertFalse(gate.requestStart { throw SecurityException("Permission revoked") })
        assertFalse(gate.requestStart { throw IllegalStateException("Background start denied") })
        assertTrue(gate.requestStart {})
        assertFalse(gate.foregrounded())
        assertTrue(gate.stopping())
    }

    @Test
    fun `rejected overlapping start preserves the accepted start`() {
        assertTrue(gate.requestStart {})
        assertFalse(gate.requestStart { throw IllegalStateException("Denied") })
        assertFalse(gate.stopping())
        assertTrue(gate.foregrounded())
    }
}
