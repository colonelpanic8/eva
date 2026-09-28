package com.colonelpanic.eva.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRingBufferTest {
    @Test
    fun fullBufferDropsOldestAndCounts() {
        val ring = FrameRingBuffer<Int>(3)
        (1..5).forEach(ring::offer)
        assertEquals(2L, ring.dropped)
        assertEquals(listOf(3, 4, 5), generateSequence { ring.poll() }.toList())
        assertEquals(2L, ring.dropped)
    }

    @Test
    fun takeTimesOutWhenEmptyAndClearEmpties() {
        val ring = FrameRingBuffer<Int>(2)
        assertNull(ring.take(1))
        ring.offer(1)
        ring.offer(2)
        ring.clear()
        assertEquals(0, ring.count)
        ring.offer(3)
        assertEquals(3, ring.take(1))
    }
}
