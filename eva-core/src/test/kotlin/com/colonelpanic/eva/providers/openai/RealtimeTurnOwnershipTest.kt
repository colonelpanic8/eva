package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.conversation.MemoryConversationStore
import com.colonelpanic.eva.conversation.ThreadController
import com.colonelpanic.eva.conversation.ThreadItem
import com.colonelpanic.eva.conversation.TurnStatus
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeTurnOwnershipTest {
    @Test
    fun `late calls and interleaved transcripts retain their original response and turn`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "First request")
            f.call("r1", "waiting", "test.wait")
            runCurrent()
            val first = f.turn("First request")
            f.done("r1", listOf(f.callItem("waiting", "test.wait")))
            runCurrent()
            f.speech("second", "r2", "Second request")
            val second = f.turn("Second request")
            f.call("r1", "late-read", "test.read")
            f.call("r2", "new-read", "test.read")
            f.text("r2", "Second answer")
            f.text("r1", "First answer")
            runCurrent()
            val receipts = f.repository.history().associateBy { it.callId }
            val late = receipts.getValue("provider:session:late-read")
            val recent = receipts.getValue("provider:session:new-read")
            assertEquals(first, late.turnId)
            assertEquals(second, recent.turnId)
            assertEquals("r1", late.initiator!!.responseId)
            assertEquals("first", late.initiator.itemId)
            assertEquals("out-late-read", late.initiator.outputItemId)
            assertEquals(InitiatorKind.USER_SPEECH, late.initiator.kind)
            val messages =
                f.store
                    .items(f.thread)
                    .filterIsInstance<ThreadItem.AssistantMessage>()
                    .associateBy { it.text }
            assertEquals(first, messages.getValue("First answer").turnId)
            assertEquals(second, messages.getValue("Second answer").turnId)
            f.gate.complete(Unit)
            f.close()
        }

    @Test
    fun `fast interleaved tool results coalesce into one metadata-bound follow-up`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Read both")
            f.call("r1", "read-one", "test.read")
            runCurrent()
            f.call("r1", "read-two", "test.read")
            runCurrent()
            f.done("r1")
            runCurrent()
            val followup = f.acceptRequest("followup")
            val metadata = followup.getValue("metadata").jsonObject
            assertEquals("tool_follow_up", metadata.getValue("eva_purpose").jsonPrimitive.content)
            assertEquals("r1", metadata.getValue("eva_parent_response_id").jsonPrimitive.content)
            assertEquals("voice:first", metadata.getValue("eva_input_id").jsonPrimitive.content)
            assertEquals("user_speech", metadata.getValue("eva_initiator").jsonPrimitive.content)
            f.done("followup")
            runCurrent()
            assertEquals(
                1,
                f.media.sent.count {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["type"]
                        ?.jsonPrimitive
                        ?.content == "response.create"
                },
            )
            assertEquals(listOf("test.read", "test.read"), f.executions)
            assertEquals(
                TurnStatus.ANSWERED,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            f.close()
        }

    @Test
    fun `response done dispatches its own final calls once even when item events arrive later`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "Open it")
            val call = f.callItem("one", "test.wait")
            f.done("r1", listOf(call))
            runCurrent()
            assertEquals(listOf("test.wait"), f.executions)
            assertEquals(
                TurnStatus.OPEN,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            f.media.incoming.send(
                buildJsonObject {
                    put("type", "response.output_item.done")
                    put("response_id", "r1")
                    put("item", call)
                }.toString(),
            )
            runCurrent()
            assertEquals(1, f.executions.size)
            f.gate.complete(Unit)
            runCurrent()
            f.acceptRequest("follow-up")
            f.done("follow-up")
            runCurrent()
            assertEquals(
                TurnStatus.ANSWERED,
                f.store
                    .turns(f.thread)
                    .single()
                    .status,
            )
            assertEquals(1, f.outputs("one").size)
            assertEquals(
                "r1",
                f.repository
                    .history()
                    .single()
                    .initiator!!
                    .responseId,
            )
            f.close()
        }

    @Test
    fun `finished and unknown responses cannot borrow the current spoken requests authority`() =
        runTest {
            val f = fixture()
            f.speech("first", "r1", "First request")
            val first = f.turn("First request")
            f.done("r1")
            runCurrent()
            f.speech("second", "r2", "Second request")
            f.call("r1", "late", "test.read")
            f.call("not-observed", "unknown", "test.read")
            runCurrent()
            assertTrue(f.executions.isEmpty())
            assertEquals(
                "NOT_EXECUTED",
                f
                    .outputs("late")
                    .single()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            val unknown = f.outputs("unknown").single()
            assertEquals("NOT_EXECUTED", unknown.getValue("status").jsonPrimitive.content)
            assertTrue(
                unknown
                    .getValue("message")
                    .jsonPrimitive.content
                    .contains("unknown origin"),
            )
            val receipts = f.repository.history().associateBy { it.callId }
            assertEquals(first, receipts.getValue("provider:session:late").turnId)
            assertNull(receipts.getValue("provider:session:unknown").turnId)
            assertEquals(InitiatorKind.UNKNOWN, receipts.getValue("provider:session:unknown").initiator!!.kind)
            assertEquals("not-observed", receipts.getValue("provider:session:unknown").initiator!!.responseId)
            f.call("r2", "current", "test.read")
            runCurrent()
            assertEquals(listOf("test.read"), f.executions)
            f.close()
        }

    @Test
    fun `captions before creation and late captions are bound by committed item identity`() =
        runTest {
            val f = fixture()
            f.raw("""{"type":"input_audio_buffer.committed","item_id":"first"}""")
            f.raw("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"first","transcript":"First request"}""")
            f.raw("""{"type":"response.created","response":{"id":"r1"}}""")
            runCurrent()
            val first = f.turn("First request")
            f.done("r1")
            f.speech("second", "r2", "Second request")
            f.raw(
                """{"type":"conversation.item.input_audio_transcription.completed",
                    "item_id":"first","transcript":"First final caption"}""",
            )
            runCurrent()
            val caption =
                f.store
                    .items(f.thread)
                    .filterIsInstance<ThreadItem.UserMessage>()
                    .last()
            assertEquals(first, caption.turnId)
            f.close()
        }

    @Test
    fun `delegated announcements have explicit origins and refuse tools without restricting new speech`() =
        runTest {
            val f = fixture()
            f.speech("request", "r1", "Research this")
            f.call("r1", "delegate", ThreadController.DEFER_TO_TEXT.capabilityId, buildJsonObject { put("task", "Research this") })
            f.done("r1")
            runCurrent()
            val turn =
                f.background.request.continuation!!
                    .turnId
            assertNotNull(
                f.background.request.continuation!!
                    .legId,
            )
            f.acceptRequest("handoff-ack")
            f.done("handoff-ack")
            runCurrent()
            f.background.incoming.send(ProviderEvent.AssistantText(turn, "Finding from a web page", false))
            f.background.incoming.send(ProviderEvent.ResponseEnded(turn, "completed"))
            runCurrent()
            val announcement = f.acceptRequest("announcement")
            assertEquals("none", announcement.getValue("tool_choice").jsonPrimitive.content)
            assertEquals(
                "lifecycle_note",
                announcement
                    .getValue("metadata")
                    .jsonObject
                    .getValue("eva_purpose")
                    .jsonPrimitive.content,
            )
            f.call("announcement", "stray", "test.read")
            f.done("announcement")
            runCurrent()
            assertTrue(f.executions.isEmpty())
            val refused = f.repository.history().single()
            assertEquals(InitiatorKind.LIFECYCLE_NOTE_REPLY, refused.initiator!!.kind)
            assertEquals(InvocationStatus.NOT_EXECUTED, refused.status)
            assertTrue(refused.message.contains("Ask the user"))
            f.acceptRequest("announcement-refusal")
            f.done("announcement-refusal")
            runCurrent()
            f.speech("next", "r2", "Look it up")
            f.call("r2", "requested", "test.read")
            runCurrent()
            assertEquals(listOf("test.read"), f.executions)
            assertEquals(
                InitiatorKind.USER_SPEECH,
                f.repository
                    .history()
                    .last()
                    .initiator!!
                    .kind,
            )
            f.close()
        }

    private suspend fun TestScope.fixture(): Fixture {
        val f = Fixture(this)
        runCurrent()
        f.controller.connectVoice("")
        runCurrent()
        f.raw("""{"type":"session.created","session":{"id":"session"}}""")
        runCurrent()
        return f
    }

    private class Fixture(
        val test: TestScope,
    ) {
        val media = Media()
        val background = Background()
        val repository = MemoryInvocationRepository()
        val store = MemoryConversationStore()
        val gate = CompletableDeferred<Unit>()
        val executions = mutableListOf<String>()
        private lateinit var request: SessionOpenRequest
        private val acknowledged = mutableSetOf<String>()
        private val registry =
            CapabilityRegistry(
                listOf("test.read", "test.wait").associateWith { id ->
                    object : ExecutionBackend {
                        override suspend fun unavailableReason(): String? = null

                        override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                            executions += id
                            if (id == "test.wait") gate.await()
                            return ExecutionOutcome(InvocationStatus.COMPLETED, "Done")
                        }
                    }
                },
                listOf("test.read", "test.wait").map {
                    CapabilityDefinition(
                        it,
                        it,
                        it,
                        Json
                            .parseToJsonElement(
                                """{"type":"object","properties":{},"required":[],"additionalProperties":false}""",
                            ).jsonObject,
                        readOnly =
                            it == "test.read",
                    )
                },
            )
        private val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("v=0 answer".toResponseBody("application/sdp".toMediaType()))
                        .build()
                }.build()
        val controller =
            ThreadController(
                registry,
                CapabilityDispatcher(registry, repository),
                repository,
                store,
                test.backgroundScope,
                providerFactory = { background },
                mediaFactory = { media },
                backgroundProviderFactory = { background },
                voiceProviderFactory = { _, _ ->
                    object : ConversationProvider {
                        override suspend fun open(request: SessionOpenRequest): ConversationSession {
                            this@Fixture.request = request
                            return OpenAiRealtimeProvider(
                                ApiKeyAccess("test", "https://example.test"),
                                media,
                                client = client,
                                ioDispatcher = StandardTestDispatcher(test.testScheduler),
                            ).open(request)
                        }
                    }
                },
            )
        val thread get() = controller.state.value.threadId!!

        suspend fun raw(event: String) = media.incoming.send(event)

        suspend fun speech(
            item: String,
            response: String,
            text: String,
        ) {
            raw("""{"type":"input_audio_buffer.speech_started","item_id":"$item"}""")
            raw("""{"type":"input_audio_buffer.speech_stopped","item_id":"$item"}""")
            raw("""{"type":"input_audio_buffer.committed","item_id":"$item"}""")
            raw("""{"type":"response.created","response":{"id":"$response"}}""")
            raw("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$item","transcript":"$text"}""")
            test.runCurrent()
        }

        fun callItem(
            call: String,
            capability: String,
            arguments: JsonObject = buildJsonObject {},
        ): JsonObject =
            buildJsonObject {
                put("id", "out-$call")
                put("type", "function_call")
                put("call_id", call)
                put("name", "eva_tool_${request.catalog.tools.indexOfFirst { it.capabilityId == capability }}")
                put("arguments", arguments.toString())
            }

        suspend fun call(
            response: String,
            call: String,
            capability: String,
            arguments: JsonObject = buildJsonObject {},
        ) = raw(
            buildJsonObject {
                put("type", "response.output_item.done")
                put("response_id", response)
                put("item", callItem(call, capability, arguments))
            }.toString(),
        )

        suspend fun done(
            response: String,
            output: List<JsonObject> = emptyList(),
        ) = raw(
            buildJsonObject {
                put("type", "response.done")
                put(
                    "response",
                    buildJsonObject {
                        put("id", response)
                        put("status", "completed")
                        put("output", JsonArray(output))
                    },
                )
            }.toString(),
        )

        suspend fun text(
            response: String,
            text: String,
        ) = raw(
            buildJsonObject {
                put("type", "response.output_text.done")
                put("response_id", response)
                put("text", text)
            }.toString(),
        )

        suspend fun turn(request: String) =
            store
                .items(thread)
                .filterIsInstance<ThreadItem.UserMessage>()
                .first { it.text == request }
                .turnId!!

        fun outputs(call: String) =
            media.sent
                .map { Json.parseToJsonElement(it).jsonObject }
                .mapNotNull { it["item"] as? JsonObject }
                .filter {
                    it["call_id"]?.jsonPrimitive?.content == call
                }.map { Json.parseToJsonElement(it.getValue("output").jsonPrimitive.content).jsonObject }

        suspend fun acceptRequest(response: String): JsonObject {
            val message =
                media.sent.map { Json.parseToJsonElement(it).jsonObject }.first {
                    it["type"]?.jsonPrimitive?.content == "response.create" &&
                        it.getValue("event_id").jsonPrimitive.content !in acknowledged
                }
            acknowledged += message.getValue("event_id").jsonPrimitive.content
            val parameters = message.getValue("response").jsonObject
            raw(
                buildJsonObject {
                    put("type", "response.created")
                    put(
                        "response",
                        buildJsonObject {
                            put("id", response)
                            put("metadata", parameters.getValue("metadata"))
                        },
                    )
                }.toString(),
            )
            test.runCurrent()
            return parameters
        }

        suspend fun close() {
            gate.complete(Unit)
            controller.drain("Test finished")
            controller.disconnect()
            test.runCurrent()
        }
    }

    private class Background :
        ConversationProvider,
        ConversationSession {
        override val connectionEpoch = "background"
        val incoming = Channel<ProviderEvent>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        lateinit var request: SessionOpenRequest

        override suspend fun open(request: SessionOpenRequest): ConversationSession {
            this.request = request
            incoming.send(ProviderEvent.Connected("background", request.catalog.revision))
            return this
        }

        override suspend fun submit(input: ConversationInput) = Unit

        override suspend fun requestResponse(request: ResponseRequest) = Unit

        override suspend fun submitToolResult(result: CorrelatedToolResult) = Unit

        override suspend fun close() {
            incoming.close()
        }
    }

    private class Media : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Connected(true))
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        override val eventsReady = MutableStateFlow(true)
        val sent = mutableListOf<String>()

        override suspend fun createOffer() = "v=0 offer"

        override suspend fun acceptAnswer(sdp: String) = Unit

        override fun send(event: String) {
            sent += event
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            controls.value = controls.value.copy(microphoneMuted = muted)
        }

        override fun setPlaybackMuted(muted: Boolean) = Unit

        override fun close() = Unit
    }
}
