package com.voicedeviceagent.companion.keyword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SignalProcessingTest {
    private fun tone(
        hz: Double,
        rate: Int,
        samples: Int,
        amplitude: Double = 10_000.0,
    ) = ShortArray(samples) { (amplitude * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    private fun rms(samples: ShortArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private fun resampleInPieces(
        resampler: PolyphaseResampler,
        input: ShortArray,
        piece: Int,
    ): ShortArray =
        input
            .toList()
            .chunked(piece)
            .flatMap { resampler.process(it.toShortArray()).toList() }
            .toShortArray()

    @Test
    fun resamplerKeepsSpeechBandAndRejectsWhatWouldAlias() {
        val second = 24_000
        val passband = resampleInPieces(PolyphaseResampler(24_000, 16_000), tone(1_000.0, 24_000, second), 480)
        assertEquals(16_000.0, passband.size.toDouble(), 2.0)
        assertEquals(10_000 / sqrt(2.0), rms(passband.copyOfRange(1_000, 15_000)), 150.0)

        val aliasing = resampleInPieces(PolyphaseResampler(24_000, 16_000), tone(10_000.0, 24_000, second), 480)
        assertTrue(rms(aliasing.copyOfRange(1_000, 15_000)) < 10_000 / sqrt(2.0) * 0.03)
    }

    @Test
    fun resamplerOutputDoesNotDependOnHowInputIsSplit() {
        val input = tone(440.0, 48_000, 9_600)
        val whole = PolyphaseResampler(48_000, 16_000).process(input)
        val pieces = resampleInPieces(PolyphaseResampler(48_000, 16_000), input, 333)
        assertEquals(whole.size, pieces.size)
        assertTrue(whole.indices.all { abs(whole[it] - pieces[it]) <= 1 })
    }

    @Test
    fun detectorNeedsThresholdPatienceAndRearmsAfterRefractoryAndDrop() {
        val detector = PhraseDetector(PhraseSpec("p", threshold = 0.5f, refractoryMs = 1_000, patience = 2))
        val ms = 1_000_000L
        assertFalse(detector.offer(0.9f, 0))
        assertTrue(detector.offer(0.9f, 80 * ms))
        assertFalse("sustained score fires once", detector.offer(0.99f, 160 * ms))
        assertFalse(detector.offer(0.1f, 240 * ms))
        assertFalse(detector.offer(0.9f, 320 * ms))
        assertFalse("inside the refractory period", detector.offer(0.9f, 400 * ms))
        assertFalse(detector.offer(0.2f, 1_100 * ms))
        assertFalse(detector.offer(0.6f, 1_180 * ms))
        assertTrue(detector.offer(0.6f, 1_260 * ms))
        assertFalse("just below the threshold", PhraseDetector(PhraseSpec("q", threshold = 0.5f)).offer(0.4999f, 0))
    }

    @Test
    fun speechActivityFindsOnsetOfTheBurstBeforeADetection() {
        val activity = SpeechActivity()
        val ms = 1_000_000L
        var now = 0L

        fun feed(samples: ShortArray) {
            now += samples.size * 62_500L
            activity.accept(samples, now)
        }
        repeat(100) { feed(tone(200.0, 16_000, 160, amplitude = 30.0)) }
        val onset = now
        feed(tone(300.0, 16_000, 16_000 * 6 / 10, amplitude = 8_000.0))
        repeat(10) { feed(ShortArray(160)) }
        feed(tone(300.0, 16_000, 16_000 * 2 / 10, amplitude = 8_000.0))
        val end = now
        repeat(20) { feed(tone(200.0, 16_000, 160, amplitude = 30.0)) }

        val found = activity.onsetBefore(now)
        assertNotNull(found)
        assertEquals(onset.toDouble(), found!!.toDouble(), 20.0 * ms)
        assertEquals(800.0, activity.speechMillis(onset - 1, end).toDouble(), 30.0)
        assertTrue(SpeechBurstGate(minSpeechMs = 200, windowMs = 1_500).passes(activity, now))
        assertNull("no speech within the lookback", activity.onsetBefore(onset - 10 * ms))
        assertEquals(end.toDouble(), activity.lastSpeechNanos!!.toDouble(), 10.0 * ms)
    }

    @Test
    fun burstGateRejectsAClickEvenWhenLoud() {
        val activity = SpeechActivity()
        var now = 0L
        repeat(100) {
            now += 10_000_000L
            activity.accept(ShortArray(160) { (it % 7).toShort() }, now)
        }
        now += 20_000_000L
        activity.accept(ShortArray(320) { if (it % 2 == 0) 30_000 else -30_000 }, now)
        repeat(30) {
            now += 10_000_000L
            activity.accept(ShortArray(160), now)
        }
        assertFalse(SpeechBurstGate().passes(activity, now))
    }
}
