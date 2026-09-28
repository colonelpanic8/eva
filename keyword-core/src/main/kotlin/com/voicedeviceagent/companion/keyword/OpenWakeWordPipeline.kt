package com.voicedeviceagent.companion.keyword

import kotlin.random.Random

/** Phrase scores for one 80 ms chunk; [endSample] counts 16 kHz samples since the pipeline started. */
class ChunkScores(
    val endSample: Long,
    val scores: Map<String, Float>,
)

/**
 * openWakeWord's streaming feature pipeline (openwakeword/utils.py, `AudioFeatures`, v0.6.0),
 * fed one 1280-sample (80 ms) chunk at a time at 16 kHz:
 *
 * 1. mel spectrogram of the chunk plus the previous 480 samples, appended to a mel buffer that
 *    starts as 76 frames of ones;
 * 2. one embedding of the latest 76 mel frames, appended to a feature buffer that starts with the
 *    embeddings of 4 s of low-level noise;
 * 3. each phrase classifier on its latest N embeddings (N = 16 for the bundled models, 1.28 s).
 *
 * The first [WARMUP_SCORES] scores of every phrase are reported as zero, as upstream does.
 */
class OpenWakeWordPipeline(
    private val models: WakeWordModels,
    seed: Long = 0,
) {
    private val pending = ShortArray(CHUNK_SAMPLES)
    private var pendingCount = 0
    private var context = ShortArray(0)
    private val mel = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private val scored = models.phrases.associateWith { 0 }.toMutableMap()
    private var samples = 0L

    init {
        repeat(EMBEDDING_WINDOW) { mel.addLast(FloatArray(WakeWordModels.MEL_BINS) { 1f }) }
        val random = Random(seed)
        val noise = FloatArray(NOISE_SAMPLES) { random.nextInt(-1000, 1000).toFloat() }
        val noiseMel = models.melspectrogram(noise)
        var start = 0
        while (start + EMBEDDING_WINDOW <= noiseMel.size) {
            features.addLast(models.embedding(noiseMel.copyOfRange(start, start + EMBEDDING_WINDOW)))
            start += MEL_STEP
        }
    }

    /** Buffers [input] and returns the scores of every chunk it completed. */
    fun accept(input: ShortArray): List<ChunkScores> {
        var results: MutableList<ChunkScores>? = null
        for (sample in input) {
            pending[pendingCount++] = sample
            samples++
            if (pendingCount == CHUNK_SAMPLES) {
                pendingCount = 0
                val chunk = runChunk()
                (results ?: mutableListOf<ChunkScores>().also { results = it }).add(chunk)
            }
        }
        return results ?: emptyList()
    }

    private fun runChunk(): ChunkScores {
        val window = FloatArray(context.size + CHUNK_SAMPLES)
        for (i in context.indices) window[i] = context[i].toFloat()
        for (i in 0 until CHUNK_SAMPLES) window[context.size + i] = pending[i].toFloat()
        context = pending.copyOfRange(CHUNK_SAMPLES - MEL_CONTEXT_SAMPLES, CHUNK_SAMPLES)

        models.melspectrogram(window).forEach(mel::addLast)
        while (mel.size > MAX_MEL_FRAMES) mel.removeFirst()
        features.addLast(models.embedding(Array(EMBEDDING_WINDOW) { mel[mel.size - EMBEDDING_WINDOW + it] }))
        while (features.size > MAX_FEATURES) features.removeFirst()

        val scores =
            models.phrases.associateWith { phrase ->
                val frames = models.inputFrames(phrase)
                val score = models.score(phrase, Array(frames) { features[features.size - frames + it] })
                val count = scored.getValue(phrase)
                scored[phrase] = count + 1
                if (count < WARMUP_SCORES) 0f else score
            }
        return ChunkScores(samples, scores)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_280
        const val MEL_CONTEXT_SAMPLES = 480
        const val EMBEDDING_WINDOW = 76
        const val MEL_STEP = 8
        const val MAX_MEL_FRAMES = 970
        const val MAX_FEATURES = 120
        const val WARMUP_SCORES = 5
        private const val NOISE_SAMPLES = SAMPLE_RATE * 4
    }
}
