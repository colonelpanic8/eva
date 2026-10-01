package com.colonelpanic.eva.providers

import com.colonelpanic.eva.capability.ActionInitiator
import com.colonelpanic.eva.capability.ReceiptProvenance
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ProviderToolDefinition(
    val capabilityId: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** An enabled action the session could not offer, named so the model and the person can tell what is missing. */
data class ExcludedTool(
    val capabilityId: String,
    val title: String,
)

data class ProviderToolCatalog(
    val revision: String,
    val tools: List<ProviderToolDefinition>,
    val excludedTools: List<ExcludedTool> = emptyList(),
) {
    fun sessionNotice(label: String): String =
        if (excludedTools.isEmpty()) {
            label
        } else {
            "$label · ${excludedTools.size} tools unavailable: ${excludedNames(NOTICE_NAMES)} — $SEE_EXTENSIONS"
        }

    /**
     * The first [limit] excluded titles, then how many more were left unnamed. Titles can come from
     * extensions, so model-facing text passes [quoted] to keep them as quoted data.
     */
    fun excludedNames(
        limit: Int,
        quoted: Boolean = false,
    ): String {
        val named = excludedTools.take(limit).joinToString(", ") { if (quoted) JsonPrimitive(it.title).toString() else it.title }
        val rest = excludedTools.size - limit
        return if (rest > 0) "$named and $rest more" else named
    }

    companion object {
        const val SEE_EXTENSIONS = "see Extensions"
        const val NOTICE_NAMES = 3
        const val NOTE_NAMES = 20
    }
}

data class SessionOpenRequest(
    val instructions: String,
    val catalog: ProviderToolCatalog,
    /**
     * Literal terms the speech transcriber should expect, such as contact and app names. Only
     * affects the displayed captions; providers without input transcription ignore them.
     */
    val keywords: List<String> = emptyList(),
    /** Prior conversation a resumed or re-homed leg is seeded with, oldest first. */
    val history: List<HistoryItem> = emptyList(),
    /** Present only on a re-homed leg: produce this turn's next generation without new user input. */
    val continuation: Continuation? = null,
)

/**
 * The provider-facing view of a thread. Only app-authored envelopes and notes
 * are instructions; external action evidence remains attributed, quoted data.
 */
sealed interface HistoryItem {
    data class User(
        val text: String,
    ) : HistoryItem

    data class Assistant(
        val text: String,
    ) : HistoryItem

    data class ActionEvidence(
        val title: String,
        val arguments: Map<String, String>,
        val status: String,
        val message: String,
        val provenance: ReceiptProvenance? = null,
        val data: JsonObject? = null,
    ) : HistoryItem

    data class Note(
        val text: String,
    ) : HistoryItem
}

data class Continuation(
    val turnId: String,
    val legId: String? = null,
)

data class ConversationInput(
    val id: String,
    val text: String,
)

data class ResponseRequest(
    val inputId: String,
)

data class CallIdentity(
    val connectionEpoch: String,
    val providerSessionId: String,
    val inputId: String,
    val generationId: String,
    val providerTurnId: String,
    val catalogRevision: String,
    val callId: String,
    val initiator: ActionInitiator? = null,
)

data class CorrelatedToolResult(
    val call: CallIdentity,
    val status: String,
    val message: String,
    val data: JsonObject? = null,
    val provenance: ReceiptProvenance? = null,
    /** False when the model asked for no spoken follow-up; a provider that cannot skip one ignores it. */
    val respond: Boolean = true,
)

enum class CallRejection { INTERRUPTED_RESPONSE, INCOMPLETE_CALL }

sealed interface ProviderEvent {
    data class Connected(
        val sessionId: String,
        val catalogRevision: String,
        /** Model the provider reports for this session, when it names one. */
        val model: String? = null,
        /** Model that runs tool-selecting turns when a speech model fronts the session. */
        val backendModel: String? = null,
    ) : ProviderEvent

    data class Account(
        val label: String,
    ) : ProviderEvent

    data class ResponseStarted(
        val inputId: String,
        val generationId: String,
        val announceOnly: Boolean = false,
    ) : ProviderEvent

    data class AssistantText(
        val inputId: String,
        val text: String,
        val truncated: Boolean,
    ) : ProviderEvent

    data class ToolCallReady(
        val call: CallIdentity,
        val capabilityId: String,
        val arguments: JsonObject,
        val rejection: CallRejection? = null,
    ) : ProviderEvent

    data class ResponseEnded(
        val inputId: String,
        val status: String,
    ) : ProviderEvent

    data class Transcript(
        val role: String,
        val text: String,
        val itemId: String? = null,
        val inputId: String? = null,
    ) : ProviderEvent

    /**
     * Whether assistant audio is still being delivered to the phone. Only a provider that
     * observes its own audio output reports this; the rest never send it.
     */
    data class AssistantSpeaking(
        val speaking: Boolean,
    ) : ProviderEvent

    /** The user started speaking. Only a provider that detects speech itself sends this. */
    data object UserSpeaking : ProviderEvent

    data class SpeechInputStarted(
        val itemId: String,
    ) : ProviderEvent

    data class Notice(
        val message: String,
        /** Also kept in the thread, after the session-start notice, so later banners cannot hide it. */
        val persistent: Boolean = false,
    ) : ProviderEvent

    /** Delivery means the announcement reached its terminal response, not merely the send queue. */
    data class ContextDelivery(
        val ids: List<String>,
        val delivered: Boolean,
    ) : ProviderEvent

    data class Failure(
        val message: String,
    ) : ProviderEvent

    data object Closed : ProviderEvent
}

interface ConversationProvider {
    suspend fun open(request: SessionOpenRequest): ConversationSession
}

/** The longest typed input EVA's OpenAI sessions accept. */
const val MAX_INPUT_CHARS = 4000

interface ConversationSession {
    val connectionEpoch: String
    val events: Flow<ProviderEvent>

    /** The longest typed input [submit] accepts. */
    val maxInputChars: Int get() = MAX_INPUT_CHARS

    suspend fun submit(input: ConversationInput)

    suspend fun requestResponse(request: ResponseRequest)

    suspend fun submitToolResult(result: CorrelatedToolResult)

    /** Queues an EVA-authored note with external findings. True means accepted; [ProviderEvent.ContextDelivery] confirms delivery. */
    suspend fun submitContext(
        note: String,
        respond: Boolean,
        data: JsonObject? = null,
        deliveryId: String? = null,
    ): Boolean = false

    suspend fun close()
}
