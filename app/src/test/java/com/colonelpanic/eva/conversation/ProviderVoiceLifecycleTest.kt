package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptConfigException
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.providers.CallIdentity
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderVoiceLifecycleTest {
    @Test
    fun `a voice session takes no typed submissions and disconnect releases both connections`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertTrue(provider.request.instructions.contains("supplied tools"))
            assertTrue(provider.request.instructions.contains("5 additional lookup queries"))
            assertTrue(provider.request.instructions.contains("first name or last name by itself"))
            assertTrue(provider.request.instructions.contains("generate and rank the plausible spellings"))
            controller.submit("Open a map")
            advanceUntilIdle()
            assertEquals(0, provider.submissions)
            controller.disconnect()
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertFalse(controller.state.value.voiceMode)
            assertEquals(RealtimeMediaState.Closed, controller.state.value.mediaState)
        }

    @Test
    fun `closed event terminates an otherwise open stream and releases media`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.Closed)
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertFalse(controller.state.value.voiceMode)
            assertEquals(RealtimeMediaState.Closed, controller.state.value.mediaState)
        }

    @Test
    fun `voice lookup retry setting is included when the session opens`() =
        runTest {
            val provider = VoiceProvider()
            val controller = controller(provider, { VoiceMedia() }, voiceLookupRetries = { 8 })
            advanceUntilIdle()

            controller.connectVoice("test")
            advanceUntilIdle()

            assertTrue(provider.request.instructions.contains("8 additional lookup queries"))
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `provider failure closes its session and preserves the failure message`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.Failure("Connection lost"))
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals("Connection lost", controller.state.value.providerMessage)
            assertFalse(controller.state.value.voiceMode)
            assertEquals(RealtimeMediaState.Closed, controller.state.value.mediaState)
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
            controller.connectVoice("old")
            advanceUntilIdle()
            controller.connectVoice("current")
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

    @Test
    fun `a delegated voice turn is the input and its tool call executes on the phone`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            var executions = 0
            val definition =
                CapabilityDefinition(
                    "test.timer",
                    "Set a timer",
                    "Start a countdown timer",
                    Json
                        .parseToJsonElement(
                            """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1}},
                            "required":["seconds"],"additionalProperties":false}""",
                        ).jsonObject,
                )
            val registry =
                CapabilityRegistry(
                    mapOf(
                        definition.id to
                            object : ExecutionBackend {
                                override suspend fun unavailableReason(): String? = null

                                override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                                    executions++
                                    return ExecutionOutcome(InvocationStatus.HANDED_OFF, "Timer started.")
                                }
                            },
                    ),
                    listOf(definition),
                )
            val repository = MemoryInvocationRepository()
            val controller =
                ProviderSessionController(
                    registry,
                    CapabilityDispatcher(registry, repository),
                    repository,
                    this,
                    { provider },
                    { media },
                    { _, _ -> provider },
                )
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertEquals(
                listOf("test.timer", "eva.session.end"),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
            val revision = provider.request.catalog.revision

            fun call(
                turn: String,
                callId: String,
            ) = ProviderEvent.ToolCallReady(
                CallIdentity("test-epoch", "test-session", "voice:$turn", "voice:$turn", turn, revision, callId),
                definition.id,
                buildJsonObject { put("seconds", 180) },
            )

            // The provider observed the turn starting before the user transcript landed.
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(ProviderEvent.Transcript("user", "Set a timer for three minutes"))
            provider.channel.send(call("turn-1", "call-1"))
            advanceUntilIdle()
            assertEquals(1, executions)
            assertEquals("HANDED_OFF", provider.results.single().status)
            assertEquals(
                "voice:turn-1",
                provider.results
                    .single()
                    .call.inputId,
            )
            assertEquals("Set a timer for three minutes", repository.history().single().request)
            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Timer started.", false))
            provider.channel.send(ProviderEvent.ResponseEnded("voice:turn-1", "completed"))
            advanceUntilIdle()
            assertEquals(ProviderStatus.CONNECTED, controller.state.value.providerStatus)
            assertEquals(
                "Timer started.",
                controller.state.value.entries
                    .first { it.id == "voice:turn-1" }
                    .response,
            )

            // A second spoken request gets a fresh turn and a fresh one-action budget.
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-2", "voice:turn-2"))
            provider.channel.send(call("turn-2", "call-2"))
            advanceUntilIdle()
            assertEquals(2, executions)
            assertEquals("Voice request", repository.history().first { it.callId.endsWith("call-2") }.request)
            provider.channel.send(ProviderEvent.ResponseEnded("voice:turn-2", "completed"))
            advanceUntilIdle()
            controller.disconnect()
            advanceUntilIdle()
            assertTrue(media.closed)
        }

    @Test
    fun `contact keywords reach a spoken session and are never gathered for a typed one`() =
        runTest {
            val provider = VoiceProvider()
            var reads = 0
            val controller =
                controller(provider, { VoiceMedia() }, voiceKeywords = {
                    reads++
                    listOf("Ana Beltrán")
                })
            advanceUntilIdle()

            controller.connect("test")
            advanceUntilIdle()
            assertEquals(emptyList<String>(), provider.request.keywords)
            assertEquals(0, reads)
            controller.disconnect()
            advanceUntilIdle()

            controller.connectVoice("test")
            advanceUntilIdle()
            assertEquals(listOf("Ana Beltrán"), provider.request.keywords)
            assertEquals(1, reads)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `only a spoken session offers to end the conversation`() =
        runTest {
            val provider = VoiceProvider()
            val controller = controller(provider, { VoiceMedia() })
            advanceUntilIdle()

            controller.connect("test")
            advanceUntilIdle()
            assertEquals(
                emptyList<String>(),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
            val typedRevision = provider.request.catalog.revision
            controller.disconnect()
            advanceUntilIdle()

            controller.connectVoice("test")
            advanceUntilIdle()
            assertEquals(
                listOf("eva.session.end"),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
            assertTrue(provider.request.instructions.contains("end the conversation with its tool"))
            assertTrue(provider.request.catalog.revision != typedRevision)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `the prompt file decides the instructions, the tool wording, and which tools are offered`() =
        runTest {
            val provider = VoiceProvider()
            val controller = controller(provider, { VoiceMedia() })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            val stock = provider.request
            assertTrue(stock.instructions.startsWith("You are EVA"))
            assertTrue(stock.instructions.contains("This call is for one request"))
            assertFalse(stock.instructions.contains("This call stays open"))
            assertTrue(stock.instructions.contains("The user's current local time is"))
            assertTrue(
                stock.catalog.tools
                    .single()
                    .description
                    .contains("as soon as the user's request is complete"),
            )
            controller.disconnect()
            advanceUntilIdle()

            val open = VoiceProvider()
            val openController = controller(open, { VoiceMedia() }, prompt = { PromptDefaults.config.toggle("open-conversation", true) })
            advanceUntilIdle()
            openController.connectVoice("test")
            advanceUntilIdle()
            assertTrue(open.request.instructions.contains("This call stays open"))
            assertFalse(open.request.instructions.contains("This call is for one request"))
            assertTrue(
                open.request.catalog.tools
                    .single()
                    .description
                    .contains("A finished request is not a reason to call it"),
            )
            // The tool reads differently, so a connection made under one file cannot be confused with the other.
            assertTrue(open.request.catalog.revision != stock.catalog.revision)
            openController.disconnect()
            advanceUntilIdle()

            val muted = VoiceProvider()
            val mutedController =
                controller(muted, { VoiceMedia() }, prompt = {
                    PromptConfig(
                        listOf(PromptComponent(id = "no-hangup", instruction = "Never hang up.", hide = listOf("eva.session.end"))),
                    )
                })
            advanceUntilIdle()
            mutedController.connectVoice("test")
            advanceUntilIdle()
            assertEquals("Never hang up.", muted.request.instructions)
            assertEquals(
                emptyList<String>(),
                muted.request.catalog.tools
                    .map { it.capabilityId },
            )
            mutedController.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `a broken prompt file fails the connection with its own message`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media }, prompt = { throw PromptConfigException("Line 4, column 3: no such field") })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals("Line 4, column 3: no such field", controller.state.value.providerMessage)
            assertEquals(0, provider.opens)
        }

    @Test
    fun `ending the conversation waits for the goodbye to finish playing`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()

            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(ProviderEvent.AssistantSpeaking(true))
            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Goodbye!", false))
            provider.channel.send(endCall(provider, "turn-1"))
            runCurrent()
            advanceTimeBy(5_000)
            assertEquals(ProviderStatus.CONNECTED, controller.state.value.providerStatus)
            assertFalse(media.closed)

            provider.channel.send(ProviderEvent.AssistantSpeaking(false))
            runCurrent()
            assertFalse(media.closed)
            advanceTimeBy(1_000)
            assertTrue(media.closed)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals(1, provider.closes)
            // Hanging up is not a phone action, and answering it would prompt the model to speak again.
            assertEquals(emptyList<CorrelatedToolResult>(), provider.results)
            assertEquals(
                "Goodbye!",
                controller.state.value.entries
                    .first { it.id == "voice:turn-1" }
                    .response,
            )
        }

    @Test
    fun `ending without reported speech hangs up at once and an unreported end is bounded`() =
        runTest {
            val provider = VoiceProvider()
            val media = VoiceMedia()
            val controller = controller(provider, { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(endCall(provider, "turn-1"))
            runCurrent()
            assertTrue(media.closed)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals(
                "Conversation ended.",
                controller.state.value.entries
                    .first { it.id == "voice:turn-1" }
                    .response,
            )

            val stuck = VoiceProvider()
            val stuckMedia = VoiceMedia()
            val second = controller(stuck, { stuckMedia })
            advanceUntilIdle()
            second.connectVoice("test")
            advanceUntilIdle()
            stuck.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            stuck.channel.send(ProviderEvent.AssistantSpeaking(true))
            stuck.channel.send(endCall(stuck, "turn-1"))
            runCurrent()
            assertFalse(stuckMedia.closed)
            advanceTimeBy(11_000)
            assertTrue(stuckMedia.closed)
        }

    @Test
    fun `the model hanging up is signalled to call surfaces, and a user disconnect is not`() =
        runTest {
            val stopped = VoiceProvider()
            val byUser = controller(stopped, { VoiceMedia() })
            advanceUntilIdle()
            var userHangUps = 0
            val userWatcher = launch { byUser.hangUps.collect { userHangUps++ } }
            runCurrent()
            byUser.connectVoice("test")
            advanceUntilIdle()
            byUser.disconnect()
            advanceUntilIdle()
            assertEquals(0, userHangUps)
            userWatcher.cancel()

            val provider = VoiceProvider()
            val byModel = controller(provider, { VoiceMedia() })
            advanceUntilIdle()
            var modelHangUps = 0
            val modelWatcher = launch { byModel.hangUps.collect { modelHangUps++ } }
            runCurrent()
            byModel.connectVoice("test")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(endCall(provider, "turn-1"))
            advanceUntilIdle()
            assertEquals(1, modelHangUps)
            modelWatcher.cancel()
        }

    private fun endCall(
        provider: VoiceProvider,
        turn: String,
    ) = ProviderEvent.ToolCallReady(
        CallIdentity("test-epoch", "test-session", "voice:$turn", "voice:$turn", turn, provider.request.catalog.revision, "end-$turn"),
        "eva.session.end",
        buildJsonObject {},
    )

    private fun TestScope.controller(
        provider: VoiceProvider,
        mediaFactory: () -> RealtimeMediaSession,
        voiceProviderFactory: suspend (String, RealtimeMediaSession) -> ConversationProvider = { _, _ -> provider },
        voiceLookupRetries: () -> Int = { 5 },
        voiceKeywords: suspend () -> List<String> = { emptyList() },
        prompt: suspend () -> PromptConfig = { PromptDefaults.config },
    ): ProviderSessionController {
        val registry = CapabilityRegistry(emptyMap(), emptyList())
        val repository = MemoryInvocationRepository()
        return ProviderSessionController(
            registry = registry,
            dispatcher = CapabilityDispatcher(registry, repository),
            repository = repository,
            scope = this,
            providerFactory = { provider },
            mediaFactory = mediaFactory,
            voiceProviderFactory = voiceProviderFactory,
            voiceLookupRetries = voiceLookupRetries,
            voiceKeywords = voiceKeywords,
            prompt = prompt,
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
        var opens = 0
        var closes = 0
        var submissions = 0

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            opens++
            this.request = request
            withContext(NonCancellable) { openGate?.await() }
            channel.send(ProviderEvent.Connected("test-session", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) {
            submissions++
        }

        override suspend fun requestResponse(request: ResponseRequest) = Unit

        val results = mutableListOf<CorrelatedToolResult>()

        override suspend fun submitToolResult(result: CorrelatedToolResult) {
            results.add(result)
        }

        override suspend fun close() {
            closes++
            channel.close()
        }
    }

    private class VoiceMedia : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Connected(true))
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        override val events = emptyFlow<String>()
        override val eventsReady = MutableStateFlow(false)
        var closed = false

        override fun send(event: String) = Unit

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
