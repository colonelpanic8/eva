package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.HistoryItem
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import com.colonelpanic.eva.providers.wireOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Direct WebRTC session with the OpenAI Realtime API. The phone posts its own SDP offer
 * with the session configuration and speaks the event protocol over the data channel.
 * The speech model calls EVA's tools itself; there is no host and no second model.
 */
class OpenAiRealtimeProvider(
    private val access: OpenAiAccess,
    private val media: RealtimeMediaSession,
    private val model: String = OpenAiModels.REALTIME,
    private val client: OkHttpClient = realtimeCallClient(),
    private val voice: String = "marin",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        val named = toolNames(request.catalog.tools)
        val session = publicApiSession(model, request, named, voice)
        val microphoneWasMuted = media.controls.value.microphoneMuted
        if (request.history.isNotEmpty()) media.setMicrophoneMuted(true)
        try {
            val offer = media.createOffer()
            val answer =
                withContext(ioDispatcher) {
                    val body =
                        MultipartBody
                            .Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("sdp", offer)
                            .addFormDataPart("session", session.toString())
                            .build()
                    val http = access.authorize(Request.Builder().url(access.realtimeCallsUrl)).post(body).build()
                    val response =
                        try {
                            client.newCall(http).execute()
                        } catch (error: IOException) {
                            // A bare socket message names nothing the person can act on.
                            throw IllegalStateException(
                                "EVA could not complete the voice session request to ${http.url.host}: " +
                                    "${error.message ?: error::class.simpleName}.",
                                error,
                            )
                        }
                    response.use {
                        val text = it.body.string()
                        check(it.isSuccessful) { openAiErrorMessage(it.code, text, "the voice session") }
                        check(text.startsWith("v=")) { "OpenAI returned an unexpected answer." }
                        text
                    }
                }
            media.acceptAnswer(answer)
            withTimeout(30_000) { media.eventsReady.first { it } }
            return OpenAiRealtimeSession(
                media,
                named,
                request.catalog.revision,
                model,
                access.label,
                request.history,
                microphoneWasMuted,
            )
        } catch (error: Exception) {
            if (request.history.isNotEmpty() && !microphoneWasMuted) media.setMicrophoneMuted(false)
            throw error
        }
    }
}

/** A stalled negotiation should surface quickly rather than sit on a default socket timeout. */
private fun realtimeCallClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

internal const val REALTIME_HISTORY_ACK_TIMEOUT_MILLIS = 10_000L

private data class RealtimeSeedItem(
    val id: String,
    val event: String,
)

