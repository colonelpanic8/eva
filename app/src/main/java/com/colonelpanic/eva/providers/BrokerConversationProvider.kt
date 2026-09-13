package com.colonelpanic.eva.providers

import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BrokerConversationProvider(
    private val endpoint: BrokerEndpoint,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build(),
    private val offerSdp: String? = null,
    private val onAnswer: suspend (String) -> Unit = {},
) : ConversationProvider {
    override suspend fun open(request: SessionOpenRequest): ConversationSession {
        require(request.catalog.tools.size <= 32)
        require(
            request.catalog.tools
                .map { it.capabilityId }
                .distinct()
                .size == request.catalog.tools.size,
        )
        request.catalog.tools.forEach { ToolSchema.check(it.inputSchema) }
        return BrokerSession(endpoint, request, client, offerSdp, onAnswer)
    }
}

private class BrokerSession(
    private val endpoint: BrokerEndpoint,
    private val request: SessionOpenRequest,
    client: OkHttpClient,
    private val offerSdp: String?,
    private val onAnswer: suspend (String) -> Unit,
) : ConversationSession {
    override val connectionEpoch: String = UUID.randomUUID().toString()
    private val incoming = Channel<String>(64)
    private val closed = AtomicBoolean(false)
    private var collected = false
    private var sessionId: String? = null
    private var buffered: ConversationInput? = null
    private var active: ConversationInput? = null
    private var providerTurnId: String? = null
    private val pending = mutableMapOf<String, CallIdentity>()
    private val tools =
        request.catalog.tools
            .mapIndexed { index, tool -> "eva_tool_$index" to tool }
            .toMap()
    private val socket =
        client.newWebSocket(
            Request.Builder().url(endpoint.socketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    receive("""{"kind":"socket-open"}""")
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) = receive(text)

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    webSocket.close(code, null)
                    incoming.close()
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    incoming.close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    incoming.close(IllegalStateException("Provider connection ended. Reconnect to start a new session."))
                }

                private fun receive(text: String) {
                    if (text.length > 128 * 1024 || !incoming.trySend(text).isSuccess) {
                        incoming.close(IllegalStateException("Provider event limit exceeded."))
                    }
                }
            },
        )

    override val events: Flow<ProviderEvent> =
        flow {
            check(!collected) { "Provider events have one owner" }
            collected = true
            try {
                while (!closed.get()) {
                    val received =
                        try {
                            if (sessionId == null) {
                                withTimeout(65_000L) { incoming.receiveCatching() }
                            } else {
                                incoming.receiveCatching()
                            }
                        } catch (_: TimeoutCancellationException) {
                            throw IllegalStateException("Provider setup timed out. Reconnect to try again.")
                        }
                    received.exceptionOrNull()?.let { throw it }
                    if (received.isClosed) {
                        break
                    }
                    val raw = received.getOrThrow()
                    val message = Json.parseToJsonElement(raw).jsonObject
                    val kind = message.string("kind")
                    when (kind) {
                        "socket-open" -> {
                            send(
                                buildJsonObject {
                                    put("version", 1)
                                    put("token", endpoint.accessCode)
                                },
                            )
                        }

                        "authorized" -> {
                            check(message["version"] == JsonPrimitive(1))
                            send(
                                buildJsonObject {
                                    put("type", "start")
                                    put("catalogRevision", request.catalog.revision)
                                    put("instructions", request.instructions)
                                    put(
                                        "tools",
                                        JsonArray(
                                            tools.map { (name, tool) ->
                                                buildJsonObject {
                                                    put("name", name)
                                                    put("description", tool.description)
                                                    put("inputSchema", tool.inputSchema)
                                                }
                                            },
                                        ),
                                    )
                                    if (offerSdp != null) {
                                        put("mode", "voice")
                                        put("sdp", offerSdp)
                                    }
                                },
                            )
                        }

                        "auth" -> {
                            emit(ProviderEvent.Account("ChatGPT subscription"))
                        }

                        "answer" -> {
                            onAnswer(message.string("sdp"))
                        }

                        "started" -> {
                            check(sessionId == null && message.string("catalogRevision") == request.catalog.revision)
                            sessionId = message.string("sessionId").also { check(it.isNotBlank()) }
                            emit(
                                ProviderEvent.Connected(
                                    checkNotNull(sessionId),
                                    request.catalog.revision,
                                    message.optionalString("model"),
                                    message.optionalString("backendModel"),
                                ),
                            )
                        }

                        "backend-turn" -> {
                            checkFrame(message)
                            check(providerTurnId == null)
                            providerTurnId = message.string("providerTurnId").also { check(it.isNotBlank()) }
                            emit(ProviderEvent.ResponseStarted(checkNotNull(active).id, checkNotNull(active).id))
                        }

                        "tool-call" -> {
                            checkFrame(message)
                            val turn = checkNotNull(providerTurnId)
                            check(message.string("providerTurnId") == turn)
                            val id = message.string("callId").also { check(it.isNotBlank() && it.length <= 128) }
                            val tool = checkNotNull(tools[message.string("tool")]) { "Provider called an unadvertised tool" }
                            val identity =
                                CallIdentity(
                                    connectionEpoch,
                                    checkNotNull(sessionId),
                                    checkNotNull(active).id,
                                    checkNotNull(active).id,
                                    turn,
                                    request.catalog.revision,
                                    id,
                                )
                            val args = message["arguments"] as? JsonObject ?: error("Tool arguments must be an object")
                            pending[id] = identity
                            emit(ProviderEvent.ToolCallReady(identity, tool.capabilityId, args))
                        }

                        "backend-output" -> {
                            checkFrame(message)
                            check(message.string("providerTurnId") == providerTurnId)
                            emit(
                                ProviderEvent.AssistantText(
                                    checkNotNull(active).id,
                                    message.string("text"),
                                    (message["truncated"] as? JsonPrimitive)?.booleanOrNull == true,
                                ),
                            )
                        }

                        "backend-completed" -> {
                            checkFrame(message)
                            check(message.string("providerTurnId") == providerTurnId && pending.isEmpty())
                            emit(ProviderEvent.ResponseEnded(checkNotNull(active).id, message.string("status")))
                            active = null
                            providerTurnId = null
                        }

                        "transcript" -> {
                            if (offerSdp != null) emit(ProviderEvent.Transcript(message.string("role"), message.string("text")))
                        }

                        "error", "turn-error" -> {
                            emit(ProviderEvent.Failure(message.string("message").take(800)))
                            break
                        }

                        "closed", "provider-closed" -> {
                            break
                        }
                    }
                }
            } finally {
                close()
            }
            emit(ProviderEvent.Closed)
        }

    override suspend fun submit(input: ConversationInput) {
        check(sessionId != null && !closed.get() && buffered == null && active == null)
        require(input.text.isNotBlank() && input.text.length <= 1000 && input.id.length in 1..128)
        buffered = input
    }

    override suspend fun requestResponse(request: ResponseRequest) {
        val input = checkNotNull(buffered)
        check(input.id == request.inputId && active == null)
        active = input
        buffered = null
        send(
            buildJsonObject {
                put("type", "text")
                put("sessionId", sessionId)
                put("catalogRevision", this@BrokerSession.request.catalog.revision)
                put("inputId", input.id)
                put("text", input.text)
            },
        )
    }

    override suspend fun submitToolResult(result: CorrelatedToolResult) {
        check(!closed.get() && result.call.connectionEpoch == connectionEpoch && pending[result.call.callId] == result.call)
        send(
            buildJsonObject {
                put("type", "tool-result")
                put("sessionId", result.call.providerSessionId)
                put("inputId", result.call.inputId)
                put("generationId", result.call.generationId)
                put("providerTurnId", result.call.providerTurnId)
                put("catalogRevision", result.call.catalogRevision)
                put("callId", result.call.callId)
                put(
                    "result",
                    buildJsonObject {
                        put("status", result.status)
                        put("message", result.message.take(2000))
                        result.data?.let { put("data", it) }
                    },
                )
            },
        )
        pending.remove(result.call.callId)
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close(1000, "EVA disconnected")
            socket.cancel()
            incoming.close()
        }
    }

    private fun checkFrame(message: JsonObject) {
        check(message.string("sessionId") == sessionId && message.string("catalogRevision") == request.catalog.revision)
        check(active != null && message.string("inputId") == active?.id && message.string("generationId") == active?.id)
    }

    private fun send(value: JsonObject) {
        check(!closed.get() && socket.send(value.toString())) { "Provider connection is unavailable" }
    }
}

private fun JsonObject.optionalString(key: String) =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Missing provider field: $key")
