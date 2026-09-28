package com.colonelpanic.eva.keyword

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake models that number every mel frame, so each embedding and score can be traced back to
 * the audio it came from. Scores are scripted per chunk.
 */
private class TracingModels(
    override val phrases: Set<String>,
    private val script: (phrase: String, chunk: Int) -> Float,
) : WakeWordModels {
    var melFrames = 0
    val melInputs = mutableListOf<Int>()
    val melSamples = mutableListOf<FloatArray>()
    var closes = 0
    val embeddingInputs = mutableListOf<Pair<Int, Float>>()
    val scoreInputs = mutableListOf<List<Float>>()
    val threads = mutableSetOf<String>()
    private var chunks = 0
    private var warm = false

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        if (warm) {
            melInputs += samples.size
            melSamples += samples.copyOf()
        }
        return Array(samples.size / 160) { FloatArray(32) { (melFrames + 1).toFloat() }.also { melFrames++ } }
    }

    override fun embedding(mel: Array<FloatArray>): FloatArray {
        if (warm) embeddingInputs += mel.size to mel.last()[0]
        return FloatArray(96) { mel.last()[0] }
    }

    override fun inputFrames(phrase: String) = 16

    override fun score(
        phrase: String,
        features: Array<FloatArray>,
    ): Float {
        scoreInputs += features.map { it[0] }
        threads += Thread.currentThread().name
        return script(phrase, chunks++ / phrases.size)
    }

    fun warmedUp() {
        warm = true
    }

    override fun close() {
        closes++
    }
}

class OpenWakeWordSpotterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 0L

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun frame(
        rate: Int,
        ms: Int,
        amplitude: Int = 0,
    ): PcmFrame {
        now += ms * 1_000_000L
        return PcmFrame(ShortArray(rate * ms / 1_000) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }, rate, now)
    }

    @Test
    fun scoresEvery80msOnTheLatestWindows() {
        val models = TracingModels(setOf("wake")) { _, _ -> 0f }
        val spotter = OpenWakeWordSpotter(models, listOf(PhraseSpec("wake")), scope, { now })
        models.warmedUp()
        repeat(100) { spotter.process(frame(24_000, 20)) }

        assertEquals("2 s of audio is 25 chunks of 80 ms", 25, models.melInputs.size)
        assertEquals(1_280, models.melInputs.first())
        assertTrue("later chunks carry 480 samples of context", models.melInputs.drop(1).all { it == 1_760 })
        assertTrue(models.embeddingInputs.all { it.first == 76 })
        assertTrue("each embedding ends at the newest mel frame", models.embeddingInputs.last().second == models.melFrames.toFloat())
        val lastScore = models.scoreInputs.last()
        assertEquals(16, lastScore.size)
        assertEquals(models.melFrames.toFloat(), lastScore.last())
    }

    @Test
    fun thresholdRefractoryWarmupAndTimestamps() {
        val scores = mapOf(2 to 0.99f, 8 to 0.7f, 9 to 0.7f, 12 to 0.2f, 13 to 0.6f, 40 to 0.4f, 41 to 0.8f)
        val models = TracingModels(setOf("wake")) { _, chunk -> scores[chunk] ?: 0.1f }
        val spotter = OpenWakeWordSpotter(models, listOf(PhraseSpec("wake", threshold = 0.5f, refractoryMs = 1_000)), scope, { now + 7 })
        val events = mutableListOf<Pair<KeywordEvent, Long>>()
        repeat(60) {
            val frame = frame(16_000, 80)
            spotter.process(frame).forEach { events += it to frame.capturedAtNanos }
        }
        assertEquals(listOf(0.7f, 0.8f), events.map { it.first.score })
        val (first, capturedAt) = events.first()
        assertEquals("chunk 8 is the ninth 80 ms chunk", 9 * 80_000_000L, capturedAt)
        assertEquals(capturedAt + 7, first.detectedAtNanos)
    }

    @Test
    fun twoPhrasesAreIndependent() {
        val models = TracingModels(setOf("wake", "stop")) { phrase, chunk -> if (phrase == "stop" && chunk == 10) 0.9f else 0f }
        val spotter = OpenWakeWordSpotter(models, listOf(PhraseSpec("wake"), PhraseSpec("stop")), scope, { now })
        val events = (0 until 20).flatMap { spotter.process(frame(16_000, 80)) }
        assertEquals(listOf("stop"), events.map { it.phrase })
    }

    @Test
    fun burstGateVetoesADetectionWithoutSpeechEnergy() {
        val gated = PhraseSpec("stop", burstGate = SpeechBurstGate())
        val quiet = TracingModels(setOf("stop")) { _, chunk -> if (chunk == 20) 0.9f else 0f }
        val silent = OpenWakeWordSpotter(quiet, listOf(gated), scope, { now })
        assertEquals(emptyList<KeywordEvent>(), (0 until 30).flatMap { silent.process(frame(16_000, 80)) })
        assertEquals(1L, silent.stats().gatedDetections)

        val spoken = TracingModels(setOf("stop")) { _, chunk -> if (chunk == 20) 0.9f else 0f }
        val speaking = OpenWakeWordSpotter(spoken, listOf(gated), scope, { now })
        val start = now
        val events = (0 until 30).flatMap { i -> speaking.process(frame(16_000, 80, amplitude = if (i in 15..19) 8_000 else 20)) }
        assertEquals(listOf("stop"), events.map { it.phrase })
        val event = events.single()
        assertEquals(start + 15 * 80_000_000L, event.speechOnsetNanos)
    }

    @Test
    fun startRunsOnItsOwnThreadFromAFlow() =
        runBlocking {
            val models = TracingModels(setOf("wake")) { _, chunk -> if (chunk == 6) 0.9f else 0f }
            val spotter = OpenWakeWordSpotter(models, listOf(PhraseSpec("wake")), scope, { System.nanoTime() })
            val detected = async(start = CoroutineStart.UNDISPATCHED) { spotter.events.first() }
            spotter.start(
                flow {
                    repeat(100) {
                        emit(frame(24_000, 20))
                    }
                },
            )
            val event = withTimeout(5_000) { detected.await() }
            assertEquals("wake", event.phrase)
            assertEquals(SpotterStatus.Running, spotter.status.value)
            spotter.close()
            assertEquals(SpotterStatus.Idle, spotter.status.value)
            assertEquals(setOf("keyword-spotter"), models.threads)
        }

    @Test
    fun queuedFramesOwnTheirSamplesAndClosedModelsCannotBeReused() =
        runBlocking {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val complete = java.util.concurrent.CountDownLatch(1)
            val models = TracingModels(setOf("wake")) { _, _ -> 0f }
            var chunks = 0
            val spotter =
                OpenWakeWordSpotter(models, listOf(PhraseSpec("wake")), scope, { 0L }, onScores = {
                    if (chunks++ == 0) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    } else {
                        complete.countDown()
                    }
                })
            models.warmedUp()
            try {
                spotter.start(
                    flow {
                        emit(PcmFrame(ShortArray(1280), 16000, 80_000_000))
                        check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        val samples = ShortArray(1280) { 8000 }
                        emit(PcmFrame(samples, 16000, 160_000_000))
                        samples.fill(0)
                        release.countDown()
                    },
                )
                assertTrue(complete.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                release.countDown()
                spotter.close()
            }
            assertTrue(models.melSamples[1].takeLast(1280).all { it == 8000f })
            spotter.close()
            assertEquals(1, models.closes)
            assertTrue(runCatching { spotter.start(flow {}) }.isFailure)
            assertTrue(runCatching { spotter.process(PcmFrame(ShortArray(0), 16000, 0)) }.isFailure)
        }
}
