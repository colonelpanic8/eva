package com.colonelpanic.eva.ui

import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaFailure
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControlsTest {
    private val live = MediaControls(focus = AudioFocusState.HELD, microphoneAvailable = true)
    private val listenOnly = MediaControls(focus = AudioFocusState.HELD, microphoneAvailable = false)

    @Test
    fun `status labels distinguish transport, provider audio, focus, and failure`() {
        assertEquals("Voice connected, no provider audio yet", voiceStatusLabel(RealtimeMediaState.Connected(false), live))
        assertEquals("Voice connected", voiceStatusLabel(RealtimeMediaState.Connected(true), live))
        assertEquals(
            "Voice paused: another app has audio",
            voiceStatusLabel(RealtimeMediaState.Connected(true), live.copy(focus = AudioFocusState.LOST)),
        )
        assertEquals("Voice connected, mic muted", voiceStatusLabel(RealtimeMediaState.Connected(true), live.copy(microphoneMuted = true)))
        assertEquals(
            MediaFailure.MicrophonePermissionRequired.message,
            voiceStatusLabel(RealtimeMediaState.Failed(MediaFailure.MicrophonePermissionRequired), live),
        )
    }

    @Test
    fun `listen-only never claims the microphone is on or mutable`() {
        assertEquals("Listen-only off", voiceStatusLabel(RealtimeMediaState.Idle, listenOnly))
        assertEquals("Connecting listen-only…", voiceStatusLabel(RealtimeMediaState.Connecting, listenOnly))
        assertEquals("Listen-only connected, your mic is off", voiceStatusLabel(RealtimeMediaState.Connected(true), listenOnly))
        assertEquals(
            "Listen-only connected, your mic is off",
            voiceStatusLabel(RealtimeMediaState.Connected(true), listenOnly.copy(microphoneMuted = true)),
        )
        assertEquals(
            "Listen-only connected, speaker stopped",
            voiceStatusLabel(RealtimeMediaState.Connected(true), listenOnly.copy(playbackMuted = true)),
        )
        assertEquals("Listen-only session · your microphone is off", voiceSessionLabel(false))
        assertEquals("Voice session · ask for a phone action or just talk", voiceSessionLabel(true))
    }

    @Test
    fun `composer hint tells listen-only users nothing is captured`() {
        val connected = ConversationState(isLoading = false, providerStatus = ProviderStatus.CONNECTED, voiceMode = true)
        assertEquals("Speak to EVA, or disconnect to use text.", composerHint(connected.copy(mediaControls = live)))
        assertEquals(
            "Listening only: EVA can speak, but nothing you say is captured. Disconnect to use text.",
            composerHint(connected.copy(mediaControls = listenOnly)),
        )
        assertEquals("Setting up voice…", composerHint(connected.copy(providerStatus = ProviderStatus.CONNECTING)))
    }

    @Test
    fun `denial messages match whether the system will ask again`() {
        assertTrue(microphoneDenialMessage(MicrophoneDenial("l", canAskAgain = true)).contains("listen-only"))
        assertTrue(microphoneDenialMessage(MicrophoneDenial("l", canAskAgain = false)).contains("system settings"))
    }

    @Test
    fun `only live transport states offer stop`() {
        assertTrue(RealtimeMediaState.Preparing.isActive())
        assertTrue(RealtimeMediaState.Connected(false).isActive())
        assertFalse(RealtimeMediaState.Idle.isActive())
        assertFalse(RealtimeMediaState.Failed(MediaFailure.PeerFailed).isActive())
        assertFalse(RealtimeMediaState.Closed.isActive())
    }
}
