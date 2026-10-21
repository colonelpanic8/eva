package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.call.CallAgentBrain
import com.colonelpanic.eva.call.CallAgentBrainFactory
import com.colonelpanic.eva.call.CallAgentTools
import com.colonelpanic.eva.call.CallAgentTuning
import com.colonelpanic.eva.call.CallBrainEvent
import com.colonelpanic.eva.call.CallBrainRequest
import com.colonelpanic.eva.call.CallBrief
import com.colonelpanic.eva.call.CallFloor
import com.colonelpanic.eva.call.CallMedia
import com.colonelpanic.eva.call.CallProviderTraits
import com.colonelpanic.eva.call.CallSpeaker
import com.colonelpanic.eva.call.CallTranscriptItem
import com.colonelpanic.eva.call.CaptionState
import com.colonelpanic.eva.call.PcmFrame
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opens call brains on OpenAI's GA Realtime WebSocket (`/v1/realtime?model=...`) with the same access
 * as the rest of EVA, including ChatGPT-subscription token refresh; it never switches payment modes.
 * The read timeout is disabled because a call can sit silent on hold for minutes.
 */
class OpenAiCallAgentBrainFactory internal constructor(
    private val access: suspend () -> OpenAiAccess,
    private val model: () -> String,
    private val voice: String,
    private val tuning: () -> CallAgentTuning = { CallAgentTuning() },
    private val connect: (Request, WebSocketListener) -> WebSocket,
) : CallAgentBrainFactory {
    constructor(
        access: suspend () -> OpenAiAccess,
        model: () -> String = { OpenAiModels.REALTIME },
        voice: String = "marin",
        tuning: () -> CallAgentTuning = { CallAgentTuning() },
        client: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    ) : this(access, model, voice, tuning, client::newWebSocket)

    override suspend fun open(request: CallBrainRequest): CallAgentBrain {
        require(
            request.media is CallMedia.LocalPcm,
        ) { "The PCM Realtime brain requires local call media; hosted calls need a sideband brain." }
        val auth = access()
        val url =
            auth.realtimeCallsUrl
                .removeSuffix("/calls")
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("model", model())
                .build()
        val authorized = auth.authorize(Request.Builder().url(url)).build()
        val brain = OpenAiCallAgentBrain(request, voice, tuning())
        try {
            brain.connect(authorized, connect)
            brain.ready.await()
            return brain
        } catch (error: Exception) {
            brain.close()
            throw error
        }
    }
}

/**
 * One Realtime session conducting one phone call over local PCM.
 *
 * Audio: the leg's PCM is resampled to and from the session's 24 kHz. The brain does no playback
 * pacing; it hands model audio to the provider in bursts, the provider plays it in real time, and
 * cancellation clears what has not played yet ([CallBrainEvent.OutputCancelled]).
 *
 * Turn taking, and why it is EVA's: server VAD commits the other party's speech, but with
 * `create_response: false`, so EVA decides when to request a response ([pump]): for a newly
 * committed remote segment, a non-quiet tool result, an owner whisper or handback, or the call
 * ending without an outcome. The voice model, which hears the audio, then decides whether to speak
 * or call the quiet `keep_listening`; transcription never gates a reply. Quiet results do not prompt
 * another response, so an idle line (NIST ticks, hold music) cannot loop the agent, and bookkeeping
 * the agent already spoke around (a completed `finish` with a hang-up coming) does not make it
 * narrate. Nothing new is requested while the remote party is mid-utterance, a response is in
 * flight, tools are pending, the owner holds the floor, or a hang-up has gone through.
 *
 * Event ordering quirks handled here: a response can be created before EVA's request is matched
 * (metadata tokens tie responses to requests, and unmatched ones are cancelled); audio deltas from a
 * cancelled or superseded generation are dropped by epoch; a `finish` can be emitted before the
 * caption of the speech it cites exists, so it is parked until captions catch up ([park]); and the
 * audio transcript runs ahead of real-time playback, so captions can lead the voice.
 *
 * Wire callbacks and public commands share [monitor]; none waits for provider playback or event
 * consumption, and events reach the coordinator through an unbounded queue in order.
 */
