package com.colonelpanic.eva.call

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/** What a provider can tell about its call at one look. */
sealed interface LegObservation {
    /** No call of this leg is visible yet; the provider is still placing it. */
    data object Pending : LegObservation

    /** The call is in progress; [answered] is null while it still rings. */
    data class Live(
        val answered: AnsweredEvidence?,
    ) : LegObservation

    /** The call is over; [cause] is null when the provider cannot tell why. */
    data class Gone(
        val cause: EndCause?,
        val detail: String,
    ) : LegObservation
}

/** Folds a provider's successive observations into the leg state the coordinator follows. */
class LegStateTracker(
    placing: Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow<LegState>(if (placing) LegState.Placing else LegState.Ringing)
    val state: StateFlow<LegState> = mutable.asStateFlow()

    @Volatile var hangUpRequested = false
    private var answered = false

    val ended get() = mutable.value is LegState.Ended

    fun observe(observation: LegObservation) {
        if (ended) return
        when (observation) {
            LegObservation.Pending -> {
                Unit
            }

            is LegObservation.Live -> {
                val evidence = observation.answered
                when {
                    answered -> {
                        Unit
                    }

                    evidence != null -> {
                        answered = true
                        mutable.value = LegState.Connected(nowMillis(), evidence)
                    }

                    mutable.value == LegState.Placing -> {
                        mutable.value = LegState.Ringing
                    }
                }
            }

            is LegObservation.Gone -> {
                mutable.value = LegState.Ended(endCause(observation.cause), observation.detail)
            }
        }
    }

    /** The provider lost sight of the call; [check] tells the owner what to verify. */
    fun lost(
        what: String,
        check: String,
    ) {
        if (ended) return
        mutable.value =
            if (hangUpRequested) {
                LegState.Ended(EndCause.LOCAL_HANGUP, "Hang-up requested; then lost $what.")
            } else {
                LegState.Ended(EndCause.FAILED, "Lost $what. $check")
            }
    }

    private fun endCause(cause: EndCause?) =
        when {
            hangUpRequested || cause == EndCause.LOCAL_HANGUP -> EndCause.LOCAL_HANGUP
            cause == EndCause.BUSY -> EndCause.BUSY
            cause == EndCause.FAILED -> EndCause.FAILED
            !answered -> EndCause.NO_ANSWER
            cause == EndCause.REMOTE_HANGUP -> EndCause.REMOTE_HANGUP
            else -> cause ?: EndCause.DROPPED
        }
}

/**
 * Agent audio and in-band tones waiting for a local call uplink. The brain delivers audio in
 * generation bursts; [run] releases it in real-time frames, filling gaps with silence, and
 * [cancel] drops whatever has not been released yet.
 */
class PacedUplink(
    val sampleRateHz: Int,
) {
    val frameSamples = sampleRateHz / FRAMES_PER_SECOND
    private val queue = ArrayDeque<Short>()
    private var cleared = 0

    fun enqueue(frame: PcmFrame) {
        val samples = if (frame.sampleRateHz == sampleRateHz) frame.samples else resample(frame.samples, frame.sampleRateHz, sampleRateHz)
        synchronized(queue) { samples.forEach(queue::addLast) }
    }

    fun cancel() {
        synchronized(queue) {
            queue.clear()
            cleared++
        }
    }

    /** Returns once [samples] have been released, so a stopped keypad sequence never counts queued keys. */
    suspend fun playAndAwait(samples: ShortArray) {
        val generation =
            synchronized(queue) {
                samples.forEach(queue::addLast)
                cleared
            }
        while (synchronized(queue) { queue.isNotEmpty() && cleared == generation }) delay(FRAME_MILLIS)
        check(synchronized(queue) { cleared } == generation) { "Queued keypad tones were cleared before they finished playing." }
    }

    /**
     * Returns once everything queued so far has been released, [cancel] cleared it, or [stopped] says
     * nothing will be released any more (the call ended). Audio queued after this starts waiting can
     * extend the wait; the agent queues none once it has hung up.
     */
    suspend fun awaitDrained(stopped: () -> Boolean = { false }) {
        val generation = synchronized(queue) { cleared }
        while (!stopped() && synchronized(queue) { queue.isNotEmpty() && cleared == generation }) delay(FRAME_MILLIS)
    }

    /** The next frame of uplink audio, padded with silence. */
    fun nextFrame(): ShortArray =
        synchronized(queue) {
            ShortArray(frameSamples) { queue.removeFirstOrNull() ?: 0 }
        }

    /** Writes one frame every 20 ms of [nanoTime] until [write] returns false or the coroutine ends. */
    suspend fun run(
        nanoTime: () -> Long = System::nanoTime,
        write: (ShortArray) -> Boolean,
    ) {
        var next = nanoTime()
        while (currentCoroutineContext().isActive) {
            if (!write(nextFrame())) return
            next += FRAME_MILLIS * 1_000_000
            delay(((next - nanoTime()) / 1_000_000).coerceAtLeast(0))
        }
    }

    companion object {
        const val FRAME_MILLIS = 20L
        private const val FRAMES_PER_SECOND = 50
        private val DTMF =
            mapOf(
                '1' to (697 to 1209),
                '2' to (697 to 1336),
                '3' to (697 to 1477),
                '4' to (770 to 1209),
                '5' to (770 to 1336),
                '6' to (770 to 1477),
                '7' to (852 to 1209),
                '8' to (852 to 1336),
                '9' to (852 to 1477),
                '*' to (941 to 1209),
                '0' to (941 to 1336),
                '#' to (941 to 1477),
                'A' to (697 to 1633),
                'B' to (770 to 1633),
                'C' to (852 to 1633),
                'D' to (941 to 1633),
            )

        /**
         * In-band DTMF, the fallback when a route has no out-of-band keypad: 160 ms tones with 120 ms
         * gaps, which carrier codecs pass. They do reach the line, but systems that listen only for
         * out-of-band DTMF (Twilio Gather included, on the device) ignore them; receipts say so.
         */
        fun dtmfTones(
            digits: String,
            sampleRateHz: Int,
        ): ShortArray {
            val tone = sampleRateHz * 160 / 1000
            val gap = sampleRateHz * 120 / 1000
            val out = ArrayList<Short>()
            digits.uppercase().forEach { digit ->
                val (low, high) = DTMF[digit] ?: throw IllegalArgumentException("Not a keypad key: $digit")
                for (i in 0 until tone) {
                    val t = i.toDouble() / sampleRateHz
                    out += ((sin(2 * PI * low * t) + sin(2 * PI * high * t)) * 7_000).toInt().toShort()
                }
                repeat(gap) { out += 0 }
            }
            return out.toShortArray()
        }

        fun resample(
            samples: ShortArray,
            fromHz: Int,
            toHz: Int,
        ): ShortArray {
            val count = (samples.size.toLong() * toHz / fromHz).toInt()
            return ShortArray(count) { i ->
                val position = i.toDouble() * fromHz / toHz
                val index = position.toInt().coerceAtMost(samples.size - 1)
                val next = (index + 1).coerceAtMost(samples.size - 1)
                val fraction = position - index
                (samples[index] * (1 - fraction) + samples[next] * fraction).toInt().toShort()
            }
        }
    }
}
