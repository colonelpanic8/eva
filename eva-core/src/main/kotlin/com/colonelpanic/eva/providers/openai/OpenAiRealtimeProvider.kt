package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CatalogAdmission
import com.colonelpanic.eva.capability.InitiatorKind
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
import kotlinx.coroutines.channels.Channel
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
    private val reasoningEffort: String = OpenAiModels.VOICE_REASONING_EFFORT,
    private val client: OkHttpClient = realtimeCallClient(),
    private val voice: String = "marin",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConversationProvider {
    override suspend fun open(request: SessionOpenRequest): ConversationSession {
        require(request.catalog.tools.size <= CatalogAdmission.LIMIT) {
            "The session offers ${request.catalog.tools.size} tools, but at most ${CatalogAdmission.LIMIT} are allowed."
        }
        require(
            request.catalog.tools
                .map { it.capabilityId }
                .distinct()
                .size == request.catalog.tools.size,
        )
        request.catalog.tools.forEach { ToolSchema.check(it.inputSchema) }
        val named = toolNames(request.catalog.tools)
        val session = publicApiSession(model, request, named, voice, reasoningEffort)
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

    private data class Origin(
        val inputId: String,
        val initiator: ActionInitiator,
        val purpose: String,
        val metadata: JsonObject = JsonObject(emptyMap()),
    ) {
        val announceOnly: Boolean get() = initiator.kind == InitiatorKind.LIFECYCLE_NOTE_REPLY
        val known: Boolean get() = initiator.kind != InitiatorKind.UNKNOWN
    }

    private data class ResponseState(
        val origin: Origin,
        var status: String? = null,
    )

    private data class SpeechCommit(
        val itemId: String,
        val previousItemId: String?,
    )

    private data class RequestedResponse(
        val id: String,
        val origin: Origin,
    )

    private val responses = linkedMapOf<String, ResponseState>()
    private val requested = mutableMapOf<String, RequestedResponse>()
    private val queued = ArrayDeque<Origin>()
    private var inFlight: String? = null
    private var waitingForBusyResponse = false
    private val activeResponses = mutableSetOf<String>()
    private val startedInputs = mutableSetOf<String>()
    private val endedInputs = mutableSetOf<String>()
    private val speechCommits = linkedMapOf<String, SpeechCommit>()
    private val speechInputs = mutableMapOf<String, String>()
    private val awaitingSpeech = mutableSetOf<String>()
    private val captions = mutableMapOf<String, MutableList<ProviderEvent.Transcript>>()
    private val pending = mutableMapOf<String, CallIdentity>()
    private val seenCalls = mutableSetOf<Pair<String, String>>()
    private val replyWanted = mutableSetOf<String>()
    private var contextReplyPending = false
    private var userSpeaking = false
    private var assistantAudioActive = false
    private var closed = false
    private val local = Channel<ProviderEvent>(Channel.UNLIMITED)
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
            val forward = launch { for (event in local) send(event) }
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
                            val response = message.obj("response") ?: return@collect
                            val id = response.str("id") ?: return@collect
                            if (!seedReady) {
                                media.send(responseCancel(id))
                                return@collect
                            }
                            if (id in responses) return@collect
                            val metadata = response.obj("metadata") ?: JsonObject(emptyMap())
                            val requestId = metadata.str("eva_request_id")
                            val ownRequest = requestId?.let(requested::remove)
                            if (ownRequest != null && inFlight == requestId) inFlight = null
                            val origin =
                                if (ownRequest != null) {
                                    ownRequest.origin.copy(metadata = metadata)
                                } else if (metadata.isEmpty() && speechCommits.isNotEmpty()) {
                                    // The ordered data channel delivers committed speech before its automatic VAD response.
                                    val committed =
                                        speechCommits.values.firstOrNull { it.previousItemId !in speechCommits }
                                            ?: speechCommits.values.first()
                                    speechCommits.remove(committed.itemId)
                                    awaitingSpeech.remove(committed.itemId)
                                    val input = speechInputs.getValue(committed.itemId)
                                    Origin(
                                        input,
                                        ActionInitiator(InitiatorKind.USER_SPEECH, inputId = input, itemId = committed.itemId),
                                        "user_speech",
                                        metadata,
                                    )
                                } else {
                                    Origin("unowned:$id", ActionInitiator(InitiatorKind.UNKNOWN, responseId = id), "unknown", metadata)
                                }
                            val bound = origin.copy(initiator = origin.initiator.copy(responseId = id))
                            responses[id] = ResponseState(bound)
                            activeResponses += id
                            if (bound.known && startedInputs.add(bound.inputId)) {
                                send(ProviderEvent.ResponseStarted(bound.inputId, bound.inputId, announceOnly = bound.announceOnly))
                            }
                            if (bound.known) captions.remove(bound.inputId)?.forEach { send(it) }
                        }

                        "input_audio_buffer.speech_started" -> {
                            userSpeaking = true
                            send(ProviderEvent.UserSpeaking)
                            message.str("item_id")?.let {
                                awaitingSpeech += it
                                send(ProviderEvent.SpeechInputStarted(it))
                            }
                        }

                        "input_audio_buffer.speech_stopped" -> {
                            userSpeaking = false
                        }

                        "input_audio_buffer.committed" -> {
                            val id = message.str("item_id") ?: return@collect
                            if (id !in speechInputs) {
                                speechInputs[id] = "voice:$id"
                                speechCommits[id] = SpeechCommit(id, message.str("previous_item_id"))
                                awaitingSpeech += id
                            }
                        }

                        "conversation.item.input_audio_transcription.completed" -> {
                            val text = message.str("transcript")?.takeIf { it.isNotBlank() } ?: return@collect
                            val item = message.str("item_id")
                            val input = item?.let { speechInputs[it] ?: "voice:$it" }
                            val transcript = ProviderEvent.Transcript("user", text, item, input)
                            if (input != null && input !in startedInputs) {
                                captions.getOrPut(input) { mutableListOf() } += transcript
                            } else {
                                send(transcript)
                            }
                        }

                        "response.output_audio_transcript.done", "response.audio_transcript.done", "response.output_text.done" -> {
                            val id = message.str("response_id") ?: return@collect
                            val text = message.str("transcript") ?: message.str("text") ?: return@collect
                            val origin = responses[id]?.origin
                            send(ProviderEvent.AssistantText(origin?.inputId ?: "unowned:$id", text, false))
                        }

                        "response.output_item.done" -> {
                            val item = message.obj("item") ?: return@collect
                            receiveCall(message.str("response_id"), item)
                        }

                        "output_audio_buffer.started" -> {
                            assistantAudioActive = true
                            send(ProviderEvent.AssistantSpeaking(true))
                        }

                        "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                            assistantAudioActive = false
                            send(ProviderEvent.AssistantSpeaking(false))
                            pumpRequests()
                        }

                        "response.done" -> {
                            val response = message.obj("response") ?: return@collect
                            val id = response.str("id") ?: return@collect
                            // The final response is also an authoritative source of calls if item events arrive late.
                            (response["output"] as? JsonArray)?.forEach { item -> (item as? JsonObject)?.let { receiveCall(id, it) } }
                            responses[id]?.status = response.str("status") ?: "completed"
                            activeResponses.remove(id)
                            waitingForBusyResponse = false
                            responses[id]?.origin?.let { finishInput(it.inputId) }
                            pumpRequests()
                        }

                        "error" -> {
                            val error = message.obj("error")
                            if (error?.str("code") == "response_cancel_not_active") return@collect
                            val requestId = error?.str("event_id")
                            val failedRequest = requestId?.let(requested::remove)
                            if (error?.str("code") == "conversation_already_has_active_response" && failedRequest != null) {
                                if (inFlight == requestId) inFlight = null
                                queued.addFirst(failedRequest.origin)
                                waitingForBusyResponse = true
                                return@collect
                            }
                            send(ProviderEvent.Failure(error?.str("message") ?: "The provider reported an error."))
                        }
                    }
                }
            } finally {
                closed = true
                seedTimeout?.cancel()
                forward.cancel()
            }
            send(ProviderEvent.Closed)
        }

    private suspend fun receiveCall(
        responseId: String?,
        item: JsonObject,
    ) {
        if (item.str("type") != "function_call") return
        val callId = item.str("call_id") ?: return
        val responseKey = responseId ?: "missing-response"
        if (!seenCalls.add(responseKey to callId)) return
        val origin = responseId?.let { responses[it]?.origin }
        val inputId = origin?.inputId ?: "unowned:$responseKey"
        val initiator =
            (
                origin?.initiator ?: ActionInitiator(
                    InitiatorKind.UNKNOWN,
                )
            ).copy(responseId = responseId, outputItemId = item.str("id"))
        val identity =
            CallIdentity(connectionEpoch, sessionId ?: connectionEpoch, inputId, inputId, responseKey, catalogRevision, callId, initiator)
        val arguments =
            runCatching {
                json
                    .parseToJsonElement(
                        item.str("arguments").orEmpty(),
                    ).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
        pending[callId] = identity
        local.send(ProviderEvent.ToolCallReady(identity, tools[item.str("name")]?.capabilityId ?: item.str("name").orEmpty(), arguments))
    }

    private fun finishInput(inputId: String) {
        if (inputId !in startedInputs || inputId in endedInputs || pending.values.any { it.inputId == inputId } ||
            requested.values.any { it.origin.inputId == inputId } || queued.any { it.inputId == inputId } ||
            activeResponses.any { responses[it]?.origin?.inputId == inputId }
        ) {
            return
        }
        val state = responses.values.lastOrNull { it.origin.inputId == inputId } ?: return
        val status = state.status ?: return
        endedInputs += inputId
        local.trySend(ProviderEvent.ResponseEnded(inputId, status))
    }

    override suspend fun submit(input: ConversationInput) {
        check(sessionId != null && buffered == null)
        require(input.text.isNotBlank() && input.text.length <= 4000) { "A request must be 1 to 4,000 characters." }
        buffered = input
    }

    override suspend fun requestResponse(request: ResponseRequest) {
        val input = checkNotNull(buffered)
        check(input.id == request.inputId)
        buffered = null
        media.send(realtimeSeedItem(OpenAiHistoryMessage("user", input.text)).event)
        queued += Origin(input.id, ActionInitiator(InitiatorKind.USER_TYPED, inputId = input.id), "typed_input")
        pumpRequests()
    }

    override suspend fun submitToolResult(result: CorrelatedToolResult) {
        check(result.call.connectionEpoch == connectionEpoch && pending[result.call.callId] == result.call)
        media.send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", result.call.callId)
                        put("output", result.wireOutcome().toString())
                    },
                )
            }.toString(),
        )
        pending.remove(result.call.callId)
        val responseId = result.call.providerTurnId
        val origin = responses[responseId]?.origin
        if (origin == null || !origin.known || origin.inputId in endedInputs) {
            pumpRequests()
            return
        }
        if (result.respond) replyWanted += responseId
        if (pending.values.none { it.providerTurnId == responseId } && replyWanted.remove(responseId) &&
            queued.none { it.initiator.parentResponseId == responseId }
        ) {
            queued += origin.copy(purpose = "tool_follow_up", initiator = origin.initiator.copy(parentResponseId = responseId))
        }
        finishInput(origin.inputId)
        pumpRequests()
    }

    override suspend fun submitContext(
        note: String,
        respond: Boolean,
        data: JsonObject?,
    ): Boolean {
        if (closed || sessionId == null || !seedReady) return false
        media.send(realtimeSeedItem(OpenAiHistoryMessage("developer", note)).event)
        data?.let { media.send(realtimeSeedItem(OpenAiHistoryMessage("assistant", it.toString())).event) }
        if (respond) contextReplyPending = true
        pumpRequests()
        return true
    }

    private fun pumpRequests() {
        if (closed || !seedReady || activeResponses.isNotEmpty() || inFlight != null || waitingForBusyResponse || userSpeaking ||
            awaitingSpeech.isNotEmpty()
        ) {
            return
        }
        val ready =
            queued.firstOrNull { origin ->
                val parent = origin.initiator.parentResponseId
                parent == null || pending.values.none { it.providerTurnId == parent }
            }
        val origin =
            if (ready != null) {
                queued.remove(ready)
                ready
            } else if (contextReplyPending && pending.isEmpty() && !assistantAudioActive) {
                contextReplyPending = false
                val input = "announcement:${UUID.randomUUID()}"
                Origin(input, ActionInitiator(InitiatorKind.LIFECYCLE_NOTE_REPLY, inputId = input), "lifecycle_note")
            } else {
                return
            }
        val id = "eva_${UUID.randomUUID().toString().replace("-", "")}"
        val metadata =
            buildJsonObject {
                put("eva_request_id", id)
                put("eva_purpose", origin.purpose)
                put("eva_input_id", origin.inputId)
                put("eva_initiator", origin.initiator.kind.wireName)
                origin.initiator.itemId?.let { put("eva_speech_item_id", it) }
                origin.initiator.parentResponseId?.let { put("eva_parent_response_id", it) }
                if (origin.announceOnly) put("eva_announce_only", "true")
            }
        requested[id] = RequestedResponse(id, origin.copy(metadata = metadata))
        inFlight = id
        media.send(
            buildJsonObject {
                put("type", "response.create")
                put("event_id", id)
                put(
                    "response",
                    buildJsonObject {
                        put("metadata", metadata)
                        if (origin.announceOnly) put("tool_choice", "none")
                    },
                )
            }.toString(),
        )
    }

    override suspend fun close() {
        closed = true
        queued.clear()
        contextReplyPending = false
    }
}

/** The Realtime API rejects an item id longer than this, so the random part is cut to fit. */
internal const val REALTIME_ITEM_ID_LIMIT = 32

private const val SEED_ITEM_PREFIX = "item_eva_"

internal fun seedItemId(): String = (SEED_ITEM_PREFIX + UUID.randomUUID().toString().replace("-", "")).take(REALTIME_ITEM_ID_LIMIT)

private fun realtimeSeedItem(message: OpenAiHistoryMessage): RealtimeSeedItem {
    val role = if (message.role == "developer") "system" else message.role
    val contentType = if (role == "assistant") "output_text" else "input_text"
    val id = seedItemId()
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
    reasoningEffort: String,
): JsonObject =
    buildJsonObject {
        put("type", "realtime")
        put("model", model)
        put("instructions", request.instructions)
        put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
        put("reasoning", buildJsonObject { put("effort", reasoningEffort) })
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
