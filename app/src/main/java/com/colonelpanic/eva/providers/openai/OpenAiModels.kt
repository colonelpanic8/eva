package com.colonelpanic.eva.providers.openai

import com.colonelpanic.eva.providers.ProviderToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object OpenAiModels {
    /** Speech-to-speech model for direct WebRTC sessions. */
    const val REALTIME = "gpt-realtime-2.1"

    /** Text model for typed turns over the Responses API. */
    const val TEXT = "gpt-6-astra"

    const val TRANSCRIPTION = "gpt-4o-transcribe"
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
