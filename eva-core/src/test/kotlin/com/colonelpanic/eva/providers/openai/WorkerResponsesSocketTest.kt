@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerResponsesSocketTest {
    private class Socket(
        private val request: Request,
    ) : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false

        override fun request() = request

        override fun queueSize() = 0L

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun send(bytes: ByteString) = error("unexpected binary frame")

        override fun close(
            code: Int,
            reason: String?,
        ) = true

        override fun cancel() {
            cancelled = true
        }
    }

    @Test fun reusesConnectionAndCancellationDiscardsLateFrames() =
        runTest {
            val sockets = mutableListOf<Socket>()
            val listeners = mutableListOf<WebSocketListener>()
            val transport =
                WorkerResponsesSocket(ApiKeyAccess("test")) { request, listener ->
                    listeners += listener
                    Socket(request).also { sockets += it }
                }
            val payload =
                buildJsonObject {
                    put("prompt_cache_key", "task")
                    put("stream", true)
                }
            val first = async { transport.complete(payload) }
            runCurrent()
            val socket = sockets.single()
            assertEquals("task", socket.request().header("session-id"))
            listeners[0].onOpen(
                socket,
                Response
                    .Builder()
                    .request(socket.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching")
                    .build(),
            )
            val sent = Json.parseToJsonElement(socket.sent.single()).jsonObject
            assertEquals("response.create", sent.getValue("type").jsonPrimitive.content)
            assertFalse(sent.containsKey("stream"))
            listeners[0].onMessage(
                socket,
                """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c","name":"home","arguments":"{}"}}""",
            )
            listeners[0].onMessage(socket, """{"type":"response.completed","response":{"id":"one","output":[]}}""")
            assertEquals(
                "one",
                first
                    .await()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            val second = async { transport.complete(payload) }
            runCurrent()
            assertEquals(1, sockets.size)
            assertEquals(2, socket.sent.size)
            second.cancelAndJoin()
            assertTrue(socket.cancelled)
            val third = async { transport.complete(payload) }
            runCurrent()
            assertEquals(2, sockets.size)
            listeners[0].onMessage(socket, """{"type":"response.completed","response":{"id":"late"}}""")
            assertFalse(third.isCompleted)
            third.cancelAndJoin()
            assertTrue(sockets[1].cancelled)
        }
}
