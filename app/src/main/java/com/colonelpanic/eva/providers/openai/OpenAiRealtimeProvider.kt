package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.util.UUID

/**
 * Direct WebRTC session with the OpenAI Realtime API. The phone posts its own SDP offer
 * with the session configuration and speaks the event protocol over the data channel.
 * The speech model calls EVA's tools itself; there is no host and no second model.
 */
class OpenAiRealtimeProvider(
    private val access: OpenAiAccess,
    private val media: RealtimeMediaSession,
    private val model: String = OpenAiModels.REALTIME,
    private val client: OkHttpClient = OkHttpClient(),
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
        val session =
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
                val http =
                    access
                        .authorize(Request.Builder().url(access.realtimeCallsUrl))
                        .post(body)
                        .build()
                client.newCall(http).execute().use { response ->
                    val text = response.body.string()
                    check(response.isSuccessful) { openAiErrorMessage(response.code, text, "the voice session") }
                    check(text.startsWith("v=")) { "OpenAI returned an unexpected answer." }
                    text
                }
            }
        media.acceptAnswer(answer)
        withTimeout(30_000) { media.eventsReady.first { it } }
        return OpenAiRealtimeSession(media, named, request.catalog.revision, model)
    }
}

private class OpenAiRealtimeSession(
    private val media: RealtimeMediaSession,
    private val tools: Map<String, ProviderToolDefinition>,
    private val catalogRevision: String,
    private val model: String,
) : ConversationSession {
    override val connectionEpoch: String = UUID.randomUUID().toString()
    private var sessionId: String? = null
    private var buffered: ConversationInput? = null
    private var pendingTypedInput: ConversationInput? = null
    private var activeInput: String? = null
    private var activeResponse: String? = null
    private var awaitingTool = false
    private val pending = mutableMapOf<String, CallIdentity>()
    private val json = Json { ignoreUnknownKeys = true }

    override val events: Flow<ProviderEvent> =
        flow {
            emit(ProviderEvent.Account(OpenAiModels.ACCOUNT_LABEL))
            media.events.collect { raw ->
                val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@collect
                when (message.str("type")) {
                    "session.created", "session.updated" -> {
                        if (sessionId == null) {
                            val session = message.obj("session")
                            sessionId = session?.str("id") ?: UUID.randomUUID().toString()
                            emit(ProviderEvent.Connected(checkNotNull(sessionId), catalogRevision, session?.str("model") ?: model))
                        }
                    }

                    "response.created" -> {
                        val id = message.obj("response")?.str("id") ?: return@collect
                        activeResponse = id
                        if (activeInput == null) {
                            // A spoken turn has no typed input; the response is the input, mirroring the
                            // delegated-turn convention. A follow-up after a tool result keeps its input.
                            val input = pendingTypedInput?.id ?: "voice:$id"
                            pendingTypedInput = null
                            activeInput = input
                            emit(ProviderEvent.ResponseStarted(input, input))
                        }
                    }

                    "conversation.item.input_audio_transcription.completed" -> {
                        message.str("transcript")?.takeIf { it.isNotBlank() }?.let { emit(ProviderEvent.Transcript("user", it)) }
                    }

                    "response.output_audio_transcript.done", "response.audio_transcript.done", "response.output_text.done" -> {
                        val text = message.str("transcript") ?: message.str("text")
                        val input = activeInput
                        if (text != null && input != null) emit(ProviderEvent.AssistantText(input, text, false))
                    }

                    "response.output_item.done" -> {
                        val item = message.obj("item") ?: return@collect
                        if (item.str("type") != "function_call") return@collect
                        val input = activeInput ?: return@collect
                        val callId = item.str("call_id") ?: return@collect
                        val tool = tools[item.str("name")]
                        if (tool == null) {
                            emit(ProviderEvent.Failure("The model called a tool that was not advertised."))
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
                        emit(ProviderEvent.ToolCallReady(identity, tool.capabilityId, arguments))
                    }

                    "response.done" -> {
                        val input = activeInput ?: return@collect
                        if (awaitingTool && pending.isNotEmpty()) return@collect
                        val status = message.obj("response")?.str("status") ?: "completed"
                        activeInput = null
                        activeResponse = null
                        awaitingTool = false
                        emit(ProviderEvent.ResponseEnded(input, status))
                    }

                    "error" -> {
                        val error = message.obj("error")
                        if (error?.str("code") == "response_cancel_not_active") return@collect
                        emit(ProviderEvent.Failure(error?.str("message") ?: "The provider reported an error."))
                    }
                }
            }
            emit(ProviderEvent.Closed)
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
        val output =
            buildJsonObject {
                put("status", result.status)
                put("message", result.message.take(2000))
                result.data?.let { put("data", it) }
            }
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

/**
 * The transcriber runs beside the speech model rather than in front of it, so it starts with no
 * knowledge of the session. Giving it the setting, the expected vocabulary and a fixed language
 * is what keeps captions readable; the extra delay buys word accuracy the captions are never
 * racing anything to deliver.
 */
private fun transcription(keywords: List<String>): JsonObject =
    buildJsonObject {
        put("model", OpenAiModels.TRANSCRIPTION)
        put("prompt", OpenAiModels.TRANSCRIPTION_PROMPT)
        put("delay", OpenAiModels.TRANSCRIPTION_DELAY)
        put("languages", JsonArray(OpenAiModels.TRANSCRIPTION_LANGUAGES.map(::JsonPrimitive)))
        val bounded = keywords.filter { it.isNotBlank() }.distinct().take(OpenAiModels.TRANSCRIPTION_KEYWORD_LIMIT)
        if (bounded.isNotEmpty()) put("keywords", JsonArray(bounded.map(::JsonPrimitive)))
    }
