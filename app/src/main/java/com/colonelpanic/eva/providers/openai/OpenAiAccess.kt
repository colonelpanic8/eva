package com.colonelpanic.eva.providers.openai

import okhttp3.Request

/** Supplies signed-in tokens, refreshing them when they are about to expire. */
fun interface ChatGptTokenSource {
    suspend fun current(): ChatGptTokens
}

/**
 * Where the phone's requests go and what pays for them. An API key is billed per token and
 * reaches the public API; a ChatGPT sign-in is covered by the subscription and reaches the
 * account's own backend, which keeps no conversation state of its own.
 */
sealed interface OpenAiAccess {
    val label: String
    val responsesUrl: String
    val modelsUrl: String

    /** Realtime calls are served by the public API host whichever credential pays for them. */
    val realtimeCallsUrl: String

    /** Whether the provider retains the turn, or the phone must resend the conversation. */
    val serverKeepsHistory: Boolean

    suspend fun authorize(builder: Request.Builder): Request.Builder
}

class ApiKeyAccess(
    private val key: String,
    baseUrl: String = OpenAiModels.BASE_URL,
) : OpenAiAccess {
    override val label = OpenAiModels.ACCOUNT_LABEL
    override val responsesUrl = "$baseUrl/v1/responses"
    override val modelsUrl = "$baseUrl/v1/models"
    override val realtimeCallsUrl = "$baseUrl/v1/realtime/calls"
    override val serverKeepsHistory = true

    override suspend fun authorize(builder: Request.Builder): Request.Builder = builder.header("Authorization", "Bearer $key")
}

class SubscriptionAccess(
    private val tokens: ChatGptTokenSource,
    clientVersion: String,
    baseUrl: String = ChatGpt.BASE_URL,
    realtimeBaseUrl: String = OpenAiModels.BASE_URL,
) : OpenAiAccess {
    override val label = ChatGpt.ACCOUNT_LABEL
    override val responsesUrl = "$baseUrl/responses"
    override val modelsUrl = "$baseUrl/models?client_version=${semanticVersion(clientVersion)}"

    /**
     * Typed turns go to the account's backend, but a realtime call is taken by the public
     * host against the same subscription token, so voice needs no second credential.
     */
    override val realtimeCallsUrl = "$realtimeBaseUrl/v1/realtime/calls"

    /** The subscription backend rejects stored responses, so continuity is the phone's job. */
    override val serverKeepsHistory = false

    override suspend fun authorize(builder: Request.Builder): Request.Builder {
        val current = tokens.current()
        builder.header("Authorization", "Bearer ${current.accessToken}")
        builder.header("originator", ChatGpt.ORIGINATOR)
        current.accountId?.let { builder.header("chatgpt-account-id", it) }
        return builder
    }
}

/** The backend requires a plain three-part version, which a debug suffix would not satisfy. */
internal fun semanticVersion(raw: String?): String = Regex("""\d+\.\d+\.\d+""").find(raw.orEmpty())?.value ?: "1.0.0"
