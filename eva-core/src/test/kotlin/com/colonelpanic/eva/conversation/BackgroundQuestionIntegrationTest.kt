package com.colonelpanic.eva.conversation

import com.colonelpanic.eva.capability.CapabilityDefinition
import com.colonelpanic.eva.capability.CapabilityDispatcher
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.ExecutionBackend
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.MemoryInvocationRepository
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import com.colonelpanic.eva.providers.openai.ChatGptTokens
import com.colonelpanic.eva.providers.openai.OpenAiAccess
import com.colonelpanic.eva.providers.openai.OpenAiResponsesProvider
import com.colonelpanic.eva.providers.openai.SubscriptionAccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundQuestionIntegrationTest {
    @Test fun `stored Responses keeps one leg across two questions and parallel action`() = exercise(false)

    @Test fun `subscription Responses keeps full context across two questions and parallel action`() = exercise(true)

    private fun exercise(subscription: Boolean) =
        runTest {
            val requests = mutableListOf<JsonObject>()
            var authorizations = 0
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val listing =
                            chain
                                .request()
                                .url.encodedPath
                                .endsWith("/models")
                        val body =
                            if (listing) {
                                if (subscription) {
                                    """{"models":[{"slug":"test-model"}]}"""
                                } else {
                                    """{"data":[{"id":"test-model"}]}"""
                                }
                            } else {
                                val payload =
                                    Json
                                        .parseToJsonElement(
                                            Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(),
                                        ).jsonObject
                                requests += payload
                                val output =
                                    if (requests.size == 1) {
                                        val tools = payload.getValue("tools").jsonArray.map { it.jsonObject }
                                        val ask =
                                            tools
                                                .single {
                                                    "question" in
                                                        it
                                                            .getValue(
                                                                "parameters",
                                                            ).jsonObject
                                                            .getValue("properties")
                                                            .jsonObject
                                                }.getValue("name")
                                        val lookup = tools.single { it.getValue("name") != ask }.getValue("name")
                                        JsonArray(
                                            listOf("first", "second", "lookup").map { id ->
                                                buildJsonObject {
                                                    put("type", "function_call")
                                                    put("call_id", id)
                                                    put("id", "item-$id")
                                                    put("name", if (id == "lookup") lookup else ask)
                                                    put("arguments", if (id == "lookup") "{}" else """{"question":"$id question?"}""")
                                                }
                                            },
                                        )
                                    } else {
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", "message")
                                                    put("role", "assistant")
                                                    put(
                                                        "content",
                                                        buildJsonArray {
                                                            add(
                                                                buildJsonObject {
                                                                    put("type", "output_text")
                                                                    put("text", "Finished")
                                                                },
                                                            )
                                                        },
                                                    )
                                                },
                                            ),
                                        )
                                    }
                                val response =
                                    buildJsonObject {
                                        put("id", "response-${requests.size}")
                                        put("status", "completed")
                                        put("output", output)
                                    }
                                if (subscription) {
                                    "data: ${buildJsonObject {
                                        put("type", "response.completed")
                                        put("response", response)
                                    }}\n\n"
                                } else {
                                    response.toString()
                                }
                            }
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                body.toResponseBody(
                                    (
                                        if (subscription &&
                                            !listing
                                        ) {
                                            "text/event-stream"
                                        } else {
                                            "application/json"
                                        }
                                    ).toMediaType(),
                                ),
                            ).build()
                    }.build()
            val access: OpenAiAccess =
                if (subscription) {
                    SubscriptionAccess({
                        authorizations++
                        ChatGptTokens("id", "access-$authorizations", "refresh", "account", "user@example.test", "pro", 0)
                    }, "1.0.0", "https://example.test")
                } else {
                    ApiKeyAccess("test", "https://example.test")
                }
            val background = OpenAiResponsesProvider(access, "test-model", client, StandardTestDispatcher(testScheduler))
            var executions = 0
            val lookup =
                CapabilityDefinition(
                    "test.lookup",
                    "Lookup",
                    "Look up information",
                    Json.parseToJsonElement("""{"type":"object","properties":{},"required":[],"additionalProperties":false}""").jsonObject,
                    readOnly = true,
                )
            val registry =
                CapabilityRegistry(
                    mapOf(
                        lookup.id to
                            object : ExecutionBackend {
                                override suspend fun unavailableReason(): String? = null

                                override suspend fun execute(arguments: Map<String, String>): ExecutionOutcome {
                                    executions++
                                    return ExecutionOutcome(InvocationStatus.COMPLETED, "Lookup result")
                                }
                            },
                    ),
                    listOf(lookup),
                )
            val repository = MemoryInvocationRepository()
            val store = MemoryConversationStore()
            val foreground =
                object : ConversationSession {
                    override val connectionEpoch = "foreground"
                    val incoming = Channel<ProviderEvent>(Channel.UNLIMITED)
                    override val events = incoming.receiveAsFlow()

                    override suspend fun submit(input: ConversationInput) = Unit

                    override suspend fun requestResponse(request: ResponseRequest) = Unit

                    override suspend fun submitToolResult(result: CorrelatedToolResult) = Unit

                    override suspend fun close() {
                        incoming.close()
                    }
                }
            val controller =
                ThreadController(
                    registry,
                    CapabilityDispatcher(registry, repository),
                    repository,
                    store,
                    backgroundScope,
                    providerFactory = {
                        object : ConversationProvider {
                            override suspend fun open(request: SessionOpenRequest): ConversationSession {
                                foreground.incoming.send(ProviderEvent.Connected("foreground", request.catalog.revision))
                                return foreground
                            }
                        }
                    },
                    backgroundProviderFactory = { background },
                )
            runCurrent()
            controller.connect("")
            runCurrent()
            controller.submit("Research this")
            runCurrent()
            controller.disconnect()
            runCurrent()
            assertEquals(1, executions)
            assertEquals(1, requests.size)
            assertEquals(2, controller.state.value.pendingQuestionCount)
            val thread = controller.state.value.threadId!!
            val first = controller.state.value.currentQuestion!!
            assertEquals("first question?", first.question)
            advanceTimeBy(24 * 60 * 60 * 1000L)
            runCurrent()
            controller.refreshTaskSnapshots()
            assertFalse(
                controller.taskSnapshots.value
                    .single()
                    .looksStuck,
            )
            assertEquals(
                TaskState.NEEDS_INPUT,
                controller.taskSnapshots.value
                    .single()
                    .state,
            )
            assertTrue(controller.needsWorkCoverage.value)
            controller.submit("First answer")
            runCurrent()
            assertEquals(1, requests.size)
            assertEquals(
                "second question?",
                controller.state.value.currentQuestion!!
                    .question,
            )
            controller.submit("Second answer")
            runCurrent()
            assertEquals(2, requests.size)
            assertEquals(1, executions)
            assertEquals(TurnStatus.ANSWERED, store.turns(thread).single().status)
            assertEquals(1, store.items(thread).filterIsInstance<ThreadItem.TextLeg>().size)
            val input = requests.last().getValue("input").jsonArray
            val outputs = input.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
            assertEquals(setOf("first", "second", "lookup"), outputs.map { it.getValue("call_id").jsonPrimitive.content }.toSet())
            assertTrue(outputs.any { "First answer" in it.toString() })
            assertTrue(outputs.any { "Second answer" in it.toString() })
            if (subscription) {
                assertTrue(input.any { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" })
                assertTrue(input.any { "Research this" in it.toString() })
                assertTrue(authorizations >= 3)
                assertNull(requests.last()["previous_response_id"])
            } else {
                assertEquals(
                    "response-1",
                    requests
                        .last()
                        .getValue("previous_response_id")
                        .jsonPrimitive.content,
                )
            }
            assertFalse(controller.needsWorkCoverage.value)
        }
}
