package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.CorrelatedToolResult
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

class OpenAiResponsesProviderTest {
    private val schema =
        Json
            .parseToJsonElement(
                """{"type":"object","properties":{"destination":{"type":"string","minLength":1}},
                "required":["destination"],"additionalProperties":false}""",
            ).jsonObject
    private val catalog =
        ProviderToolCatalog("rev-1", listOf(ProviderToolDefinition("eva.android.maps.search", "Search maps", "Open a map search", schema)))
    private val requests = mutableListOf<String>()
    private val modelList = """{"data":[{"id":"gpt-test"},{"id":"gpt-realtime-2.1"}]}"""
    private val replies =
        ArrayDeque(
            listOf(
                """{"id":"resp_1","status":"completed","output":[{"type":"function_call","call_id":"call_1","name":"eva_tool_0","arguments":"{\"destination\":\"Ferry Building\"}"}]}""",
                """{"id":"resp_2","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Opened the Ferry Building on the map."}]}]}""",
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
                val buffer = Buffer().also { chain.request().body?.writeTo(it) }
                if (!listing) requests += buffer.readUtf8()
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body((if (listing) modelList else replies.removeFirst()).toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    @Test
    fun `a model the account cannot use is refused before the session claims to be connected`() =
        runTest {
            val provider =
                OpenAiResponsesProvider("sk-test", "gpt-missing", client, "https://example.test", StandardTestDispatcher(testScheduler))
            val error = runCatching { provider.open(SessionOpenRequest("You are EVA.", catalog)) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("This account cannot use gpt-missing. Choose another text model.", error!!.message)
        }

    @Test
    fun `a rejected request surfaces the provider's own message`() =
        runTest {
            replies.clear()
            replies.addLast(
                """{"error":{"message":"Incorrect API key provided: sk-test-***","type":"invalid_request_error","code":"invalid_api_key"}}""",
            )
            val failing =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val listing =
                            chain
                                .request()
                                .url.encodedPath
                                .endsWith("/models")
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (listing) 200 else 401)
                            .message(if (listing) "OK" else "Unauthorized")
                            .body((if (listing) modelList else replies.removeFirst()).toResponseBody("application/json".toMediaType()))
                            .build()
                    }.build()
            val session =
                OpenAiResponsesProvider("sk-test", "gpt-test", failing, "https://example.test", StandardTestDispatcher(testScheduler))
                    .open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            advanceUntilIdle()
            session.submit(ConversationInput("input-1", "Hello"))
            session.requestResponse(ResponseRequest("input-1"))
            advanceUntilIdle()
            assertEquals(
                "OpenAI rejected the request (401): Incorrect API key provided: sk-test-***",
                events.filterIsInstance<ProviderEvent.Failure>().single().message,
            )
            collector.cancel()
        }

    @Test
    fun `a typed turn round-trips a function call and continues from the previous response`() =
        runTest {
            val session =
                OpenAiResponsesProvider("sk-test", "gpt-test", client, "https://example.test", StandardTestDispatcher(testScheduler))
                    .open(SessionOpenRequest("You are EVA.", catalog))
            val events = mutableListOf<ProviderEvent>()
            val collector = launch { session.events.collect { events += it } }
            advanceUntilIdle()
            assertEquals("gpt-test", events.filterIsInstance<ProviderEvent.Connected>().single().model)
            session.submit(ConversationInput("input-1", "Find the Ferry Building"))
            session.requestResponse(ResponseRequest("input-1"))
            advanceUntilIdle()
            val first = Json.parseToJsonElement(requests[0]).jsonObject
            assertTrue(requests[0].contains("eva_tool_0"))
            assertEquals(JsonPrimitive("gpt-test"), first["model"])
            val call = events.filterIsInstance<ProviderEvent.ToolCallReady>().single()
            assertEquals("eva.android.maps.search", call.capabilityId)
            assertEquals(JsonPrimitive("Ferry Building"), call.arguments["destination"])
            assertEquals("input-1", call.call.inputId)
            assertEquals("resp_1", call.call.providerTurnId)
            assertTrue(events.none { it is ProviderEvent.ResponseEnded })
            session.submitToolResult(CorrelatedToolResult(call.call, "HANDED_OFF", "Map search opened."))
            advanceUntilIdle()
            val second = Json.parseToJsonElement(requests[1]).jsonObject
            assertEquals(JsonPrimitive("resp_1"), second["previous_response_id"])
            assertTrue(requests[1].contains("function_call_output"))
            assertTrue(requests[1].contains("HANDED_OFF"))
            assertEquals(
                "Opened the Ferry Building on the map.",
                events.filterIsInstance<ProviderEvent.AssistantText>().single().text,
            )
            assertEquals("input-1", events.filterIsInstance<ProviderEvent.ResponseEnded>().single().inputId)
            session.close()
            advanceUntilIdle()
            assertEquals(ProviderEvent.Closed, events.last())
            collector.cancel()
        }
}
