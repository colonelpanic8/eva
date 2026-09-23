package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.CapabilitySource
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.conversation.prompt.PromptComponent
import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptConfigException
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.VoiceCallMode
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadControllerTest {
    private val repository = MemoryInvocationRepository()
    private val store = MemoryConversationStore()
    private var executions = 0
    private val answers = mutableListOf<ThreadController.BackgroundAnswer>()

    private val action =
        CapabilityDefinition(
            "test.custom",
            "Custom action",
            "Execute a custom test action",
            schema(
                """{"type":"object","properties":{"place":{"type":"string","minLength":1}},"required":["place"],"additionalProperties":false}""",
            ),
        )
    private val lookup =
        CapabilityDefinition(
            "test.lookup",
            "Look something up",
            "Read-only lookup",
            schema(
                """{"type":"object","properties":{"query":{"type":"string","minLength":1}},"required":["query"],"additionalProperties":false}""",
            ),
            readOnly = true,
        )
    private val registry =
        CapabilityRegistry(
            mapOf(
                action.id to backend { ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened ${it.getValue("place")}") },
                lookup.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Found ${it.getValue("query")}") },
            ),
            listOf(action, lookup),
        )

    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun backend(outcome: (Map<String, String>) -> ExecutionOutcome) =
        object : ExecutionBackend {
            override suspend fun unavailableReason(): String? = null

            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                executions++
                return outcome(arguments)
            }
        }

    private fun TestScope.controller(
        provider: FakeProvider,
        registry: CapabilityRegistry = this@ThreadControllerTest.registry,
        background: FakeProvider = provider,
        media: (() -> RealtimeMediaSession)? = null,
        voiceProvider: FakeProvider = provider,
        voiceKeywords: suspend () -> List<String> = { emptyList() },
        hiddenCapabilities: () -> Set<String> = { emptySet() },
        prompt: suspend () -> PromptConfig = { PromptDefaults.config },
        awaitCapabilities: suspend () -> Unit = {},
    ) = ThreadController(
        registry = registry,
        dispatcher = CapabilityDispatcher(registry, repository),
        repository = repository,
        store = store,
        scope = liveScope(),
        providerFactory = { provider },
        mediaFactory = media,
        voiceProviderFactory = { _, _ -> voiceProvider },
        backgroundProviderFactory = { background },
        voiceKeywords = voiceKeywords,
        awaitCapabilities = awaitCapabilities,
        hiddenCapabilities = hiddenCapabilities,
        prompt = prompt,
        onBackgroundAnswer = { answers += it },
    )

    /**
     * The controller watches the store for as long as it lives, so it gets a scope that shares
     * the test scheduler without being a child of the test body.
     */
    private fun TestScope.liveScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun entry(
        controller: ThreadController,
        id: String,
    ) = controller.state.value.entries
        .first { it.id == id }

    /** The store owns turn identity; a provider's input id is only meaningful to its own leg. */
    private suspend fun latestTurn(controller: ThreadController) = store.turns(controller.state.value.threadId!!).last().id

    // ---- text ----

    @Test
    fun `registry changes reject stale attached actions and new connections see additions`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            val before = provider.request.catalog.revision
            val added = action.copy(id = "extension.extra", title = "Extra action")
            registry.replace(
                mapOf(
                    action.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "ok") },
                    added.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "extra") },
                ),
                listOf(action, added),
            )
            controller.submit("Do action")
            advanceUntilIdle()
            provider.call("stale", action.id, "place" to "Park")
            advanceUntilIdle()
            assertEquals(0, executions)
            assertEquals("NOT_EXECUTED", provider.results.single().status)
            controller.disconnect()
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            assertNotEquals(before, provider.request.catalog.revision)
            assertTrue(
                provider.request.catalog.tools
                    .any { it.capabilityId == added.id },
            )
        }

    @Test
    fun `imported mutations can follow reads and earlier mutations in one request`() =
        runTest {
            val imported = action.copy(source = CapabilitySource("plugin:test", "Test plugin"))
            registry.replace(
                mapOf(
                    imported.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Changed") },
                    lookup.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Found") },
                ),
                listOf(imported, lookup),
            )
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            controller.submit("Find and change it")
            advanceUntilIdle()
            provider.call("read", lookup.id, "query" to "Park")
            advanceUntilIdle()
            provider.call("write", imported.id, "place" to "Park")
            advanceUntilIdle()
            provider.call("write-again", imported.id, "place" to "Beach")
            advanceUntilIdle()
            assertEquals(3, executions)
            assertEquals(listOf("COMPLETED", "COMPLETED", "COMPLETED"), provider.results.map { it.status })
        }

    @Test
    fun `a typed request runs its action on the phone and the answer closes the turn`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            assertEquals(
                "Text session",
                controller.state.value.entries
                    .single { it.status == EntryStatus.SESSION }
                    .response,
            )
            controller.submit("Please show me the park")
            advanceUntilIdle()
            assertTrue(controller.state.value.working)
            provider.call("first", action.id, "place" to "Park")
            advanceUntilIdle()
            assertEquals(1, executions)
            assertEquals("HANDED_OFF", provider.results.single().status)
            assertEquals("Opened Park", provider.results.single().message)
            val turn = latestTurn(controller)
            assertEquals(turn, entry(controller, "provider:session:first").parentId)
            assertEquals(turn, repository.history().single().turnId)
            provider.channel.send(ProviderEvent.AssistantText(provider.input.id, "The park is open.", false))
            provider.channel.send(ProviderEvent.ResponseEnded(provider.input.id, "completed"))
            advanceUntilIdle()
            assertFalse(controller.state.value.isSubmitting)
            assertFalse(controller.state.value.working)
            assertEquals("The park is open.", entry(controller, turn).response)
            assertEquals(TurnStatus.ANSWERED, store.turns(controller.state.value.threadId!!).single().status)
            assertEquals("Please show me the park", store.threads().single().title)
            controller.disconnect()
            advanceUntilIdle()
            assertEquals(
                listOf("Text session", "Session ended by you"),
                controller.state.value.entries
                    .filter {
                        it.status == EntryStatus.SESSION
                    }.map { it.response },
            )
        }

    @Test
    fun `multiple side effects and lookups run in one request`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open two places")
            advanceUntilIdle()
            provider.call("first", action.id, "place" to "Park")
            provider.call("second", action.id, "place" to "Beach")
            provider.call("look-1", lookup.id, "query" to "a")
            provider.call("look-2", lookup.id, "query" to "b")
            advanceUntilIdle()
            assertEquals(4, executions)
            assertEquals(
                mapOf("first" to "HANDED_OFF", "second" to "HANDED_OFF", "look-1" to "COMPLETED", "look-2" to "COMPLETED"),
                provider.results.associate { it.call.callId to it.status },
            )
        }

    @Test
    fun `uncertain mutations block queued changes but permit verification reads across rehoming`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            registry.replace(
                mapOf(
                    action.id to
                        object : ExecutionBackend {
                            override suspend fun unavailableReason(): String? = null

                            override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                                executions++
                                gate.await()
                                return ExecutionOutcome(InvocationStatus.UNKNOWN, "Reply lost")
                            }
                        },
                    lookup.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Checked") },
                ),
                listOf(action, lookup),
            )
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            controller.submit("Change two things")
            advanceUntilIdle()
            val turn = latestTurn(controller)
            provider.call("first", action.id, "place" to "Park")
            provider.call("second", action.id, "place" to "Beach")
            runCurrent()
            assertEquals(1, executions)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("UNKNOWN", "NOT_EXECUTED"), provider.results.map { it.status })
            controller.disconnect()
            advanceUntilIdle()
            background.input = ConversationInput(turn, "")
            background.call("retry", action.id, "place" to "Park")
            background.call("verify", lookup.id, "query" to "Park")
            advanceUntilIdle()
            assertEquals(listOf("NOT_EXECUTED", "COMPLETED"), background.results.map { it.status })
            assertEquals(2, executions)
        }

    @Test
    fun `the total action budget survives rehoming`() =
        runTest {
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            controller.submit("Do several things")
            advanceUntilIdle()
            val turn = latestTurn(controller)
            repeat(ThreadController.CALLS_PER_TURN) { provider.call("action-$it", action.id, "place" to "Place $it") }
            advanceUntilIdle()
            assertEquals(ThreadController.CALLS_PER_TURN, executions)
            controller.disconnect()
            advanceUntilIdle()
            background.input = ConversationInput(turn, "")
            background.call("extra", action.id, "place" to "Another")
            advanceUntilIdle()
            assertEquals(ThreadController.CALLS_PER_TURN, executions)
            assertEquals("NOT_EXECUTED", background.results.single().status)
        }

    @Test
    fun `connection waits for capabilities before capturing its catalog`() =
        runTest {
            val ready = CompletableDeferred<Unit>()
            val provider = FakeProvider()
            val controller = controller(provider, awaitCapabilities = { ready.await() })
            advanceUntilIdle()
            controller.connect("test")
            runCurrent()
            val extra = action.copy(id = "extension.loaded")
            registry.replace(mapOf(extra.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "done") }), listOf(extra))
            ready.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf(extra.id),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
        }

    @Test
    fun `voice service rejection preserves accepted work for text continuation`() =
        runTest {
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            controller.submit("Do something")
            advanceUntilIdle()
            controller.voiceUnavailable("Android refused microphone foreground access")
            advanceUntilIdle()
            assertEquals("Android refused microphone foreground access", controller.state.value.providerMessage)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals(TurnStatus.OPEN, store.turns(store.threads().single().id).single().status)
            assertEquals(listOf(latestTurn(controller)), background.responseRequests)
            assertEquals(0, executions)
        }

    @Test
    fun `readiness failure disconnects with an actionable error`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, awaitCapabilities = { error("Extensions are still loading") })
            advanceUntilIdle()
            controller.connect("test")
            advanceUntilIdle()
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals("Extensions are still loading", controller.state.value.providerMessage)
            assertEquals(0, executions)
        }

    @Test
    fun `the lookup budget is bounded`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Look everywhere")
            advanceUntilIdle()
            repeat(ThreadController.READ_ONLY_CALLS_PER_TURN + 1) { provider.call("look-$it", lookup.id, "query" to "q$it") }
            advanceUntilIdle()
            assertEquals(ThreadController.READ_ONLY_CALLS_PER_TURN, executions)
            assertEquals("Too many lookups for one request.", provider.results.last().message)
        }

    @Test
    fun `a new request is refused while the thread is still working`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("First")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.ResponseEnded(provider.input.id, "completed"))
            advanceUntilIdle()
            controller.submit("Second")
            advanceUntilIdle()
            assertEquals(2, provider.submissions)
            controller.submit("Third")
            advanceUntilIdle()
            assertEquals(2, provider.submissions)
            assertEquals("EVA is still working on the last request.", controller.state.value.providerMessage)
        }

    @Test
    fun `claim storage failure stops the session without executing an action`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open the park")
            advanceUntilIdle()
            repository.failClaim = true
            provider.call("first", action.id, "place" to "Park")
            advanceUntilIdle()
            assertEquals(0, executions)
            assertNotNull(controller.state.value.errorMessage)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertFalse(controller.state.value.isSubmitting)
            assertFalse(controller.state.value.working)
        }

    // ---- work outlives the attachment ----

    @Test
    fun `voice can delegate a Paseo workflow while text keeps the extension catalog and turn`() =
        runTest {
            val paseoLookup =
                lookup.copy(
                    id = "extension.package.android.paseo.read_workspace_messages",
                    source = CapabilitySource("plugin:paseo", "Paseo"),
                )
            val paseoSend =
                action.copy(
                    id = "extension.package.android.paseo.send_agent_prompt",
                    source = CapabilitySource("plugin:paseo", "Paseo"),
                )
            registry.replace(
                mapOf(
                    paseoLookup.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Recent messages") },
                    paseoSend.id to backend { ExecutionOutcome(InvocationStatus.HANDED_OFF, "Prompt opened") },
                ),
                listOf(paseoLookup, paseoSend),
            )
            val voice = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(voice, background = background, media = { VoiceMedia() })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertTrue(
                voice.request.catalog.tools
                    .any { it.capabilityId == ThreadController.DEFER_TO_TEXT.capabilityId },
            )
            voice.input = ConversationInput("voice:turn-1", "")
            voice.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            voice.channel.send(ProviderEvent.Transcript("user", "What happened in the Paseo workspace for EVA?"))
            advanceUntilIdle()
            val turn = latestTurn(controller)
            voice.call("delegate", ThreadController.DEFER_TO_TEXT.capabilityId, "task" to "Find the EVA workspace and read recent messages")
            advanceUntilIdle()

            assertEquals("HANDED_OFF", voice.results.single().status)
            assertEquals(turn, background.request.continuation?.turnId)
            assertTrue(background.request.instructions.contains("Find the EVA workspace and read recent messages"))
            assertTrue(
                background.request.catalog.tools
                    .any { it.capabilityId == paseoLookup.id },
            )
            assertTrue(
                background.request.catalog.tools
                    .any { it.capabilityId == paseoSend.id },
            )
            assertTrue(background.request.history.any { it is HistoryItem.User && it.text.contains("Paseo workspace") })

            voice.channel.send(voice.endCall("turn-1"))
            advanceUntilIdle()
            assertTrue(controller.state.value.working)

            background.input = ConversationInput(turn, "")
            background.call("read", paseoLookup.id, "query" to "EVA")
            advanceUntilIdle()
            assertEquals("Recent messages", background.results.first().message)
            background.call("send", paseoSend.id, "place" to "agent")
            advanceUntilIdle()
            assertEquals("Prompt opened", background.results.last().message)
            background.channel.send(ProviderEvent.AssistantText(turn, "The agent reported its latest changes.", false))
            background.channel.send(ProviderEvent.ResponseEnded(turn, "completed"))
            advanceUntilIdle()
            assertEquals(TurnStatus.ANSWERED, store.turns(controller.state.value.threadId!!).single().status)
            assertEquals("The agent reported its latest changes.", answers.single().answer)
        }

    @Test
    fun `failed text continuation is reported as a failed handoff`() =
        runTest {
            val voice = FakeProvider()
            val background = FakeProvider(openFailure = IllegalStateException("Text provider unavailable"))
            val controller = controller(voice, background = background, media = { VoiceMedia() })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            voice.input = ConversationInput("voice:turn-1", "")
            voice.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            advanceUntilIdle()
            voice.call("delegate", ThreadController.DEFER_TO_TEXT.capabilityId, "task" to "Read recent Paseo messages")
            advanceUntilIdle()
            assertEquals("NOT_EXECUTED", voice.results.single().status)
            assertEquals(TurnStatus.FAILED, store.turns(controller.state.value.threadId!!).single().status)
        }

    @Test
    fun `hanging up leaves the turn running and it finishes on a background leg`() =
        runTest {
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open the park")
            advanceUntilIdle()
            val turn = latestTurn(controller)
            provider.call("first", action.id, "place" to "Park")
            advanceUntilIdle()
            assertEquals(1, executions)

            // The user hangs up before the model has answered.
            controller.disconnect()
            advanceUntilIdle()
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertTrue(controller.state.value.working)
            assertEquals(setOf(controller.state.value.threadId), controller.working.value)

            // The turn re-homed: the background leg was seeded with the receipt and asked to continue.
            val opened = background.request
            assertEquals(turn, opened.continuation?.turnId)
            assertTrue(opened.history.any { it is HistoryItem.User && it.text == "Open the park" })
            val evidence = opened.history.filterIsInstance<HistoryItem.ActionEvidence>().single()
            assertEquals("HANDED_OFF", evidence.status)
            assertEquals("Opened Park", evidence.message)
            assertEquals(listOf(turn), background.responseRequests)
            assertEquals(0, background.submissions)

            background.channel.send(ProviderEvent.AssistantText(turn, "The park is open.", false))
            background.channel.send(ProviderEvent.ResponseEnded(turn, "completed"))
            advanceUntilIdle()
            assertFalse(controller.state.value.working)
            assertEquals("The park is open.", entry(controller, turn).response)
            assertEquals("The park is open.", answers.single().answer)
            assertEquals(1, background.closes)
            assertTrue(
                controller.state.value.entries
                    .any { it.status == EntryStatus.SESSION && it.response.contains("Continuing") },
            )
        }

    @Test
    fun `a request can continue acting after re-homing`() =
        runTest {
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open the park")
            advanceUntilIdle()
            val turn = latestTurn(controller)
            provider.call("first", action.id, "place" to "Park")
            advanceUntilIdle()
            controller.disconnect()
            advanceUntilIdle()
            background.input = ConversationInput(turn, "")
            background.call("second", action.id, "place" to "Beach")
            advanceUntilIdle()
            assertEquals(2, executions)
            assertEquals("HANDED_OFF", background.results.single().status)
        }

    @Test
    fun `stopping the task interrupts it and re-homing happens only once`() =
        runTest {
            val provider = FakeProvider()
            val background = FakeProvider(epoch = "background")
            val controller = controller(provider, background = background)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Open the park")
            advanceUntilIdle()
            val turn = latestTurn(controller)
            controller.disconnect()
            advanceUntilIdle()
            assertTrue(controller.state.value.working)
            controller.stopTask()
            advanceUntilIdle()
            assertFalse(controller.state.value.working)
            assertEquals(TurnStatus.INTERRUPTED, store.turns(controller.state.value.threadId!!).single().status)
            assertEquals("Interrupted.", entry(controller, turn).response)
            assertEquals(1, background.closes)

            // A background leg that fails does not get a second chance.
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Again")
            advanceUntilIdle()
            val second = latestTurn(controller)
            controller.disconnect()
            advanceUntilIdle()
            background.channel.send(ProviderEvent.Failure("boom"))
            advanceUntilIdle()
            assertFalse(controller.state.value.working)
            assertEquals(TurnStatus.FAILED, store.turns(controller.state.value.threadId!!).first { it.id == second }.status)
        }

    // ---- threads ----

    @Test
    fun `a hands-free launch starts a new thread and old threads can be shown again`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, media = { VoiceMedia() })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("First thread")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.ResponseEnded(provider.input.id, "completed"))
            advanceUntilIdle()
            val first = controller.state.value.threadId!!
            controller.connectVoice("unused", newThread = true)
            advanceUntilIdle()
            val second = controller.state.value.threadId!!
            assertTrue(first != second)
            assertEquals(2, controller.threads.value.size)
            assertEquals(
                listOf("Voice session"),
                controller.state.value.entries
                    .filter { it.status == EntryStatus.SESSION }
                    .map { it.response },
            )
            controller.disconnect()
            advanceUntilIdle()
            controller.showThread(first)
            advanceUntilIdle()
            assertEquals(first, controller.state.value.threadId)
            assertEquals(
                "First thread",
                controller.state.value.entries
                    .first { it.request.isNotBlank() }
                    .request,
            )
        }

    @Test
    fun `resuming a thread seeds the session with what was said`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider)
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            controller.submit("Remember the park")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.AssistantText(provider.input.id, "Noted.", false))
            provider.channel.send(ProviderEvent.ResponseEnded(provider.input.id, "completed"))
            advanceUntilIdle()
            controller.disconnect()
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val history = provider.request.history
            assertTrue(history.any { it is HistoryItem.User && it.text == "Remember the park" })
            assertTrue(history.any { it is HistoryItem.Assistant && it.text == "Noted." })
            assertNull(provider.request.continuation)
        }

    // ---- voice ----

    @Test
    fun `a spoken turn is the input and its tool call executes on the phone`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test", callMode = VoiceCallMode.OPEN_CONVERSATION)
            advanceUntilIdle()
            assertEquals(
                listOf("eva.session.end", "eva.session.defer_to_text", action.id, lookup.id),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
            provider.input = ConversationInput("voice:turn-1", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(ProviderEvent.Transcript("user", "Show me the park"))
            provider.call("call-1", action.id, "place" to "Park")
            advanceUntilIdle()
            assertEquals(1, executions)
            assertEquals(
                "voice:turn-1",
                provider.results
                    .single()
                    .call.inputId,
            )
            assertEquals("Show me the park", repository.history().single().request)
            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Opened.", false))
            provider.channel.send(ProviderEvent.ResponseEnded("voice:turn-1", "completed"))
            advanceUntilIdle()
            val spoken = latestTurn(controller)
            assertEquals("Show me the park", entry(controller, spoken).request)
            assertEquals("Opened.", entry(controller, spoken).response)

            provider.input = ConversationInput("voice:turn-2", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-2", "voice:turn-2"))
            provider.call("call-2", action.id, "place" to "Beach")
            advanceUntilIdle()
            assertEquals(2, executions)
            assertEquals("Voice request", repository.history().first { it.callId.endsWith("call-2") }.request)
            controller.disconnect()
            advanceUntilIdle()
            assertTrue(media.closed)
        }

    @Test
    fun `a voice session takes no typed submissions and disconnect releases both connections`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertEquals(
                listOf("eva.session.end", "eva.session.defer_to_text"),
                provider.request.catalog.tools
                    .map { it.capabilityId }
                    .filter { it.startsWith("eva.") },
            )
            assertTrue(provider.request.instructions.contains("short closing line"))
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
    fun `provider failure closes its session and preserves the failure message`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.channel.send(ProviderEvent.Failure("Connection lost"))
            advanceUntilIdle()
            assertTrue(media.closed)
            assertEquals(1, provider.closes)
            assertEquals("Connection lost", controller.state.value.providerMessage)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
        }

    @Test
    fun `late open from a canceled attempt is closed without replacing the new session`() =
        runTest {
            val old = FakeProvider(openGate = CompletableDeferred())
            val current = FakeProvider()
            val oldMedia = VoiceMedia()
            val currentMedia = VoiceMedia()
            var attempts = 0
            val controller =
                ThreadController(
                    registry = registry,
                    dispatcher = CapabilityDispatcher(registry, repository),
                    repository = repository,
                    store = store,
                    scope = liveScope(),
                    providerFactory = { current },
                    mediaFactory = { if (attempts++ == 0) oldMedia else currentMedia },
                    voiceProviderFactory = { link, _ -> if (link == "old") old else current },
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
    fun `contact keywords reach a spoken session and are never gathered for a typed one`() =
        runTest {
            val provider = FakeProvider()
            var reads = 0
            val controller =
                controller(provider, media = { VoiceMedia() }, voiceKeywords = {
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
        }

    @Test
    fun `ending the conversation waits for the goodbye to finish playing`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.input = ConversationInput("voice:turn-1", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(ProviderEvent.AssistantSpeaking(true))
            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Goodbye!", false))
            provider.channel.send(provider.endCall("turn-1"))
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
            // Hanging up is not a phone action, and answering it would prompt the model to speak again.
            assertEquals(emptyList<CorrelatedToolResult>(), provider.results)
            advanceUntilIdle()
            assertEquals("Goodbye!", entry(controller, latestTurn(controller)).response)
            assertFalse(controller.state.value.working)
        }

    @Test
    fun `ending without reported speech hangs up at once and an unreported end is bounded`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            provider.input = ConversationInput("voice:turn-1", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(provider.endCall("turn-1"))
            runCurrent()
            assertTrue(media.closed)
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            advanceUntilIdle()
            assertEquals("Response completed.", entry(controller, latestTurn(controller)).response)

            val stuck = FakeProvider()
            val stuckMedia = VoiceMedia()
            val second = controller(stuck, media = { stuckMedia }, voiceProvider = stuck)
            advanceUntilIdle()
            second.connectVoice("test")
            advanceUntilIdle()
            stuck.input = ConversationInput("voice:turn-1", "")
            stuck.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            stuck.channel.send(ProviderEvent.AssistantSpeaking(true))
            stuck.channel.send(stuck.endCall("turn-1"))
            runCurrent()
            assertFalse(stuckMedia.closed)
            advanceTimeBy(11_000)
            assertTrue(stuckMedia.closed)
        }

    @Test
    fun `a one-request call hangs up when the line goes quiet after its action is reported`() =
        runTest {
            val note = action.copy(id = "test.note", title = "Note", bookkeeping = true)
            val withNote =
                CapabilityRegistry(
                    mapOf(
                        action.id to backend { ExecutionOutcome(InvocationStatus.HANDED_OFF, "Opened") },
                        note.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "Noted") },
                    ),
                    listOf(action, note),
                )

            suspend fun TestScope.actOnce(
                callMode: VoiceCallMode,
                userKeepsGoing: Boolean = false,
                capability: String = action.id,
            ): VoiceMedia {
                val provider = FakeProvider()
                val media = VoiceMedia()
                val controller = controller(provider, registry = withNote, media = { media })
                advanceUntilIdle()
                controller.connectVoice("test", callMode = callMode)
                advanceUntilIdle()
                provider.input = ConversationInput("voice:turn-1", "")
                provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
                provider.call("first:$capability", capability, "place" to "Park")
                advanceUntilIdle()
                provider.channel.send(ProviderEvent.AssistantSpeaking(true))
                provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Opened the park.", false))
                provider.channel.send(ProviderEvent.ResponseEnded("voice:turn-1", "completed"))
                provider.channel.send(ProviderEvent.AssistantSpeaking(false))
                runCurrent()
                advanceTimeBy(4_000)
                assertFalse(media.closed)
                if (userKeepsGoing) provider.channel.send(ProviderEvent.UserSpeaking)
                advanceUntilIdle()
                if (media.closed) {
                    assertEquals("Call ended by EVA: the request was done and the line went quiet", sessionNotices(controller).last())
                }
                return media
            }

            assertTrue(actOnce(VoiceCallMode.ONE_REQUEST).closed)
            // A request can take several exchanges; speaking again keeps the call.
            assertFalse(actOnce(VoiceCallMode.ONE_REQUEST, userKeepsGoing = true).closed)
            assertFalse(actOnce(VoiceCallMode.OPEN_CONVERSATION).closed)
            // A note EVA made for itself is not the request being served.
            assertFalse(actOnce(VoiceCallMode.ONE_REQUEST, capability = note.id).closed)
        }

    @Test
    fun `a hang-up proposed alongside an action waits until its result is spoken`() =
        runTest {
            val provider = FakeProvider()
            val media = VoiceMedia()
            val controller = controller(provider, media = { media })
            advanceUntilIdle()
            controller.connectVoice("test", callMode = VoiceCallMode.OPEN_CONVERSATION)
            advanceUntilIdle()
            provider.input = ConversationInput("voice:turn-1", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(ProviderEvent.AssistantSpeaking(true))
            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Let me check.", false))
            provider.call("look", lookup.id, "query" to "agents")
            provider.channel.send(provider.endCall("turn-1"))
            provider.channel.send(ProviderEvent.AssistantSpeaking(false))
            advanceUntilIdle()

            assertFalse(media.closed)
            assertEquals(listOf("COMPLETED", "NOT_EXECUTED"), provider.results.map { it.status })

            provider.channel.send(ProviderEvent.AssistantText("voice:turn-1", "Found agents.", false))
            provider.channel.send(ProviderEvent.ResponseEnded("voice:turn-1", "completed"))
            advanceUntilIdle()

            assertTrue(media.closed)
            assertEquals("Call ended by EVA with its end-call tool", sessionNotices(controller).last())
            assertEquals(TurnStatus.ANSWERED, store.turns(controller.state.value.threadId!!).single().status)
        }

    private fun sessionNotices(controller: ThreadController) =
        controller.state.value.entries
            .filter { it.status == EntryStatus.SESSION }
            .map { it.response }

    // ---- catalog and prompt, per connection ----

    @Test
    fun `an extension's guidance joins the instructions only while its tools are offered`() =
        runTest {
            val listed =
                CapabilityDefinition(
                    "extension.package.paseo.list_agents",
                    "List agents",
                    "List agent sessions.",
                    schema("""{"type":"object","properties":{},"required":[],"additionalProperties":false}"""),
                    readOnly = true,
                    source = CapabilitySource("paseo-instance", "Paseo"),
                    guidance = "Find the agent with list_agents, then act on its id.",
                )
            val withExtension =
                CapabilityRegistry(
                    mapOf(listed.id to backend { ExecutionOutcome(InvocationStatus.COMPLETED, "none") }),
                    listOf(listed),
                )
            val provider = FakeProvider()
            val offered = controller(provider, registry = withExtension)
            advanceUntilIdle()
            offered.connect("unused")
            advanceUntilIdle()

            assertTrue(provider.request.instructions.contains("Find the agent with list_agents, then act on its id."))
            assertTrue(provider.request.instructions.contains(Wording.bundled.message(Wording.EXTENSION_GUIDANCE)))
            assertTrue(
                provider.request.catalog.tools
                    .single()
                    .description
                    .contains("\"name\":\"list_agents\""),
            )

            val hidden = FakeProvider()
            val withheld = controller(hidden, registry = withExtension, hiddenCapabilities = { setOf(listed.id) })
            advanceUntilIdle()
            withheld.connect("unused")
            advanceUntilIdle()

            assertFalse(hidden.request.instructions.contains("list_agents"))
        }

    @Test
    fun `a switched off capability is never offered to the model`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, hiddenCapabilities = { setOf(lookup.id) })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            assertEquals(
                listOf(action.id),
                provider.request.catalog.tools
                    .map { it.capabilityId },
            )
        }

    @Test
    fun `switching a capability back on offers it again under a different catalog revision`() =
        runTest {
            val provider = FakeProvider()
            var hidden = setOf(lookup.id)
            val controller = controller(provider, hiddenCapabilities = { hidden })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            val without = provider.request.catalog
            controller.disconnect()
            advanceUntilIdle()

            hidden = emptySet()
            controller.connect("unused")
            advanceUntilIdle()
            val with = provider.request.catalog
            assertEquals(listOf(action.id, lookup.id), with.tools.map { it.capabilityId })
            assertNotEquals(without.revision, with.revision)
        }

    @Test
    fun `the prompt file decides the instructions and the tool wording`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, media = { VoiceMedia() })
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            assertTrue(provider.request.instructions.startsWith("You are EVA"))
            assertTrue(provider.request.instructions.contains("The user's current local time is"))
            controller.disconnect()
            advanceUntilIdle()

            val muted = FakeProvider()
            val mutedController =
                controller(muted, media = { VoiceMedia() }, prompt = {
                    PromptConfig(
                        listOf(
                            PromptComponent(
                                id = "no-hangup",
                                instruction = "Never hang up.",
                                hide = listOf(PromptDefaults.END_CONVERSATION_ID),
                            ),
                        ),
                    )
                })
            advanceUntilIdle()
            mutedController.connectVoice("test")
            advanceUntilIdle()
            assertEquals("Never hang up.", muted.request.instructions)
            assertFalse(
                muted.request.catalog.tools
                    .any { it.capabilityId == PromptDefaults.END_CONVERSATION_ID },
            )
        }

    @Test
    fun `a voice launch can override the configured call mode`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, media = { VoiceMedia() })
            advanceUntilIdle()

            controller.connectVoice("test", callMode = VoiceCallMode.OPEN_CONVERSATION)
            advanceUntilIdle()

            assertTrue(provider.request.instructions.contains("This call stays open"))
            assertFalse(provider.request.instructions.contains("This call is for one request"))
        }

    @Test
    fun `a broken prompt file fails the connection with its own message`() =
        runTest {
            val provider = FakeProvider()
            val controller = controller(provider, prompt = { throw PromptConfigException("Line 4, column 3: no such field") })
            advanceUntilIdle()
            controller.connect("unused")
            advanceUntilIdle()
            assertEquals(ProviderStatus.DISCONNECTED, controller.state.value.providerStatus)
            assertEquals("Line 4, column 3: no such field", controller.state.value.providerMessage)
        }

    @Test
    fun `the model hanging up is signalled to call surfaces, and a user disconnect is not`() =
        runTest {
            val stopped = FakeProvider()
            val byUser = controller(stopped, media = { VoiceMedia() })
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

            val provider = FakeProvider()
            val byModel = controller(provider, media = { VoiceMedia() })
            advanceUntilIdle()
            var modelHangUps = 0
            val modelWatcher = launch { byModel.hangUps.collect { modelHangUps++ } }
            runCurrent()
            byModel.connectVoice("test")
            advanceUntilIdle()
            provider.input = ConversationInput("voice:turn-1", "")
            provider.channel.send(ProviderEvent.ResponseStarted("voice:turn-1", "voice:turn-1"))
            provider.channel.send(provider.endCall("turn-1"))
            advanceUntilIdle()
            assertEquals(1, modelHangUps)
            modelWatcher.cancel()
        }

    private class FakeProvider(
        val openGate: CompletableDeferred<Unit>? = null,
        epoch: String = "epoch",
        val openFailure: Exception? = null,
    ) : ConversationProvider,
        ConversationSession {
        override val connectionEpoch = epoch

        /** A fresh channel per open, so one fake can serve a reconnect or a background leg. */
        var channel = Channel<ProviderEvent>(Channel.UNLIMITED)
            private set
        override val events: Flow<ProviderEvent> get() = channel.receiveAsFlow()
        lateinit var request: SessionOpenRequest
        lateinit var input: ConversationInput
        val results = mutableListOf<CorrelatedToolResult>()
        val responseRequests = mutableListOf<String>()
        var submissions = 0
        var closes = 0

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            this.request = request
            openFailure?.let { throw it }
            if (channel.isClosedForSend) channel = Channel(Channel.UNLIMITED)
            withContext(NonCancellable) { openGate?.await() }
            channel.send(ProviderEvent.Connected("session", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) {
            this.input = input
            submissions++
        }

        override suspend fun requestResponse(request: ResponseRequest) {
            responseRequests += request.inputId
        }

        override suspend fun submitToolResult(result: CorrelatedToolResult) {
            results.add(result)
        }

        override suspend fun close() {
            closes++
            channel.close()
        }

        suspend fun call(
            id: String,
            capabilityId: String,
            vararg arguments: Pair<String, String>,
        ) {
            channel.send(
                ProviderEvent.ToolCallReady(
                    CallIdentity(connectionEpoch, "session", input.id, input.id, "turn", request.catalog.revision, id),
                    capabilityId,
                    buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } },
                ),
            )
        }

        fun endCall(turn: String) =
            ProviderEvent.ToolCallReady(
                CallIdentity(connectionEpoch, "session", "voice:$turn", "voice:$turn", turn, request.catalog.revision, "end-$turn"),
                "eva.session.end",
                buildJsonObject {},
            )
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
