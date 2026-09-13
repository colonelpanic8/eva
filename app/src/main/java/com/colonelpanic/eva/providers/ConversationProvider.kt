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
