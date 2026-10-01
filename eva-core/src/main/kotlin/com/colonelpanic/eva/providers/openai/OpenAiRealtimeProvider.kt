package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.audio.RealtimeMediaSession
import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.CapabilityRegistry
import com.colonelpanic.eva.capability.CatalogAdmission
import com.colonelpanic.eva.capability.InitiatorKind
import com.colonelpanic.eva.capability.ToolSchema
import com.colonelpanic.eva.diagnostics.ProviderEventLog
import com.colonelpanic.eva.providers.CallIdentity
import com.colonelpanic.eva.providers.CallRejection
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private val sideband: RealtimeSideband = OkHttpRealtimeSideband(access, client),
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
        val sessionBytes = session.toString().toByteArray(Charsets.UTF_8).size
        val microphoneWasMuted = media.controls.value.microphoneMuted
        if (request.history.isNotEmpty()) media.setMicrophoneMuted(true)
        try {
            val offer = media.createOffer()
            val (answer, callId) =
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
                        text to it.header("Location")?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    }
                }
            // The call request carries the whole configuration, but OpenAI only echoes a session
            // that fits the data channel's advertised message size. A larger one is confirmed
            // through a sideband connection to the same call, which has no such bound.
            val confirm: (suspend () -> JsonObject)? =
                if (named.isNotEmpty() && sessionBytes > realtimeEchoByteLimit(offer)) {
                    if (callId == null) {
                        { error("OpenAI did not identify the call to connect to") }
                    } else {
                        { sideband.configuredSession(callId) }
                    }
                } else {
                    null
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
                confirm,
                sessionBytes,
                ioDispatcher,
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

/**
 * The largest session whose echo OpenAI delivers over the data channel: the offer's advertised
 * receive size (64 KiB when absent, unbounded when zero) less room for server-added fields.
 */
internal fun realtimeEchoByteLimit(offer: String): Long {
    val advertised =
        Regex("(?m)^a=max-message-size:(\\d+)")
            .find(offer)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull() ?: 65_536L
    if (advertised == 0L) return Long.MAX_VALUE
    return (advertised - 8 * 1024).coerceAtLeast(0)
}

/** Reads a call's configured session through a second connection to it. */
fun interface RealtimeSideband {
    suspend fun configuredSession(callId: String): JsonObject
}

/**
 * Attaches a WebSocket to the call, asks for an unchanged session update and returns the session
 * OpenAI echoes, then detaches; closing the sideband leaves the call running.
 */
class OkHttpRealtimeSideband(
    private val access: OpenAiAccess,
    private val client: OkHttpClient,
) : RealtimeSideband {
    override suspend fun configuredSession(callId: String): JsonObject {
        val url = access.realtimeCallsUrl.removeSuffix("/calls") + "?call_id=" + callId
        val request = access.authorize(Request.Builder().url(url)).build()
        val json = Json { ignoreUnknownKeys = true }
        return suspendCancellableCoroutine { continuation ->
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            webSocket.send(REALTIME_UNCHANGED_UPDATE)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                            when (message.str("type")) {
                                "session.updated" -> {
                                    webSocket.close(1000, null)
                                    message.obj("session")?.let { if (continuation.isActive) continuation.resume(it) }
                                }

                                "error" -> {
                                    webSocket.close(1000, null)
                                    val detail = message.obj("error")?.str("message") ?: "OpenAI reported an error."
                                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(detail))
                                }
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val detail =
                                response?.let { "HTTP ${it.code}" } ?: t.message ?: t::class.simpleName
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(detail, t))
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(IllegalStateException("the connection closed before OpenAI replied"))
                            }
                        }
                    },
                )
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }
}

/** A no-op change to a setting EVA always sends with tools, so OpenAI echoes the session. */
internal const val REALTIME_UNCHANGED_UPDATE = """{"type":"session.update","session":{"type":"realtime","tool_choice":"auto"}}"""

internal const val REALTIME_CONFIG_ACK_TIMEOUT_MILLIS = 8_000L

