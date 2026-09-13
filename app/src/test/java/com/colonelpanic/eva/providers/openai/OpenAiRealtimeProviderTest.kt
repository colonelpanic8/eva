package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val buffer = Buffer().also { chain.request().body?.writeTo(it) }
                requests += buffer.readUtf8()
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
                    "sk-test",
                    media,
                    "gpt-realtime-2.1",
                    client,
                    "https://example.test",
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            val session = provider.open(SessionOpenRequest("You are EVA.", catalog))
            assertEquals("v=0 answer", media.answer)
            val posted = requests.single()
            assertTrue(posted.contains("Bearer").not())
            assertTrue(posted.contains("\"type\":\"realtime\""))
            assertTrue(posted.contains("eva_tool_0"))
            assertTrue(posted.contains("gpt-4o-transcribe"))
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
    fun `typed input over the channel keeps the phone input ID`() =
        runTest {
            val media = FakeMedia()
            val session =
                OpenAiRealtimeProvider(
                    "sk-test",
                    media,
                    client = client,
                    baseUrl = "https://example.test",
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

        override fun setMicrophoneMuted(muted: Boolean) = Unit

        override fun setPlaybackMuted(muted: Boolean) = Unit

        override fun close() = Unit
    }
}
