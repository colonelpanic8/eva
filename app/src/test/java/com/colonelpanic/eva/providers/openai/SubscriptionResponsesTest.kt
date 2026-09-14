package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolCatalog
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subscription backend keeps nothing and answers as a stream, so these cover the two ways
 * it differs from a key: the conversation is resent by the phone, and items arrive as events.
 */
class SubscriptionResponsesTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{"destination":{"type":"string"}},"required":["destination"],"additionalProperties":false}""",
            ).jsonObject
    private val catalog =
        ProviderToolCatalog("rev-1", listOf(ProviderToolDefinition("eva.android.maps.search", "Search maps", "Open a map search", schema)))
    private val tokens = ChatGptTokens("id", "access-1", "refresh-1", "acct-1", "eva@example.test", "pro", 0)
    private val access = SubscriptionAccess({ tokens }, "0.4.0-debug", "https://backend.test")
    private val requests = mutableListOf<String>()
    private val headers = mutableListOf<Map<String, String>>()
    private val streams =
        ArrayDeque(
            listOf(
                """
                data: {"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}

                data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"eva_tool_0","arguments":"{\"destination\":\"Ferry Building\"}"}}

                data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[]}}
                """.trimIndent(),
                """
                data: {"type":"response.created","response":{"id":"resp_2","status":"in_progress"}}

                data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Opened the Ferry Building."}]}}

                data: {"type":"response.completed","response":{"id":"resp_2","status":"completed","output":[]}}
                """.trimIndent(),
            ),
        )
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val listing =
                    chain
                        .request()
                        .url.encodedPath
                        .endsWith("/models")
                if (!listing) {
                    requests += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
                    headers += chain.request().headers.toMap()
                }
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        if (listing) {
                            """{"models":[{"slug":"gpt-test"}]}""".toResponseBody("application/json".toMediaType())
                        } else {
                            streams.removeFirst().toResponseBody("text/event-stream".toMediaType())
                        },
                    ).build()
            }.build()

    @Test
    fun `a subscription turn streams its items and carries the conversation forward itself`() =
        runTest {
            val session =
                OpenAiResponsesProvider(access, "gpt-test", client, StandardTestDispatcher(testScheduler))
                    .open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            advanceUntilIdle()
            session.submit(ConversationInput("input-1", "Take me to the Ferry Building"))
            session.requestResponse(ResponseRequest("input-1"))
            advanceUntilIdle()

            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            assertEquals("eva.android.maps.search", call.capabilityId)
            session.submitToolResult(CorrelatedToolResult(call.call, "ok", "Map opened.", null))
            advanceUntilIdle()

            assertEquals("Opened the Ferry Building.", events.filterIsInstance<ProviderEvent.AssistantText>().single().text)
            assertEquals(ChatGpt.ACCOUNT_LABEL, events.filterIsInstance<ProviderEvent.Account>().single().label)

            val first = Json.parseToJsonElement(requests.first()).jsonObject
            assertFalse(first.getValue("store").jsonPrimitive.boolean)
            assertTrue(first.getValue("stream").jsonPrimitive.boolean)
            assertNull(first["previous_response_id"])
            val content =
                first
                    .getValue("input")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("content")
                    .jsonArray[0]
                    .jsonObject
            assertEquals("input_text", content.getValue("type").jsonPrimitive.content)

            // Nothing is stored for it, so the follow-up has to carry the turn so far.
            val second =
                Json
                    .parseToJsonElement(requests[1])
                    .jsonObject
                    .getValue("input")
                    .jsonArray
            assertEquals(
                listOf("message", "function_call", "function_call_output"),
                second.map {
                    it.jsonObject
                        .getValue("type")
                        .jsonPrimitive.content
                },
            )

            assertEquals("Bearer access-1", headers.first()["Authorization"])
            assertEquals("acct-1", headers.first()["chatgpt-account-id"])
            assertEquals("eva", headers.first()["originator"])
            collector.cancel()
        }

    @Test
    fun `subscription responses keep explicit seed messages at the head of local history`() =
        runTest {
            val history =
                listOf(
                    HistoryItem.User("Take me downtown"),
                    HistoryItem.Assistant("I can open a route."),
                    HistoryItem.ActionEvidence("Open route", mapOf("destination" to "Downtown"), "HANDED_OFF", "Maps opened."),
                    HistoryItem.Note("The voice attachment ended."),
                )
            val session =
                OpenAiResponsesProvider(access, "gpt-test", client, StandardTestDispatcher(testScheduler))
                    .open(SessionOpenRequest("You are EVA.", catalog, history = history))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            advanceUntilIdle()

            session.submit(ConversationInput("input-1", "Continue"))
            session.requestResponse(ResponseRequest("input-1"))
            advanceUntilIdle()
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            session.submitToolResult(CorrelatedToolResult(call.call, "ok", "Map opened."))
            advanceUntilIdle()

            val first =
                Json
                    .parseToJsonElement(requests[0])
                    .jsonObject
                    .getValue("input")
                    .jsonArray
            assertEquals(
                listOf("user", "assistant", "developer", "developer", "user"),
                first.map {
                    it.jsonObject
                        .getValue("role")
                        .jsonPrimitive.content
                },
            )
            assertTrue(
                first.all {
                    it.jsonObject
                        .getValue("type")
                        .jsonPrimitive.content == "message"
                },
            )
            assertTrue(
                first.all {
                    it.jsonObject
                        .getValue("content")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive.content == "input_text"
                },
            )
            val evidenceText =
                first[2]
                    .jsonObject
                    .getValue("content")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            assertTrue(evidenceText.contains("Title: Open route"))
            assertTrue(evidenceText.contains("Status: HANDED_OFF"))
            assertTrue(evidenceText.contains("Message: Maps opened."))

            val second =
                Json
                    .parseToJsonElement(requests[1])
                    .jsonObject
                    .getValue("input")
                    .jsonArray
            assertEquals(
                listOf("user", "assistant", "developer", "developer"),
                second.take(4).map {
                    it.jsonObject
                        .getValue("role")
                        .jsonPrimitive.content
                },
            )
            collector.cancel()
        }

    @Test
    fun `the model list URL keeps a debug suffix out and stays above the backend floor`() {
        assertTrue(access.modelsUrl.endsWith("/models?client_version=$MIN_MODELS_CLIENT_VERSION"))
    }

    @Test
    fun `a client newer than the floor lists models as itself`() {
        val newer = SubscriptionAccess({ tokens }, "2.3.4", "https://backend.test")
        assertTrue(newer.modelsUrl.endsWith("/models?client_version=2.3.4"))
    }

    @Test
    fun `client versions compare numerically, not lexicographically`() {
        assertTrue(compareSemantic("0.8.0", "1.0.0") < 0)
        assertTrue(compareSemantic("0.100.0", "0.99.9") > 0)
        assertTrue(compareSemantic("1.0.0", "1.0.0") == 0)
    }
}