internal const val REALTIME_HISTORY_ACK_TIMEOUT_MILLIS = 10_000L
internal const val REALTIME_SPEECH_WAIT_MILLIS = 30_000L
internal const val REALTIME_RESPONSE_ACK_TIMEOUT_MILLIS = 10_000L
internal const val REALTIME_CALL_CORRELATION_MILLIS = 1_000L
internal const val REALTIME_CORRELATION_HISTORY = 256

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
    /** Present when the configuration is too large for its echo to arrive on the data channel. */
    private val confirm: (suspend () -> JsonObject)?,
    private val sessionBytes: Int,
    private val ioDispatcher: CoroutineDispatcher,
) : ConversationSession {
    override val connectionEpoch: String = UUID.randomUUID().toString()
    private var sessionId: String? = null
    private var buffered: ConversationInput? = null
    private var eventScope: CoroutineScope? = null

    private data class Origin(
        val inputId: String,
        val initiator: ActionInitiator,
        val purpose: String,
        val contextIds: List<String> = emptyList(),
    ) {
        val announceOnly: Boolean get() = initiator.kind == InitiatorKind.LIFECYCLE_NOTE_REPLY
        val known: Boolean get() = initiator.kind != InitiatorKind.UNKNOWN
    }

    private data class RequestState(
        val origin: Origin,
        var interrupted: Boolean = false,
        var interruptionHandled: Boolean = false,
    )

    private data class ResponseState(
        val origin: Origin,
        val request: RequestState?,
        var status: String? = null,
        var interrupted: Boolean = request?.interrupted == true,
    )

    private data class CallState(
        var responseId: String?,
        var item: JsonObject,
        var emitted: Boolean = false,
        var resolved: Boolean = false,
        var timer: Job? = null,
    )

    private data class ItemRequest(
        val inputId: String? = null,
        val seedItemId: String? = null,
        val contextIds: List<String> = emptyList(),
    )

    private val responses = linkedMapOf<String, ResponseState>()
    private val requested = linkedMapOf<String, RequestState>()
    private val retiredRequests = linkedMapOf<String, RequestState>()
    private val requestTimers = mutableMapOf<String, Job>()
    private val itemRequests = linkedMapOf<String, ItemRequest>()
    private val queued = ArrayDeque<Origin>()
    private var inFlight: String? = null
    private var reportedMissingEcho = false
    private var waitingForBusyResponse = false
    private var busyTimer: Job? = null
    private val activeResponses = linkedSetOf<String>()
    private val startedInputs = linkedMapOf<String, Origin>()
    private val endedInputs = linkedSetOf<String>()
    private val inputStatus = mutableMapOf<String, String>()
    private val speechInputs = linkedMapOf<String, String>()
    private val awaitingSpeech = linkedMapOf<String, Job?>()
    private val captions = linkedMapOf<String, MutableList<ProviderEvent.Transcript>>()
    private val pending = mutableMapOf<String, CallIdentity>()
    private val calls = linkedMapOf<String, CallState>()
    private val replyWanted = mutableSetOf<String>()
    private var contextReplyPending = false
    private val contextIds = linkedSetOf<String>()
    private val failedContextIds = linkedSetOf<String>()
    private var userSpeaking = false
    private var speakingItemId: String? = null
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

    private fun transmit(raw: String) {
        ProviderEventLog.realtime.recordOutbound(
            connectionEpoch,
            raw,
        ) { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        media.send(raw)
    }

    override val events: Flow<ProviderEvent> =
        channelFlow {
            eventScope = this
            send(ProviderEvent.Account(accountLabel))
            val forward = launch { for (event in local) send(event) }

            /** Set once the sideband cannot answer: the session OpenAI created is used without its echo. */
            var unverified = false
            var bareSession: JsonObject? = null

            fun configured(session: JsonObject?) {
                val acknowledged =
                    (session?.get("tools") as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.str("name") }
                        ?.toSet()
                        .orEmpty()
                if (sessionId != null) return
                if (acknowledged != tools.keys) {
                    if (confirm != null && session != null) bareSession = session
                    if (!unverified) return
                }
                sessionId = session?.str("id") ?: UUID.randomUUID().toString()
                val connected =
                    ProviderEvent.Connected(
                        checkNotNull(sessionId),
                        catalogRevision,
                        session?.str("model") ?: model,
                    )
                if (seedReady) {
                    emit(connected)
                } else {
                    pendingConnected = connected
                    val gate = CompletableDeferred<Boolean>()
                    seedGate = gate
                    seedItems.forEach { sendItem(it.event, ItemRequest(seedItemId = it.id)) }
                    seedTimeout =
                        launch {
                            delay(REALTIME_HISTORY_ACK_TIMEOUT_MILLIS)
                            if (gate.complete(false)) {
                                emit(
                                    ProviderEvent.Failure(
                                        "OpenAI did not acknowledge EVA's conversation history within 10 seconds.",
                                    ),
                                )
                            }
                        }
                }
            }
            val receiving =
                launch {
                    media.events.collect { raw ->
                        val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@collect
                        ProviderEventLog.realtime.record(connectionEpoch, outbound = false, message)
                        if (sessionId == null &&
                            message.str("type") !in setOf("session.created", "session.updated", "error")
                        ) {
                            return@collect
                        }
                        when (message.str("type")) {
                            "session.created", "session.updated" -> {
                                if (sessionId == null) configured(message.obj("session"))
                            }

                            "conversation.item.created", "conversation.item.added" -> {
                                message.obj("item")?.str("id")?.let(::acknowledgeSeed)
                            }

                            "response.created" -> {
                                val response = message.obj("response") ?: return@collect
                                if (!seedReady) {
                                    response.str("id")?.let { transmit(responseCancel(it)) }
                                    clearSpeech()
                                    return@collect
                                }
                                bindResponse(response)?.let { id ->
                                    val state = responses.getValue(id)
                                    if (state.status == null) {
                                        activeResponses += id
                                        if (state.interrupted || state.origin.inputId in endedInputs) cancelResponse(id)
                                    }
                                }
                            }

                            "input_audio_buffer.speech_started" -> {
                                clearSpeech()
                                userSpeaking = true
                                emit(ProviderEvent.UserSpeaking)
                                val id = message.str("item_id") ?: "unidentified-speech"
                                speakingItemId = id
                                awaitingSpeech[id] = null
                                interruptForSpeech()
                                message.str("item_id")?.let { emit(ProviderEvent.SpeechInputStarted(it)) }
                            }

                            "input_audio_buffer.speech_stopped" -> {
                                val id = message.str("item_id") ?: speakingItemId ?: "unidentified-speech"
                                if (speakingItemId == null || speakingItemId == id || speakingItemId == "unidentified-speech") {
                                    speakingItemId?.takeIf { it != id }?.let(::releaseSpeech)
                                    userSpeaking = false
                                    speakingItemId = null
                                    awaitSpeech(id)
                                }
                            }

                            "input_audio_buffer.cleared" -> {
                                clearSpeech()
                                pumpRequests()
                            }

                            "input_audio_buffer.committed" -> {
                                val id = message.str("item_id") ?: return@collect
                                val inputId = "voice:$id"
                                clearSpeech(except = inputId)
                                if (!seedReady) {
                                    flushCaptions(inputId)
                                    return@collect
                                }
                                if (id in speechInputs) return@collect
                                speechInputs[id] = inputId
                                val origin =
                                    Origin(
                                        inputId,
                                        ActionInitiator(InitiatorKind.USER_SPEECH, inputId = inputId, itemId = id),
                                        "user_speech",
                                    )
                                startInput(origin)
                                queued.addFirst(origin)
                                pumpRequests()
                            }

                            "conversation.item.input_audio_transcription.failed" -> {
                                val item = message.str("item_id")
                                if (item != null && item == speakingItemId) {
                                    userSpeaking = false
                                    speakingItemId = null
                                }
                                if (item == null && !userSpeaking) clearSpeech() else item?.let(::releaseSpeech)
                                emit(
                                    ProviderEvent.Notice(
                                        if (item in speechInputs) {
                                            "The speech caption is unavailable."
                                        } else {
                                            message.obj("error")?.str("message")
                                                ?: "OpenAI could not transcribe that speech. You can keep talking."
                                        },
                                    ),
                                )
                                pumpRequests()
                            }

                            "conversation.item.input_audio_transcription.completed" -> {
                                val text = message.str("transcript")?.takeIf { it.isNotBlank() } ?: return@collect
                                val item = message.str("item_id")
                                val input =
                                    item?.let { speechInputs[it] ?: "voice:$it" }
                                        ?: activeResponses.singleOrNull()?.let { responses[it]?.origin?.inputId }
                                val transcript =
                                    ProviderEvent.Transcript(
                                        "user",
                                        text,
                                        item ?: if (input == null) "uncorrelated:${UUID.randomUUID()}" else null,
                                        input,
                                    )
                                if (seedReady && item in awaitingSpeech && input !in startedInputs) {
                                    captions.getOrPut(checkNotNull(input)) { mutableListOf() } += transcript
                                } else {
                                    emit(transcript)
                                }
                            }

                            "response.output_audio_transcript.done", "response.audio_transcript.done", "response.output_text.done" -> {
                                val id = message.str("response_id") ?: activeResponses.singleOrNull()
                                val text = message.str("transcript") ?: message.str("text") ?: return@collect
                                val input = id?.let { responses[it]?.origin?.inputId }
                                if (input != null) {
                                    emit(ProviderEvent.AssistantText(input, text, false))
                                } else {
                                    emit(ProviderEvent.AssistantText("unowned:${UUID.randomUUID()}", text, false))
                                }
                            }

                            "response.output_item.done" -> {
                                message.obj("item")?.let { receiveCall(message.str("response_id"), it) }
                            }

                            "output_audio_buffer.started" -> {
                                assistantAudioActive = true
                                emit(ProviderEvent.AssistantSpeaking(true))
                            }

                            "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                                assistantAudioActive = false
                                emit(ProviderEvent.AssistantSpeaking(false))
                                pumpRequests()
                            }

                            "response.done" -> {
                                val response = message.obj("response") ?: return@collect
                                val id = bindResponse(response) ?: return@collect
                                val state = responses.getValue(id)
                                state.status = if (state.interrupted) "cancelled" else response.str("status") ?: "incomplete"
                                (response["output"] as? JsonArray)?.forEach { item -> (item as? JsonObject)?.let { receiveCall(id, it) } }
                                calls
                                    .filterValues { it.responseId == id && !it.emitted }
                                    .keys
                                    .toList()
                                    .forEach(::publishCall)
                                activeResponses.remove(id)
                                waitingForBusyResponse = false
                                busyTimer?.cancel()
                                if (state.status != "completed") {
                                    replyWanted.remove(id)
                                    queued.removeAll { it.initiator.parentResponseId == id }
                                    state.origin.initiator.itemId
                                        ?.let(::releaseSpeech)
                                }
                                if (state.interrupted) state.request?.let(::resumeInterrupted)
                                finishInput(state.origin.inputId)
                                prune()
                                pumpRequests()
                            }

                            "error" -> {
                                handleError(message.obj("error"))
                            }
                        }
                    }
                }
            var unconfirmed = false

            fun failUnconfigured(cause: String?) {
                if (sessionId != null || unconfirmed) return
                unconfirmed = true
                val how = if (confirm == null) "" else " through its sideband connection"
                emit(
                    ProviderEvent.Failure(
                        "OpenAI did not confirm the voice configuration with ${tools.size} tools ($sessionBytes bytes)$how" +
                            (cause?.let { ": $it" } ?: " within 8 seconds.") +
                            " EVA did not start the call without its tools. Try again, or use a typed conversation.",
                    ),
                )
                receiving.cancel()
            }

            /**
             * A sideband that cannot answer is no evidence about the configuration, which OpenAI
             * applies from the call request, so the call goes on with a loud notice; only a
             * session that names the wrong tools, or no session at all, ends it.
             */
            fun continueUnverified(cause: String) {
                if (sessionId != null || unverified || unconfirmed) return
                unverified = true
                emit(
                    ProviderEvent.Notice(
                        "EVA could not confirm that OpenAI configured all ${tools.size} voice tools ($sessionBytes bytes): " +
                            "its sideband connection to the call failed ($cause). OpenAI normally applies the whole " +
                            "configuration, so the call continues; if an action seems missing, end the call and try again " +
                            "or use a typed conversation.",
                        persistent = true,
                    ),
                )
                bareSession?.let(::configured)
            }
            val confirmation =
                confirm?.let { read ->
                    launch {
                        try {
                            val session = withContext(ioDispatcher) { read() }
                            configured(session)
                            val count = (session["tools"] as? JsonArray)?.size ?: 0
                            if (sessionId == null) failUnconfigured("OpenAI reports $count of the ${tools.size} tools configured.")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            continueUnverified(error.message ?: error::class.simpleName.orEmpty())
                        }
                    }
                }
            val configurationTimeout =
                launch {
                    delay(REALTIME_CONFIG_ACK_TIMEOUT_MILLIS)
                    if (confirmation?.isActive == true && bareSession != null) {
                        confirmation.cancel()
                        continueUnverified("no answer within 8 seconds")
                    } else {
                        failUnconfigured(null)
                    }
                }
            try {
                receiving.join()
            } finally {
                confirmation?.cancel()
                configurationTimeout.cancel()
                this@OpenAiRealtimeSession.close()
                forward.cancel()
                eventScope = null
            }
            send(ProviderEvent.Closed)
        }

    private fun emit(event: ProviderEvent) {
        local.trySend(event)
    }

    private fun acknowledgeSeed(id: String) {
        if (seedReady || !pendingSeedItemIds.remove(id) || pendingSeedItemIds.isNotEmpty()) return
        if (seedGate?.complete(true) == true) {
            seedReady = true
            seedTimeout?.cancel()
            clearSpeech()
            if (!microphoneWasMuted) media.setMicrophoneMuted(false)
            pendingConnected?.let(::emit)
            pendingConnected = null
            pumpRequests()
        }
    }

    private fun bindResponse(response: JsonObject): String? {
        val id = response.str("id") ?: return null
        if (id in responses) return id
        val echoed = response.obj("metadata")?.str("eva_request_id")
        // EVA keeps at most one request in flight, so a reply without the echo can only be that one.
        val requestId = echoed ?: inFlight?.takeIf { it in requested }
        if (echoed == null && requestId != null && !reportedMissingEcho) {
            reportedMissingEcho = true
            emit(ProviderEvent.Notice("OpenAI did not echo EVA's request ID; EVA matched the reply to its only waiting request."))
        }
        val own = requestId?.let { requested.remove(it) ?: retiredRequests[it] }
        if (requestId != null) {
            requestTimers.remove(requestId)?.cancel()
            if (inFlight == requestId) inFlight = null
            if (own != null) retiredRequests[requestId] = own
        }
        val origin = own?.origin ?: Origin("unowned:$id", ActionInitiator(InitiatorKind.UNKNOWN), "unknown")
        val bound = origin.copy(initiator = origin.initiator.copy(responseId = id))
        responses[id] = ResponseState(bound, own)
        startInput(bound)
        prune()
        return id
    }

    private fun cancelResponse(id: String) {
        transmit(responseCancel(id))
        transmit("""{"type":"output_audio_buffer.clear"}""")
    }

    private fun interruptForSpeech() {
        requested.values.forEach { it.interrupted = true }
        activeResponses.forEach { id ->
            responses.getValue(id).let { state ->
                state.interrupted = true
                state.request?.interrupted = true
            }
            cancelResponse(id)
        }
        if (activeResponses.isEmpty() && assistantAudioActive) {
            transmit("""{"type":"output_audio_buffer.clear"}""")
        }
        queued.filter { it.purpose == "user_speech" }.forEach { origin ->
            queued.remove(origin)
            inputStatus.putIfAbsent(origin.inputId, "cancelled")
            finishInput(origin.inputId)
        }
    }

    private fun resumeInterrupted(request: RequestState) {
        if (request.interruptionHandled) return
        request.interruptionHandled = true
        val origin = request.origin
        if (origin.inputId in endedInputs) return
        if ((origin.purpose == "tool_follow_up" || origin.announceOnly) && origin.inputId !in inputStatus) {
            if (origin !in queued) queued.addLast(origin)
        } else {
            inputStatus.putIfAbsent(origin.inputId, "cancelled")
        }
        finishInput(origin.inputId)
    }

    private fun startInput(origin: Origin) {
        if (origin.known && origin.inputId !in startedInputs) {
            startedInputs[origin.inputId] = origin
            emit(ProviderEvent.ResponseStarted(origin.inputId, origin.inputId, announceOnly = origin.announceOnly))
        }
        flushCaptions(origin.inputId)
    }

    private fun awaitSpeech(itemId: String) {
        awaitingSpeech.remove(itemId)?.cancel()
        awaitingSpeech[itemId] =
            checkNotNull(eventScope).launch {
                delay(REALTIME_SPEECH_WAIT_MILLIS)
                releaseSpeech(itemId)
                emit(
                    ProviderEvent.Notice("OpenAI did not finish the speech input. EVA released the waiting replies; you can keep talking."),
                )
                pumpRequests()
            }
    }

    private fun releaseSpeech(itemId: String) {
        awaitingSpeech.remove(itemId)?.cancel()
        flushCaptions(speechInputs[itemId] ?: "voice:$itemId")
    }

    private fun clearSpeech(except: String? = null) {
        awaitingSpeech.values.forEach { it?.cancel() }
        awaitingSpeech.clear()
        userSpeaking = false
        speakingItemId = null
        captions.keys
            .toList()
            .filter { it != except }
            .forEach(::flushCaptions)
    }

    private fun flushCaptions(inputId: String) {
        captions.remove(inputId)?.forEach(::emit)
    }

    private fun receiveCall(
        responseId: String?,
        item: JsonObject,
    ) {
        if (item.str("type") != "function_call") return
        val callId = item.str("call_id") ?: return
        val previous = calls[callId]
        if (previous?.emitted == true) return
        val state = previous ?: CallState(responseId, item).also { calls[callId] = it }
        if (responseId != null && (state.responseId == null || responses[state.responseId]?.origin?.known != true)) {
            state.responseId = responseId
            state.item = item
        } else if (state.responseId == responseId) {
            state.item = item
        }
        if (responses[state.responseId]?.status != null) {
            publishCall(callId)
        } else if (state.responseId == null || state.responseId !in responses) {
            if (state.timer == null) {
                state.timer =
                    checkNotNull(eventScope).launch {
                        delay(REALTIME_CALL_CORRELATION_MILLIS)
                        publishCall(callId, orphaned = true)
                    }
            }
        }
    }

    private fun publishCall(
        callId: String,
        orphaned: Boolean = false,
    ) {
        val call = calls[callId] ?: return
        if (call.emitted) return
        val response = responses[call.responseId]
        if (response?.status == null && !orphaned) return
        call.timer?.cancel()
        call.emitted = true
        val origin = response?.origin.takeUnless { orphaned && response?.status == null }
        val responseId = call.responseId ?: "missing-response"
        val inputId = origin?.inputId ?: "unowned:$responseId"
        val initiator =
            (origin?.initiator ?: ActionInitiator(InitiatorKind.UNKNOWN))
                .copy(responseId = call.responseId, outputItemId = call.item.str("id"))
        val identity =
            CallIdentity(connectionEpoch, sessionId ?: connectionEpoch, inputId, inputId, responseId, catalogRevision, callId, initiator)
        val arguments = runCatching { json.parseToJsonElement(call.item.str("arguments").orEmpty()).jsonObject }.getOrNull()
        val rejection =
            when {
                response?.status != null && response.status != "completed" -> CallRejection.INTERRUPTED_RESPONSE
                call.item.str("status") != "completed" || arguments == null -> CallRejection.INCOMPLETE_CALL
                else -> null
            }
        pending[callId] = identity
        emit(
            ProviderEvent.ToolCallReady(
                identity,
                tools[call.item.str("name")]?.capabilityId ?: call.item.str("name").orEmpty(),
                arguments ?: JsonObject(emptyMap()),
                rejection,
            ),
        )
    }

    private fun finishInput(inputId: String) {
        if (inputId !in startedInputs || inputId in endedInputs || pending.values.any { it.inputId == inputId } ||
            calls.values.any { !it.emitted && responses[it.responseId]?.origin?.inputId == inputId } ||
            requested.values.any { it.origin.inputId == inputId } || queued.any { it.inputId == inputId } ||
            activeResponses.any { responses[it]?.origin?.inputId == inputId }
        ) {
            return
        }
        val origin = startedInputs.getValue(inputId)
        val status = inputStatus[inputId] ?: responses.values.lastOrNull { it.origin.inputId == inputId }?.status ?: return
        endedInputs += inputId
        emit(ProviderEvent.ResponseEnded(inputId, status))
        if (origin.contextIds.isNotEmpty()) {
            val successful = origin.contextIds.filter { status == "completed" && it !in failedContextIds }
            val failed = origin.contextIds - successful.toSet()
            if (successful.isNotEmpty()) emit(ProviderEvent.ContextDelivery(successful, true))
            if (failed.isNotEmpty()) emit(ProviderEvent.ContextDelivery(failed, false))
            failedContextIds.removeAll(origin.contextIds.toSet())
        }
        flushCaptions(inputId)
        prune()
    }

    override suspend fun submit(input: ConversationInput) {
        check(sessionId != null && buffered == null)
        require(input.text.isNotBlank()) { "A request must not be empty." }
        buffered = input
    }

    override suspend fun requestResponse(request: ResponseRequest) {
        val input = checkNotNull(buffered)
        check(input.id == request.inputId)
        buffered = null
        val origin = Origin(input.id, ActionInitiator(InitiatorKind.USER_TYPED, inputId = input.id), "typed_input")
        startInput(origin)
        sendItem(realtimeSeedItem(OpenAiHistoryMessage("user", input.text)).event, ItemRequest(inputId = input.id))
        queued += origin
        pumpRequests()
    }

    private fun hasBlockingCalls(responseId: String): Boolean =
        pending.values.any {
            it.providerTurnId == responseId && tools[calls[it.callId]?.item?.str("name")]?.capabilityId != CapabilityRegistry.DEVICE_TASK
        }

    override suspend fun submitToolResult(result: CorrelatedToolResult) {
        if (closed) throw IOException("The voice session is closed.")
        check(result.call.connectionEpoch == connectionEpoch && pending[result.call.callId] == result.call)
        sendItem(
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
            ItemRequest(inputId = result.call.inputId),
        )
        pending.remove(result.call.callId)
        calls[result.call.callId]?.resolved = true
        val responseId = result.call.providerTurnId
        val response = responses[responseId]
        val origin = response?.origin
        if (origin == null || !origin.known || origin.inputId in endedInputs) {
            prune()
            pumpRequests()
            return
        }
        if (result.respond && response.status == "completed" && origin.inputId !in inputStatus) replyWanted += responseId
        if (!hasBlockingCalls(responseId) && replyWanted.remove(responseId) &&
            queued.none { it.initiator.parentResponseId == responseId }
        ) {
            queued += origin.copy(purpose = "tool_follow_up", initiator = origin.initiator.copy(parentResponseId = responseId))
        }
        finishInput(origin.inputId)
        prune()
        pumpRequests()
    }

    override suspend fun submitContext(
        note: String,
        respond: Boolean,
        data: JsonObject?,
        deliveryId: String?,
    ): Boolean {
        if (closed || sessionId == null || !seedReady) return false
        val ids = listOfNotNull(deliveryId)
        sendItem(realtimeSeedItem(OpenAiHistoryMessage("developer", note)).event, ItemRequest(contextIds = ids))
        data?.let {
            val attributed =
                buildJsonObject {
                    put("contentTrust", "external_data")
                    put("source", "eva.background_task")
                    put("data", it)
                }
            sendItem(realtimeSeedItem(OpenAiHistoryMessage("user", attributed.toString())).event, ItemRequest(contextIds = ids))
        }
        if (respond) {
            contextReplyPending = true
            contextIds += ids
        }
        pumpRequests()
        return true
    }

    private fun sendItem(
        raw: String,
        request: ItemRequest,
    ) {
        val id = eventId()
        itemRequests[id] = request
        while (itemRequests.size > REALTIME_CORRELATION_HISTORY) itemRequests.remove(itemRequests.keys.first())
        transmit(JsonObject(json.parseToJsonElement(raw).jsonObject + ("event_id" to JsonPrimitive(id))).toString())
    }

    private fun handleError(error: JsonObject?) {
        if (error?.str("code") == "response_cancel_not_active") return
        val id = error?.str("event_id")
        val reason = contextOverflow(error, model) ?: error?.str("message")
        val own = id?.let { requested.remove(it) }
        if (own != null) {
            requestTimers.remove(id)?.cancel()
            if (inFlight == id) inFlight = null
            retiredRequests[id] = own
            if (own.interrupted) {
                resumeInterrupted(own)
                pumpRequests()
                return
            }
            if (error.str("code") == "conversation_already_has_active_response") {
                queued.addFirst(own.origin)
                waitingForBusyResponse = true
                busyTimer?.cancel()
                busyTimer =
                    checkNotNull(eventScope).launch {
                        delay(REALTIME_RESPONSE_ACK_TIMEOUT_MILLIS)
                        waitingForBusyResponse = false
                        emit(ProviderEvent.Notice("The voice response was busy. EVA is sending the waiting reply again."))
                        pumpRequests()
                    }
                return
            }
            failInput(own.origin.inputId)
            emit(ProviderEvent.Notice(reason ?: "OpenAI could not start that response. You can keep talking."))
            pumpRequests()
            return
        }
        val item = id?.let(itemRequests::remove)
        if (item != null) {
            item.inputId?.let(::failInput)
            item.seedItemId?.let(::acknowledgeSeed)
            if (item.contextIds.isNotEmpty()) {
                failedContextIds += item.contextIds
                emit(ProviderEvent.ContextDelivery(item.contextIds, false))
            }
            emit(
                ProviderEvent.Notice(
                    reason ?: "OpenAI could not accept the conversation item. The call is still connected.",
                ),
            )
            pumpRequests()
        } else if (id in retiredRequests) {
            emit(ProviderEvent.Notice(reason ?: "OpenAI could not complete an earlier response request."))
        } else {
            emit(ProviderEvent.Failure(reason ?: "The provider reported an error."))
        }
    }

    private fun failInput(inputId: String) {
        inputStatus[inputId] = "failed"
        queued.removeAll { it.inputId == inputId }
        activeResponses.filter { responses[it]?.origin?.inputId == inputId }.forEach { transmit(responseCancel(it)) }
        finishInput(inputId)
    }

    private fun pumpRequests() {
        if (closed || !seedReady || activeResponses.isNotEmpty() || inFlight != null || waitingForBusyResponse || userSpeaking ||
            awaitingSpeech.isNotEmpty()
        ) {
            return
        }
        val ready = queued.firstOrNull { origin -> origin.initiator.parentResponseId?.let { !hasBlockingCalls(it) } != false }
        val origin =
            when {
                ready != null -> {
                    queued.remove(ready)
                    ready
                }

                contextReplyPending && !assistantAudioActive -> {
                    contextReplyPending = false
                    val input = "announcement:${UUID.randomUUID()}"
                    Origin(
                        input,
                        ActionInitiator(InitiatorKind.LIFECYCLE_NOTE_REPLY, inputId = input),
                        "lifecycle_note",
                        contextIds.toList(),
                    ).also { contextIds.clear() }
                }

                else -> {
                    return
                }
            }
        startInput(origin)
        val id = eventId()
        requested[id] = RequestState(origin)
        inFlight = id
        requestTimers[id] =
            checkNotNull(eventScope).launch {
                delay(REALTIME_RESPONSE_ACK_TIMEOUT_MILLIS)
                val abandoned = requested.remove(id) ?: return@launch
                retiredRequests[id] = abandoned
                requestTimers.remove(id)
                if (inFlight == id) inFlight = null
                if (abandoned.interrupted) {
                    resumeInterrupted(abandoned)
                } else {
                    failInput(abandoned.origin.inputId)
                    emit(
                        ProviderEvent.Notice(
                            "OpenAI did not acknowledge the response request. The call remains connected; please try your request again.",
                        ),
                    )
                }
                pumpRequests()
            }
        transmit(
            buildJsonObject {
                put("type", "response.create")
                put("event_id", id)
                put(
                    "response",
                    buildJsonObject {
                        put(
                            "metadata",
                            buildJsonObject {
                                put("eva_request_id", id)
                                put("eva_purpose", origin.purpose)
                                put("eva_input_id", origin.inputId)
                                put("eva_initiator", origin.initiator.kind.wireName)
                                origin.initiator.itemId?.let { put("eva_speech_item_id", it) }
                                origin.initiator.parentResponseId?.let { put("eva_parent_response_id", it) }
                                if (origin.announceOnly) put("eva_announce_only", "true")
                            },
                        )
                        if (origin.announceOnly) put("tool_choice", "none")
                    },
                )
            }.toString(),
        )
    }

    private fun prune() {
        while (endedInputs.size > REALTIME_CORRELATION_HISTORY) {
            val input = endedInputs.first()
            endedInputs.remove(input)
            startedInputs.remove(input)
            inputStatus.remove(input)
            speechInputs.entries.removeAll { it.value == input }
            flushCaptions(input)
        }
        val retiredResponses =
            responses
                .filter { (id, state) ->
                    state.status != null && id !in activeResponses && pending.values.none { it.providerTurnId == id } &&
                        calls.values.none { !it.emitted && it.responseId == id }
                }.keys
        retiredResponses.take((retiredResponses.size - REALTIME_CORRELATION_HISTORY).coerceAtLeast(0)).forEach(responses::remove)
        val resolved = calls.filterValues { it.resolved }.keys
        resolved.take((resolved.size - REALTIME_CORRELATION_HISTORY).coerceAtLeast(0)).forEach(calls::remove)
        while (retiredRequests.size > REALTIME_CORRELATION_HISTORY) retiredRequests.remove(retiredRequests.keys.first())
        val orphanSpeech = speechInputs.filterValues { it !in startedInputs }.keys
        orphanSpeech.take((orphanSpeech.size - REALTIME_CORRELATION_HISTORY).coerceAtLeast(0)).forEach(speechInputs::remove)
    }

    override suspend fun close() {
        closed = true
        queued.clear()
        clearSpeech()
        requestTimers.values.forEach { it.cancel() }
        calls.values.forEach { it.timer?.cancel() }
        busyTimer?.cancel()
        seedTimeout?.cancel()
        contextReplyPending = false
    }

    private fun eventId(): String = "eva_${UUID.randomUUID().toString().replace("-", "")}"
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
                put(
                    "input",
                    buildJsonObject {
                        put("transcription", transcription(request.keywords))
                        put(
                            "turn_detection",
                            buildJsonObject {
                                put("type", "server_vad")
                                put("create_response", false)
                                put("interrupt_response", true)
                            },
                        )
                    },
                )
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
