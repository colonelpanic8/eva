package com.colonelpanic.eva.messaging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.IOException

/** A scripted bridge behind OkHttp's interceptor, the same fake the package HTTP client tests use. */
class FakeBridge(
    val origin: String = ORIGIN,
) {
    data class Recorded(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests = mutableListOf<Recorded>()
    var status = """{"state":"connected","transport_connected":true,"phone_responsive":true}"""
    var conversations = "[]"
    var contacts = "[]"
    var messages = "[]"
    val outbox = mutableMapOf<String, String>()
    var onQueue: (String, String, String) -> Pair<Int, String> = { path, key, body -> 202 to defaultQueue(path, key, body) }
    var onOutboxRead: (String) -> Unit = {}
    var failNextPost: IOException? = null
    var unauthorized = false

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body =
                        request.body?.let { content ->
                            Buffer().also(content::writeTo).readUtf8()
                        }
                    val recorded =
                        Recorded(
                            request.method,
                            request.url.encodedPath,
                            request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() },
                            request.headers.names().associateWith { request.header(it).orEmpty() },
                            body,
                        )
                    requests += recorded
                    val (code, text) = respond(recorded)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("scripted")
                        .body(text.toResponseBody())
                        .build()
                },
            ).build()

    private fun respond(request: Recorded): Pair<Int, String> {
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        if (unauthorized) return 401 to "unauthorized\n"
        val path = request.path
        return when {
            request.method == "GET" && path == "/v1/status" -> {
                200 to status
            }

            request.method == "GET" && path == "/v1/conversations" -> {
                200 to """{"conversations":$conversations,"cursor":"c1"}"""
            }

            request.method == "GET" && path == "/v1/contacts" -> {
                200 to
                    """{"contacts":$contacts,"updated":"2026-09-26T10:00:00Z","stale":false}"""
            }

            request.method == "GET" && path.endsWith("/messages") -> {
                if (path.contains("/missing/")) {
                    404 to "record not found\n"
                } else {
                    200 to
                        """{"messages":$messages,"cursor":"c2","next_before":""}"""
                }
            }

            request.method == "GET" && path.startsWith("/v1/outbox/") -> {
                val key = path.removePrefix("/v1/outbox/")
                onOutboxRead(key)
                outbox[key]?.let { 200 to it } ?: (404 to "record not found\n")
            }

            request.method == "POST" -> {
                failNextPost?.let {
                    failNextPost = null
                    throw it
                }
                val key = request.headers.getValue("Idempotency-Key")
                assertTrue(Regex("[A-Za-z0-9_-]{16,128}").matches(key))
                onQueue(path, key, request.body.orEmpty())
            }

            else -> {
                404 to "record not found\n"
            }
        }
    }

    private fun defaultQueue(
        path: String,
        key: String,
        body: String,
    ): String {
        val request = Json.parseToJsonElement(body).jsonObject
        val record =
            if (path == "/v1/conversations") {
                """{"id":"$key","state":"queued","schema":1}"""
            } else {
                """{"id":"$key","state":"queued","conversation_id":${request.getValue("conversation_id")},"schema":1}"""
            }
        outbox[key] = record
        return record
    }

    companion object {
        const val ORIGIN = "https://bridge.example.ts.net"
        const val TOKEN = "bridge-secret-token"
    }
}
