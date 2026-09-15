package com.colonelpanic.eva.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VoiceCueTest {
    @Test
    fun `each cue is a distinct pair of notes that begins and ends at silence`() {
        val started = cueSamples(VoiceCue.Started, SAMPLE_RATE)
        val ended = cueSamples(VoiceCue.Ended, SAMPLE_RATE)

        assertEquals(started.size, ended.size)
        assertTrue(started.any { abs(it.toInt()) > QUIET })
        assertFalse(started.contentEquals(ended))
        for (samples in listOf(started, ended)) {
            // An abrupt edge where a note starts or stops is heard as a click, not a chime.
            for (edge in listOf(0, samples.size / 2 - 1, samples.size / 2, samples.size - 1)) {
                assertTrue("sample $edge is ${samples[edge]}", abs(samples[edge].toInt()) <= QUIET)
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val QUIET = 400
    }
}
