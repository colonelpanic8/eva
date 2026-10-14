package com.colonelpanic.eva.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AlternatingTwistDetectorTest {
    private fun stream(
        config: WearGestureConfiguration = WearGestureConfiguration(),
        duration: Long = 2200,
        rate: (Long) -> Double,
        crossAxis: Double = 0.1,
        acceleration: (Long) -> Double = { 0.3 },
    ): List<WatchGestureEvent> {
        val detector = AlternatingTwistDetector(config)
        return (0L..duration step 10).flatMap { t -> detector.sample(t, crossAxis, rate(t), 0.0, acceleration(t)) }
    }

    private fun pulse(
        t: Long,
        start: Long,
        direction: Int,
    ): Double = if (t in start until start + 100) direction * 8.0 * sin(PI * (t - start) / 100.0) else 0.0

    private fun sequence(
        t: Long,
        start: Long = 100,
        count: Int = 4,
        spacing: Long = 220,
        direction: Int = 1,
    ): Double = (0 until count).sumOf { pulse(t, start + it * spacing, if (it % 2 == 0) direction else -direction) }

    @Test fun `four alternating pulses trigger in either starting direction and retain every pulse`() {
        for (direction in listOf(-1, 1)) {
            val hit = stream(rate = { sequence(it, direction = direction) }).single { it.detected }
            assertEquals(4, hit.pulses.size)
            assertEquals(listOf(direction, -direction, direction, -direction), hit.pulses.map { it.direction })
            assertEquals(listOf(220L, 220L, 220L), hit.pulses.zipWithNext { a, b -> b.startMillis - a.startMillis })
            assertTrue(hit.pulses.all { it.angleRadians > 0.45 && it.peakRate > 7.5 })
        }
    }

    @Test fun `one full twist does not trigger and expires after two valid pulses`() {
        val events = stream(rate = { sequence(it, count = 2) })
        assertFalse(events.any { it.detected })
        assertEquals("next_pulse_missing (2/4 valid pulses)", events.single().reason)
        assertEquals(2, events.single().pulses.size)
    }

    @Test fun `three pulses followed by a pause never trigger`() {
        val events = stream(rate = { sequence(it, count = 3) })
        assertFalse(events.any { it.detected })
        assertEquals("next_pulse_missing (3/4 valid pulses)", events.single().reason)
    }

    @Test fun `an excessive gap midway resets the sequence instead of joining two pairs`() {
        val events = stream(rate = { sequence(it, count = 2) + sequence(it, start = 1000, count = 2) })
        assertFalse(events.any { it.detected })
        assertEquals(listOf(2, 2), events.map { it.pulses.size })
        assertTrue(events.all { it.reason == "next_pulse_missing (2/4 valid pulses)" })
    }

    @Test fun `same direction repeats fail with the accepted count before the offending pulse`() {
        val events = stream(rate = { pulse(it, 100, 1) + pulse(it, 320, -1) + pulse(it, 540, -1) + pulse(it, 760, 1) })
        assertFalse(events.any { it.detected })
        val rejected = events.first()
        assertEquals("same_direction (2/4 valid pulses)", rejected.reason)
        assertEquals(-1, rejected.candidate!!.direction)
    }

    @Test fun `interval bounds apply to every consecutive pulse`() {
        // Each later pulse still has a quiet separation, but the third starts too soon.
        val events = stream(rate = { pulse(it, 100, 1) + pulse(it, 320, -1) + pulse(it, 430, 1) + pulse(it, 650, -1) })
        assertFalse(events.any { it.detected })
        assertTrue(events.any { it.reason == "pulse_interval (2/4 valid pulses)" })
    }

    @Test fun `whole gesture cap rejects otherwise valid intervals that accumulate too long`() {
        val events = stream(rate = { sequence(it, spacing = 360) })
        assertFalse(events.any { it.detected })
        assertTrue(events.any { it.reason == "gesture_duration (3/4 valid pulses)" })
    }

    @Test fun `two pulse configuration restores a single out and back gesture`() {
        val events = stream(config = WearGestureConfiguration(requiredPulses = 2), rate = { sequence(it, count = 2) })
        assertEquals(2, events.single().pulses.size)
        assertTrue(events.single().detected)
    }

    @Test fun `larger configured counts require the complete alternating sequence`() {
        val config = WearGestureConfiguration(requiredPulses = 6, maxGestureMillis = 1600)
        assertFalse(stream(config, rate = { sequence(it, count = 4) }).any { it.detected })
        assertEquals(6, stream(config, rate = { sequence(it, count = 6) }).single { it.detected }.pulses.size)
    }

    @Test fun `road buzz rejects even high angular peaks and linear acceleration`() {
        for (acceleration in listOf(0.3, 12.0)) {
            val events = stream(duration = 6000, rate = { 9 * sin(2 * PI * 35 * it / 1000.0) }, acceleration = { acceleration })
            assertFalse(events.any { it.detected })
            assertTrue(events.isNotEmpty())
        }
        assertFalse(stream(rate = { sequence(it) }, acceleration = { 15.0 }).any { it.detected })
    }

    @Test fun `walking arm swing does not trigger`() {
        assertFalse(stream(duration = 10_000, rate = { 2.8 * sin(2 * PI * 1.8 * it / 1000.0) }).any { it.detected })
    }

    @Test fun `refractory period suppresses another full sequence but allows a later one`() {
        val events = stream(duration = 5500, rate = { t -> listOf(100L, 1200L, 4000L).sumOf { sequence(t, start = it) } })
        assertEquals(2, events.count { it.detected })
    }

    @Test fun `off axis motion and sample gaps cannot complete an otherwise valid gesture`() {
        assertFalse(stream(rate = { sequence(it) }, crossAxis = 8.0).any { it.detected })
        val detector = AlternatingTwistDetector(WearGestureConfiguration())
        val events =
            (0L..2200 step 10).filterNot { it in 370..520 }.flatMap {
                detector.sample(it, 0.0, sequence(it), 0.0, 0.0)
            }
        assertFalse(events.any { it.detected })
        assertTrue(events.any { it.reason == "sample_gap (1/4 valid pulses)" })
    }

    @Test fun `long rotation tails cannot count as a fresh pulse`() {
        val events = stream(rate = { if (it in 100..600) 7.0 else sequence(it, start = 750, count = 3, direction = -1) })
        assertFalse(events.any { it.detected })
        assertTrue(events.any { it.reason.startsWith("pulse_duration (0/4") })
        assertTrue(events.any { it.reason == "next_pulse_missing (3/4 valid pulses)" })
    }

    @Test fun `threshold changes affect admission of a complete sequence`() {
        assertFalse(stream(config = WearGestureConfiguration(peakRate = 10.0), rate = { sequence(it) }).any { it.detected })
    }

    @Test fun `invalid pulse counts and impossible whole gesture budgets are rejected`() {
        for (count in listOf(-2, 0, 1, 3, 5)) {
            assertThrows(IllegalArgumentException::class.java) { WearGestureConfiguration(requiredPulses = count) }
        }
        assertThrows(IllegalArgumentException::class.java) { WearGestureConfiguration(requiredPulses = 10) }
    }
}