internal class OpenAiCallAgentBrain(
    private val request: CallBrainRequest,
    private val voice: String,
    private val tuning: CallAgentTuning,
) : CallAgentBrain {
    private val monitor = Any()
    private val queue = Channel<Queued>(Channel.UNLIMITED)
    internal val ready = CompletableDeferred<Unit>()
    private var socket: WebSocket? = null
    private var closed = false
    private var disconnected = false
    private var configured = false
    private var seeded = false
    private var floor = CallFloor.MUTED
    private var floorInitialized = false

    // Bumped on every cancellation; generations and queued audio from an older epoch are discarded.
    private var outputEpoch = 0L
    private var responseSequence = 0L
    private var responsePending: String? = null
    private var activeResponse: String? = null
    private var needsResponse = false
    private var remoteSpeaking = false
    private val seedAcks = mutableSetOf<String>()
    private val contextIds = mutableSetOf<String>()
    private val remoteItems = mutableSetOf<String>()

    // Remote segments committed but not yet transcribed; a `finish` waits for them so its quotes resolve.
    private val uncaptioned = mutableSetOf<String>()
    private val parked = mutableListOf<CallBrainEvent.ToolCall>()
    private var parkSequence = 0L
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Tool calls emitted and awaiting results; a new response waits for all of them.
    private val pendingTools = mutableSetOf<String>()

    // Results that should not prompt a reply (keep_listening, a phase report made beside another action).
    private val quietTools = mutableSetOf<String>()
    private val hangUpTools = mutableSetOf<String>()

    /**
     * `finish` calls whose response also spoke. Recording the outcome is bookkeeping the other party
     * never hears, and the agent already addressed them in that response; asking it to reply to the
     * receipt made it narrate on a Twilio call ("Thank you. The cancellation confirmation number is
     * 22:34, which I'm treating as 2234..."). So a completed one is quiet once a hang-up is coming
     * ([hangUpRequested]); without one the line went silent, so its receipt is answered. A rejected one
     * prompts a reply (the agent must fix its evidence), as does a finish made alone, since the agent
     * has not yet said anything and someone may be waiting.
     */
    private val spokenFinishes = mutableSetOf<String>()

    /** The agent called `hang_up`; one refused for lack of an outcome goes through with the next `finish`. */
    private var hangUpRequested = false

    /** A hang-up went through: anything the agent said next would reach the line until it drops. */
    private var hangingUp = false
    private val finishedTools = mutableSetOf<String>()
    private val responses = mutableMapOf<String, Generation>()
    private val names = toolNames(request.tools)
    private val legRate = (request.media as CallMedia.LocalPcm).sampleRateHz
    private var inputResampler = CallPcmResampler(legRate, SESSION_RATE)
    private var inputRate = legRate
    private var outputResampler = CallPcmResampler(SESSION_RATE, legRate)
    private val identity = UUID.randomUUID().toString()
    private val transcriptJson = Json { encodeDefaults = true }

    /** Partial transcription per remote item, when the transcription model streams deltas. */
    private val remoteLive = mutableMapOf<String, StringBuilder>()

    private data class Queued(
        val event: CallBrainEvent,
        val epoch: Long,
    )

    private class Generation(
        val epoch: Long,
    ) {
        var interrupted = false
        var ended = false
        val transcripts = linkedMapOf<String, String>()

        /** Audio transcript text so far per output item, streamed to the card as live captions. */
        val live = linkedMapOf<String, StringBuilder>()
        val tools = linkedMapOf<String, JsonObject>()
    }

    override val events: Flow<CallBrainEvent> =
        flow {
            for (queued in queue) {
                val keep =
                    synchronized(monitor) {
                        !closed && (
                            queued.event !is CallBrainEvent.Audio ||
                                (!disconnected && queued.epoch == outputEpoch && floor == CallFloor.AGENT)
                        )
                    }
                if (keep) emit(queued.event)
            }
        }

    internal fun connect(
        request: Request,
        open: (Request, WebSocketListener) -> WebSocket,
    ) {
        synchronized(monitor) {
            socket = open(request, listener)
        }
    }

    private val listener =
        object : WebSocketListener() {
            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) = synchronized(monitor) {
                if (closed || disconnected) return@synchronized
                try {
                    receive(Json.parseToJsonElement(text).jsonObject)
                } catch (_: Exception) {
                    expire("Realtime call-brain protocol failed.")
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) = synchronized(monitor) {
                if (!closed) expire("Realtime call-brain connection failed.")
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) = synchronized(monitor) {
                if (!closed) expire("Realtime call-brain connection ended (WebSocket $code).")
                webSocket.close(code, null)
                Unit
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) = synchronized(monitor) {
                if (!closed) expire("Realtime call-brain connection ended (WebSocket $code).")
            }
        }

    private fun send(event: JsonObject) {
        if (closed || disconnected) return
        if (socket?.send(event.toString()) != true) expire("Realtime call-brain event could not be sent.")
    }

    private fun emit(event: CallBrainEvent) {
        queue.trySend(Queued(event, outputEpoch))
    }

    private fun expire(reason: String) {
        if (closed || disconnected) return
        disconnected = true
        outputEpoch++
        timers.cancel()
        socket?.cancel()
        if (!ready.isCompleted) {
            ready.completeExceptionally(IllegalStateException(reason))
        } else {
            responses.values.filter { !it.ended }.forEach(::cutOff)
            emit(CallBrainEvent.OutputCancelled)
            emit(
                CallBrainEvent.Transcript(
                    seedItemId(),
                    CallSpeaker.SYSTEM_IVR,
                    buildJsonObject {
                        put("brainConnection", "disconnected")
                        put("detail", reason)
                    }.toString(),
                ),
            )
            emit(CallBrainEvent.Expired)
            queue.close()
        }
    }

    private fun receive(event: JsonObject) {
        when (event.text("type")) {
            "session.created" -> {
                send(configuration())
            }

            "session.updated" -> {
                if (!configured) {
                    configured = true
                    seed()
                }
            }

            "conversation.item.created", "conversation.item.added" -> {
                event.obj("item")?.text("id")?.let { seedAcks.remove(it) }
                if (configured && seedAcks.isEmpty() && !seeded) {
                    seeded = true
                    needsResponse = awaitsReply(request.transcript)
                    ready.complete(Unit)
                }
            }

            "input_audio_buffer.speech_started" -> {
                // Barge-in: the other party started talking, so the agent stops at once, including audio
                // already queued in the provider. With listen-in on, the provider gates the agent's own echo
                // out of this input; without that gate, the agent's leaked voice cut it off mid-sentence.
                remoteSpeaking = true
                cancelGeneration()
            }

            "input_audio_buffer.speech_stopped" -> {
                remoteSpeaking = false
            }

            "input_audio_buffer.committed" -> {
                val id = event.text("item_id") ?: return
                remoteSpeaking = false
                if (remoteItems.add(id)) {
                    uncaptioned += id
                    needsResponse = true
                    pump()
                }
            }

            "conversation.item.input_audio_transcription.delta" -> {
                val id = event.text("item_id") ?: return
                val text = remoteLive.getOrPut(id) { StringBuilder() }.append(event.text("delta").orEmpty()).toString()
                if (text.isNotBlank()) emit(CallBrainEvent.Caption(id, CallSpeaker.REMOTE, text, CaptionState.LIVE))
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val id = event.text("item_id") ?: return
                val text = event.text("transcript").orEmpty()
                // A blank result never becomes a transcript item, so its partial caption must go.
                if (remoteLive.remove(id) != null && text.isBlank()) {
                    emit(CallBrainEvent.Caption(id, CallSpeaker.REMOTE, "", CaptionState.DROPPED))
                }
                if (text.isNotBlank() && contextIds.add(id)) {
                    context(
                        seedItemId(),
                        transcriptJson.encodeToString(
                            CallTranscriptItem.serializer(),
                            CallTranscriptItem(id, CallSpeaker.REMOTE, text, System.currentTimeMillis()),
                        ),
                    )
                    emit(CallBrainEvent.Transcript(id, CallSpeaker.REMOTE, text))
                }
                captioned(id)
            }

            "conversation.item.input_audio_transcription.failed" -> {
                event.text("item_id")?.let { id ->
                    if (remoteLive.remove(id) != null) emit(CallBrainEvent.Caption(id, CallSpeaker.REMOTE, "", CaptionState.DROPPED))
                }
                emit(
                    CallBrainEvent.Transcript(
                        event.text("event_id") ?: UUID.randomUUID().toString(),
                        CallSpeaker.SYSTEM_IVR,
                        JsonObject(mapOf("transcription" to JsonPrimitive("unavailable"))).toString(),
                    ),
                )
                event.text("item_id")?.let(::captioned)
            }

            "response.created" -> {
                // Only a response EVA asked for, on the AGENT floor, may speak; anything else (a stale
                // request, a server-initiated response) is cancelled before it produces audio.
                val response = event.obj("response") ?: return
                val id = response.text("id") ?: return
                val token = response.obj("metadata")?.text("eva_call_response")
                val accepted = responsePending != null && token == responsePending && floor == CallFloor.AGENT
                val generation = responses.getOrPut(id) { Generation(outputEpoch) }
                if (token == responsePending) responsePending = null
                if (!accepted) {
                    generation.interrupted = true
                    send(
                        buildJsonObject {
                            put("type", "response.cancel")
                            put("response_id", id)
                        },
                    )
                } else {
                    activeResponse = id
                }
            }

            "response.output_audio.delta" -> {
                val generation = current(event) ?: return
                if (generation.interrupted || floor != CallFloor.AGENT) return
                val bytes = Base64.getDecoder().decode(event.text("delta") ?: return)
                require(bytes.size % 2 == 0) { "Incomplete PCM16 sample" }
                val samples = outputResampler.convert(decodePcm(bytes))
                if (samples.isNotEmpty()) emit(CallBrainEvent.Audio(PcmFrame(samples, legRate)))
            }

            "response.output_audio_transcript.delta" -> {
                // Interrupted or superseded generations stop captioning; cancelGeneration marked them cut off.
                val generation = current(event) ?: return
                if (generation.interrupted) return
                val id = event.text("item_id") ?: return
                val text =
                    generation.live
                        .getOrPut(id) { StringBuilder() }
                        .append(event.text("delta").orEmpty())
                        .toString()
                if (text.isNotBlank()) emit(CallBrainEvent.Caption(id, CallSpeaker.AGENT, text, CaptionState.LIVE))
            }

            "response.output_audio_transcript.done" -> {
                val generation = current(event) ?: return
                val id = event.text("item_id") ?: return
                generation.transcripts[id] = event.text("transcript").orEmpty()
            }

            "response.function_call_arguments.done" -> {
                val generation = current(event) ?: return
                event.text("call_id")?.let { generation.tools[it] = event }
            }

            "response.done" -> {
                responseDone(event.obj("response") ?: return)
            }

            "session.closed" -> {
                expire("Realtime call-brain session ended.")
            }

            "error" -> {
                val error = event.obj("error")
                val code = error?.text("code")
                if (code != "response_cancel_not_active") {
                    expire("Realtime call-brain rejected an event (${code ?: "unknown code"}).")
                }
            }
        }
    }

    private fun current(event: JsonObject): Generation? {
        val generation = responses[event.text("response_id")] ?: return null
        return generation.takeIf { !it.ended && !it.interrupted && it.epoch == outputEpoch }
    }

    private fun responseDone(response: JsonObject) {
        val id = response.text("id") ?: return
        val generation = responses[id] ?: return
        if (generation.ended) return
        generation.ended = true
        if (id == activeResponse) activeResponse = null
        val completed = response.text("status") == "completed" && !generation.interrupted && generation.epoch == outputEpoch
        if (completed) {
            (response["output"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .filter { it.text("type") == "function_call" }
                .forEach { item ->
                    item.text("call_id")?.let { generation.tools[it] = item }
                }
            generation.transcripts.forEach { (item, text) ->
                if (text.isNotBlank() && contextIds.add(item)) emit(CallBrainEvent.Transcript(item, CallSpeaker.AGENT, text))
            }
            // A phase report is quiet beside speech or another tool; alone, the agent has not acted yet.
            val acted =
                generation.transcripts.values.any { it.isNotBlank() } ||
                    generation.tools.values.any { names[it.text("name")]?.capabilityId != CallAgentTools.PHASE }
            val calls =
                generation.tools.mapNotNull { (callId, tool) ->
                    if (callId in pendingTools || callId in finishedTools) return@mapNotNull null
                    val definition = names[tool.text("name")] ?: error("Unknown call-agent tool")
                    val arguments = Json.parseToJsonElement(tool.text("arguments") ?: "{}").jsonObject
                    require(ToolSchema.error(definition.inputSchema, arguments) == null) { "Invalid call-agent tool arguments" }
                    pendingTools += callId
                    if (definition.capabilityId in QUIET_TOOLS &&
                        (acted || definition.capabilityId != CallAgentTools.PHASE)
                    ) {
                        quietTools += callId
                    }
                    if (definition.capabilityId == CallAgentTools.HANG_UP) {
                        hangUpTools += callId
                        hangUpRequested = true
                    }
                    if (definition.capabilityId == CallAgentTools.FINISH && generation.transcripts.values.any { it.isNotBlank() }) {
                        spokenFinishes += callId
                    }
                    CallBrainEvent.ToolCall(
                        callId,
                        definition.capabilityId,
                        arguments.mapValues { (_, value) ->
                            if (value is JsonPrimitive) value.content else value.toString()
                        },
                    )
                }
            if (calls.any { it.name == CallAgentTools.FINISH } && uncaptioned.isNotEmpty()) {
                park(calls)
            } else {
                calls.forEach(::emit)
            }
        }
        // A completed response finalized its captions as transcript items above; any other end
        // (cancelled, incomplete, failed, superseded) leaves them cut off.
        cutOff(generation, finalized = if (completed) generation.transcripts.filterValues { it.isNotBlank() }.keys else emptySet())
        if (response.text("status") == "failed") {
            expire("Realtime call-brain response failed; replacing its session.")
        } else {
            pump()
        }
    }

    /**
     * A finish can start before the caption of the speech it cites arrives, so its evidence would
     * reference a transcript item the coordinator does not hold yet. The pending tool keeps the agent
     * silent meanwhile; a caption that never arrives releases it anyway, and the coordinator then
     * rejects unknown evidence specifically.
     */
    private fun park(calls: List<CallBrainEvent.ToolCall>) {
        parked += calls
        val token = ++parkSequence
        timers.launch {
            delay(CAPTION_WAIT_MILLIS)
            synchronized(monitor) {
                if (parkSequence == token && !closed && !disconnected) {
                    uncaptioned.clear()
                    release()
                }
            }
        }
    }

    private fun captioned(id: String) {
        uncaptioned -= id
        if (uncaptioned.isEmpty()) release()
    }

    private fun release() {
        parkSequence++
        parked.forEach(::emit)
        parked.clear()
    }

    /**
     * Session setup. Server VAD only segments remote speech: `create_response: false` leaves the
     * decision to reply with EVA (see the class doc), and `interrupt_response: true` lets the other
     * party barge in on the agent, which is what a person on the phone expects. VAD values come from
     * portable tuning ([CallAgentTuning]); the defaults were chosen by replaying a NIST downlink,
     * where the API defaults committed tick noise as speech. Transcription is for captions and
     * evidence only.
     */
    private fun configuration() =
        buildJsonObject {
            put("type", "session.update")
            put(
                "session",
                buildJsonObject {
                    put("type", "realtime")
                    put("instructions", request.instructions)
                    put("output_modalities", JsonArray(listOf(JsonPrimitive("audio"))))
                    put(
                        "audio",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject {
                                    put("format", pcmFormat())
                                    put("transcription", buildJsonObject { put("model", OpenAiModels.TRANSCRIPTION) })
                                    put(
                                        "turn_detection",
                                        buildJsonObject {
                                            put("type", "server_vad")
                                            put("threshold", tuning.vadThreshold)
                                            put("prefix_padding_ms", tuning.vadPrefixPaddingMillis)
                                            put("silence_duration_ms", tuning.vadSilenceMillis)
                                            put("create_response", false)
                                            put("interrupt_response", true)
                                        },
                                    )
                                },
                            )
                            put(
                                "output",
                                buildJsonObject {
                                    put("format", pcmFormat())
                                    put("voice", voice)
                                },
                            )
                        },
                    )
                    put(
                        "tools",
                        JsonArray(
                            names.map { (name, tool) ->
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", name)
                                    put("description", tool.description)
                                    put("parameters", tool.inputSchema)
                                }
                            },
                        ),
                    )
                    put("tool_choice", "auto")
                },
            )
        }

    private fun seed() {
        val initial =
            buildJsonObject {
                put("callBrief", Json.encodeToJsonElement(CallBrief.serializer(), request.brief))
                put("providerTraits", Json.encodeToJsonElement(CallProviderTraits.serializer(), request.providerTraits))
                put("sessionSampleRateHz", SESSION_RATE)
            }
        val items =
            listOf(seedItemId() to initial.toString()) +
                request.transcript.map { item ->
                    contextIds += item.id
                    seedItemId() to transcriptJson.encodeToString(CallTranscriptItem.serializer(), item)
                }
        seedAcks.addAll(items.map { it.first })
        items.forEach { (id, text) -> context(id, text) }
    }

    private fun context(
        id: String,
        text: String,
    ) = send(
        buildJsonObject {
            put("type", "conversation.item.create")
            put(
                "item",
                buildJsonObject {
                    put("id", id)
                    put("type", "message")
                    put("role", "user")
                    put(
                        "content",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", text)
                                },
                            ),
                        ),
                    )
                },
            )
        },
    )

    override suspend fun pushRemotePcm(frame: PcmFrame) =
        synchronized(monitor) {
            if (closed || disconnected || !seeded) return@synchronized
            if (inputRate != frame.sampleRateHz) {
                inputRate = frame.sampleRateHz
                inputResampler = CallPcmResampler(inputRate, SESSION_RATE)
            }
            val samples = inputResampler.convert(frame.samples)
            if (samples.isNotEmpty()) {
                send(
                    buildJsonObject {
                        put("type", "input_audio_buffer.append")
                        put("audio", Base64.getEncoder().encodeToString(encodePcm(samples)))
                    },
                )
            }
        }

    override suspend fun appendContext(item: CallTranscriptItem) =
        synchronized(monitor) {
            if (closed || disconnected || !contextIds.add(item.id)) return@synchronized
            context(seedItemId(), transcriptJson.encodeToString(CallTranscriptItem.serializer(), item))
            if (item.speaker == CallSpeaker.OWNER_TO_AGENT) {
                needsResponse = true
                pump()
            }
        }

    override suspend fun legEnded(note: CallTranscriptItem) =
        synchronized(monitor) {
            if (closed || disconnected || !contextIds.add(note.id)) return@synchronized
            context(seedItemId(), transcriptJson.encodeToString(CallTranscriptItem.serializer(), note))
            // No more audio arrives to end a remote turn the call cut off.
            remoteSpeaking = false
            needsResponse = true
            pump()
        }

    override suspend fun toolResult(
        callId: String,
        outcome: ExecutionOutcome,
    ) = synchronized(monitor) {
        if (closed || disconnected || callId !in pendingTools || !finishedTools.add(callId)) return@synchronized
        send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", callId)
                        put(
                            "output",
                            buildJsonObject {
                                put("status", outcome.status.name)
                                put("message", outcome.message)
                                outcome.data?.let { put("data", it) }
                                put("contentTrust", "external_data")
                            }.toString(),
                        )
                    },
                )
            },
        )
        pendingTools -= callId
        if (hangUpTools.remove(callId) && outcome.status == InvocationStatus.COMPLETED) hangingUp = true
        val quietFinish =
            spokenFinishes.remove(callId) && outcome.status == InvocationStatus.COMPLETED && hangUpRequested
        if (!quietTools.remove(callId) && !quietFinish) needsResponse = true
        pump()
    }

    /**
     * Floor changes from the coordinator. Leaving AGENT cancels any generation; returning to AGENT
     * after the initial floor (an owner handback, or the end of a stall while the owner answered)
     * requests a response, since the situation may have changed and no new remote audio is
     * guaranteed to prompt one.
     */
    override suspend fun setFloor(floor: CallFloor) =
        synchronized(monitor) {
            val handback = floorInitialized
            floorInitialized = true
            if (this.floor == floor) return@synchronized
            this.floor = floor
            if (floor != CallFloor.AGENT) {
                cancelGeneration()
            } else {
                if (handback) needsResponse = true
                pump()
            }
        }

    override suspend fun cancelOutput() = synchronized(monitor) { cancelGeneration() }

    /**
     * Marks a generation's unfinished captions cut off. Only items that became transcript items
     * ([finalized]) are spared; everything else stopped partway as far as anyone can tell.
     */
    private fun cutOff(
        generation: Generation,
        finalized: Set<String> = emptySet(),
    ) {
        generation.live.forEach { (id, text) ->
            if (id !in finalized) emit(CallBrainEvent.Caption(id, CallSpeaker.AGENT, text.toString(), CaptionState.CUT_OFF))
        }
        generation.live.clear()
    }

    private fun cancelGeneration() {
        responses.values.filter { !it.ended }.forEach(::cutOff)
        outputEpoch++
        responses.values.filter { !it.ended }.forEach { it.interrupted = true }
        responsePending = null
        activeResponse = null
        needsResponse = false
        outputResampler = CallPcmResampler(SESSION_RATE, legRate)
        send(buildJsonObject { put("type", "response.cancel") })
        emit(CallBrainEvent.OutputCancelled)
    }

    /**
     * Requests the next response when, and only when, one is due and nothing forbids it (see the
     * class doc). Each request carries a unique metadata token so the matching `response.created`
     * can be told apart from stale or server-initiated responses.
     */
    private fun pump() {
        if (!seeded || closed || disconnected || hangingUp || floor != CallFloor.AGENT || remoteSpeaking ||
            responsePending != null || activeResponse != null || pendingTools.isNotEmpty() || !needsResponse
        ) {
            return
        }
        needsResponse = false
        val token = "$identity:${++responseSequence}"
        responsePending = token
        send(
            buildJsonObject {
                put("type", "response.create")
                put("response", buildJsonObject { put("metadata", buildJsonObject { put("eva_call_response", token) }) })
            },
        )
    }

    override suspend fun close() =
        synchronized(monitor) {
            if (!closed) {
                closed = true
                outputEpoch++
                timers.cancel()
                socket?.close(1000, null)
                socket?.cancel()
                queue.close()
                if (!ready.isCompleted) ready.cancel()
            }
            Unit
        }

    companion object {
        internal const val SESSION_RATE = 24_000

        /** Results that change nothing the agent must answer; replying to them makes it act on an idle line. */
        private val QUIET_TOOLS = setOf(CallAgentTools.PHASE, CallAgentTools.KEEP_LISTENING)

        private const val CAPTION_WAIT_MILLIS = 10_000L

        /** Whether a reseeded transcript ends with input no earlier brain answered: remote speech, an owner whisper or a non-quiet tool result. */
        internal fun awaitsReply(transcript: List<CallTranscriptItem>): Boolean {
            for (item in transcript.asReversed()) {
                when (item.speaker) {
                    CallSpeaker.REMOTE, CallSpeaker.OWNER_TO_AGENT -> {
                        return true
                    }

                    CallSpeaker.SYSTEM_IVR -> {
                        val tool =
                            runCatching { Json.parseToJsonElement(item.text).jsonObject.text("tool") }.getOrNull() ?: continue
                        return tool !in QUIET_TOOLS
                    }

                    else -> {
                        return false
                    }
                }
            }
            return false
        }

        private fun pcmFormat() =
            buildJsonObject {
                put("type", "audio/pcm")
                put("rate", SESSION_RATE)
            }

        internal fun encodePcm(samples: ShortArray): ByteArray =
            ByteArray(samples.size * 2).also { bytes ->
                samples.forEachIndexed { index, sample ->
                    bytes[index * 2] = sample.toByte()
                    bytes[index * 2 + 1] =
                        (sample.toInt() shr 8).toByte()
                }
            }

        internal fun decodePcm(bytes: ByteArray): ShortArray =
            ShortArray(bytes.size / 2) { index ->
                ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
            }

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    }
}
