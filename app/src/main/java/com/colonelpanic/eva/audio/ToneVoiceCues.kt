package com.colonelpanic.eva.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.concurrent.thread

/**
 * Plays the session cues as generated tones. Neither usage is silenced by the ringer: the start
 * cue rides the live call route at call volume, and the end cue, played after that route is
 * handed back, uses the assistant stream at media volume rather than the earpiece.
 */
internal class ToneVoiceCues(
    private val sampleRate: Int = CUE_SAMPLE_RATE,
) : VoiceCuePlayer {
    override fun play(cue: VoiceCue) {
        val samples = cueSamples(cue, sampleRate)
        // A cue is never worth failing a session over: a device that refuses the track stays silent.
        thread(name = "eva-voice-cue", isDaemon = true) { runCatching { sound(samples, usage(cue)) } }
    }

    private fun usage(cue: VoiceCue): Int =
        when (cue) {
            VoiceCue.Started -> {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            }

            VoiceCue.Ended -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes.USAGE_ASSISTANT
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
            }
        }

    private fun sound(
        samples: ShortArray,
        usage: Int,
    ) {
        val bytes = samples.size * Short.SIZE_BYTES
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(usage)
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
