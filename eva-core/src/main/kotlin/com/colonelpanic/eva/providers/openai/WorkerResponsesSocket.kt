package com.colonelpanic.eva.providers.openai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One task's subscription connection; retaining it preserves prompt-cache routing across turns. */
internal class WorkerResponsesSocket(
    private val access: OpenAiAccess,
    private val connect: (Request, WebSocketListener) -> WebSocket,
) {
    constructor(client: OkHttpClient, access: OpenAiAccess) : this(access, client::newWebSocket)

    private val monitor = Any()
    private var socket: WebSocket? = null
    private var opened = false
    private var pending: Pending? = null

    private class Pending(
        val payload: String,
        val success: (JsonObject) -> Unit,
        val failure: (Exception) -> Unit,
        val items: MutableList<JsonElement> = mutableListOf(),
    )

    suspend fun complete(payload: JsonObject): JsonObject {
        val key = payload.getValue("prompt_cache_key").jsonPrimitive.content
        val request =
            access
                .authorize(Request.Builder().url(access.responsesUrl))
                .header("OpenAI-Beta", "responses_websockets=2026-02-06")
                .header("session-id", key)
                .header("thread-id", key)
                .build()
        val body =
            buildJsonObject {
                payload.filterKeys { it != "stream" }.forEach { (name, value) -> put(name, value) }
                put("type", "response.create")
            }.toString()
        return suspendCancellableCoroutine { continuation ->
            val turn =
                Pending(body, { if (continuation.isActive) continuation.resume(it) }, {
                    if (continuation.isActive) continuation.resumeWithException(it)
                })
            continuation.invokeOnCancellation {
                synchronized(monitor) { if (pending === turn) close() }
            }
            synchronized(monitor) {
                check(pending == null)
                if (!continuation.isActive) return@synchronized
                pending = turn
                if (socket == null) {
                    socket = connect(request, listener())
                } else if (opened) {
                    send()
                }
            }
        }
    }

    fun close() =
        synchronized(monitor) {
            val old = socket
            socket = null
            opened = false
            pending = null
            old?.cancel()
            Unit
        }

    private fun send() {
        val turn = pending ?: return
        if (socket?.send(turn.payload) != true) fail(IllegalStateException("Responses socket could not send the request."))
    }

    private fun fail(error: Exception) {
        val turn = pending
        close()
        turn?.failure?.invoke(error)
    }

    private fun listener() =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) = synchronized(monitor) {
                if (socket !== webSocket) return@synchronized
                opened = true
                send()
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) = synchronized(monitor) {
                if (socket !== webSocket) return@synchronized
                try {
                    val event = Json.parseToJsonElement(text).jsonObject
                    when (event["type"]?.jsonPrimitive?.content) {
                        "response.output_item.done" -> {
                            event["item"]?.let { pending?.items?.add(it) }
                        }

                        "response.completed" -> {
                            val result = event.getValue("response").jsonObject
                            val turn = pending
                            pending = null
                            turn?.success?.invoke(
                                JsonObject(
                                    result + ("output" to JsonArray(turn.items.ifEmpty { (result["output"] as? JsonArray).orEmpty() })),
                                ),
                            )
                        }

                        "error", "response.failed", "response.incomplete" -> {
                            fail(IllegalStateException("Responses socket did not complete the request."))
                        }
                    }
                } catch (error: Exception) {
                    fail(error)
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) = synchronized(monitor) {
                if (socket === webSocket) fail(IllegalStateException("Responses socket connection failed.", t))
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) = synchronized(monitor) {
                if (socket === webSocket) fail(IllegalStateException("Responses socket closed."))
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) = synchronized(monitor) {
                if (socket === webSocket) fail(IllegalStateException("Responses socket closed."))
            }
        }
}
