package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object OpenAiModels {
    /** Speech-to-speech model for direct WebRTC sessions. */
    const val REALTIME = "gpt-realtime-2.1"

    /** Text model for typed turns over the Responses API. */
    const val TEXT = "gpt-5.6-sol"

    /** Default reasoning effort for typed turns; small routing tasks favor speed. */
    const val REASONING_EFFORT = "low"

    /**
     * Efforts the account's models confirm they accept, verified against the live model
     * list. Offer nothing else: an unsupported effort is a rejected turn.
     */
    val REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max", "ultra")

    const val TRANSCRIPTION = "gpt-transcribe"

    val TRANSCRIPTION_LANGUAGES = listOf("en")

    const val TRANSCRIPTION_PROMPT =
        "Spoken commands to EVA, a voice assistant on an Android phone. Expect contact names, app names, " +
            "and short requests to call, text, email, navigate, search, set alarms and timers, and open apps."

    /** Keeps the session payload bounded when a large address book supplies the names. */
    const val TRANSCRIPTION_KEYWORD_LIMIT = 200

    const val BASE_URL = "https://api.openai.com"
    const val ACCOUNT_LABEL = "OpenAI API key"
}

/** Projects EVA's catalog into OpenAI function tools under protocol-safe names. */
internal fun toolNames(tools: List<ProviderToolDefinition>): Map<String, ProviderToolDefinition> =
    tools.mapIndexed { index, tool -> "eva_tool_$index" to tool }.toMap()

internal fun functionTools(
    named: Map<String, ProviderToolDefinition>,
    strict: Boolean? = null,
): JsonArray =
    JsonArray(
        named.map { (name, tool) ->
            buildJsonObject {
                put("type", "function")
                put("name", name)
                put("description", tool.description)
                put("parameters", tool.inputSchema)
                if (strict != null) put("strict", strict)
            }
        },
    )

internal fun JsonObject.str(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

/** OpenAI error bodies carry the useful text under error.message; fall back to the raw body. */
internal fun openAiErrorMessage(
    code: Int,
    body: String,
    what: String,
): String {
    val message =
        runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(body)
                .jsonObject
                .obj("error")
                ?.str("message")
        }.getOrNull()
    return "OpenAI rejected $what ($code): ${(message ?: body).trim().take(300)}"
}
