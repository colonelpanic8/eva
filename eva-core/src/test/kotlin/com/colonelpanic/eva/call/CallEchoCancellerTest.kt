package com.colonelpanic.eva.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class CallEchoCancellerTest {
    private val rate = 24_000
    private val frame = rate / 50

    /** Speech-like on/off reference: low-passed noise for 1.2 s, then 0.6 s of silence. */
    private fun reference(
        seconds: Int,
        random: Random,
    ): ShortArray {
        var low = 0.0
        return ShortArray(rate * seconds) { n ->
            low = 0.7 * low + 0.3 * (random.nextDouble() * 2 - 1)
            if (n % (rate * 18 / 10) < rate * 12 / 10) (low * 12_000).toInt().toShort() else 0
        }
    }

    private class Run(
        val input: List<ShortArray>,
        val output: List<ShortArray>,
        val echo: List<DoubleArray>,
    )

    /** Feeds playback and capture frame by frame, as the uplink writer and downlink reader do. */
    private fun run(
        played: ShortArray,
        echoAt: (Int, Int) -> Double,
        remote: (Int) -> Double = { 0.0 },
        random: Random = Random(7),
    ): Run {
        val canceller = CallEchoCanceller(rate)
        val inputs = mutableListOf<ShortArray>()
        val outputs = mutableListOf<ShortArray>()
        val echoes = mutableListOf<DoubleArray>()
        for (f in 0 until played.size / frame) {
            canceller.played(played.copyOfRange(f * frame, (f + 1) * frame))
            val echo = DoubleArray(frame) { i -> echoAt(f, f * frame + i) }
            val capture =
                ShortArray(frame) { i ->
                    (echo[i] + remote(f * frame + i) + random.nextDouble() * 6 - 3).toInt().coerceIn(-32_768, 32_767).toShort()
                }
            inputs += capture.copyOf()
            canceller.process(capture)
            outputs += capture
            echoes += echo
        }
        return Run(inputs, outputs, echoes)
    }

    private fun energy(samples: ShortArray) = samples.sumOf { it.toDouble() * it }

    private fun energy(samples: DoubleArray) = samples.sumOf { it * it }

    private fun linearPath(
        played: ShortArray,
        delay: (Int) -> Int,
    ): (Int, Int) -> Double =
        { f, n ->
            val d = delay(f)
            val taps = doubleArrayOf(0.45, 0.2, -0.08)
            taps.indices.sumOf { k -> taps[k] * played.getOrElse(n - d - k) { 0 } }
        }

    @Test fun captureIsUntouchedWhileNothingPlays() {
        val silent = ShortArray(rate * 3)
        val random = Random(3)
        val result = run(silent, { _, _ -> 0.0 }, remote = { random.nextDouble() * 4_000 - 2_000 })
        result.input.indices.forEach { assertArrayEquals(result.input[it], result.output[it]) }
    }

    @Test fun cancelsALinearDelayedPathAndStopsSilencingTheBrain() {
        val played = reference(12, Random(1))
        val result = run(played, linearPath(played) { rate * 180 / 1_000 })
        val late = (result.output.size / 2 until result.output.size).filter { energy(result.echo[it]) > 1e6 * frame }
        assertTrue(late.isNotEmpty())
        late.forEach { f ->
            assertTrue("frame $f kept echo", energy(result.output[f]) <= energy(result.input[f]) / 100)
        }
        val passed = late.count { f -> result.output[f].any { it != 0.toShort() } }
        assertTrue("only $passed of ${late.size} echo frames reached the brain", passed >= late.size * 9 / 10)
    }

    @Test fun keepsTheOtherPartyWhenTheyTalkOverTheAgent() {
        val played = reference(12, Random(2))
        val talk = Random(9)
        val voice = DoubleArray(played.size) { n -> if (n in rate * 9 until rate * 10) talk.nextDouble() * 6_000 - 3_000 else 0.0 }
        val result = run(played, linearPath(played) { rate * 150 / 1_000 }, remote = { voice[it] })
        val output = (9 * 50 until 10 * 50).flatMap { result.output[it].toList() }.map { it.toDouble() }
        val remote = (rate * 9 until rate * 10).map { voice[it] }
        val cross = output.indices.sumOf { output[it] * remote[it] }
        val correlation = cross / sqrt(output.sumOf { it * it } * remote.sumOf { it * it })
        assertTrue("the other party was lost: correlation $correlation", correlation > 0.9)
    }

    @Test fun aPathItCannotModelIsSilencedRatherThanPassed() {
        val played = reference(10, Random(4))
        val jumps = Random(5)
        val delays = IntArray(played.size / frame) { rate * 150 / 1_000 + jumps.nextInt(3) * 200 }
        val result = run(played, linearPath(played) { delays[it] })
        result.output.indices.filter { energy(result.echo[it]) > 1e6 * frame }.forEach { f ->
            assertTrue("frame $f passed echo", energy(result.output[f]) <= energy(result.input[f]) / 63)
        }
    }

    @Test fun relearnsAfterThePlaybackDelayShifts() {
        val played = reference(20, Random(6))
        val result = run(played, linearPath(played) { f -> if (f < 8 * 50) rate * 180 / 1_000 else rate * 220 / 1_000 })
        val late = (15 * 50 until result.output.size).filter { energy(result.echo[it]) > 1e6 * frame }
        late.forEach { f -> assertTrue("frame $f kept echo", energy(result.output[f]) <= energy(result.input[f]) / 100) }
        assertTrue(late.count { f -> result.output[f].any { it != 0.toShort() } } >= late.size * 9 / 10)
    }

    @Test fun aTurnStartingWithAStaleDelayIsGatedUntilItsEchoIsMeasured() {
        // On the device a stale delay passed the start of a turn as echo-free, then the late echo as
        // double talk: the agent heard its own first word and stopped speaking.
        val noise = Random(10)
        var low = 0.0
        val played =
            ShortArray(rate * 14) { n ->
                low = 0.7 * low + 0.3 * (noise.nextDouble() * 2 - 1)
                if (n < rate * 5 / 2 || n in rate * 10 until rate * 13) (low * 12_000).toInt().toShort() else 0
            }
        val ticks =
            DoubleArray(played.size) { n ->
                if (n % rate <
                    rate / 200
                ) {
                    8_000 * kotlin.math.sin(n * 2 * Math.PI * 1_000 / rate)
                } else {
                    0.0
                }
            }
        val result = run(played, linearPath(played) { f -> rate * (if (f < 5 * 50) 17 else 388) / 1_000 }, remote = { ticks[it] })
        (10 * 50 until 13 * 50).filter { f -> energy(result.echo[f]) > 1e6 * frame && f % 50 != 0 }.forEach { f ->
            assertTrue("frame $f passed echo", energy(result.output[f]) <= energy(result.input[f]) / 63)
        }
    }

    @Test fun withoutAnyLeakTheAgentsTurnsAreNotSilenced() {
        val played = reference(6, Random(8))
        val result = run(played, { _, _ -> 0.0 })
        val speaking = (50 until result.output.size).filter { f -> energy(played.copyOfRange(f * frame, (f + 1) * frame)) > 1e6 * frame }
        assertTrue(speaking.count { f -> result.output[f].any { it != 0.toShort() } } >= speaking.size * 9 / 10)
    }
}
