package com.colonelpanic.eva.devicecontrol.portal

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class PortalClientTest {
    @Test
    fun authenticatesLoopbackAndUsesAsciiJsonForUnicodeDeepLinks() =
        runBlocking {
            val requests = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            var body = ""
            var authorization = ""
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                authorization = exchange.requestHeaders.getFirst("Authorization")
                body = exchange.requestBody.bufferedReader().use { it.readText() }
                val bytes = """{"status":"success","result":"ok"}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val client = PortalClient(server.address.port, { "test-token" })
                client.command(PortalCommand("app/deep-link", buildJsonObject { put("deepLink", "https://example.org/café/🎉") }))
                assertEquals("Bearer test-token", authorization)
                assertTrue(body.all { it.code < 128 })
                assertEquals(
                    "https://example.org/café/🎉",
                    Json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("deepLink")
                        .jsonPrimitive.content,
                )
                assertEquals(1, requests.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun readsStringWrappedStateAndJsonScreenshotWithoutExposingCredentials() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val value =
                    if (exchange.requestURI.path == "/screenshot") {
                        java.util.Base64
                            .getEncoder()
                            .encodeToString(PortalBackendTest.PNG)
                    } else {
                        PortalBackendTest.stateOf().toString()
                    }
                val bytes =
                    buildJsonObject {
                        put("status", "success")
                        put("result", JsonPrimitive(value))
                    }.toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val client = PortalClient(server.address.port, { "test-token" })
                assertEquals(PortalBackendTest.stateOf(), client.state())
                assertTrue(PortalBackendTest.PNG.contentEquals(client.screenshot()))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun neverRetriesOrFollowsRedirectsOnMutation() =
        runBlocking {
            val requests = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/second")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            server.start()
            try {
                val client = PortalClient(server.address.port, { "test-token" })
                assertTrue(runCatching { client.command(PrimitivePlanner.keyForTest()) }.isFailure)
                assertEquals(1, requests.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun healthSeparatesAStoppedServiceFromARejectedToken() =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/version") { exchange ->
                val authorized = exchange.requestHeaders.getFirst("Authorization") == "Bearer good"
                val bytes = (if (authorized) """{"status":"success","result":"0.7.25"}""" else """{"status":"error"}""").toByteArray()
                exchange.sendResponseHeaders(if (authorized) 200 else 401, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val port = server.address.port
            try {
                assertEquals(PortalHealth.READY, PortalClient(port, { "good" }).health())
                assertEquals(PortalHealth.UNAUTHORIZED, PortalClient(port, { "stale" }).health())
            } finally {
                server.stop(0)
            }
            assertEquals(PortalHealth.UNREACHABLE, PortalClient(port, { "good" }).health())
        }
}

private fun PrimitivePlanner.keyForTest() = PortalCommand("keyboard/key", buildJsonObject { put("key_code", 66) })
