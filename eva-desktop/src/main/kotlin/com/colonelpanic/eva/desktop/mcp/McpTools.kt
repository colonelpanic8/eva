package com.colonelpanic.eva.desktop.mcp

import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.extensions.Descriptor
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One tool as an MCP server lists it. */
data class McpToolListing(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: JsonObject,
)

/** What the server returned for a call: text blocks, any non-text blocks, structured content, and its error flag. */
data class McpCallReply(
    val text: List<String>,
    val omittedBlocks: Int,
    val structured: JsonObject?,
    val isError: Boolean,
)

/**
 * Translates MCP tools into EVA capabilities and their replies into EVA outcomes. A plain MCP server
 * makes no effect claims EVA can rely on, so every tool's effect is unknown and needs its own grant.
 */
object McpTools {
    data class Translation(
        val descriptor: Descriptor?,
        /** Tools EVA cannot offer, with why. */
        val unsupported: Map<String, String>,
        /** Optional parameters left out because EVA's schemas cannot express them. */
        val hidden: Map<String, List<String>>,
    )

    fun translate(
        serverTitle: String,
        serverVersion: String,
        tools: List<McpToolListing>,
        maxWaitMillis: Long = ExtensionProtocol.DEFAULT_WAIT_MILLIS,
    ): Translation {
        val unsupported = linkedMapOf<String, String>()
        val hidden = linkedMapOf<String, List<String>>()
        val capabilities =
            tools.mapNotNull { tool ->
                val (schema, dropped) =
                    try {
                        inputSchema(tool.inputSchema)
                    } catch (failure: IllegalArgumentException) {
                        unsupported[tool.name] = failure.message ?: "its parameters cannot be expressed"
                        return@mapNotNull null
                    }
                if (dropped.isNotEmpty()) hidden[tool.name] = dropped
                val capability =
                    buildJsonObject {
                        put(
                            "tool",
                            buildJsonObject {
                                put("name", tool.name)
                                put("title", clip(tool.title?.takeIf(String::isNotBlank) ?: tool.name, MAX_TITLE))
                                put("description", clip(tool.description?.takeIf(String::isNotBlank) ?: tool.name, MAX_DESCRIPTION))
                                put("inputSchema", schema)
                            },
                        )
                        put("effects", "unknown")
                        put(
                            "execution",
                            buildJsonObject {
                                put("mode", "synchronous")
                                put("requiresForeground", false)
                                put("maxWaitMillis", maxWaitMillis)
                            },
                        )
                        put("result", buildJsonObject { put("maxBytes", ExtensionProtocol.RESULT_BYTES) })
                    }
                runCatching { describe(serverTitle, serverVersion, listOf(capability)) }
                    .onFailure { unsupported[tool.name] = "EVA's extension rules reject it" }
                    .getOrNull()
                    ?.let { capability }
            }
        val descriptor =
            capabilities.takeIf { it.isNotEmpty() }?.let { describe(serverTitle, serverVersion, it.take(MAX_TOOLS)) }
        capabilities.drop(MAX_TOOLS).forEach { unsupported[it.tool()] = "more than $MAX_TOOLS tools" }
        return Translation(descriptor, unsupported, hidden)
    }

