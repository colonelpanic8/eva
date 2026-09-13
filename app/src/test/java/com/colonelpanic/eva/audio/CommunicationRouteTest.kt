package com.colonelpanic.eva.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunicationRouteTest {
    @Test
    fun `a paired headset outranks the speaker`() {
        val available = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, preferredCommunicationDevice(available, speakerphone = true))
    }

    @Test
    fun `a wired headset outranks the speaker`() {
        val available = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, preferredCommunicationDevice(available, speakerphone = true))
    }

    @Test
    fun `the speaker is only forced when nothing is worn`() {
        val speakerOnly = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, preferredCommunicationDevice(speakerOnly, speakerphone = true))
        assertNull(preferredCommunicationDevice(speakerOnly, speakerphone = false))
    }
}
