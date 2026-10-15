package com.colonelpanic.eva.providers.grok

import com.colonelpanic.eva.audio.PcmVoiceMedia
import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.CallRejection
import com.colonelpanic.eva.providers.ConversationInput
import com.colonelpanic.eva.providers.ConversationProvider
import com.colonelpanic.eva.providers.ConversationSession
import com.colonelpanic.eva.providers.CorrelatedToolResult
import com.colonelpanic.eva.providers.ProviderEvent
import com.colonelpanic.eva.providers.ProviderToolDefinition
import com.colonelpanic.eva.providers.ResponseRequest
import com.colonelpanic.eva.providers.SessionOpenRequest
import com.colonelpanic.eva.providers.openai.functionTools
import com.colonelpanic.eva.providers.openai.obj
import com.colonelpanic.eva.providers.openai.str
import com.colonelpanic.eva.providers.openai.toOpenAiMessages
import com.colonelpanic.eva.providers.openai.toolNames
import com.colonelpanic.eva.providers.wireOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.UUID

class GrokVoiceProvider(
    private val apiKey: String,
    private val media: PcmVoiceMedia,
    private val configuration: GrokVoiceConfiguration = GrokVoiceConfiguration(),
    private val client: OkHttpClient = grokClient(),
) : ConversationProvider {
    override suspend fun open(request: SessionOpenRequest): ConversationSession {
        require(apiKey.isNotBlank()) { "Add an xAI API key in Settings > Account before starting Grok voice." }
        require(
            request.catalog.tools
                .map { it.capabilityId }
                .distinct()
                .size == request.catalog.tools.size,
        )
        request.catalog.tools.forEach { ToolSchema.check(it.inputSchema) }
        return GrokVoiceSession(OkHttpGrokSocket(apiKey, configuration.model, client), media, configuration, request)
    }
}

