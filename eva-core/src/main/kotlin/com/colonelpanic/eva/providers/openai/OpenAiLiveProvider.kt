package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.diagnostics.EvaTrace
import com.colonelpanic.eva.providers.ClientDelegationSession
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Live owns speech; EVA owns the independent worker accepting its delegations. */
class OpenAiLiveProvider(
    private val access: OpenAiAccess,
    private val media: RealtimeMediaSession,
    private val instructions: String,
    model: String = OpenAiModels.LIVE,
    private val backendModel: String = OpenAiModels.TEXT,
    private val voice: String = if (access is SubscriptionAccess) "cove" else "marin",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConversationProvider {
    private val protocol =
        when (access) {
            is ApiKeyAccess -> LiveProtocol.API
            is SubscriptionAccess -> LiveProtocol.CODEX
        }
    private val model = if (protocol == LiveProtocol.CODEX && model == OpenAiModels.LIVE) OpenAiModels.LIVE_SUBSCRIPTION else model

    override suspend fun open(request: SessionOpenRequest): ConversationSession {
        var opened: OpenAiLiveSession? = null
        try {
            val offer = media.createOffer()
            val history = request.history.flatMap { it.toOpenAiMessages() }
            // UTF-8 bytes bound token count conservatively without depending on a model tokenizer.
            val retained =
                history
                    .takeLast(128)
                    .asReversed()
                    .runningFold(emptyList<OpenAiHistoryMessage>()) { kept, next -> kept + next }
                    .takeWhile { messages -> messages.sumOf { it.text.toByteArray(Charsets.UTF_8).size + 16 } <= 8192 }
                    .last()
                    .asReversed()
            val body =
                buildJsonObject {
                    put(
                        "session",
                        buildJsonObject {
                            put("model", model)
                            put("delegation", buildJsonObject { put("type", "client") })
                            put("instructions", request.instructions + "\n\n" + instructions)
                            if (protocol == LiveProtocol.API) put("store", false)
                            put("audio", buildJsonObject { put("output", buildJsonObject { put("voice", voice) }) })
                            put(
                                protocol.historyField,
                                JsonArray(
                                    retained.map { message ->
                                        buildJsonObject {
                                            put("type", "message")
                                            put("role", message.role)
                                            put(
                                                "content",
                                                JsonArray(
                                                    listOf(
                                                        buildJsonObject {
                                                            put("type", if (message.role == "assistant") "output_text" else "input_text")
                                                            put("text", message.text)
                                                        },
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                ),
                            )
                        },
                    )
                    if (protocol == LiveProtocol.CODEX) {
                        put("sdp", offer)
                    } else {
                        put(
                            "transport",
                            buildJsonObject {
                                put("type", "webrtc")
                                put("sdp", offer)
                            },
                        )
                    }
                }
            val (answer, expectedSessionId) =
                withContext(ioDispatcher) {
                    val http =
                        access
                            .authorize(Request.Builder().url(access.liveSessionsUrl))
                            .apply { if (protocol == LiveProtocol.CODEX) header("OpenAI-Alpha", "quicksilver=v2") }
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    client.newCall(http).execute().use {
                        val text = it.body.string()
                        check(it.isSuccessful) { openAiErrorMessage(it.code, text, "the GPT-Live session", model) }
                        if (protocol == LiveProtocol.CODEX) {
                            check(text.startsWith("v=")) { "OpenAI returned no SDP answer for Codex Live." }
                            text to null
                        } else {
                            val response =
                                Json.parseToJsonElement(text) as? JsonObject ?: error("OpenAI returned an invalid GPT-Live session.")
                            val answer = response.obj("transport")?.str("sdp") ?: error("OpenAI returned no SDP answer for GPT-Live.")
                            val sessionId = response.obj("session")?.str("id") ?: error("OpenAI returned no GPT-Live session ID.")
                            answer to sessionId
                        }
                    }
                }
            opened =
                OpenAiLiveSession(
                    media,
                    expectedSessionId,
                    request.catalog.revision,
                    model,
                    backendModel,
                    access.label,
                    protocol,
                    ioDispatcher,
                )
            media.acceptAnswer(answer)
            withTimeout(30_000) {
                media.eventsReady.first { it }
                opened.awaitStarted()
            }
            if (retained.size < history.size) {
                opened.notice(
                    "GPT-Live received ${retained.size} of ${history.size} prior context messages to fit its startup context. EVA retains the full conversation and task history.",
                )
            }
            return opened
        } catch (error: Exception) {
            opened?.abort()
            media.close()
            throw error
        }
    }
}

private class OpenAiLiveSession(
    private val media: RealtimeMediaSession,
    private val expectedSessionId: String?,
    private val revision: String,
    private val model: String,
    private val backendModel: String,
    private val accountLabel: String,
    private val protocol: LiveProtocol,
    dispatcher: CoroutineDispatcher,
) : ClientDelegationSession {
    override val connectionEpoch = UUID.randomUUID().toString()
    private val output = Channel<ProviderEvent>(Channel.UNLIMITED)
    override val events = output.receiveAsFlow()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val started = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val writes = Mutex()
    private val seen = mutableSetOf<String>()
    private val delegations = mutableSetOf<String>()
    private val pending = mutableMapOf<String, String?>()
    private var closing = false
    private var sessionId = expectedSessionId.orEmpty()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                media.events.collect { raw ->
                    val event = protocol.receive(Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid GPT-Live event."))
                    val id = event.str("event_id")
                    if (id == null || seen.add(id)) receive(event)
                }
                if (!closed.isCompleted) error("GPT-Live disconnected before confirming session closure; final usage is unconfirmed.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                started.completeExceptionally(error)
                output.trySend(ProviderEvent.Failure(error.message ?: "GPT-Live event connection failed."))
            } finally {
                output.trySend(ProviderEvent.Closed)
                output.close()
            }
        }
    }

    suspend fun awaitStarted() = started.await()

    fun notice(message: String) {
        output.trySend(ProviderEvent.Notice(message, persistent = true))
    }

    private suspend fun receive(event: JsonObject) {
        when (val type = event.str("type")) {
            "session.started" -> {
                val confirmed = event.obj("session")?.str("id") ?: error("GPT-Live startup omitted its session ID.")
                check(expectedSessionId == null || confirmed == expectedSessionId) { "GPT-Live startup identified a different session." }
                if (!started.isCompleted) {
                    sessionId = confirmed
                    output.send(ProviderEvent.Account(accountLabel))
                    output.send(ProviderEvent.Connected(sessionId, revision, event.obj("session")?.str("model") ?: model, backendModel))
                    started.complete(Unit)
                }
            }

            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event.str("delta") ?: error("GPT-Live caption omitted its text.")
                val start = (event["start_ms"] as? JsonPrimitive)?.longOrNull
                val end = (event["end_ms"] as? JsonPrimitive)?.longOrNull
                check(start != null && end != null && start >= 0 && end >= start) { "GPT-Live caption has invalid timestamps." }
                output.send(
                    ProviderEvent.TimedTranscript(
                        if (type ==
                            "session.input_transcript.delta"
                        ) {
                            "user"
                        } else {
                            "assistant"
                        },
                        text,
                        start,
                        end,
                    ),
                )
            }

            "session.delegation.created" -> {
                val delegation = event.obj("delegation") ?: error("GPT-Live omitted delegation metadata.")
                check(delegation.str("target") == "client") { "GPT-Live requested an unexpected delegation target." }
                val id = delegation.str("id") ?: error("GPT-Live omitted its delegation ID.")
                val offset = (event["offset_ms"] as? JsonPrimitive)?.longOrNull
                check(offset != null && offset >= 0) { "GPT-Live delegation has no valid timeline offset." }
                writes.withLock { delegations.add(id) }
                output.send(ProviderEvent.ClientDelegationRequested(sessionId, id, offset))
            }

            "session.thinking.appended", "session.commentary.appended", "session.instructions.appended",
            "session.context.appended", "delegation.context.appended",
            -> {
                writes.withLock { pending.remove(event.str("client_event_id")) }
                // Context injection is neither speech completion nor playback evidence.
            }

            "error" -> {
                val error = event.obj("error")
                val message = error?.str("message") ?: "GPT-Live rejected a session command."
                if (!started.isCompleted) {
                    started.completeExceptionally(IllegalStateException(message))
                } else {
                    val delivery = writes.withLock { pending.remove(error?.str("client_event_id")) }
                    if (delivery != null) output.send(ProviderEvent.ContextDelivery(listOf(delivery), delivered = false))
                    notice(message)
                }
            }

            "session.closed" -> {
                if (!started.isCompleted) started.completeExceptionally(IllegalStateException("GPT-Live closed before startup completed."))
                EvaTrace.info(
                    "live.closed",
                    "session" to sessionId,
                    "reason" to event.str("reason"),
                    "usageSeconds" to (event.obj("usage")?.get("seconds") as? JsonPrimitive)?.content,
                )
                closed.complete(Unit)
                output.send(ProviderEvent.Closed)
                output.close()
            }
        }
    }

    override suspend fun submit(input: ConversationInput): Unit = error("GPT-Live typed requests must run through EVA's task worker.")

    override suspend fun requestResponse(request: ResponseRequest): Unit =
        error("GPT-Live speaks continuously; Responses requests belong to EVA's task worker.")

    override suspend fun submitToolResult(result: CorrelatedToolResult): Unit =
        error("GPT-Live delegates work to EVA; tool results belong to the task's Responses session.")

    override suspend fun appendDelegationUpdate(
        delegationId: String?,
        note: String,
        data: JsonObject?,
        respond: Boolean,
    ): Boolean = append(delegationId, note, data, respond, null)

    override suspend fun submitContext(
        note: String,
        respond: Boolean,
        data: JsonObject?,
        deliveryId: String?,
    ): Boolean = append(null, note, data, respond, deliveryId)

    private suspend fun append(
        delegationId: String?,
        note: String,
        data: JsonObject?,
        respond: Boolean,
        deliveryId: String?,
    ): Boolean =
        writes.withLock {
            if (closing || closed.isCompleted || !started.isCompleted) return@withLock false
            require(delegationId == null || delegationId in delegations) { "Unknown GPT-Live delegation ID." }
            val content =
                buildJsonObject {
                    put("note", note)
                    if (data != null) put("data", data)
                }.toString()
            val chunks = liveUpdateChunks(content)
            for ((index, chunk) in chunks.withIndex()) {
                val id = UUID.randomUUID().toString()
                pending[id] = deliveryId
                try {
                    media.send(protocol.append(id, delegationId, chunk, respond && index == chunks.lastIndex).toString())
                } catch (error: Exception) {
                    pending.remove(id)
                    notice(
                        "GPT-Live could not receive the task update: ${error.message ?: "event channel unavailable"}. The task result remains in EVA.",
                    )
                    return@withLock false
                }
            }
            true
        }

    override suspend fun close() {
        writes.withLock {
            if (closing) return
            closing = true
            if (started.isCompleted && !closed.isCompleted && media.eventsReady.value) {
                runCatching { media.send(buildJsonObject { put("type", "session.close") }.toString()) }
            }
        }
        try {
            if (withTimeoutOrNull(3_000) {
                    closed.await()
                    true
                } != true
            ) {
                EvaTrace.info("live.close_unconfirmed", "session" to sessionId)
                notice("GPT-Live closure was not confirmed; final usage is unconfirmed.")
            }
        } finally {
            abort()
            media.close()
        }
    }

    fun abort() {
        scope.cancel()
        output.close()
    }
}

/** Each complete envelope is at most 500 UTF-8 bytes, hence at most the API's 500 tokens. */
internal fun liveUpdateChunks(content: String): List<String> {
    val id = UUID.randomUUID().toString()
    val parts = mutableListOf<String>()
    var part = ""
    for (point in content.codePoints().toArray()) {
        val next = part + String(Character.toChars(point))
        if (JsonPrimitive(next).toString().toByteArray(Charsets.UTF_8).size > 350) {
            parts += part
            part = String(Character.toChars(point))
        } else {
            part = next
        }
    }
    if (part.isNotEmpty()) parts += part
    return parts.mapIndexed { index, text ->
        buildJsonObject {
            put("update_id", id)
            put("part", index + 1)
            put("parts", parts.size)
            put("text", text)
        }.toString().also { check(it.toByteArray(Charsets.UTF_8).size <= 500) }
    }
}
