package com.colonelpanic.eva.ui

import com.colonelpanic.eva.audio.AudioFocusState
import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaFailure
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.audio.isActive
import com.colonelpanic.eva.audio.voiceStatusLabel
import com.colonelpanic.eva.conversation.ConversationState
import com.colonelpanic.eva.conversation.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceControlsTest {
    private val live = MediaControls(focus = AudioFocusState.HELD)

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
    fun `composer hint follows the voice session through setup and connection`() {
        val connected = ConversationState(isLoading = false, providerStatus = ProviderStatus.CONNECTED, voiceMode = true)
        assertEquals("Speak to EVA, or disconnect to use text.", composerHint(connected.copy(mediaControls = live)))
        assertEquals("Setting up voice…", composerHint(connected.copy(providerStatus = ProviderStatus.CONNECTING)))
    }

    @Test
    fun `denial messages match whether the system will ask again`() {
        assertTrue(microphoneDenialMessage(MicrophoneDenial("l", canAskAgain = true)).contains("cannot hold a voice conversation"))
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
