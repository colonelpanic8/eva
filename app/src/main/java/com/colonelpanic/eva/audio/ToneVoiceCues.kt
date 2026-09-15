package com.colonelpanic.eva.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Plays the session cues as generated tones. Sonification usage keeps them on whatever the
 * phone is already using for alerts, so a cue is audible both while the session holds
 * communication routing and after it has handed that routing back.
 */
internal class ToneVoiceCues(
    private val sampleRate: Int = CUE_SAMPLE_RATE,
) : VoiceCuePlayer {
    override fun play(cue: VoiceCue) {
        val samples = cueSamples(cue, sampleRate)
        // A cue is never worth failing a session over: a device that refuses the track stays silent.
        thread(name = "eva-voice-cue", isDaemon = true) { runCatching { sound(samples) } }
    }

    private fun sound(samples: ShortArray) {
        val bytes = samples.size * Short.SIZE_BYTES
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            Thread.sleep(samples.size * 1_000L / sampleRate + TAIL_MILLIS)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val TAIL_MILLIS = 120L
    }
}
