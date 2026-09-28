package com.colonelpanic.eva.keyword

import kotlin.math.log10

/**
 * Energy-based speech activity over 10 ms blocks: a block is speech when it is [marginDb] above
 * an adaptive noise floor and above [minLevelDb]. It is not a keyword detector. It supplies the
 * speech onset for latency measurement, the "no speech" signal for the idle timeout, and the
 * [SpeechBurstGate] that a phrase model may additionally require.
 */
class SpeechActivity(
    private val sampleRate: Int = 16_000,
    private val marginDb: Double = 9.0,
    private val minLevelDb: Double = -60.0,
    private val floorRiseDbPerSecond: Double = 1.0,
    private val hangoverMs: Long = 300,
    historyMs: Long = 4_000,
) {
    private val blockSamples = sampleRate / 100
    private val blockNanos = 10_000_000L
    private val capacity = (historyMs / 10).toInt()
    private val times = LongArray(capacity)
    private val speech = BooleanArray(capacity)
    private var head = 0
    private var size = 0
    private var partial = 0
    private var energy = 0.0
    private var floor = Double.NaN

    var lastSpeechNanos: Long? = null
        private set

    /** [endNanos] is the capture time of the last sample in [samples]. */
    fun accept(
        samples: ShortArray,
        endNanos: Long,
    ) {
        val sampleNanos = 1_000_000_000L / sampleRate
        for (i in samples.indices) {
            val s = samples[i].toDouble()
            energy += s * s
            if (++partial == blockSamples) {
                block(endNanos - (samples.size - 1 - i) * sampleNanos)
                partial = 0
                energy = 0.0
            }
        }
    }

    /**
     * Start of the speech run that ends at most [lookbackMs] before [atNanos], where gaps
     * shorter than the hangover do not end a run. Null when there is none, or when the run is
     * older than the history, as in continuous speech.
     */
    fun onsetBefore(
        atNanos: Long,
        lookbackMs: Long = 1_000,
    ): Long? {
        var i = size - 1
        while (i >= 0 && timeAt(i) > atNanos) i--
        while (i >= 0 && !speechAt(i)) {
            if (atNanos - timeAt(i) > lookbackMs * 1_000_000) return null
            i--
        }
        if (i < 0) return null
        var onset = timeAt(i)
        var lastSpeech = onset
        while (i >= 0) {
            if (speechAt(i)) {
                onset = timeAt(i)
                lastSpeech = onset
            } else if (lastSpeech - timeAt(i) > hangoverMs * 1_000_000) {
                return onset - blockNanos
            }
            i--
        }
        return null
    }

    /** Milliseconds of speech blocks that ended in (fromNanos, toNanos]. */
    fun speechMillis(
        fromNanos: Long,
        toNanos: Long,
    ): Long {
        var blocks = 0
        for (i in 0 until size) {
            val t = timeAt(i)
            if (t in (fromNanos + 1)..toNanos && speechAt(i)) blocks++
        }
        return blocks * 10L
    }

    private fun block(endNanos: Long) {
        val level = 10 * log10(energy / blockSamples / FULL_SCALE_SQUARED + 1e-12)
        floor =
            when {
                floor.isNaN() -> level
                level < floor -> floor + (level - floor) * 0.3
                else -> floor + minOf(level - floor, floorRiseDbPerSecond / 100)
            }
        val isSpeech = level > floor + marginDb && level > minLevelDb
        if (isSpeech) lastSpeechNanos = endNanos
        val slot = (head + size) % capacity
        times[slot] = endNanos
        speech[slot] = isSpeech
        if (size == capacity) head = (head + 1) % capacity else size++
    }

    private fun timeAt(i: Int) = times[(head + i) % capacity]

    private fun speechAt(i: Int) = speech[(head + i) % capacity]

    private companion object {
        const val FULL_SCALE_SQUARED = 32768.0 * 32768.0
    }
}

/**
 * Requires at least [minSpeechMs] of speech in the [windowMs] before a detection. It only ever
 * vetoes a model's detection: a loud noise alone never produces a keyword event.
 */
data class SpeechBurstGate(
    val minSpeechMs: Long = 200,
    val windowMs: Long = 1_500,
) {
    fun passes(
        activity: SpeechActivity,
        atNanos: Long,
    ): Boolean = activity.speechMillis(atNanos - windowMs * 1_000_000, atNanos) >= minSpeechMs
}