private class OpenAiRealtimeSession(
    private val media: RealtimeMediaSession,
    private val tools: Map<String, ProviderToolDefinition>,
    private val catalogRevision: String,
    private val model: String,
    private val accountLabel: String,
    history: List<HistoryItem>,
    private val microphoneWasMuted: Boolean,
) : ConversationSession {
    override val connectionEpoch: String = UUID.randomUUID().toString()
    private var sessionId: String? = null
    private var buffered: ConversationInput? = null
    private var pendingTypedInput: ConversationInput? = null
    private var activeInput: String? = null
    private var activeResponse: String? = null
    private var awaitingTool = false
    private val pending = mutableMapOf<String, CallIdentity>()
    private val seedItems = history.flatMap { it.toOpenAiMessages().map(::realtimeSeedItem) }
    private val pendingSeedItemIds = seedItems.mapTo(mutableSetOf()) { it.id }
    private var seedReady = seedItems.isEmpty()
    private var seedGate: CompletableDeferred<Boolean>? = null
    private var seedTimeout: Job? = null
    private var pendingConnected: ProviderEvent.Connected? = null
    private val json = Json { ignoreUnknownKeys = true }

    override val events: Flow<ProviderEvent> =
        channelFlow {
            send(ProviderEvent.Account(accountLabel))
            try {
                media.events.collect { raw ->
                    val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@collect
                    when (message.str("type")) {
                        "session.created", "session.updated" -> {
                            if (sessionId == null) {
                                val session = message.obj("session")
                                sessionId = session?.str("id") ?: UUID.randomUUID().toString()
                                val connected =
                                    ProviderEvent.Connected(
                                        checkNotNull(sessionId),
                                        catalogRevision,
                                        session?.str("model") ?: model,
                                    )
                                if (seedReady) {
                                    send(connected)
                                } else {
                                    pendingConnected = connected
                                    val gate = CompletableDeferred<Boolean>()
                                    seedGate = gate
                                    seedItems.forEach { media.send(it.event) }
                                    seedTimeout =
                                        launch {
                                            delay(REALTIME_HISTORY_ACK_TIMEOUT_MILLIS)
                                            if (gate.complete(false)) {
                                                send(
                                                    ProviderEvent.Failure(
                                                        "OpenAI did not acknowledge EVA's conversation history within 10 seconds.",
                                                    ),
                                                )
                                            }
                                        }
                                }
                            }
                        }

                        "conversation.item.created", "conversation.item.added" -> {
                            if (seedReady) return@collect
                            val itemId = message.obj("item")?.str("id") ?: return@collect
                            if (!pendingSeedItemIds.remove(itemId) || pendingSeedItemIds.isNotEmpty()) return@collect
                            val gate = checkNotNull(seedGate)
                            if (gate.complete(true)) {
                                seedReady = true
                                seedTimeout?.cancel()
                                if (!microphoneWasMuted) media.setMicrophoneMuted(false)
                                send(checkNotNull(pendingConnected))
                                pendingConnected = null
                            }
                        }

                        "response.created" -> {
                            val id = message.obj("response")?.str("id") ?: return@collect
                            if (!seedReady) {
                                media.send(responseCancel(id))
                                return@collect
                            }
                            activeResponse = id
                            awaitingTool = false
                            if (activeInput == null) {
                                // A spoken turn has no typed input; the response is the input, mirroring the
                                // delegated-turn convention. A follow-up after a tool result keeps its input.
                                val input = pendingTypedInput?.id ?: "voice:$id"
                                pendingTypedInput = null
                                activeInput = input
                                send(ProviderEvent.ResponseStarted(input, input))
                            }
                        }

                        "conversation.item.input_audio_transcription.completed" -> {
                            message.str("transcript")?.takeIf { it.isNotBlank() }?.let {
                                send(ProviderEvent.Transcript("user", it))
                            }
                        }

                        "response.output_audio_transcript.done", "response.audio_transcript.done", "response.output_text.done" -> {
                            val text = message.str("transcript") ?: message.str("text")
                            val input = activeInput
                            if (text != null && input != null) send(ProviderEvent.AssistantText(input, text, false))
                        }

                        "response.output_item.done" -> {
                            val item = message.obj("item") ?: return@collect
                            if (item.str("type") != "function_call") return@collect
                            val input = activeInput ?: return@collect
                            val callId = item.str("call_id") ?: return@collect
                            val tool = tools[item.str("name")]
                            if (tool == null) {
                                send(ProviderEvent.Failure("The model called a tool that was not advertised."))
                                return@collect
                            }
                            val arguments =
                                runCatching { json.parseToJsonElement(item.str("arguments").orEmpty()).jsonObject }
                                    .getOrDefault(JsonObject(emptyMap()))
                            val identity =
                                CallIdentity(
                                    connectionEpoch,
                                    checkNotNull(sessionId),
                                    input,
                                    input,
                                    checkNotNull(activeResponse),
                                    catalogRevision,
                                    callId,
                                )
                            pending[callId] = identity
                            awaitingTool = true
                            send(ProviderEvent.ToolCallReady(identity, tool.capabilityId, arguments))
                        }

                        // WebRTC only: the server paces audio out after generating it, so these, not
                        // response.done, say when the reply has finished reaching the phone.
                        "output_audio_buffer.started" -> {
                            send(ProviderEvent.AssistantSpeaking(true))
                        }

                        "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                            send(ProviderEvent.AssistantSpeaking(false))
                        }

                        "response.done" -> {
                            val response = message.obj("response") ?: return@collect
                            if (response.str("id") != activeResponse || awaitingTool || pending.isNotEmpty()) return@collect
                            val input = activeInput ?: return@collect
                            val status = response.str("status") ?: "completed"
                            activeInput = null
                            activeResponse = null
                            send(ProviderEvent.ResponseEnded(input, status))
                        }

                        "error" -> {
                            val error = message.obj("error")
                            if (error?.str("code") == "response_cancel_not_active") return@collect
                            send(ProviderEvent.Failure(error?.str("message") ?: "The provider reported an error."))
                        }
                    }
                }
            } finally {
                seedTimeout?.cancel()
            }
            send(ProviderEvent.Closed)
        }

    override suspend fun submit(input: ConversationInput) {
        check(sessionId != null && buffered == null && activeInput == null)
        require(input.text.isNotBlank() && input.text.length <= 4000)
        buffered = input
    }

    override suspend fun requestResponse(request: ResponseRequest) {
        val input = checkNotNull(buffered)
        check(input.id == request.inputId)
        buffered = null
        pendingTypedInput = input
        media.send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "message")
                        put("role", "user")
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", input.text)
                                    },
                                ),
                            ),
                        )
                    },
                )
            }.toString(),
        )
        media.send(buildJsonObject { put("type", "response.create") }.toString())
    }

    override suspend fun submitToolResult(result: CorrelatedToolResult) {
        check(result.call.connectionEpoch == connectionEpoch && pending[result.call.callId] == result.call)
        val output = result.wireOutcome()
        media.send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.call.callId)
                        put("output", output.toString())
                    },
                )
            }.toString(),
        )
        pending.remove(result.call.callId)
        if (pending.isEmpty()) media.send(buildJsonObject { put("type", "response.create") }.toString())
    }

    override suspend fun close() = Unit
}

