package com.colonelpanic.eva.providers.grok

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrokSocketTest {
    @Test fun `real websocket sends model and bearer separately and transports JSON in both directions`() =
        runBlocking {
            val server = MockWebServer()
            val received = CompletableDeferred<String>()
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send("""{"type":"session.created","session":{"id":"server-session"}}""")
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            received.complete(text)
                        }
                    },
                ),
            )
            val client = OkHttpClient()
            val socket = OkHttpGrokSocket("test-runtime-key", "grok-voice-3.0", client, server.url("/v1/realtime").toString())
            try {
                withTimeout(5000) {
                    assertTrue(socket.events.first().contains("server-session"))
                    socket.send("""{"type":"response.create"}""")
                    assertEquals("""{"type":"response.create"}""", received.await())
                    val request = server.takeRequest()
                    assertEquals("/v1/realtime?model=grok-voice-3.0", request.path)
                    assertEquals("Bearer test-runtime-key", request.getHeader("Authorization"))
                    assertFalse(request.path!!.contains("key"))
                }
            } finally {
                socket.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }
        }

    @Test fun `authentication failure identifies provisioning without exposing response body or credentials`() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive server body"))
            val client = OkHttpClient()
            val socket = OkHttpGrokSocket("test-runtime-key", "grok-voice-latest", client, server.url("/v1/realtime").toString())
            try {
                val error = runCatching { withTimeout(5000) { socket.events.first() } }.exceptionOrNull()!!
                assertTrue(error.message!!.contains("HTTP 401"))
                assertTrue(error.message!!.contains("xAI key"))
                assertFalse(error.message!!.contains("sensitive"))
                assertFalse(error.message!!.contains("test-runtime-key"))
            } finally {
                socket.close()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                server.close()
            }
        }
}
