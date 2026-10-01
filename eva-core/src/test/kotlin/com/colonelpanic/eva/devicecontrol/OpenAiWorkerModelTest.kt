package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.worker.WorkerCall
import com.colonelpanic.eva.devicecontrol.worker.WorkerMessage
import com.colonelpanic.eva.devicecontrol.worker.WorkerRequest
import com.colonelpanic.eva.providers.openai.ApiKeyAccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Test

class OpenAiWorkerModelTest {
    @Test fun truncatedSubscriptionWorkerReplyDoesNotReturnPartialActions() =
        runBlocking {
            val server = okhttp3.mockwebserver.MockWebServer()
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(
                    object : okhttp3.WebSocketListener() {
                        override fun onMessage(
                            webSocket: okhttp3.WebSocket,
                            text: String,
                        ) {
                            webSocket.send(
                                """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"partial","name":"home","arguments":"{}"}}""",
                            )
                            webSocket.close(1000, "end before completion")
                        }
                    },
                ),
            )
            server.start()
            val access =
                com.colonelpanic.eva.providers.openai.SubscriptionAccess({
                    com.colonelpanic.eva.providers.openai
                        .ChatGptTokens("id", "test", "refresh", null, null, null, Long.MAX_VALUE)
                }, "1.0.0", baseUrl = server.url("/").toString().removeSuffix("/"))
            val model = OpenAiWorkerModel(access)
            try {
                val failure =
                    runCatching {
                        kotlinx.coroutines.withTimeout(5000) {
                            model.complete(WorkerRequest("instructions", listOf(WorkerMessage("user", "go home")), emptyList(), "task"))
                        }
                    }.exceptionOrNull()
                org.junit.Assert.assertTrue(failure is IllegalStateException)
                assertEquals("Responses socket closed.", failure!!.message)
                assertEquals(1, server.requestCount)
            } finally {
                model.close()
                server.shutdown()
            }
        }

    @Test fun sendsCorrelatedToolResultsAndReplaysProviderItems() =
        runBlocking {
            var sent: JsonObject? = null
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val buffer = Buffer()
                        chain.request().body!!.writeTo(buffer)
                        sent = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                {"id":"response","output":[{"id":"output-item","type":"function_call","call_id":"next","name":"finish","arguments":"{}"}]}
                                """.toResponseBody(
                                    "application/json".toMediaType(),
                                ),
                            ).build()
                    }.build()
            val model = OpenAiWorkerModel(ApiKeyAccess("test"), client = client)
            val reasoning = Json.parseToJsonElement("""{"type":"reasoning","encrypted_content":"opaque"}""").jsonObject
            val reply =
                model.complete(
                    WorkerRequest(
                        "instructions",
                        listOf(
                            WorkerMessage("assistant", "", providerItem = reasoning),
                            WorkerMessage("assistant", "", call = WorkerCall("c", "home", JsonObject(emptyMap()))),
                            WorkerMessage("tool", "home ok.", resultFor = "c"),
                            WorkerMessage("user", "fresh screen"),
                        ),
                        emptyList(),
                        "task",
                    ),
                )
            val input = sent!!.getValue("input").jsonArray
            assertEquals(reasoning, input[0])
            assertEquals(
                "function_call",
                input[1]
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "function_call_output",
                input[2]
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "c",
                input[2]
                    .jsonObject
                    .getValue("call_id")
                    .jsonPrimitive.content,
            )
            assertFalse(input[2].jsonObject.containsKey("role"))
            assertEquals("next", reply.calls.single().id)
            assertEquals("response", reply.calls.single().responseId)
            assertEquals("output-item", reply.calls.single().outputItemId)
            assertEquals(
                "next",
                reply.output
                    .single()
                    .providerItem!!
                    .getValue("call_id")
                    .jsonPrimitive.content,
            )
            model.close()
        }
}
