package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.MediaControls
import com.colonelpanic.eva.audio.MediaTimeline
import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.audio.RealtimeMediaState
import com.colonelpanic.eva.providers.ClientDelegationSession
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiLiveProviderTest {
    private val requests = mutableListOf<okhttp3.Request>()
    private val bodies = mutableListOf<JsonObject>()
    private var status = 200
    private var response = """{"session":{"id":"live_1"},"transport":{"sdp":"v=0 answer"}}"""
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                bodies += Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("test")
                    .body(response.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
    private val request =
        SessionOpenRequest(
            "User's configured instructions",
            ProviderToolCatalog("rev", emptyList()),
            history = listOf(HistoryItem.User("Earlier request")),
        )

    private fun TestScope.provider(
        media: FakeMedia,
        access: OpenAiAccess = ApiKeyAccess("test-key", "https://example.test"),
    ) = OpenAiLiveProvider(
        access,
        media,
        "Live delegation policy",
        client = client,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `WebRTC starts once with client delegation and waits for session started`() =
        runTest {
            val media = FakeMedia(startAutomatically = false)
            val opening = async { provider(media).open(request) }
            runCurrent()
            assertFalse(opening.isCompleted)
            assertEquals("v=0 answer", media.answer)
            assertEquals("/v1/live/sessions", requests.single().url.encodedPath)
            val payload = bodies.single()
            assertEquals("webrtc", payload.obj("transport")!!.str("type"))
            val config = payload.obj("session")!!
            assertEquals("client", config.obj("delegation")!!.str("type"))
            assertEquals(OpenAiModels.LIVE, config.str("model"))
            assertTrue(config.str("instructions")!!.contains(request.instructions))
            assertFalse(config.containsKey("tools"))
            assertFalse(config.containsKey("reasoning"))
            assertEquals(
                "Earlier request",
                config["input"]!!
                    .jsonArray
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject
                    .str("text"),
            )
            media.started()
            val session = opening.await()
            val events = mutableListOf<ProviderEvent>()
            val collect = launch { session.events.collect { events += it } }
            runCurrent()
            assertEquals("live_1", events.filterIsInstance<ProviderEvent.Connected>().single().sessionId)
            assertTrue(media.sent.isEmpty())
            session.close()
            collect.join()
            assertTrue(media.closed)
            assertEquals(listOf("session.close"), media.sent.map { it.str("type") })
        }

    @Test
    fun `timed captions preserve fragments and duplicate packets cannot repeat a delegation`() =
        runTest {
            val media = FakeMedia()
            val session = provider(media).open(request)
            val events = mutableListOf<ProviderEvent>()
            val collect = launch { session.events.collect { events += it } }
            media.incoming.send(
                """{"type":"session.input_transcript.delta","event_id":"t1","delta":"No, no","start_ms":100,"end_ms":200}""",
            )
            media.incoming.send("""{"type":"session.output_transcript.delta","event_id":"t2","delta":"Okay","start_ms":150,"end_ms":250}""")
            media.incoming.send(
                """{"type":"session.input_transcript.delta","event_id":"t3","delta":" tomorrow","start_ms":201,"end_ms":400}""",
            )
            repeat(2) { media.delegation() }
            runCurrent()
            val captions = events.filterIsInstance<ProviderEvent.TimedTranscript>()
            assertEquals(listOf("No, no", "Okay", " tomorrow"), captions.map { it.text })
            assertEquals(listOf(100L, 150L, 201L), captions.map { it.startMs })
            assertEquals(1, events.count { it is ProviderEvent.ClientDelegationRequested })
            assertTrue(events.none { it is ProviderEvent.ResponseStarted || it is ProviderEvent.ToolCallReady })
            session.close()
            collect.join()
        }

    @Test
    fun `result chunks preserve Unicode and delegation but acknowledgments never confirm speech delivery`() =
        runTest {
            val media = FakeMedia()
            val session = provider(media).open(request) as ClientDelegationSession
            val events = mutableListOf<ProviderEvent>()
            val collect = launch { session.events.collect { events += it } }
            media.delegation()
            runCurrent()
            val result = buildJsonObject { put("answer", "Verified café 😀 你好\n".repeat(200)) }
            assertTrue(session.appendDelegationUpdate("delegation_1", "Verified task result", result, true))
            val sent = media.sent.toList()
            assertTrue(sent.size > 1)
            assertTrue(sent.all { it.str("delegation_id") == "delegation_1" })
            assertTrue(sent.dropLast(1).all { it.str("type") == "session.thinking.append" })
            assertEquals("session.commentary.append", sent.last().str("type"))
            val pieces = sent.map { Json.parseToJsonElement(it.str("content")!!).jsonObject }
            assertEquals((1..pieces.size).toList(), pieces.map { it["part"]!!.jsonPrimitive.int })
            assertEquals(1, pieces.map { it.str("update_id") }.distinct().size)
            assertTrue(sent.all { it.str("content")!!.toByteArray(Charsets.UTF_8).size <= 500 })
            val reconstructed = Json.parseToJsonElement(pieces.joinToString("") { it.str("text")!! }).jsonObject
            assertEquals(result, reconstructed["data"])
            for (command in sent) {
                media.incoming.send(
                    buildJsonObject {
                        put("type", command.str("type")!!.replace(".append", ".appended"))
                        put("client_event_id", command.str("event_id"))
                    }.toString(),
                )
            }
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ContextDelivery || it is ProviderEvent.AssistantSpeaking })
            session.close()
            collect.join()
        }

    private fun subscription(): SubscriptionAccess =
        SubscriptionAccess(
            { ChatGptTokens("id", "access-1", "refresh-1", "acct-1", "eva@example.test", "pro", 0) },
            "1.0.0",
            "https://backend.test",
        )

    @Test
    fun `subscription Live uses Codex handshake captions and speakable delegation results without an API key`() =
        runTest {
            response = "v=0 subscription answer"
            val media = FakeMedia(startedModel = null)
            val session = provider(media, subscription()).open(request) as ClientDelegationSession
            val events = mutableListOf<ProviderEvent>()
            val collect = launch { session.events.collect { events += it } }
            runCurrent()
            val http = requests.single()
            assertEquals("https://backend.test/realtime/calls?intent=quicksilver&architecture=avas", http.url.toString())
            assertEquals("Bearer access-1", http.header("Authorization"))
            assertEquals("acct-1", http.header("chatgpt-account-id"))
            assertEquals("quicksilver=v2", http.header("OpenAI-Alpha"))
            val body = bodies.single()
            assertEquals("v=0 offer", body.str("sdp"))
            val config = body.obj("session")!!
            assertEquals(OpenAiModels.LIVE_SUBSCRIPTION, config.str("model"))
            assertEquals("cove", config.obj("audio")!!.obj("output")!!.str("voice"))
            assertEquals(
                "Earlier request",
                config["initial_items"]!!
                    .jsonArray
                    .single()
                    .jsonObject["content"]!!
                    .jsonArray
                    .single()
                    .jsonObject
                    .str("text"),
            )
            assertFalse(config.containsKey("input"))
            assertFalse(config.containsKey("store"))
            assertEquals(ProviderEvent.Account(ChatGpt.ACCOUNT_LABEL), events.first())
            assertEquals(OpenAiModels.LIVE_SUBSCRIPTION, events.filterIsInstance<ProviderEvent.Connected>().single().model)
            repeat(2) {
                media.incoming.send(
                    """{"type":"input_transcript.added","start_ms":100,"end_ms":200,"item":{"id":"caption","text":"Find my flight"}}""",
                )
                media.incoming.send(
                    """{"type":"delegation.created","offset_ms":200,"item":{"id":"delegation_1","type":"delegation","target":"client"}}""",
                )
            }
            media.incoming.send(
                """{"type":"output_transcript.added","start_ms":200,"end_ms":300,"item":{"id":"reply","text":"Checking."}}""",
            )
            runCurrent()
            assertEquals(listOf("Find my flight", "Checking."), events.filterIsInstance<ProviderEvent.TimedTranscript>().map { it.text })
            assertEquals(
                ProviderEvent.ClientDelegationRequested("live_1", "delegation_1", 200),
                events.filterIsInstance<ProviderEvent.ClientDelegationRequested>().single(),
            )
            session.appendDelegationUpdate("delegation_1", "Still checking", respond = false)
            session.appendDelegationUpdate("delegation_1", "It departs at nine.")
            session.submitContext("Another task finished.", respond = true, deliveryId = "result")
            val updates = media.sent
            assertEquals("delegation.context.append", updates[0].str("type"))
            assertFalse(updates[0].containsKey("channel"))
            assertEquals("delegation_1", updates[1].str("delegation_item_id"))
            assertEquals("speakable", updates[1].str("channel"))
            val envelope =
                Json
                    .parseToJsonElement(
                        updates[1]["content"]!!
                            .jsonArray
                            .single()
                            .jsonObject
                            .str("text")!!,
                    ).jsonObject
            assertEquals("It departs at nine.", Json.parseToJsonElement(envelope.str("text")!!).jsonObject.str("note"))
            assertEquals("session.context.append", updates[2].str("type"))
            assertFalse(updates[2].containsKey("delegation_item_id"))
            media.incoming.send("""{"type":"session.context.appended","client_event_id":"${updates[2].str("event_id")}"}""")
            runCurrent()
            assertTrue(events.none { it is ProviderEvent.ContextDelivery || it is ProviderEvent.AssistantSpeaking })
            session.close()
            collect.join()
            assertTrue(media.closed)
        }

    @Test
    fun `a subscription SDP answer followed by access denied never counts as a connected session`() =
        runTest {
            response = "v=0 subscription answer"
            val media = FakeMedia(startAutomatically = false)
            val opening = async { runCatching { provider(media, subscription()).open(request) } }
            runCurrent()
            assertFalse(opening.isCompleted)
            media.incoming.send("""{"type":"error","error":{"code":"forbidden","message":"Voice session access denied."}}""")
            val error = opening.await().exceptionOrNull()
            assertEquals("Voice session access denied.", error?.message)
            assertTrue(media.closed)
            assertEquals(1, requests.size)
        }

    @Test
    fun `API rejection releases media and explains the funding error`() =
        runTest {
            status = 429
            response = """{"error":{"code":"credit_balance_exhausted","message":"You have no credits remaining."}}"""
            val media = FakeMedia()
            val error = runCatching { provider(media).open(request) }.exceptionOrNull()
            assertTrue(error!!.message!!.contains("no credits remaining"))
            assertTrue(media.closed)
        }

    @Test
    fun `trimming startup history is visible and leaves full task history outside Live`() =
        runTest {
            val media = FakeMedia()
            val session = provider(media).open(request.copy(history = List(140) { HistoryItem.User("message $it") }))
            val events = mutableListOf<ProviderEvent>()
            val collect = launch { session.events.collect { events += it } }
            runCurrent()
            assertEquals(
                128,
                bodies
                    .single()
                    .obj("session")!!["input"]!!
                    .jsonArray.size,
            )
            assertTrue(events.filterIsInstance<ProviderEvent.Notice>().any { it.persistent && it.message.contains("128 of 140") })
            session.close()
            collect.join()
        }

    @Test
    fun `Live voice selection keeps delegated phone calls on a Realtime model`() {
        assertEquals(OpenAiModels.REALTIME, OpenAiModels.callModel(OpenAiModels.LIVE))
        assertEquals("gpt-realtime-custom", OpenAiModels.callModel("gpt-realtime-custom"))
        assertFalse(OpenAiModels.isLive(OpenAiModels.REALTIME))
    }

    private class FakeMedia(
        private val startAutomatically: Boolean = true,
        private val startedModel: String? = OpenAiModels.LIVE,
    ) : RealtimeMediaSession {
        override val state = MutableStateFlow<RealtimeMediaState>(RealtimeMediaState.Idle)
        override val controls = MutableStateFlow(MediaControls())
        override val timeline = MutableStateFlow(MediaTimeline())
        override val eventsReady = MutableStateFlow(true)
        val incoming = Channel<String>(Channel.UNLIMITED)
        override val events = incoming.receiveAsFlow()
        val sent = mutableListOf<JsonObject>()
        var answer: String? = null
        var closed = false

        override suspend fun createOffer() = "v=0 offer"

        override suspend fun acceptAnswer(sdp: String) {
            answer = sdp
            if (startAutomatically) started()
        }

        suspend fun started() =
            incoming.send(
                buildJsonObject {
                    put("type", "session.started")
                    put(
                        "session",
                        buildJsonObject {
                            put("id", "live_1")
                            startedModel?.let { put("model", it) }
                        },
                    )
                }.toString(),
            )

        suspend fun delegation() =
            incoming.send(
                """{"type":"session.delegation.created","event_id":"d1","offset_ms":400,
                "delegation":{"id":"delegation_1","target":"client"}}""",
            )

        override fun send(event: String) {
            val value = Json.parseToJsonElement(event).jsonObject
            sent += value
            if (value.str("type") == "session.close") incoming.trySend("""{"type":"session.closed","reason":"client_closed"}""")
        }

        override fun setMicrophoneMuted(muted: Boolean) {
            controls.value = controls.value.copy(microphoneMuted = muted)
        }

        override fun setPlaybackMuted(muted: Boolean) {
            controls.value = controls.value.copy(playbackMuted = muted)
        }

        override fun close() {
            closed = true
            incoming.close()
        }
    }
}
