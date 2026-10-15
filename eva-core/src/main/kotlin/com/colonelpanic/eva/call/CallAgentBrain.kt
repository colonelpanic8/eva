package com.colonelpanic.eva.call

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.coroutines.flow.Flow

/** A fresh brain receives only the call brief, attributed call transcript and restricted tools. */
fun interface CallAgentBrainFactory {
    suspend fun open(request: CallBrainRequest): CallAgentBrain
}

data class CallBrainRequest(
    val instructions: String,
    val brief: CallBrief,
    val transcript: List<CallTranscriptItem>,
    val tools: List<ProviderToolDefinition>,
    val providerTraits: CallProviderTraits,
    val media: CallMedia,
)

interface CallAgentBrain {
    val events: Flow<CallBrainEvent>

    suspend fun pushRemotePcm(frame: PcmFrame)

    suspend fun appendContext(item: CallTranscriptItem)

    suspend fun toolResult(
        callId: String,
        outcome: ExecutionOutcome,
    )

    /** For hosted media this must also stop server-side speech and disable automatic replies. */
    suspend fun setFloor(floor: CallFloor)

    suspend fun cancelOutput()

    /** The leg ended with no outcome recorded; [note] asks the agent to record one. No audio follows. */
    suspend fun legEnded(note: CallTranscriptItem) = appendContext(note)

    /** Detaches model control, NEVER the telephony leg. */
    suspend fun close()
}

sealed interface CallBrainEvent {
    data class Audio(
        val frame: PcmFrame,
    ) : CallBrainEvent

    /** Stable event ID across replay; only finalized transcripts belong here. */
    data class Transcript(
        val id: String,
        val speaker: CallSpeaker,
        val text: String,
    ) : CallBrainEvent

    /**
     * Words of an utterance still in progress, for the owner to read live; never journaled. [id] is
     * the transcript item the utterance becomes, so the finalized [Transcript] with the same ID
     * replaces it. [text] is everything recognized so far, not a delta.
     *
     * Agent captions come from the model's audio transcript, which is generated faster than the
     * paced uplink plays it, so a caption can run a little ahead of the voice. Remote captions exist
     * only when the transcription model streams partial results; otherwise remote lines still
     * appear once transcribed.
     */
    data class Caption(
        val id: String,
        val speaker: CallSpeaker,
        val text: String,
        val state: CaptionState,
    ) : CallBrainEvent

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: Map<String, String>,
    ) : CallBrainEvent

    data object OutputCancelled : CallBrainEvent

    data object Expired : CallBrainEvent
}

fun interface CallSpeechDetector {
    fun speechDetected(frame: PcmFrame): Boolean
}

/** A media window, not a call-duration limit. Retains the newest samples without losing partial frames. */
class CallPreRoll(
    private val durationMillis: Long = 2_000,
) {
    private val frames = ArrayDeque<PcmFrame>()
    private var samples = 0

    init {
        require(durationMillis > 0)
    }

    fun add(frame: PcmFrame) {
        if (frames.firstOrNull()?.sampleRateHz?.let { it != frame.sampleRateHz } == true) clear()
        frames.addLast(PcmFrame(frame.samples.copyOf(), frame.sampleRateHz))
        samples += frame.samples.size
        val capacity = (frame.sampleRateHz * durationMillis / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (samples > capacity && frames.isNotEmpty()) {
            val first = frames.removeFirst()
            val excess = samples - capacity
            val removed = minOf(excess, first.samples.size)
            samples -= removed
            if (removed < first.samples.size) {
                frames.addFirst(PcmFrame(first.samples.copyOfRange(removed, first.samples.size), first.sampleRateHz))
            }
        }
    }

    fun drain(): List<PcmFrame> = frames.toList().also { clear() }

    fun clear() {
        frames.clear()
        samples = 0
    }
}

enum class CaptionState {
    /** Still being spoken (or transcribed). */
    LIVE,

    /**
     * The agent's utterance was cancelled before it completed (barge-in, owner takeover, a dropped
     * session): what was said may have stopped partway, so it stays visible marked as cut off
     * instead of becoming a transcript item that would claim it was said in full.
     */
    CUT_OFF,

    /** Nothing will replace or finish it, such as a remote segment whose transcription failed. */
    DROPPED,
}

/**
 * What the call card shows for speech in progress. In-memory UI state held by the coordinator for
 * the life of the process; it is deliberately not part of [CallTaskSnapshot], so captions never
 * reach the journal, callback seeds or outcome evidence, which only cite finalized items.
 */
data class LiveCaption(
    val id: String,
    val speaker: CallSpeaker,
    val text: String,
    val cutOff: Boolean,
    val startedAtMillis: Long,
)