private fun realtimeSeedItem(message: OpenAiHistoryMessage): RealtimeSeedItem {
    val role = if (message.role == "developer") "system" else message.role
    val contentType = if (role == "assistant") "output_text" else "input_text"
    val id = "item_eva_${UUID.randomUUID().toString().replace("-", "")}"
    val event =
        buildJsonObject {
            put("type", "conversation.item.create")
            put(
                "item",
                buildJsonObject {
                    put("id", id)
                    put("type", "message")
                    put("role", role)
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", contentType)
                                    put("text", message.text)
                                },
                            ),
                        ),
                    )
                },
            )
        }
    return RealtimeSeedItem(id, event.toString())
}

private fun responseCancel(responseId: String): String =
    buildJsonObject {
        put("type", "response.cancel")
        put("response_id", responseId)
    }.toString()

/** The GA shape: the caller names the model, the transcriber, and its own tools. */
private fun publicApiSession(
    model: String,
    request: SessionOpenRequest,
    named: Map<String, ProviderToolDefinition>,
    voice: String,
): JsonObject =
    buildJsonObject {
        put("type", "realtime")
        put("model", model)
        put("instructions", request.instructions)
        put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
        put(
            "audio",
            buildJsonObject {
                put("input", buildJsonObject { put("transcription", transcription(request.keywords)) })
                put("output", buildJsonObject { put("voice", voice) })
            },
        )
        if (named.isNotEmpty()) {
            put("tools", functionTools(named))
            put("tool_choice", "auto")
        }
    }

/** The caption model receives context separately from the speech model. */
private fun transcription(keywords: List<String>): JsonObject =
    buildJsonObject {
        put("model", OpenAiModels.TRANSCRIPTION)
        put("prompt", OpenAiModels.TRANSCRIPTION_PROMPT)
        put("languages", JsonArray(OpenAiModels.TRANSCRIPTION_LANGUAGES.map(::JsonPrimitive)))
        val bounded = keywords.filter { it.isNotBlank() }.distinct().take(OpenAiModels.TRANSCRIPTION_KEYWORD_LIMIT)
        if (bounded.isNotEmpty()) put("keywords", JsonArray(bounded.map(::JsonPrimitive)))
    }
