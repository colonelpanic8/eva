package com.colonelpanic.eva.assist

import com.colonelpanic.eva.conversation.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantStartTest {
    @Test
    fun `an idle phone opens a session for what is about to be said`() {
        assertEquals(
            AssistantStart.CONNECT,
            assistantStart(microphoneGranted = true, voiceMode = false, providerStatus = ProviderStatus.DISCONNECTED),
        )
    }

    @Test
    fun `a live session is controlled, not replaced`() {
        assertEquals(
            AssistantStart.JOIN,
            assistantStart(microphoneGranted = true, voiceMode = true, providerStatus = ProviderStatus.CONNECTED),
        )
        assertEquals(
            AssistantStart.JOIN,
            assistantStart(microphoneGranted = true, voiceMode = true, providerStatus = ProviderStatus.CONNECTING),
        )
    }

    @Test
    fun `a typed connection is not a voice session`() {
        assertEquals(
            AssistantStart.CONNECT,
            assistantStart(microphoneGranted = true, voiceMode = false, providerStatus = ProviderStatus.CONNECTED),
        )
    }

    @Test
    fun `without the microphone nothing starts, whatever the connection`() {
        assertEquals(
            AssistantStart.NEEDS_MICROPHONE,
            assistantStart(microphoneGranted = false, voiceMode = false, providerStatus = ProviderStatus.DISCONNECTED),
        )
        assertEquals(
            AssistantStart.NEEDS_MICROPHONE,
            assistantStart(microphoneGranted = false, voiceMode = true, providerStatus = ProviderStatus.CONNECTED),
        )
    }
}
