package com.colonelpanic.eva.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class DoubleTwistDetectorTest {
    private fun stream(
        config: WearGestureConfiguration = WearGestureConfiguration(),
        duration: Long = 1600,
        rate: (Long) -> Double,
        crossAxis: Double = 0.1,
        acceleration: (Long) -> Double = { 0.3 },
    ): List<WatchGestureEvent> {
        val detector = DoubleTwistDetector(config)
        return (0L..duration step 10).flatMap { t -> detector.sample(t, crossAxis, rate(t), 0.0, acceleration(t)) }
    }

    private fun twist(
        t: Long,
        start: Long,
        direction: Int = 1,
    ): Double = if (t in start until start + 100) direction * 8.0 * sin(PI * (t - start) / 100.0) else 0.0

    @Test fun `clean opposite double twist triggers with measured features`() {
        val events = stream(rate = { twist(it, 100) + twist(it, 320, -1) })
        val hit = events.single { it.detected }
        assertEquals(220L, hit.intervalMillis)
        assertTrue(hit.first!!.angleRadians > 0.45)
        assertTrue(hit.second!!.peakRate > 7.5)
    }

    @Test fun `single twist expires with near miss evidence`() {
        val events = stream(rate = { twist(it, 100) })
        assertFalse(events.any { it.detected })
        assertEquals("second_twist_missing", events.single().reason)
    }

    @Test fun `twists too far apart never pair`() {
        assertFalse(stream(rate = { twist(it, 100) + twist(it, 800, -1) }).any { it.detected })
    }

    @Test fun `road buzz rejects even high angular peaks and linear acceleration`() {
        val events = stream(duration = 6000, rate = { 9 * sin(2 * PI * 35 * it / 1000.0) }, acceleration = { 12.0 })
        assertFalse(events.any { it.detected })
        assertTrue(events.isNotEmpty())
        assertFalse(stream(duration = 6000, rate = { 9 * sin(2 * PI * 35 * it / 1000.0) }).any { it.detected })
        assertFalse(stream(rate = { twist(it, 100) + twist(it, 320, -1) }, acceleration = { 15.0 }).any { it.detected })
    }

    @Test fun `walking arm swing does not trigger`() {
        assertFalse(stream(duration = 10_000, rate = { 2.8 * sin(2 * PI * 1.8 * it / 1000.0) }).any { it.detected })
    }

    @Test fun `refractory period prevents double firing but later gesture works`() {
        val events =
            stream(duration = 4500, rate = { t ->
                listOf(100L, 750L, 3500L).sumOf { twist(t, it) + twist(t, it + 220, -1) }
            })
        assertEquals(2, events.count { it.detected })
    }

    @Test fun `same direction off axis and a sample gap cannot trigger`() {
        assertFalse(stream(rate = { twist(it, 100) + twist(it, 320) }).any { it.detected })
        assertFalse(stream(rate = { twist(it, 100) + twist(it, 320, -1) }, crossAxis = 8.0).any { it.detected })
        val detector = DoubleTwistDetector(WearGestureConfiguration())
        val events =
            (0L..1000 step 10).filterNot { it in 150..300 }.flatMap {
                detector.sample(it, 0.0, twist(it, 100) + twist(it, 320, -1), 0.0, 0.0)
            }
        assertFalse(events.any { it.detected })
        assertTrue(events.any { it.reason == "sample_gap" })
    }

    @Test fun `long rotation tails cannot count as a fresh pulse`() {
        val events = stream(duration = 2000, rate = { if (it in 100..600) 7.0 else twist(it, 750, -1) })
        assertFalse(events.any { it.detected })
    }

    @Test fun `threshold changes affect admission`() {
        val stricter = WearGestureConfiguration(peakRate = 10.0)
        assertFalse(stream(config = stricter, rate = { twist(it, 100) + twist(it, 320, -1) }).any { it.detected })
    }
}
