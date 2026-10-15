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
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ClientDelegationSession
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveDelegationControllerTest {
    private val repository = MemoryInvocationRepository()
    private val store = MemoryConversationStore()
    private val answers = mutableListOf<ThreadController.BackgroundAnswer>()
    private val delivered = mutableListOf<ThreadController.BackgroundAnswer>()
    private var executions = 0

    private val lights =
        CapabilityDefinition(
            "test.lights",
            "Turn on the lights",
            "Switches the lights on",
            Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
        )
    private val registry =
        CapabilityRegistry(
            mapOf(
                lights.id to
                    object : ExecutionBackend {
                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            executions++
                            return ExecutionOutcome(InvocationStatus.COMPLETED, "The lights are on")
                        }
                    },
            ),
            listOf(lights),
        )

    private val wording = Wording.bundled

    private fun note(key: String) = wording.message(key)

    private fun TestScope.controller(
        live: LiveProvider,
        workers: Workers,
        media: Media = Media(),
    ) = ThreadController(
        registry = registry,
        dispatcher = CapabilityDispatcher(registry, repository),
        repository = repository,
        store = store,
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()),
        providerFactory = { workers },
        mediaFactory = { media },
        voiceProviderFactory = { _, _ -> live },
        backgroundProviderFactory = { workers },
        wording = { wording },
        onBackgroundAnswer = { answers += it },
        onBackgroundAnswerDelivered = { delivered += it },
    )

    private suspend fun TestScope.connected(
        live: LiveProvider,
        workers: Workers,
    ): ThreadController {
        val controller = controller(live, workers)
        advanceUntilIdle()
        controller.connectVoice("test")
        advanceUntilIdle()
        return controller
    }

    private suspend fun LiveSession.say(
        text: String,
        startMs: Long,
        endMs: Long,
        role: String = "user",
    ) = channel.send(ProviderEvent.TimedTranscript(role, text, startMs, endMs))

    private suspend fun LiveSession.delegate(
        id: String,
        offsetMs: Long,
    ) = channel.send(ProviderEvent.ClientDelegationRequested("live-session", id, offsetMs))

    private fun TestScope.settle() {
        advanceTimeBy(6_000)
        runCurrent()
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `a delegation reads late captions by timestamp and a repeated ID starts nothing more`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val session = live.sessions.single()
            session.say("Turn on ", 1_000, 1_400)
            session.delegate("d1", 3_000)
            runCurrent()
            // Transcribed after the delegation arrived, but said before its offset.
            session.say("the lights", 1_400, 2_000)
            session.say("Sure, one moment.", 3_100, 3_900, role = "assistant")
            session.delegate("d1", 3_000)
            settle()

            val worker = workers.sessions.single()
            val brief = worker.request.instructions.substringAfter(note(Wording.LIVE_DELEGATION) + "\n")
            val payload = Json.parseToJsonElement(brief).jsonObject
            assertEquals("Turn on the lights", payload.text("request"))
            assertEquals("d1", payload.getValue("delegation").jsonObject.text("delegationId"))
            assertEquals("3000", payload.getValue("delegation").jsonObject.text("offsetMs"))
            val threadId = controller.state.value.attachedThreadId!!
            val turn = store.turns(threadId).single()
            assertEquals("Turn on the lights", turn.request)
            assertEquals(turn.id, worker.request.continuation!!.turnId)
            val items = store.items(threadId)
            assertEquals("Turn on the lights", items.filterIsInstance<ThreadItem.TextLeg>().single().task)
            assertEquals(listOf("Turn on the lights"), items.filterIsInstance<ThreadItem.UserMessage>().map { it.text })
            assertEquals(listOf(turn.id), worker.responseRequests)
            val started = session.updates.single()
            assertEquals("d1", started.delegationId)
            assertEquals(note(Wording.LIVE_DELEGATION_STARTED), started.note)
            assertFalse(started.respond)
            controller.interruptAll("Test complete")
            advanceUntilIdle()
        }

    @Test
    fun `a delegation it cannot attribute asks to clarify and never repeats the earlier request`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val session = live.sessions.single()
            session.say("Turn on the lights", 0, 1_000)
            session.delegate("first", 1_500)
            settle()
            assertEquals(1, workers.sessions.size)
            // Same moment, new ID: the words already belong to the first request.
            session.delegate("same-moment", 1_500)
            settle()
            // Nothing new was said before this one.
            session.delegate("silent", 4_000)
            settle()

            assertEquals(1, workers.sessions.size)
            assertEquals(1, store.turns(controller.state.value.attachedThreadId!!).size)
            val unclear = session.updates.filter { it.note == note(Wording.LIVE_DELEGATION_UNCLEAR) }
            assertEquals(listOf("same-moment", "silent"), unclear.map { it.delegationId })
            assertEquals(listOf("overlaps_earlier_request", "no_user_speech"), unclear.map { it.data!!.text("reason") })
            assertTrue(unclear.all { it.respond })
            assertEquals(0, executions)
            controller.interruptAll("Test complete")
            advanceUntilIdle()
        }

    @Test
    fun `the result goes by delegation ID to the session that asked and never counts as delivered`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val session = live.sessions.single()
            session.say("Turn on the lights", 0, 1_000)
            session.delegate("d1", 1_200)
            settle()
            val worker = workers.sessions.single()
            val turnId = worker.request.continuation!!.turnId
            worker.call("lights", turnId, lights.id)
            runCurrent()
            worker.channel.send(ProviderEvent.AssistantText(turnId, "The lights are on.", false))
            worker.channel.send(ProviderEvent.ResponseEnded(turnId, "completed"))
            advanceUntilIdle()

            assertEquals(1, executions)
            val result = session.updates.last()
            assertEquals("d1", result.delegationId)
            assertEquals(note(Wording.BACKGROUND_UPDATE), result.note)
            assertEquals("ANSWERED", result.data!!.text("state"))
            assertEquals("The lights are on.", result.data.text("answer"))
            val action =
                result.data
                    .getValue("actions")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("COMPLETED", action.text("status"))
            assertEquals(0, session.contexts)
            assertTrue(delivered.isEmpty())
            assertEquals(TurnStatus.ANSWERED, answers.single().status)
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `accepted work survives hangup and a later session hears it without the old delegation ID`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val first = live.sessions.single()
            first.say("Find my flight", 0, 1_000)
            first.delegate("d1", 1_200)
            settle()
            val worker = workers.sessions.single()
            val turnId = worker.request.continuation!!.turnId
            val startedUpdates = first.updates.size
            controller.disconnect()
            advanceUntilIdle()
            assertEquals(0, worker.closes)
            assertTrue(controller.taskSnapshots.value.any { it.taskId == turnId })

            controller.connectVoice("test")
            advanceUntilIdle()
            val second = live.sessions.last()
            worker.channel.send(ProviderEvent.AssistantText(turnId, "It leaves at nine.", false))
            worker.channel.send(ProviderEvent.ResponseEnded(turnId, "completed"))
            advanceUntilIdle()

            assertEquals(startedUpdates, first.updates.size)
            val result = second.updates.single()
            assertNull(result.delegationId)
            assertEquals("It leaves at nine.", result.data!!.text("answer"))
            assertTrue(delivered.isEmpty())
            controller.disconnect()
            advanceUntilIdle()
        }

    @Test
    fun `a spoken answer reaches the asking worker only through a validated question ID`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val session = live.sessions.single()
            session.say("Book a table", 0, 1_000)
            session.delegate("book", 1_200)
            settle()
            val asking = workers.sessions.single()
            val askingTurn = asking.request.continuation!!.turnId
            asking.call("ask", askingTurn, ThreadController.ASK_USER.capabilityId, "question" to "Which city?")
            runCurrent()
            val relay = session.updates.last()
            assertEquals(note(Wording.LIVE_QUESTION), relay.note)
            assertEquals("book", relay.delegationId)
            val questionId = relay.data!!.text("questionId")

            session.say("Kyoto", 5_000, 5_400)
            session.delegate("answer", 5_600)
            settle()
            val relaying = workers.sessions.last()
            val relayingTurn = relaying.request.continuation!!.turnId
            val payload =
                Json
                    .parseToJsonElement(
                        relaying.request.instructions.substringAfter(note(Wording.LIVE_DELEGATION) + "\n"),
                    ).jsonObject
            assertEquals("Kyoto", payload.text("request"))
            assertEquals(
                questionId,
                payload
                    .getValue("pendingQuestions")
                    .jsonArray
                    .single()
                    .jsonObject
                    .text("questionId"),
            )
            assertTrue(
                relaying.request.catalog.tools
                    .any { it.capabilityId == ThreadController.BACKGROUND_ANSWER.capabilityId },
            )

            relaying.call(
                "wrong",
                relayingTurn,
                ThreadController.BACKGROUND_ANSWER.capabilityId,
                "taskId" to askingTurn,
                "questionId" to "made-up",
                "answer" to "Kyoto",
            )
            runCurrent()
            assertEquals("NOT_EXECUTED", relaying.results.single().status)
            assertTrue(asking.results.isEmpty())
            relaying.call(
                "right",
                relayingTurn,
                ThreadController.BACKGROUND_ANSWER.capabilityId,
                "taskId" to askingTurn,
                "questionId" to questionId,
                "answer" to "Kyoto",
            )
            runCurrent()
            assertEquals("COMPLETED", relaying.results.last().status)
            assertEquals(
                "ask",
                asking.results
                    .single()
                    .call.callId,
            )
            controller.interruptAll("Test complete")
            advanceUntilIdle()
        }

    @Test
    fun `a worker stops another task by its ID and ends only the voice session that asked`() =
        runTest {
            val live = LiveProvider()
            val workers = Workers()
            val controller = connected(live, workers)
            val session = live.sessions.single()
            session.say("Research flights", 0, 1_000)
            session.delegate("research", 1_200)
            settle()
            val research = workers.sessions.single()
            val researchTurn = research.request.continuation!!.turnId
            session.say("Never mind, stop that and hang up", 4_000, 5_000)
            session.delegate("stop", 5_200)
            settle()
            val control = workers.sessions.last()
            val controlTurn = control.request.continuation!!.turnId

            control.channel.send(
                ProviderEvent.ToolCallReady(
                    CallIdentity(
                        control.connectionEpoch,
                        "wrong-session",
                        controlTurn,
                        controlTurn,
                        "turn",
                        control.request.catalog.revision,
                        "stale",
                    ),
                    ThreadController.END_CONVERSATION.capabilityId,
                    buildJsonObject {},
                ),
            )
            runCurrent()
            assertEquals("NOT_EXECUTED", control.results.last().status)
            assertTrue(controller.state.value.voiceMode)
            control.call("self", controlTurn, ThreadController.BACKGROUND_CANCEL.capabilityId, "taskId" to controlTurn)
            runCurrent()
            assertEquals("NOT_EXECUTED", control.results.last().status)
            control.call("cancel", controlTurn, ThreadController.BACKGROUND_CANCEL.capabilityId, "taskId" to researchTurn)
            runCurrent()
            assertEquals("COMPLETED", control.results.last().status)
            advanceUntilIdle()
            assertFalse(controller.taskSnapshots.value.any { it.taskId == researchTurn })
            assertEquals(
                TurnStatus.INTERRUPTED,
                store.turns(controller.state.value.attachedThreadId!!).single { it.id == researchTurn }.status,
            )

            control.call("end", controlTurn, "eva.session.end")
            advanceUntilIdle()
            assertEquals("COMPLETED", control.results.last().status)
            assertEquals(note(Wording.LIVE_CALL_ENDING), control.results.last().message)
            assertFalse(controller.state.value.voiceMode)
            assertTrue(controller.taskSnapshots.value.any { it.taskId == controlTurn })
            control.call("again", controlTurn, "eva.session.end")
            runCurrent()
            assertEquals("NOT_EXECUTED", control.results.last().status)
            controller.interruptAll("Test complete")
            advanceUntilIdle()
        }

    @Test
    fun `disconnect silences media and lets Live finalize before releasing the transport`() =
        runTest {
            val live = LiveProvider()
            val media = Media()
            val controller = controller(live, Workers(), media)
            advanceUntilIdle()
            controller.connectVoice("test")
            advanceUntilIdle()
            var finalized = false
            live.sessions.single().onClose = {
                assertFalse(media.closed)
                assertTrue(media.controls.value.microphoneMuted)
                assertTrue(media.controls.value.playbackMuted)
                finalized = true
            }
            controller.disconnect()
            advanceUntilIdle()
            assertTrue(finalized)
            assertTrue(media.closed)
        }

    private data class Update(
        val delegationId: String?,
        val note: String,
        val data: JsonObject?,
        val respond: Boolean,
    )

    private class LiveProvider : ConversationProvider {
        val sessions = mutableListOf<LiveSession>()

        override suspend fun open(request: SessionOpenRequest): ConversationSession =
            LiveSession("live-${sessions.size}").also {
                sessions += it
                it.channel.send(ProviderEvent.Connected("live-session", request.catalog.revision))
            }
    }

    private class LiveSession(
        override val connectionEpoch: String,
    ) : ClientDelegationSession {
        val channel = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events: Flow<ProviderEvent> = channel.receiveAsFlow()
        val updates = mutableListOf<Update>()
        var contexts = 0
        var onClose: () -> Unit = {}

        override suspend fun appendDelegationUpdate(
            delegationId: String?,
            note: String,
            data: JsonObject?,
            respond: Boolean,
        ): Boolean {
            updates += Update(delegationId, note, data, respond)
            return true
        }

        override suspend fun submitContext(
            note: String,
            respond: Boolean,
            data: JsonObject?,
            deliveryId: String?,
        ): Boolean {
            contexts++
            return true
        }

        override suspend fun submit(input: ConversationInput) = error("Live takes no typed requests")

        override suspend fun requestResponse(request: ResponseRequest) = error("Live takes no response requests")

        override suspend fun submitToolResult(result: CorrelatedToolResult) = error("Live calls no tools")

        override suspend fun close() {
            onClose()
            channel.close()
        }
    }

    /** Every background leg is a fresh session, as on a phone. */
    private class Workers : ConversationProvider {
        val sessions = mutableListOf<Worker>()

        override suspend fun open(request: SessionOpenRequest): ConversationSession =
            Worker(request, "worker-${sessions.size}").also {
                sessions += it
                it.channel.send(ProviderEvent.Connected("session", request.catalog.revision))
            }
    }

    private class Worker(
        val request: SessionOpenRequest,
        override val connectionEpoch: String,
    ) : ConversationSession {
        val channel = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events: Flow<ProviderEvent> = channel.receiveAsFlow()
        val responseRequests = mutableListOf<String>()
        val results = mutableListOf<CorrelatedToolResult>()
        var closes = 0

        override suspend fun submit(input: ConversationInput) = Unit

        override suspend fun requestResponse(request: ResponseRequest) {
            responseRequests += request.inputId
        }

        override suspend fun submitToolResult(result: CorrelatedToolResult) {
            results += result
        }

        override suspend fun close() {
            closes++
            channel.close()
        }

        suspend fun call(
            id: String,
            turnId: String,
            capabilityId: String,
            vararg arguments: Pair<String, String>,
        ) = channel.send(
            ProviderEvent.ToolCallReady(
                CallIdentity(connectionEpoch, "session", turnId, turnId, "turn", request.catalog.revision, id),
                capabilityId,
                buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } },
            ),
        )
    }

    private class Media : RealtimeMediaSession {
        var closed = false
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Connected(true))
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        override val events = emptyFlow<String>()
        override val eventsReady = MutableStateFlow(false)

        override fun send(event: String) = Unit

        override suspend fun createOffer() = "offer"

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
