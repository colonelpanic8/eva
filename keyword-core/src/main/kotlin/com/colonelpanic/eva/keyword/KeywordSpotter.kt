package com.colonelpanic.eva.keyword

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mono PCM16 microphone audio. [capturedAtNanos] is when the last sample was captured, on the
 * same monotonic clock the spotter uses for [KeywordEvent.detectedAtNanos].
 */
class PcmFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val capturedAtNanos: Long,
) {
    companion object {
        /** Little-endian PCM16 bytes, as captured for the host link. */
        fun fromLittleEndian(
            bytes: ByteArray,
            sampleRate: Int,
            capturedAtNanos: Long,
        ): PcmFrame {
            val samples =
                ShortArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
            return PcmFrame(samples, sampleRate, capturedAtNanos)
        }
    }
}

/**
 * One detection. [speechOnsetNanos] is the start of the speech burst the phrase belongs to, when
 * one could be found; it is audio time, so onset to detection includes the spotter's own delay.
 */
data class KeywordEvent(
    val phrase: String,
    val score: Float,
    val detectedAtNanos: Long,
    val speechOnsetNanos: Long?,
)

sealed interface SpotterStatus {
    data object Idle : SpotterStatus

    data object Running : SpotterStatus

    data class Unavailable(
        val reason: String,
    ) : SpotterStatus
}

/**
 * An on-device keyword spotter over raw microphone audio. It sees every captured frame whatever
 * the voice state is, and sends nothing anywhere; detections are only published on [events].
 */
interface KeywordSpotter : AutoCloseable {
    val events: Flow<KeywordEvent>

    val status: StateFlow<SpotterStatus>

    /** The capture time of the most recent frame that sounded like speech. */
    val lastSpeechNanos: StateFlow<Long?>

    /** The phrase ids this spotter can detect. */
    val phrases: Set<String>

    fun start(frames: Flow<PcmFrame>)

    override fun close()
}
