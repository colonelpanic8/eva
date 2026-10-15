package com.colonelpanic.eva.call

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Removes audio EVA played locally from captured call audio before the call brain hears it.
 *
 * The reference is exactly what the playback accepted. Its echo in the capture is found by a
 * coarse envelope cross-correlation, then modelled by an NLMS filter spanning a few milliseconds
 * around that delay, so a linear path (mixing gain, resampling, EQ) cancels while the other
 * party's speech passes. Adaptation freezes during double talk. Until the filter measurably
 * removes at least [passErleDb] of echo, or whenever it cannot, frames that may hold echo are
 * replaced with silence; capture with no reference playing is returned untouched.
 *
 * Status on the device: on the Pixel 11 Pro Fold's PSTN path this reached only 0-21 dB, converging
 * intermittently, and its passed residual still read as speech to the model's voice detection. The
 * cellular listen-in therefore discards this filter's output and silences every frame [process]
 * reports as possibly holding echo; the filter keeps running for its measurements ([status]). JVM
 * tests cover linear paths at 40-600 ms, delay shifts, unmodellable paths, double talk and turns
 * that begin with a stale delay. Something that removes much more of this leak, or a capture point
 * without it, is still needed to listen in without losing barge-in.
 */
class CallEchoCanceller(
    private val sampleRateHz: Int,
    maxDelayMillis: Int = 1_000,
    private val taps: Int = 256,
    private val passErleDb: Double = 25.0,
) {
    private val ringSize = Integer.highestOneBit(sampleRateHz * 2 + maxDelayMillis * sampleRateHz / 1_000) shl 1
    private val mask = ringSize - 1

    /** Each sample is stored twice so any window shorter than the ring is contiguous. */
    private val reference = FloatArray(ringSize * 2)
    private var referenceCount = 0L
    private var lastAudibleReference = Long.MIN_VALUE / 2

    /** Where audible playback resumed after a silence longer than the longest delay. */
    private var audibleSince = Long.MIN_VALUE / 2
    private var captureCount = 0L

    /** Reference index minus capture index for the same moment, fixed by the first capture frame. */
    private var offset: Long? = null

    private val decimation = sampleRateHz / 1_000
    private val maxLagEnvelope = maxDelayMillis
    private val envelopeSize = Integer.highestOneBit(maxLagEnvelope * 4) shl 1
    private val referenceEnvelope = FloatArray(envelopeSize)
    private val captureEnvelope = FloatArray(envelopeSize)
    private var referenceEnvelopeAccumulator = 0f
    private var captureEnvelopeAccumulator = 0f

    /** Reference samples between a captured sample and the reference sample its echo came from. */
    private var lag: Int? = null
    private var lastEstimate = 0L
    private val weights = FloatArray(taps)
    private var erleDb = 0.0
    private var doubleTalkFrames = 0

    val converged: Boolean get() = lag != null && erleDb >= passErleDb

    /** Frames that may have held echo: silenced by the gate, or passed with the echo removed. */
    var silencedFrames = 0L
        private set
    var cancelledFrames = 0L
        private set

    /** For diagnostics: the echo delay found, the removal measured, and the gate's frame counts. */
    val status: String
        @Synchronized get() =
            "delay ${lag?.let { "${it * 1_000 / sampleRateHz} ms" } ?: "unknown"}, removal ${"%.1f".format(erleDb)} dB, " +
                "${if (converged) "converged" else "not converged"}; $silencedFrames frames silenced, $cancelledFrames cancelled"

    @Synchronized
    fun reset() {
        reference.fill(0f)
        referenceCount = 0
        lastAudibleReference = Long.MIN_VALUE / 2
        audibleSince = Long.MIN_VALUE / 2
        captureCount = 0
        offset = null
        referenceEnvelope.fill(0f)
        captureEnvelope.fill(0f)
        referenceEnvelopeAccumulator = 0f
        captureEnvelopeAccumulator = 0f
        lag = null
        lastEstimate = 0
        weights.fill(0f)
        erleDb = 0.0
        doubleTalkFrames = 0
        silencedFrames = 0
        cancelledFrames = 0
    }

    /** Samples the local playback accepted, in playback order. */
    @Synchronized
    fun played(
        samples: ShortArray,
        count: Int = samples.size,
    ) {
        for (i in 0 until count) {
            val value = samples[i] / 32_768f
            val at = (referenceCount and mask.toLong()).toInt()
            reference[at] = value
            reference[at + ringSize] = value
            if (kotlin.math.abs(samples[i].toInt()) > AUDIBLE) {
                if (referenceCount - lastAudibleReference > maxLagEnvelope * decimation) {
                    // The path may have changed since the last turn: gate until this turn's echo is measured.
                    audibleSince = referenceCount
                    erleDb = minOf(erleDb, passErleDb - RECONFIRM_DB)
                }
                lastAudibleReference = referenceCount
            }
            referenceEnvelopeAccumulator += kotlin.math.abs(value)
            referenceCount++
            if (referenceCount % decimation == 0L) {
                referenceEnvelope[((referenceCount / decimation) and (envelopeSize - 1).toLong()).toInt()] =
                    referenceEnvelopeAccumulator / decimation
                referenceEnvelopeAccumulator = 0f
            }
        }
    }

    /** Cleans one captured frame in place; returns whether it overlaps where echo of recent playback may be. */
    @Synchronized
    fun process(capture: ShortArray): Boolean {
        val start = captureCount
        val base = offset ?: (referenceCount - start).also { offset = it }
        val frame = FloatArray(capture.size) { capture[it] / 32_768f }
        for (i in frame.indices) {
            captureEnvelopeAccumulator += kotlin.math.abs(frame[i])
            if ((start + i + 1 + base) % decimation == 0L) {
                captureEnvelope[(((start + i + 1 + base) / decimation) and (envelopeSize - 1).toLong()).toInt()] =
                    captureEnvelopeAccumulator / decimation
                captureEnvelopeAccumulator = 0f
            }
        }
        captureCount += capture.size
        val referenceAtEnd = start + capture.size + base
        // A converged filter keeps its delay; re-estimating during double talk could pick the other party.
        if (!converged && referenceAtEnd - lastEstimate >= sampleRateHz / 2) {
            lastEstimate = referenceAtEnd
            estimateLag(referenceAtEnd)
        }
        val maxLag = maxLagEnvelope * decimation
        val mayEcho = lastAudibleReference >= start + base - maxLag - taps
        if (!mayEcho) return false
        val currentLag = lag
        if (currentLag == null) {
            // Without a delay, only capture far below anything recently played is surely not echo.
            if (frame.sumOf { (it * it).toDouble() } / frame.size > recentReferencePower(start + base, maxLag) * INAUDIBLE) {
                capture.fill(0)
                silencedFrames++
            }
            return true
        }
        // Silent capture only shows there is no echo once any echo of this playback would have arrived.
        cancel(frame, start + base - currentLag - taps / 2, start + base - audibleSince >= maxLag)
        if (converged) {
            for (i in capture.indices) capture[i] = (frame[i] * 32_768f).toInt().coerceIn(-32_768, 32_767).toShort()
            cancelledFrames++
        } else {
            capture.fill(0)
            silencedFrames++
        }
        return true
    }

    /** Replaces [frame] with the filter's error; [first] is the oldest reference index of the first sample's window. */
    private fun cancel(
        frame: FloatArray,
        first: Long,
        echoWouldHaveArrived: Boolean,
    ) {
        var referenceEnergy = 0.0
        val echo = FloatArray(frame.size)
        for (i in frame.indices) {
            val window = window(first + i)
            var y = 0f
            for (j in 0 until taps) y += weights[j] * reference[window + j]
            echo[i] = y
            val x = reference[window + taps / 2]
            referenceEnergy += x * x
        }
        var captured = 0.0
        var residual = 0.0
        var estimated = 0.0
        for (i in frame.indices) {
            captured += frame[i] * frame[i]
            estimated += echo[i] * echo[i]
            val e = frame[i] - echo[i]
            residual += e * e
        }
        val referenceActive = referenceEnergy / frame.size > QUIET * QUIET
        val expectedResidual = estimated * Math.pow(10.0, -(erleDb - DOUBLE_TALK_MARGIN_DB) / 10)
        // The other party adds energy the echo model cannot explain; a shifted echo path does not.
        val doubleTalk = converged && residual > expectedResidual + NOISE_FLOOR * frame.size && captured > estimated * DOUBLE_TALK_ENERGY
        doubleTalkFrames = if (doubleTalk) doubleTalkFrames + 1 else 0
        if (doubleTalkFrames * frame.size > sampleRateHz * 2) {
            // Sustained "double talk" is more likely a changed echo path: relearn behind the gate.
            erleDb = 0.0
            doubleTalkFrames = 0
        }
        if (referenceActive && !doubleTalk) {
            for (i in frame.indices) {
                val window = window(first + i)
                var y = 0f
                var power = 1e-6f
                for (j in 0 until taps) {
                    val x = reference[window + j]
                    y += weights[j] * x
                    power += x * x
                }
                val step = STEP * (frame[i] - y) / power
                for (j in 0 until taps) weights[j] += step * reference[window + j]
            }
            if (captured < referenceEnergy * INAUDIBLE) {
                // Playing reference with nothing audible captured: there is no echo to remove.
                if (echoWouldHaveArrived) track(passErleDb + INAUDIBLE_CREDIT_DB)
            } else if (captured > NOISE_FLOOR * frame.size) {
                val measured = 10 * log10(captured / (residual + 1e-12))
                track(measured)
                if (measured < DIVERGED_DB) {
                    weights.fill(0f)
                    erleDb = 0.0
                }
            }
        }
        for (i in frame.indices) frame[i] -= echo[i]
    }

    /** Falls fast and rises slowly, so worsening cancellation returns to the gate within a few frames. */
    private fun track(measured: Double) {
        val weight = if (measured < erleDb) 0.5 else 0.2
        erleDb = ((1 - weight) * erleDb + weight * measured).coerceIn(MIN_ERLE_DB, MAX_ERLE_DB)
    }

    private fun window(oldest: Long): Int = (oldest and mask.toLong()).toInt()

    private fun recentReferencePower(
        end: Long,
        span: Int,
    ): Double {
        val oldest = window(end - span)
        var energy = 0.0
        for (j in 0 until span) energy += reference[oldest + j] * reference[oldest + j]
        return energy / span
    }

    /** Coarse delay by normalized cross-correlation of 1 kHz amplitude envelopes over the last 600 ms. */
    private fun estimateLag(referenceAtEnd: Long) {
        val end = referenceAtEnd / decimation
        val span = ESTIMATE_MILLIS
        if (end - span - maxLagEnvelope < 0) return
        var best = 0.0
        var bestLag = -1
        val captured = DoubleArray(span) { captureEnvelope[((end - span + it) and (envelopeSize - 1).toLong()).toInt()].toDouble() }
        val capturedMean = captured.average()
        val capturedNorm = sqrt(captured.sumOf { (it - capturedMean) * (it - capturedMean) })
        if (capturedNorm < 1e-6) return
        for (k in 1..maxLagEnvelope) {
            var sum = 0.0
            var sumSquares = 0.0
            var cross = 0.0
            for (t in 0 until span) {
                val r = referenceEnvelope[((end - span + t - k) and (envelopeSize - 1).toLong()).toInt()].toDouble()
                sum += r
                sumSquares += r * r
                cross += r * (captured[t] - capturedMean)
            }
            val variance = sumSquares - sum * sum / span
            if (variance < 1e-9) continue
            val score = cross / (sqrt(variance) * capturedNorm)
            if (score > best) {
                best = score
                bestLag = k
            }
        }
        if (bestLag < 0 || best < MIN_CORRELATION) return
        val samples = bestLag * decimation
        val previous = lag
        if (previous == null || kotlin.math.abs(samples - previous) >= taps / 2) {
            weights.fill(0f)
            erleDb = 0.0
            lag = samples
        } else if (kotlin.math.abs(samples - previous) > taps / 8) {
            // Keep what the filter learned by sliding it with the new delay.
            val shift = samples - previous
            val moved = FloatArray(taps) { j -> weights.getOrElse(j - shift) { 0f } }
            moved.copyInto(weights)
            lag = samples
        }
    }

    private companion object {
        const val AUDIBLE = 64
        const val QUIET = 64f / 32_768f
        const val NOISE_FLOOR = 1e-8
        const val STEP = 0.5f
        const val DOUBLE_TALK_MARGIN_DB = 6.0
        const val ESTIMATE_MILLIS = 600
        const val MIN_CORRELATION = 0.5
        const val DIVERGED_DB = -3.0

        /** About 1 dB more energy than the modelled echo. */
        const val DOUBLE_TALK_ENERGY = 1.26
        const val MIN_ERLE_DB = -10.0
        const val MAX_ERLE_DB = 40.0

        /** 40 dB below the reference. */
        const val INAUDIBLE = 1e-4
        const val INAUDIBLE_CREDIT_DB = 10.0

        /** Just below passing, so one frame of measured removal passes again. */
        const val RECONFIRM_DB = 0.5
    }
}
