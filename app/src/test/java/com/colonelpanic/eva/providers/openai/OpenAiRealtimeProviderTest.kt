package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiRealtimeProviderTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{"seconds":{"type":"integer","minimum":1}},
                "required":["seconds"],"additionalProperties":false}""",
            ).jsonObject
    private val catalog =
        ProviderToolCatalog("rev-1", listOf(ProviderToolDefinition("eva.android.timer.set", "Set a timer", "Start a timer", schema)))
    private val requests = mutableListOf<String>()
    private val calls = mutableListOf<okhttp3.Request>()
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val buffer = Buffer().also { chain.request().body?.writeTo(it) }
                requests += buffer.readUtf8()
                calls += chain.request()
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("v=0 answer".toResponseBody("application/sdp".toMediaType()))
                    .build()
            }.build()

    @Test
    fun `a spoken tool call is correlated to its response and answered over the data channel`() =
        runTest {
            val media = FakeMedia()
            val provider =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    "gpt-realtime-2.1",
                    client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            val session = provider.open(SessionOpenRequest("You are EVA.", catalog, listOf("Ana Beltrán", "", "Ana Beltrán")))
            assertEquals("v=0 answer", media.answer)
            val posted = requests.single()
            assertTrue(posted.contains("Bearer").not())
            assertTrue(posted.contains("\"type\":\"realtime\""))
            assertTrue(posted.contains("eva_tool_0"))
            assertTrue(posted.contains("gpt-transcribe"))
            assertTrue(posted.contains("\"languages\":[\"en\"]"))
            assertTrue(posted.contains("\"keywords\":[\"Ana Beltrán\"]"))
            assertTrue(!posted.contains("\"delay\""))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","model":"gpt-realtime-2.1"}}""")
            media.incoming.send("""{"type":"response.created","response":{"id":"resp_1"}}""")
            media.incoming.send(
                """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Set a timer for three minutes"}""",
            )
            media.incoming.send(
                """{"type":"response.output_item.done","item":{"type":"function_call","name":"eva_tool_0","call_id":"call_1","arguments":"{\"seconds\":180}"}}""",
            )
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            advanceUntilIdle()
            val connected = events.filterIsInstance<ProviderEvent.Connected>().single()
            assertEquals("sess_1", connected.sessionId)
            assertEquals("gpt-realtime-2.1", connected.model)
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseStarted>().single().inputId)
            assertEquals("Set a timer for three minutes", events.filterIsInstance<ProviderEvent.Transcript>().single().text)
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            assertEquals("eva.android.timer.set", call.capabilityId)
            assertEquals(JsonPrimitive(180), call.arguments["seconds"])
            assertEquals("voice:resp_1", call.call.inputId)
            assertEquals("resp_1", call.call.providerTurnId)
            // response.done for the tool-calling response must not end the input yet.
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            session.submitToolResult(CorrelatedToolResult(call.call, "HANDED_OFF", "Timer started."))
            val sent = media.sent.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("conversation.item.create", sent[0]["type"]?.let { (it as JsonPrimitive).content })
            assertEquals("function_call_output", sent[0]["item"]!!.jsonObject["type"]!!.let { (it as JsonPrimitive).content })
            assertEquals("response.create", sent[1]["type"]?.let { (it as JsonPrimitive).content })

            media.incoming.send("""{"type":"response.created","response":{"id":"resp_2"}}""")
            media.incoming.send("""{"type":"response.output_audio_transcript.done","transcript":"Three-minute timer started."}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            advanceUntilIdle()
            assertEquals(1, events.count { it is ProviderEvent.ResponseStarted })
            val text = events.filterIsInstance<ProviderEvent.AssistantText>().single()
            assertEquals("voice:resp_1", text.inputId)
            assertEquals("Three-minute timer started.", text.text)
            val ended = events.filterIsInstance<ProviderEvent.ResponseEnded>().single()
            assertEquals("voice:resp_1", ended.inputId)
            assertEquals("completed", ended.status)

            // A later spontaneous turn starts fresh.
            media.incoming.send("""{"type":"response.created","response":{"id":"resp_3"}}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_3","status":"cancelled"}}""")
            advanceUntilIdle()
            assertEquals("voice:resp_3", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().inputId)
            assertEquals("cancelled", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().status)
            media.incoming.close()
            advanceUntilIdle()
            assertEquals(ProviderEvent.Closed, events.last())
            collector.cancel()
        }

    @Test
    fun `a signed-in subscription opens voice with its current token and account`() =
        runTest {
            val tokens = ChatGptTokens("id", "access-1", "refresh-1", "acct-1", "eva@example.test", "pro", 0)
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    SubscriptionAccess({ tokens }, "0.5.0", "https://backend.test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(SessionOpenRequest("You are EVA.", catalog))
            val call = calls.single()
            assertEquals("https://example.test/v1/realtime/calls", call.url.toString())
            assertEquals("Bearer access-1", call.header("Authorization"))
            assertEquals("acct-1", call.header("chatgpt-account-id"))
            assertEquals("eva", call.header("originator"))
            assertEquals("v=0 answer", media.answer)
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            advanceUntilIdle()
            assertEquals(ProviderEvent.Account(ChatGpt.ACCOUNT_LABEL), events.first())
            collector.cancel()
        }

    /** An API key still reaches the public host, which takes the call as multipart form parts. */
    @Test
    fun `an api key opens the voice call on the public host as multipart`() =
        runTest {
            OpenAiRealtimeProvider(
                ApiKeyAccess("sk-test", "https://example.test"),
                FakeMedia(),
                client = client,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ).open(SessionOpenRequest("You are EVA.", catalog))
            val call = calls.single()
            assertEquals("https://example.test/v1/realtime/calls", call.url.toString())
            assertEquals("multipart", call.body?.contentType()?.type)
            assertTrue(requests.single().contains("name=\"sdp\""))
            assertTrue(requests.single().contains("\"type\":\"realtime\""))
        }

    @Test
    fun `typed input over the channel keeps the phone input ID`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1"}}""")
            advanceUntilIdle()
            session.submit(ConversationInput("input-7", "Hello"))
            session.requestResponse(ResponseRequest("input-7"))
            assertTrue(media.sent.first().contains("\"input_text\""))
            media.incoming.send("""{"type":"response.created","response":{"id":"resp_9"}}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_9","status":"completed"}}""")
            advanceUntilIdle()
            assertEquals("input-7", events.filterIsInstance<ProviderEvent.ResponseStarted>().single().inputId)
            assertEquals("input-7", events.filterIsInstance<ProviderEvent.ResponseEnded>().single().inputId)
            collector.cancel()
        }

    @Test
    fun `playback reports when the reply starts and stops reaching the phone`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1"}}""")
            media.incoming.send("""{"type":"output_audio_buffer.started","response_id":"resp_1"}""")
            media.incoming.send("""{"type":"output_audio_buffer.stopped","response_id":"resp_1"}""")
            media.incoming.send("""{"type":"output_audio_buffer.started","response_id":"resp_2"}""")
            media.incoming.send("""{"type":"output_audio_buffer.cleared","response_id":"resp_2"}""")
            advanceUntilIdle()
            assertEquals(
                listOf(true, false, true, false),
                events.filterIsInstance<ProviderEvent.AssistantSpeaking>().map { it.speaking },
            )
            collector.cancel()
        }

    @Test
    fun `history is seeded in order before the realtime session connects`() =
        runTest {
            val media = FakeMedia()
            val history =
                listOf(
                    HistoryItem.User("Set a timer"),
                    HistoryItem.Assistant("How long should it run?"),
                    HistoryItem.ActionEvidence("Set timer", mapOf("seconds" to "180"), "HANDED_OFF", "Timer opened."),
                    HistoryItem.Note("The earlier voice attachment ended."),
                )
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(SessionOpenRequest("You are EVA.", catalog, history = history))
            assertTrue(media.controls.value.microphoneMuted)
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }

            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1"}}""")
            runCurrent()

            val seed = media.sent.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals(5, seed.size)
            assertTrue(seed.all { it.getValue("type").jsonPrimitive.content == "conversation.item.create" })
            assertEquals(
                listOf("user", "assistant", "system", "assistant", "system"),
                seed.map {
                    it
                        .getValue("item")
                        .jsonObject
                        .getValue("role")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                listOf("input_text", "output_text", "input_text", "output_text", "input_text"),
                seed.map {
                    it
                        .getValue("item")
                        .jsonObject
                        .getValue("content")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                },
            )
            assertTrue(events.none { it is ProviderEvent.Connected })

            media.incoming.send("""{"type":"response.created","response":{"id":"premature"}}""")
            runCurrent()
            assertEquals(
                "response.cancel",
                Json.parseToJsonElement(media.sent.last()).jsonObject["type"]?.let { (it as JsonPrimitive).content },
            )

            val ids =
                seed.map {
                    it
                        .getValue("item")
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                }
            media.incoming.send("""{"type":"conversation.item.created","item":{"id":"${ids[0]}"}}""")
            media.incoming.send("""{"type":"conversation.item.added","item":{"id":"${ids[1]}"}}""")
            media.incoming.send("""{"type":"conversation.item.created","item":{"id":"${ids[2]}"}}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.Connected })
            assertTrue(media.controls.value.microphoneMuted)

            media.incoming.send("""{"type":"conversation.item.added","item":{"id":"${ids[3]}"}}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.Connected })
            media.incoming.send("""{"type":"conversation.item.added","item":{"id":"${ids[4]}"}}""")
            runCurrent()
            assertEquals("sess_1", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
            assertTrue(!media.controls.value.microphoneMuted)
            val evidence =
                seed[3]
                    .getValue("item")
                    .jsonObject
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            assertTrue(evidence.contains("\"title\":\"Set timer\""))
            assertTrue(evidence.contains("\"arguments\":{\"seconds\":\"180\"}"))
            assertTrue(evidence.contains("\"reportedStatus\":\"HANDED_OFF\""))
            assertTrue(evidence.contains("\"message\":\"Timer opened.\""))
            collector.cancel()
        }

    @Test
    fun `unacknowledged realtime history fails without unmuting`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(
                    SessionOpenRequest(
                        "You are EVA.",
                        catalog,
                        history = listOf(HistoryItem.User("Continue the prior conversation")),
                    ),
                )
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.updated","session":{"id":"sess_1"}}""")
            runCurrent()

            advanceTimeBy(REALTIME_HISTORY_ACK_TIMEOUT_MILLIS - 1)
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.Failure })
            advanceTimeBy(1)
            runCurrent()

            assertEquals(
                "OpenAI did not acknowledge EVA's conversation history within 10 seconds.",
                events.filterIsInstance<ProviderEvent.Failure>().single().message,
            )
            assertTrue(events.none { it is ProviderEvent.Connected })
            assertTrue(media.controls.value.microphoneMuted)
            collector.cancel()
        }

    @Test
    fun `a tool result arriving before response done keeps the input active for the follow-up`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = client,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1"}}""")
            media.incoming.send("""{"type":"response.created","response":{"id":"resp_1"}}""")
            media.incoming.send(
                """{"type":"response.output_item.done","item":{"type":"function_call","name":"eva_tool_0","call_id":"call_1","arguments":"{\"seconds\":180}"}}""",
            )
            runCurrent()
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()

            session.submitToolResult(CorrelatedToolResult(call.call, "HANDED_OFF", "Timer started."))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            media.incoming.send("""{"type":"response.created","response":{"id":"resp_2"}}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            media.incoming.send("""{"type":"response.output_audio_transcript.done","transcript":"Timer started."}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseEnded>().single().inputId)
            assertEquals("Timer started.", events.filterIsInstance<ProviderEvent.AssistantText>().single().text)
            collector.cancel()
        }

    private class FakeMedia : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        override val eventsReady = MutableStateFlow(false)
        val sent = mutableListOf<String>()
        var answer: String? = null

        override suspend fun createOffer(): String {
            state.value = RealtimeMediaState.OfferReady("v=0 offer")
            return "v=0 offer"
        }

        override suspend fun acceptAnswer(sdp: String) {
            answer = sdp
            state.value = RealtimeMediaState.Connected(true)
            eventsReady.value = true
        }

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
