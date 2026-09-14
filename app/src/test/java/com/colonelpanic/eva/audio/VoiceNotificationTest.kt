package com.colonelpanic.eva.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNotificationTest {
    private val connected = RealtimeMediaState.Connected(remoteAudio = true)
    private val live = MediaControls(focus = AudioFocusState.HELD)

    @Test
    fun `the notification's microphone action names what tapping it will do`() {
        assertEquals("Mute", voiceNotificationContent(VoiceSessionStatus(connected, live)).microphoneAction)
        assertEquals(
            "Unmute",
            voiceNotificationContent(VoiceSessionStatus(connected, live.copy(microphoneMuted = true))).microphoneAction,
        )
    }

    @Test
    fun `the notification reports the same status the in-app controls show`() {
        val status = VoiceSessionStatus(connected, live.copy(microphoneMuted = true))
        assertEquals("Voice connected, mic muted", voiceNotificationContent(status).text)
    }
}
