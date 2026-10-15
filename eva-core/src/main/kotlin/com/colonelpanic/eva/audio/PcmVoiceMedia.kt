package com.colonelpanic.eva.audio

import kotlinx.coroutines.flow.Flow

/** PCM16 little-endian, mono, 24 kHz. Playback completion observes the device playback head. */
interface PcmVoiceMedia : VoiceMediaSession {
    val playback: Flow<Playback>

    sealed interface Playback {
        data class Started(
            val responseId: String,
        ) : Playback

        data class Finished(
            val responseId: String,
            val played: Boolean,
        ) : Playback

        data class Failed(
            val message: String,
        ) : Playback
    }

    suspend fun start(onAudio: (ByteArray) -> Unit)

    fun enqueue(
        responseId: String,
        audio: ByteArray,
    )

    fun finish(responseId: String)

    /** Flushes local audio and returns milliseconds actually played for the interrupted response. */
    fun interrupt(): Map<String, Long>
}
