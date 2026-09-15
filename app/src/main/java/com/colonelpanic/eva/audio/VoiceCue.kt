package com.colonelpanic.eva.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The two moments a voice session changes what the microphone is doing. Nothing on screen is
 * guaranteed to be visible when a call starts from the assistant gesture or ends on its own,
 * so both edges are announced audibly.
 */
enum class VoiceCue { Started, Ended }

/** Plays a cue. Implementations return before the sound finishes. */
internal fun interface VoiceCuePlayer {
    fun play(cue: VoiceCue)
}

internal const val CUE_SAMPLE_RATE = 44_100

private const val NOTE_MILLIS = 90
private const val FADE_MILLIS = 8
private const val AMPLITUDE = 0.35

/** Rising when the microphone opens, falling when it closes, so the two are never confused. */
private fun notes(cue: VoiceCue) =
    when (cue) {
        VoiceCue.Started -> listOf(784.0, 1046.5)
        VoiceCue.Ended -> listOf(1046.5, 784.0)
    }

/**
 * Signed 16-bit mono samples for [cue]. Each note fades in and out, which keeps the note
 * boundary and both ends of the buffer at silence instead of a click.
 */
internal fun cueSamples(
    cue: VoiceCue,
    sampleRate: Int = CUE_SAMPLE_RATE,
): ShortArray {
    val note = sampleRate * NOTE_MILLIS / 1_000
    val fade = sampleRate * FADE_MILLIS / 1_000
    val samples = ShortArray(note * notes(cue).size)
    notes(cue).forEachIndexed { index, hertz ->
        for (offset in 0 until note) {
            val envelope = min(1.0, min(offset + 1, note - offset).toDouble() / fade)
            val value = sin(2.0 * PI * hertz * offset / sampleRate) * envelope * AMPLITUDE
            samples[index * note + offset] = (value * Short.MAX_VALUE).toInt().toShort()
        }
    }
    return samples
}
