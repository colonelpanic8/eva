package com.colonelpanic.eva.providers

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

data class ProviderToolDefinition(
    val capabilityId: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class ProviderToolCatalog(
    val revision: String,
    val tools: List<ProviderToolDefinition>,
)

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
 * The provider-facing view of a thread. Evidence and notes are EVA's own words, not the
 * user's, and providers must present them that way (a developer or system role).
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
    ) : HistoryItem

    data class Note(
        val text: String,
    ) : HistoryItem
}

data class Continuation(
    val turnId: String,
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
)

data class CorrelatedToolResult(
    val call: CallIdentity,
    val status: String,
    val message: String,
    val data: JsonObject? = null,
)

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
    ) : ProviderEvent

    data class ResponseEnded(
        val inputId: String,
        val status: String,
    ) : ProviderEvent

    data class Transcript(
        val role: String,
        val text: String,
    ) : ProviderEvent

    /**
     * Whether assistant audio is still being delivered to the phone. Only a provider that
     * observes its own audio output reports this; the rest never send it.
     */
    data class AssistantSpeaking(
        val speaking: Boolean,
    ) : ProviderEvent

    data class Failure(
        val message: String,
    ) : ProviderEvent

    data object Closed : ProviderEvent
}

interface ConversationProvider {
    suspend fun open(request: SessionOpenRequest): ConversationSession
}

interface ConversationSession {
    val connectionEpoch: String
    val events: Flow<ProviderEvent>

    suspend fun submit(input: ConversationInput)

    suspend fun requestResponse(request: ResponseRequest)

    suspend fun submitToolResult(result: CorrelatedToolResult)

    suspend fun close()
}
