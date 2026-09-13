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
    override val modelsUrl = "$baseUrl/models?client_version=${modelsClientVersion(clientVersion)}"

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

/**
 * The backend lists no models for clients older than this floor, verified live: 0.8.0
 * returns an empty list while 1.0.0 returns the full one. EVA reads only slugs, so it
 * asks as a current client until its own version passes the floor on its own.
 */
internal const val MIN_MODELS_CLIENT_VERSION = "1.0.0"

internal fun modelsClientVersion(raw: String?): String {
    val have = semanticVersion(raw)
    return if (compareSemantic(have, MIN_MODELS_CLIENT_VERSION) < 0) MIN_MODELS_CLIENT_VERSION else have
}

internal fun compareSemantic(
    a: String,
    b: String,
): Int {
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