internal class GrokVoiceSession(
    private val socket: GrokSocket,
    private val media: PcmVoiceMedia,
    private val configuration: GrokVoiceConfiguration,
    private val request: SessionOpenRequest,
) : ConversationSession {
    override val connectionEpoch = UUID.randomUUID().toString()
    private val lock = Mutex()
    private val output = Channel<ProviderEvent>(Channel.UNLIMITED)
    private val tools = toolNames(request.catalog.tools)
    private val origins = ArrayDeque<Origin>()
    private val queued = ArrayDeque<Origin>()
    private val responses = linkedMapOf<String, Response>()
    private val speech = mutableMapOf<String, Origin>()
    private val calls = mutableMapOf<String, Pair<Response, CallIdentity>>()
    private val emittedCalls = mutableSetOf<String>()
    private val seeds = mutableSetOf<String>()
    private val cancellationEvents = mutableSetOf<String>()
    private var sessionId = ""
    private var configured = false
    private var ready = false
    private var closed = false
    private var userSpeaking = false
    private var typed: ConversationInput? = null

    private data class Origin(
        val input: String,
        val initiator: ActionInitiator,
        val deliveryIds: List<String> = emptyList(),
        val note: String? = null,
        val data: JsonObject? = null,
        var interrupted: Boolean = false,
    )

    private data class Response(
        val id: String,
        val origin: Origin?,
        val items: MutableMap<String, JsonObject> = linkedMapOf(),
        var status: String? = null,
        var interrupted: Boolean = false,
        var audio: Boolean = false,
        var playbackDone: Boolean = false,
        var played: Boolean = false,
        var text: String = "",
        var textEmitted: Boolean = false,
        var finalized: Boolean = false,
        var replyWanted: Boolean = false,
        var deliveryReported: Boolean = false,
        val audioItems: MutableMap<String, AudioRange> = linkedMapOf(),
        var audioBytes: Long = 0,
    )

    private data class AudioRange(
        val start: Long,
        var end: Long,
    )

    override val events =
        channelFlow {
            val forwarding = launch { for (event in output) send(event) }
            val startup =
                launch {
                    delay(30_000)
                    lock.withLock {
                        if (!ready && !closed) {
                            emit(
                                ProviderEvent.Failure(
                                    "Grok did not acknowledge session configuration or conversation history within 30 seconds. Reconnect to try again.",
                                ),
                            )
                            shutdown()
                        }
                    }
                }
            val playback =
                launch {
                    media.playback.collect { event -> lock.withLock { onPlayback(event) } }
                }
            try {
                socket.events.collect { raw -> lock.withLock { if (!closed) receive(Json.parseToJsonElement(raw).jsonObject) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emit(ProviderEvent.Failure(error.message ?: "Grok voice failed."))
            } finally {
                lock.withLock { shutdown() }
                startup.cancel()
                playback.cancel()
                forwarding.join()
            }
        }

    private fun emit(event: ProviderEvent) {
        output.trySend(event)
    }

    private fun send(value: JsonObject) = socket.send(value.toString())

    private fun event(
        type: String,
        body: JsonObjectBuilder.() -> Unit = {},
    ) = send(
        buildJsonObject {
            put("type", type)
            body()
        },
    )

    private fun message(
        role: String,
        text: String,
        id: String =
            "eva_" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(24),
    ): String {
        event("conversation.item.create") {
            put(
                "item",
                buildJsonObject {
                    put("id", id)
                    put("type", "message")
                    put("role", if (role == "developer") "system" else role)
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", if (role == "assistant") "text" else "input_text")
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
            )
        }
        return id
    }

    private suspend fun receive(e: JsonObject) {
        when (e.str("type")) {
            "session.created" -> {
                sessionId = e.obj("session")?.str("id") ?: error("Grok did not identify the voice session.")
                event("session.update") { put("session", grokSessionConfiguration(configuration, request, tools)) }
            }

            "session.updated" -> {
                if (!configured) {
                    configured = true
                    request.history.flatMap { it.toOpenAiMessages() }.forEach { seeds += message(it.role, it.text) }
                    connectIfSeeded()
                }
            }

            "conversation.item.added", "conversation.item.created" -> {
                e.obj("item")?.str("id")?.let { seeds.remove(it) }
                connectIfSeeded()
            }

            "input_audio_buffer.speech_started" -> {
                userSpeaking = true
                emit(ProviderEvent.UserSpeaking)
                e.str("item_id")?.let { emit(ProviderEvent.SpeechInputStarted(it)) }
                origins.forEach { it.interrupted = true }
                val played = media.interrupt()
                responses.values.filter { !it.finalized }.forEach { response ->
                    response.interrupted = true
                    if (response.status == null) cancel(response.id)
                    if (!response.playbackDone) {
                        response.audioItems.forEach { (item, range) ->
                            event("conversation.item.truncate") {
                                put("item_id", item)
                                put("content_index", 0)
                                val startMillis = range.start / PCM_BYTES_PER_MILLISECOND
                                val durationMillis = (range.end - range.start) / PCM_BYTES_PER_MILLISECOND
                                put("audio_end_ms", ((played[response.id] ?: 0) - startMillis).coerceIn(0, durationMillis))
                            }
                        }
                    }
                }
            }

            "input_audio_buffer.speech_stopped" -> {
                userSpeaking = false
            }

            "input_audio_buffer.committed" -> {
                val item = e.str("item_id") ?: error("Grok committed audio without an item ID.")
                userSpeaking = false
                val origin = Origin("speech_$item", ActionInitiator(InitiatorKind.USER_SPEECH, itemId = item))
                speech[item] = origin
                origins.addLast(origin)
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val item = e.str("item_id") ?: return
                val text = e.str("transcript") ?: return
                val origin = speech[item] ?: Origin("speech_$item", ActionInitiator(InitiatorKind.USER_SPEECH, itemId = item))
                emit(ProviderEvent.Transcript("user", text, item, origin.input))
            }

            "response.created" -> {
                val response = e.obj("response") ?: return
                val id = response.str("id") ?: error("Grok response has no ID.")
                if (responses.containsKey(id)) return
                // xAI does not document an echoed request ID. Never guess between competing origins.
                check(origins.size <= 1) {
                    "Grok response ownership became ambiguous during interruption. Reconnect voice; accepted tasks remain in the thread."
                }
                val origin = origins.removeFirstOrNull()
                val state = Response(id, origin, interrupted = origin?.interrupted == true)
                responses[id] = state
                if (origin !=
                    null
                ) {
                    emit(ProviderEvent.ResponseStarted(origin.input, id, origin.initiator.kind == InitiatorKind.LIFECYCLE_NOTE_REPLY))
                }
                if (state.interrupted) cancel(id)
            }

            "response.output_audio.delta", "response.audio.delta" -> {
                val response = response(e) ?: return
                if (response.interrupted || response.finalized) return
                val audio = e.str("delta") ?: return
                response.audio = true
                val bytes = Base64.getDecoder().decode(audio)
                e.str("item_id")?.let {
                    response.audioItems.getOrPut(it) { AudioRange(response.audioBytes, response.audioBytes) }.end += bytes.size
                }
                response.audioBytes += bytes.size
                media.enqueue(response.id, bytes)
            }

            "response.output_audio_transcript.done", "response.audio_transcript.done",
            "response.output_text.done", "response.text.done",
            -> {
                response(e)?.let { it.text = e.str("transcript") ?: e.str("text").orEmpty() }
            }

            "response.function_call_arguments.done" -> {
                val response = response(e) ?: return
                val callId = e.str("call_id") ?: return
                response.items[callId] =
                    buildJsonObject {
                        put("type", "function_call")
                        put("call_id", callId)
                        e["name"]?.let { put("name", it) }
                        e["arguments"]?.let { put("arguments", it) }
                        put("status", "completed")
                    }
            }

            "response.output_item.done" -> {
                val item = e.obj("item") ?: return
                if (item.str("type") == "function_call") item.str("call_id")?.let { response(e)?.items?.set(it, item) }
            }

            "response.done" -> {
                val body = e.obj("response") ?: return
                val response = body.str("id")?.let(responses::get) ?: return
                if (response.status != null) return
                response.status = body.str("status") ?: "failed"
                (body["output"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { item ->
                    if (item.str("type") == "function_call") item.str("call_id")?.let { response.items[it] = item }
                    (item["content"] as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { content ->
                        (content.str("transcript") ?: content.str("text"))?.let { response.text = it }
                    }
                }
                dispatchCalls(response)
                if (response.audio) media.finish(response.id) else response.playbackDone = true
                finish(response)
                pump()
            }

            "error" -> {
                error("Grok rejected the voice request: " + (e.obj("error")?.str("message") ?: "unspecified API error"))
            }
        }
    }

    private fun cancel(responseId: String) {
        val id = "cancel_${UUID.randomUUID()}"
        cancellationEvents += id
        event("response.cancel") {
            put("event_id", id)
            put("response_id", responseId)
        }
    }

    private fun response(e: JsonObject): Response? = e.str("response_id")?.let(responses::get)

    private suspend fun connectIfSeeded() {
        if (!configured || seeds.isNotEmpty() || ready) return
        ready = true
        media.start { bytes ->
            event("input_audio_buffer.append") { put("audio", Base64.getEncoder().encodeToString(bytes)) }
        }
        val hintCount =
            request.keywords
                .filter(String::isNotBlank)
                .distinct()
                .size
        val acceptedHints =
            request.keywords
                .filter { it.isNotBlank() && it.length <= 50 }
                .distinct()
                .take(100)
                .size
        if (acceptedHints < hintCount) {
            emit(
                ProviderEvent.Notice(
                    "Grok transcription uses $acceptedHints of $hintCount name hints; xAI allows 100 hints of at most 50 characters each.",
                ),
            )
        }
        emit(ProviderEvent.Account("xAI API key"))
        emit(ProviderEvent.Connected(sessionId, request.catalog.revision, configuration.model))
        emit(
            ProviderEvent.Notice(
                "Grok voice uses paid xAI access; automatic listening is billed for the entire connection.",
                persistent = true,
            ),
        )
    }

    private fun emitText(response: Response) {
        val origin = response.origin ?: return
        if (!response.textEmitted && response.text.isNotBlank()) {
            response.textEmitted = true
            emit(ProviderEvent.AssistantText(origin.input, response.text, response.interrupted || response.status != "completed"))
        }
    }

    private fun dispatchCalls(response: Response) {
        response.items.forEach { (id, item) ->
            if (!emittedCalls.add(id)) return@forEach
            val origin = response.origin
            val identity =
                CallIdentity(
                    connectionEpoch,
                    sessionId,
                    origin?.input ?: "unowned_${response.id}",
                    response.id,
                    response.id,
                    request.catalog.revision,
                    id,
                    origin?.initiator?.copy(inputId = origin.input, responseId = response.id, outputItemId = item.str("id")),
                )
            val arguments = runCatching { Json.parseToJsonElement(item.str("arguments").orEmpty()).jsonObject }.getOrNull()
            val rejection =
                when {
                    response.interrupted || response.status != "completed" -> CallRejection.INTERRUPTED_RESPONSE

                    origin == null || item
                        .str(
                            "status",
                        )?.let { it != "completed" } == true || arguments == null -> CallRejection.INCOMPLETE_CALL

                    else -> null
                }
            calls[id] = response to identity
            emit(
                ProviderEvent.ToolCallReady(
                    identity,
                    tools[item.str("name")]?.capabilityId ?: item.str("name").orEmpty(),
                    arguments ?: JsonObject(emptyMap()),
                    rejection,
                ),
            )
        }
    }

    private fun onPlayback(event: PcmVoiceMedia.Playback) {
        when (event) {
            is PcmVoiceMedia.Playback.Started -> {
                emit(ProviderEvent.AssistantSpeaking(true))
            }

            is PcmVoiceMedia.Playback.Finished -> {
                emit(ProviderEvent.AssistantSpeaking(false))
                responses[event.responseId]?.let { response ->
                    response.playbackDone = true
                    response.played = event.played
                    finish(response)
                }
                pump()
            }

            is PcmVoiceMedia.Playback.Failed -> {
                emit(ProviderEvent.Failure(event.message))
                shutdown()
            }
        }
    }

    private fun finish(response: Response) {
        if (response.finalized || response.status == null || !response.playbackDone) return
        emitText(response)
        val origin = response.origin
        if (origin != null && origin.deliveryIds.isNotEmpty() && !response.deliveryReported) {
            response.deliveryReported = true
            emit(
                ProviderEvent.ContextDelivery(
                    origin.deliveryIds,
                    response.played && !response.interrupted && response.status == "completed",
                ),
            )
        }
        if (calls.values.any { it.first === response }) return
        response.finalized = true
        if (origin == null) return
        if (response.replyWanted && !response.interrupted) {
            queued.addLast(origin.copy(deliveryIds = emptyList(), note = null, data = null))
        } else {
            emit(ProviderEvent.ResponseEnded(origin.input, if (response.interrupted) "cancelled" else response.status!!))
        }
    }

    private fun pump() {
        if (closed || !ready || userSpeaking || origins.isNotEmpty() || responses.values.any { !it.finalized }) return
        val origin = queued.removeFirstOrNull() ?: return
        origin.note?.let { message("system", it) }
        origin.data?.let { message("user", it.toString()) }
        origins.addLast(origin)
        event("response.create")
    }

    override suspend fun submit(input: ConversationInput) =
        lock.withLock {
            check(ready && !closed) { "Grok voice is not connected." }
            check(typed == null) { "A typed voice request is already pending." }
            typed = input
            message("user", input.text)
            Unit
        }

    override suspend fun requestResponse(request: ResponseRequest) =
        lock.withLock {
            val input = checkNotNull(typed) { "Submit the voice input before requesting a response." }
            check(input.id == request.inputId)
            typed = null
            queued.addLast(Origin(input.id, ActionInitiator(InitiatorKind.USER_TYPED)))
            pump()
        }

    override suspend fun submitToolResult(result: CorrelatedToolResult) =
        lock.withLock {
            val (response, identity) = calls[result.call.callId] ?: return@withLock
            check(identity == result.call) { "Grok tool result belongs to another connection or turn." }
            check(!closed) { "Grok voice disconnected; the action receipt remains in the thread." }
            event("conversation.item.create") {
                put(
                    "item",
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", identity.callId)
                        put("output", result.wireOutcome().toString())
                    },
                )
            }
            calls.remove(identity.callId)
            response.replyWanted = response.replyWanted || result.respond
            finish(response)
            pump()
        }

    override suspend fun submitContext(
        note: String,
        respond: Boolean,
        data: JsonObject?,
        deliveryId: String?,
    ): Boolean =
        lock.withLock {
            if (!ready || closed) return@withLock false
            if (!respond) {
                message("system", note)
                data?.let { message("user", it.toString()) }
                deliveryId?.let { emit(ProviderEvent.ContextDelivery(listOf(it), false)) }
            } else {
                queued.addLast(
                    Origin(
                        "context_${UUID.randomUUID()}",
                        ActionInitiator(InitiatorKind.LIFECYCLE_NOTE_REPLY),
                        listOfNotNull(deliveryId),
                        note,
                        data,
                    ),
                )
                pump()
            }
            true
        }

    override suspend fun close() = lock.withLock { shutdown() }

    private fun shutdown() {
        if (closed) return
        closed = true
        val undelivered =
            (queued.toList() + origins.toList()).flatMap { it.deliveryIds } +
                responses.values.filter { !it.deliveryReported }.flatMap { it.origin?.deliveryIds.orEmpty() }
        if (undelivered.isNotEmpty()) emit(ProviderEvent.ContextDelivery(undelivered.distinct(), false))
        responses.values.filter { !it.finalized }.forEach {
            it.interrupted = true
            emitText(it)
        }
        socket.close()
        media.close()
        emit(ProviderEvent.Closed)
        output.close()
    }
}

internal fun grokSessionConfiguration(
    configuration: GrokVoiceConfiguration,
    request: SessionOpenRequest,
    tools: Map<String, ProviderToolDefinition>,
): JsonObject =
    buildJsonObject {
        put("instructions", request.instructions)
        put("voice", configuration.voice)
        put("reasoning", buildJsonObject { put("effort", configuration.reasoningEffort) })
        put("turn_detection", buildJsonObject { put("type", "server_vad") })
        put("tools", functionTools(tools))
        put(
            "audio",
            buildJsonObject {
                for (direction in listOf("input", "output")) {
                    put(
                        direction,
                        buildJsonObject {
                            put(
                                "format",
                                buildJsonObject {
                                    put("type", "audio/pcm")
                                    put("rate", 24000)
                                },
                            )
                            put("transport", "json")
                            if (direction == "input") {
                                put(
                                    "transcription",
                                    buildJsonObject {
                                        put(
                                            "keyterms",
                                            JsonArray(
                                                request.keywords
                                                    .filter { it.isNotBlank() && it.length <= 50 }
                                                    .distinct()
                                                    .take(100)
                                                    .map(::JsonPrimitive),
                                            ),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

private const val PCM_BYTES_PER_MILLISECOND = 24000 * 2 / 1000