    /**
     * EVA's input schemas are closed objects of scalars and scalar arrays. Nullable optional
     * parameters, common in generated MCP schemas, become plain optional ones; optional parameters
     * that still do not fit are left out; a required one that does not fit makes the tool unusable.
     */
    internal fun inputSchema(schema: JsonObject): Pair<JsonObject, List<String>> {
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
        val kept = linkedMapOf<String, JsonElement>()
        val dropped = mutableListOf<String>()
        for ((name, raw) in properties) {
            val property = (raw as? JsonObject)?.let(::scalar)?.takeIf { fits(it) }
            when {
                property != null -> kept[name] = property
                name in required -> throw IllegalArgumentException("its required parameter $name cannot be expressed")
                else -> dropped += name
            }
        }
        val normalized =
            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(kept))
                put("required", JsonArray(required.filter { it in kept }.map(::JsonPrimitive)))
                put("additionalProperties", false)
            }
        ExtensionProtocol.checkSchema(normalized)
        return normalized to dropped
    }

    /** A scalar or scalar-array property with only the keywords EVA accepts, or null. */
    private fun scalar(
        property: JsonObject,
        item: Boolean = false,
    ): JsonObject? {
        val type =
            when (val declared = property["type"]) {
                is JsonPrimitive -> declared.contentOrNull
                is JsonArray -> declared.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != "null" }.singleOrNull()
                else -> null
            } ?: return null
        val keywords =
            when (type) {
                "string" -> setOf("minLength", "maxLength")
                "integer", "number" -> setOf("minimum", "maximum")
                "boolean" -> emptySet()
                "array" -> if (item) return null else setOf("minItems", "maxItems")
                else -> return null
            } + setOf("description", "enum")
        val fields = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
        for ((key, value) in property) {
            when {
                key == "enum" -> {
                    (value as? JsonArray)?.filter { it != JsonNull }?.takeIf { it.isNotEmpty() }?.let {
                        fields[key] =
                            JsonArray(it)
                    }
                }

                key in keywords -> {
                    fields[key] = value
                }
            }
        }
        if (type == "integer") {
            clampInteger(fields, "minimum")
            clampInteger(fields, "maximum")
        }
        if (type == "array") fields["items"] = (property["items"] as? JsonObject)?.let { scalar(it, item = true) } ?: return null
        return JsonObject(fields)
    }

    private fun clampInteger(
        fields: MutableMap<String, JsonElement>,
        key: String,
    ) {
        val value = (fields[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull() ?: return
        fields[key] = JsonPrimitive(value.toBigInteger().coerceIn(-MAX_SAFE_INTEGER, MAX_SAFE_INTEGER))
    }

    private fun fits(property: JsonObject): Boolean =
        runCatching {
            ExtensionProtocol.checkSchema(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(mapOf("p" to property)),
                        "required" to JsonArray(emptyList()),
                        "additionalProperties" to JsonPrimitive(false),
                    ),
                ),
            )
        }.isSuccess

    private fun describe(
        title: String,
        version: String,
        capabilities: List<JsonObject>,
    ): Descriptor {
        val reply =
            buildJsonObject {
                put("protocolVersion", ExtensionProtocol.PROTOCOL_VERSION)
                put("status", "completed")
                put("reasonCode", JsonNull)
                put("truncated", false)
                put("content", JsonArray(emptyList()))
                put(
                    "descriptor",
                    buildJsonObject {
                        put("protocolVersion", ExtensionProtocol.PROTOCOL_VERSION)
                        put("descriptorRevision", clip(version.ifBlank { "unversioned" }, MAX_TITLE))
                        put("authorizationScopeRevision", "local")
                        put("title", clip(title, MAX_TITLE))
                        put("capabilities", JsonArray(capabilities))
                    },
                )
            }
        return checkNotNull(ExtensionProtocol.describe(reply.toString()).descriptor)
    }

    /**
     * A reply's error flag means the tool ran and reported failure. The text is the server's own,
     * bounded and quoted as external content by the dispatcher.
     */
    fun outcome(reply: McpCallReply): ExecutionOutcome {
        val text =
            buildString {
                append(
                    reply.text
                        .joinToString(
                            "\n",
                        ).ifBlank { if (reply.isError) "The tool reported an error." else "The tool returned no text." },
                )
                if (reply.omittedBlocks > 0) append("\n[${reply.omittedBlocks} non-text result blocks, such as images, are not shown.]")
            }
        val data = reply.structured?.takeIf { it.toString().length <= ExtensionProtocol.RESULT_BYTES }
        return ExecutionOutcome(
            if (reply.isError) InvocationStatus.FAILED else InvocationStatus.COMPLETED,
            clip(text, ExtensionProtocol.RESULT_BYTES),
            data,
        )
    }

    private fun clip(
        text: String,
        limit: Int,
    ) = if (text.codePointCount(0, text.length) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…"

    private fun JsonObject.tool() = ((this["tool"] as JsonObject)["name"] as JsonPrimitive).content

    private const val MAX_TITLE = 120
    private const val MAX_DESCRIPTION = 2_000
    private const val MAX_TOOLS = 64
    private val MAX_SAFE_INTEGER = 9_007_199_254_740_991.toBigInteger()
}
