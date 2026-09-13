package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.MicrophoneMode
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderVoiceLifecycleTest {
    @Test
    fun `listen only exposes no tools or text submissions and background releases both connections`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            var selectedMode: MicrophoneMode? = null
            val controller =
                controller(provider, {
                    selectedMode = it
                    media
                })
            advanceUntilIdle()
            controller.connectVoice("test", listenOnly = true)
            advanceUntilIdle()
            assertEquals(MicrophoneMode.NONE, selectedMode)
            assertTrue(
                provider.request.catalog.tools
                    .isEmpty(),
            )
            assertTrue(provider.request.instructions.contains("No phone actions"))
            controller.submit("Open a map")
            advanceUntilIdle()
            assertEquals(0, provider.submissions)
            controller.stopVoiceOnBackground()
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
        }

    @Test
    fun `closed event terminates an otherwise open stream and releases media`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test", listenOnly = false)
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.Closed)
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
        }

    @Test
    fun `provider failure closes its session and preserves the failure message`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test", listenOnly = false)
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.Failure("Connection lost"))
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals("Connection lost", controller.state.value.providerMessage)
        }

    @Test
    fun `late open from canceled attempt is closed without replacing the new session`() =
        runTest {
            val old = VoiceProvider(CompletableDeferred())
            val current = VoiceProvider()
            val oldMedia = VoiceMedia()
            val currentMedia = VoiceMedia()
            var attempts = 0
            val controller =
                controller(
                    current,
                    { if (attempts++ == 0) oldMedia else currentMedia },
                    { link, _ -> if (link == "old") old else current },
                )
            advanceUntilIdle()
            controller.connectVoice("old", listenOnly = false)
            advanceUntilIdle()
            controller.connectVoice("current", listenOnly = false)
            advanceUntilIdle()
            old.openGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, old.closes)
            assertTrue(oldMedia.closed)
            assertFalse(currentMedia.closed)
            assertEquals(0, current.closes)
            assertEquals(ProviderStatus.CONNECTED, controller.state.value.providerStatus)
            controller.disconnect()
            advanceUntilIdle()
            assertEquals(1, current.closes)
            assertTrue(currentMedia.closed)
        }

    private fun TestScope.controller(
        provider: VoiceProvider,
        mediaFactory: (MicrophoneMode) -> RealtimeMediaSession,
        voiceProviderFactory: suspend (String, RealtimeMediaSession) -> ConversationProvider = { _, _ -> provider },
    ): ProviderSessionController {
        val registry = CapabilityRegistry(emptyMap(), emptyList())
        val repository = MemoryInvocationRepository()
        return ProviderSessionController(
            registry,
            CapabilityDispatcher(registry, repository),
            repository,
            this,
            { provider },
            mediaFactory,
            voiceProviderFactory,
        )
    }

    private class VoiceProvider(
        val openGate: CompletableDeferred<Unit>? = null,
    ) : ConversationProvider,
        ConversationSession {
        override val connectionEpoch = "test-epoch"
        val channel = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        lateinit var request: SessionOpenRequest
        var closes = 0
        var submissions = 0

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            this.request = request
            withContext(NonCancellable) { openGate?.await() }
            channel.send(ProviderEvent.Connected("test-session", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) {
            submissions++
        }

        override suspend fun requestResponse(request: ResponseRequest) = Unit

        override suspend fun submitToolResult(result: CorrelatedToolResult) = error("Voice must not execute tools")

        override suspend fun close() {
            closes++
            channel.close()
        }
    }

    private class VoiceMedia : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Connected(true))
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        var closed = false

        override suspend fun createOffer() = "test-offer"

        override suspend fun acceptAnswer(sdp: String) = Unit

        override fun setMicrophoneMuted(muted: Boolean) {
            controls.value = controls.value.copy(microphoneMuted = muted)
        }

        override fun setPlaybackMuted(muted: Boolean) {
            controls.value = controls.value.copy(playbackMuted = muted)
        }

        override fun close() {
            closed = true
        }
    }
}
