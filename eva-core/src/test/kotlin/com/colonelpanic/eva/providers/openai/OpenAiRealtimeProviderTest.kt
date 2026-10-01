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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        ProviderToolCatalog(
            "rev-1",
            listOf(
                ProviderToolDefinition(
                    "extension.package.00000000-0000-0000-0000-000000000001.set_timer",
                    "Set a timer",
                    "Start a timer",
                    schema,
                ),
            ),
        )
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
                    client = client,
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
            media.incoming.send(
                """{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}],"model":"gpt-realtime-2.1"}}""",
            )
            createResponse(media, "resp_1")
            media.incoming.send(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"resp_1","transcript":"Set a timer for three minutes"}""",
            )
            media.incoming.send(
                """{"type":"response.output_item.done","response_id":"resp_1","item":{"type":"function_call","status":"completed","name":"eva_tool_0","call_id":"call_1","arguments":"{\"seconds\":180}"}}""",
            )
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            val connected = events.filterIsInstance<ProviderEvent.Connected>().single()
            assertEquals("sess_1", connected.sessionId)
            assertEquals("gpt-realtime-2.1", connected.model)
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseStarted>().single().inputId)
            assertEquals("Set a timer for three minutes", events.filterIsInstance<ProviderEvent.Transcript>().single().text)
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            assertEquals("extension.package.00000000-0000-0000-0000-000000000001.set_timer", call.capabilityId)
            assertEquals(JsonPrimitive(180), call.arguments["seconds"])
            assertEquals("voice:resp_1", call.call.inputId)
            assertEquals("resp_1", call.call.providerTurnId)
            // response.done for the tool-calling response must not end the input yet.
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            session.submitToolResult(CorrelatedToolResult(call.call, "HANDED_OFF", "Timer started."))
            val sent =
                media.sent.map { Json.parseToJsonElement(it).jsonObject }.filter {
                    it["response"]
                        ?.jsonObject
                        ?.get("metadata")
                        ?.jsonObject
                        ?.get("eva_purpose")
                        ?.jsonPrimitive
                        ?.content != "user_speech"
                }
            assertEquals("conversation.item.create", sent[0]["type"]?.let { (it as JsonPrimitive).content })
            assertEquals("function_call_output", sent[0]["item"]!!.jsonObject["type"]!!.let { (it as JsonPrimitive).content })
            assertEquals("response.create", sent[1]["type"]?.let { (it as JsonPrimitive).content })

            createResponse(media, "resp_2")
            media.incoming.send(
                """{"type":"response.output_audio_transcript.done","response_id":"resp_2","transcript":"Three-minute timer started."}""",
            )
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            assertEquals(1, events.count { it is ProviderEvent.ResponseStarted })
            val text = events.filterIsInstance<ProviderEvent.AssistantText>().single()
            assertEquals("voice:resp_1", text.inputId)
            assertEquals("Three-minute timer started.", text.text)
            val ended = events.filterIsInstance<ProviderEvent.ResponseEnded>().single()
            assertEquals("voice:resp_1", ended.inputId)
            assertEquals("completed", ended.status)

            // A later spontaneous turn starts fresh.
            createResponse(media, "resp_3")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_3","status":"cancelled"}}""")
            runCurrent()
            assertEquals("voice:resp_3", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().inputId)
            assertEquals("cancelled", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().status)
            media.incoming.close()
            runCurrent()
            assertEquals(ProviderEvent.Closed, events.last())
            collector.cancel()
        }

    @Test
    fun `the chosen reasoning effort is sent with the voice session`() =
        runTest {
            OpenAiRealtimeProvider(
                ApiKeyAccess("sk-test", "https://example.test"),
                FakeMedia(),
                "gpt-realtime-2.1",
                "high",
                client = client,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ).open(SessionOpenRequest("You are EVA.", catalog))
            assertTrue(requests.single().contains("\"reasoning\":{\"effort\":\"high\"}"))
        }

    @Test
    fun `a voice session defaults to low reasoning effort`() =
        runTest {
            OpenAiRealtimeProvider(
                ApiKeyAccess("sk-test", "https://example.test"),
                FakeMedia(),
                client = client,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ).open(SessionOpenRequest("You are EVA.", catalog))
            assertTrue(requests.single().contains("\"reasoning\":{\"effort\":\"low\"}"))
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
            runCurrent()
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
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            runCurrent()
            session.submit(ConversationInput("input-7", "Hello"))
            session.requestResponse(ResponseRequest("input-7"))
            assertTrue(media.sent.first().contains("\"input_text\""))
            createResponse(media, "resp_9")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_9","status":"completed"}}""")
            runCurrent()
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
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            media.incoming.send("""{"type":"output_audio_buffer.started","response_id":"resp_1"}""")
            media.incoming.send("""{"type":"output_audio_buffer.stopped","response_id":"resp_1"}""")
            media.incoming.send("""{"type":"output_audio_buffer.started","response_id":"resp_2"}""")
            media.incoming.send("""{"type":"output_audio_buffer.cleared","response_id":"resp_2"}""")
            runCurrent()
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

            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
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

            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"ignored"}""")
            media.incoming.send("""{"type":"input_audio_buffer.committed","item_id":"ignored"}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"premature-speech"}""")
            media.incoming.send("""{"type":"input_audio_buffer.committed","item_id":"premature-speech"}""")
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
            // OpenAI rejects the seed outright if an item id runs past its 32-character cap.
            assertTrue(ids.all { it.length <= REALTIME_ITEM_ID_LIMIT })
            assertEquals(ids.size, ids.distinct().size)
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
            session.submitContext("Seeding completed", true)
            runCurrent()
            assertEquals(
                "response.create",
                Json
                    .parseToJsonElement(media.sent.last())
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
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
            media.incoming.send("""{"type":"session.updated","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
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
    fun `tool items wait for completed response done and the input stays active through its follow-up`() =
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
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            createResponse(media, "resp_1")
            media.incoming.send(
                """{"type":"response.output_item.done","response_id":"resp_1","item":{"type":"function_call","status":"completed","name":"eva_tool_0","call_id":"call_1","arguments":"{\"seconds\":180}"}}""",
            )
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ToolCallReady })
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            session.submitToolResult(CorrelatedToolResult(call.call, "HANDED_OFF", "Timer started."))
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            createResponse(media, "resp_2")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })

            media.incoming.send("""{"type":"response.output_audio_transcript.done","response_id":"resp_2","transcript":"Timer started."}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseEnded>().single().inputId)
            assertEquals("Timer started.", events.filterIsInstance<ProviderEvent.AssistantText>().single().text)
            collector.cancel()
        }

    private fun TestScope.openSession(media: FakeMedia) =
        OpenAiRealtimeProvider(
            ApiKeyAccess("sk-test", "https://example.test"),
            media,
            client = client,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun functionCall(
        callId: String,
        responseId: String = "resp_1",
    ) =
        """{"type":"response.output_item.done","response_id":"$responseId","item":{"type":"function_call","status":"completed","name":"eva_tool_0","call_id":"$callId","arguments":"{\"seconds\":180}"}}"""

    private fun FakeMedia.nonSpeechResponses() =
        sent.map { Json.parseToJsonElement(it).jsonObject }.count {
            it["type"]?.jsonPrimitive?.content == "response.create" &&
                it
                    .getValue("response")
                    .jsonObject["metadata"]
                    ?.jsonObject
                    ?.get("eva_purpose")
                    ?.jsonPrimitive
                    ?.content != "user_speech"
        }

    @Test
    fun `a quiet result ends the input without asking the model to speak`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            createResponse(media, "resp_1")
            media.incoming.send(functionCall("call_1"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            val first = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            session.submitToolResult(CorrelatedToolResult(first.call, "COMPLETED", "Tapped.", respond = false))
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseEnded>().single().inputId)

            // Quiet calls still wait for completion before they can run.
            createResponse(media, "resp_2")
            media.incoming.send(functionCall("call_2", "resp_2"))
            runCurrent()
            assertEquals(1, events.count { it is ProviderEvent.ToolCallReady })
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            val second = events.filterIsInstance<ProviderEvent.ToolCallReady>().last()
            session.submitToolResult(CorrelatedToolResult(second.call, "COMPLETED", "Tapped.", respond = false))
            runCurrent()
            assertEquals(2, events.count { it is ProviderEvent.ResponseEnded })
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            assertEquals("voice:resp_2", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().inputId)
            collector.cancel()
        }

    @Test
    fun `a later call is answered while an earlier one runs and follow-ups wait for the active response`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            createResponse(media, "resp_1")
            media.incoming.send(functionCall("long"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            // The user speaks while the long call runs; turn detection starts a response that calls another tool.
            createResponse(media, "resp_2")
            media.incoming.send(functionCall("short", "resp_2"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            val (long, short) = events.filterIsInstance<ProviderEvent.ToolCallReady>()
            session.submitToolResult(CorrelatedToolResult(short.call, "COMPLETED", "Done."))
            assertEquals(1, media.nonSpeechResponses())

            createResponse(media, "resp_3")
            runCurrent()
            session.submitToolResult(CorrelatedToolResult(long.call, "COMPLETED", "Task finished."))
            assertEquals(1, media.nonSpeechResponses())
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_3","status":"completed"}}""")
            runCurrent()
            assertEquals(2, media.nonSpeechResponses())
            assertEquals(listOf("voice:resp_2"), events.filterIsInstance<ProviderEvent.ResponseEnded>().map { it.inputId })

            createResponse(media, "resp_4")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_4","status":"completed"}}""")
            runCurrent()
            assertEquals("voice:resp_1", events.filterIsInstance<ProviderEvent.ResponseEnded>().last().inputId)
            collector.cancel()
        }

    @Test
    fun `missing catalog acknowledgement fails visibly and closes instead of hanging`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            runCurrent()
            media.incoming.send("""{"type":"session.created","session":{"id":"bare","tools":[]}}""")
            advanceTimeBy(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.Connected })
            val failure = events.filterIsInstance<ProviderEvent.Failure>().single()
            assertTrue(
                failure.message.contains("1 tools") && failure.message.contains("8 seconds") &&
                    failure.message.contains("without its tools"),
            )
            assertEquals(ProviderEvent.Closed, events.last())
            collector.cancel()
        }

    @Test
    fun `a configured update after a bare session acknowledges the catalog and cancels the failure`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"bare","tools":[]}}""")
            advanceTimeBy(4_000)
            assertTrue(events.none { it is ProviderEvent.Connected })
            media.incoming.send(
                """{"type":"session.updated","session":{"id":"configured","tools":[{"name":"eva_tool_0"}]}}""",
            )
            advanceTimeBy(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals("configured", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
            assertTrue(events.none { it is ProviderEvent.Failure })
            collector.cancel()
        }

    private val locatedClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val buffer = Buffer().also { chain.request().body?.writeTo(it) }
                requests += buffer.readUtf8()
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .header("Location", "/v1/realtime/calls/rtc_test")
                    .body("v=0 answer".toResponseBody("application/sdp".toMediaType()))
                    .build()
            }.build()

    /** Instructions large enough that OpenAI cannot echo the session on a 64 KiB data channel. */
    private val largeInstructions = "x".repeat(70_000)

    @Test
    fun `a configuration too large to echo is posted whole and confirmed through the sideband`() =
        runTest {
            val media = FakeMedia()
            val asked = mutableListOf<String>()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = locatedClient,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    sideband = { callId ->
                        asked += callId
                        Json.parseToJsonElement("""{"id":"sess_big","tools":[{"name":"eva_tool_0"}]}""").jsonObject
                    },
                ).open(SessionOpenRequest(largeInstructions, catalog))
            assertTrue(requests.single().contains(largeInstructions) && requests.single().contains("eva_tool_0"))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_big","tools":[]}}""")
            runCurrent()
            assertEquals(listOf("rtc_test"), asked)
            assertEquals("sess_big", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
            advanceTimeBy(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.Failure })
            collector.cancel()
        }

    @Test
    fun `a sideband echo missing tools fails at once instead of starting a call without them`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = locatedClient,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    sideband = { Json.parseToJsonElement("""{"id":"sess_big","tools":[]}""").jsonObject },
                ).open(SessionOpenRequest(largeInstructions, catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            runCurrent()
            val failure = events.filterIsInstance<ProviderEvent.Failure>().single()
            assertTrue(failure.message.contains("sideband") && failure.message.contains("0 of the 1 tools"))
            assertTrue(events.none { it is ProviderEvent.Connected })
            assertEquals(ProviderEvent.Closed, events.last())
            collector.cancel()
        }

    @Test
    fun `an unreachable sideband continues the call on OpenAI's own session with a loud notice`() =
        runTest {
            for ((callClient, failure) in listOf(locatedClient to "sideband refused", client to "did not identify the call")) {
                val media = FakeMedia()
                val session =
                    OpenAiRealtimeProvider(
                        ApiKeyAccess("sk-test", "https://example.test"),
                        media,
                        client = callClient,
                        ioDispatcher = StandardTestDispatcher(testScheduler),
                        sideband = { error("sideband refused") },
                    ).open(SessionOpenRequest(largeInstructions, catalog))
                val events = mutableListOf<ProviderEvent>()
                val collector = launch { session.events.collect { events += it } }
                runCurrent()
                media.incoming.send("""{"type":"session.created","session":{"id":"sess_bare","tools":[]}}""")
                runCurrent()
                advanceTimeBy(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
                runCurrent()
                assertEquals("sess_bare", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
                val shown = events.filterIsInstance<ProviderEvent.Notice>().single()
                assertTrue(shown.persistent)
                val notice = shown.message
                assertTrue(notice.contains("could not confirm") && notice.contains("1 voice tools") && notice.contains(failure))
                assertTrue(events.none { it is ProviderEvent.Failure })
                collector.cancel()
            }
        }

    @Test
    fun `a sideband that never answers is abandoned at the deadline instead of ending the call`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    ApiKeyAccess("sk-test", "https://example.test"),
                    media,
                    client = locatedClient,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    sideband = { kotlinx.coroutines.awaitCancellation() },
                ).open(SessionOpenRequest(largeInstructions, catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_bare","tools":[]}}""")
            advanceTimeBy(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
            runCurrent()
            assertTrue(
                events
                    .filterIsInstance<ProviderEvent.Notice>()
                    .single()
                    .message
                    .contains("no answer within 8 seconds"),
            )
            assertEquals("sess_bare", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
            collector.cancel()
        }

    @Test
    fun `the echo bound follows the advertised receive size with a server-envelope margin`() {
        assertEquals(248L * 1024, realtimeEchoByteLimit("v=0\r\na=max-message-size:262144\r\n"))
        assertEquals(56L * 1024, realtimeEchoByteLimit("v=0\r\na=max-message-size:65536\r\n"))
        assertEquals(56L * 1024, realtimeEchoByteLimit("v=0"))
        assertEquals(Long.MAX_VALUE, realtimeEchoByteLimit("v=0\r\na=max-message-size:0\r\n"))
    }

    @Test
    fun `lifecycle reply has no tools and attributed findings stay outside the system message`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            createResponse(media, "resp_1")
            media.incoming.send("""{"type":"output_audio_buffer.started"}""")
            runCurrent()
            assertTrue(session.submitContext("EVA task update", respond = true, data = buildJsonObject { put("answer", "quoted finding") }))
            assertTrue(session.submitContext("A second task update", respond = true))
            assertEquals(0, media.nonSpeechResponses())
            val item =
                media.sent
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .first { "item" in it }
                    .getValue("item")
                    .jsonObject
            assertEquals("system", item.getValue("role").jsonPrimitive.content)
            assertEquals(
                "input_text",
                item
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "EVA task update",
                item
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content,
            )
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            media.incoming.send("""{"type":"output_audio_buffer.stopped"}""")
            runCurrent()
            assertEquals(1, media.nonSpeechResponses())
            val request =
                Json
                    .parseToJsonElement(media.sent.last())
                    .jsonObject
                    .getValue("response")
                    .jsonObject
            assertEquals("none", request.getValue("tool_choice").jsonPrimitive.content)
            val findings =
                media.sent
                    .map { Json.parseToJsonElement(it).jsonObject }
                    .filter { "item" in it }[1]
                    .getValue("item")
                    .jsonObject
            assertEquals("user", findings.getValue("role").jsonPrimitive.content)
            assertTrue(findings.toString().contains("external_data"))
            assertTrue(findings.toString().contains("quoted finding"))
            createResponse(media, "resp_2")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            runCurrent()
            assertEquals(1, media.nonSpeechResponses())
            assertEquals(2, events.filterIsInstance<ProviderEvent.ResponseEnded>().size)
            assertTrue(events.filterIsInstance<ProviderEvent.ResponseStarted>().last().announceOnly)
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"next"}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_stopped","item_id":"next"}""")
            createResponse(media, "resp_3", "next")
            media.incoming.send(functionCall("requested", "resp_3"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_3","status":"completed"}}""")
            runCurrent()
            assertTrue(!events.filterIsInstance<ProviderEvent.ResponseStarted>().last().announceOnly)
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single().call
            session.submitToolResult(CorrelatedToolResult(call, "COMPLETED", "Opened"))
            val followUp = Json.parseToJsonElement(media.sent.last()).jsonObject
            assertTrue(!followUp.getValue("response").jsonObject.containsKey("tool_choice"))
            collector.cancel()
        }

    @Test
    fun `context can announce while a tool runs and silent context never starts a response`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            runCurrent()
            assertTrue(session.submitContext("Silent update", respond = false))
            assertEquals(0, media.nonSpeechResponses())
            createResponse(media, "resp_1")
            media.incoming.send(functionCall("task"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            runCurrent()
            assertTrue(session.submitContext("Task finished", respond = true))
            assertEquals(1, media.nonSpeechResponses())
            session.submitToolResult(
                CorrelatedToolResult(
                    events.filterIsInstance<ProviderEvent.ToolCallReady>().single().call,
                    "COMPLETED",
                    "Done",
                    respond = false,
                ),
            )
            runCurrent()
            assertEquals(1, media.nonSpeechResponses())
            // No second response is submitted while that response is awaiting its created event.
            assertTrue(session.submitContext("Another finding", respond = true))
            assertEquals(1, media.nonSpeechResponses())
            session.close()
            assertTrue(!session.submitContext("Closed", respond = true))
            collector.cancel()
        }

    @Test
    fun `context waits for committed speech response instead of racing speech stopped`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val collector = launch { session.events.collect {} }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"first"}""")
            runCurrent()
            session.submitContext("Task finished", respond = true)
            assertEquals(0, media.nonSpeechResponses())
            media.incoming.send("""{"type":"input_audio_buffer.speech_stopped","item_id":"first"}""")
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            // Even an old playback-stop event cannot race the pending server VAD response.
            media.incoming.send("""{"type":"output_audio_buffer.stopped"}""")
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            createResponse(media, "vad", "first")
            media.incoming.send("""{"type":"output_audio_buffer.started"}""")
            media.incoming.send("""{"type":"response.done","response":{"id":"vad","status":"completed"}}""")
            runCurrent()
            assertEquals(0, media.nonSpeechResponses())
            media.incoming.send("""{"type":"output_audio_buffer.stopped"}""")
            runCurrent()
            assertEquals(1, media.nonSpeechResponses())
            collector.cancel()
        }

    @Test
    fun `late speech transcripts and device corrections retain their committed input`() =
        runTest {
            val media = FakeMedia()
            val session = openSession(media).open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            media.incoming.send("""{"type":"session.created","session":{"id":"sess_1","tools":[{"name":"eva_tool_0"}]}}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"first"}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_stopped","item_id":"first"}""")
            createResponse(media, "resp_1", "first")
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_1","status":"completed"}}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"second"}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_stopped","item_id":"second"}""")
            createResponse(media, "resp_2", "second")
            media.incoming.send(functionCall("device", "resp_2"))
            media.incoming.send("""{"type":"response.done","response":{"id":"resp_2","status":"completed"}}""")
            media.incoming.send(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"first","transcript":"First request"}""",
            )
            media.incoming.send(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"second","transcript":"Device request"}""",
            )
            media.incoming.send("""{"type":"input_audio_buffer.speech_started","item_id":"correction"}""")
            media.incoming.send("""{"type":"input_audio_buffer.speech_stopped","item_id":"correction"}""")
            createResponse(media, "resp_3", "correction")
            media.incoming.send(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"correction","transcript":"Use the home network"}""",
            )
            runCurrent()
            assertEquals(
                listOf("voice:first", "voice:second", "voice:correction"),
                events.filterIsInstance<ProviderEvent.Transcript>().map { it.inputId },
            )
            collector.cancel()
        }

    private suspend fun TestScope.createResponse(
        media: FakeMedia,
        id: String,
        speechItemId: String = id,
    ) {
        runCurrent()

        fun pendingRequest() =
            media.sent.map { Json.parseToJsonElement(it).jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "response.create" && it["event_id"]?.jsonPrimitive?.content !in media.acknowledged
            }
        if (pendingRequest() == null) {
            media.incoming.send("""{"type":"input_audio_buffer.committed","item_id":"$speechItemId"}""")
            runCurrent()
        }
        val request = checkNotNull(pendingRequest())
        media.acknowledged += request.getValue("event_id").jsonPrimitive.content
        media.incoming.send(
            buildJsonObject {
                put("type", "response.created")
                put(
                    "response",
                    buildJsonObject {
                        put("id", id)
                        put("metadata", request.getValue("response").jsonObject.getValue("metadata"))
                    },
                )
            }.toString(),
        )
        runCurrent()
    }

    private class FakeMedia : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        override val eventsReady = MutableStateFlow(false)
        val sent = mutableListOf<String>()
        val acknowledged = mutableSetOf<String>()
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
