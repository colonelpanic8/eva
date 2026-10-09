package com.colonelpanic.eva.desktop.mcp

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.extensions.Descriptor
import com.colonelpanic.eva.capability.extensions.ExtensionProtocol
import com.colonelpanic.eva.providers.ToolResultImage
import com.colonelpanic.eva.providers.withToolImages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.math.RoundingMode

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
    val images: List<ToolResultImage> = emptyList(),
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
                        // The server's own definition, so a change EVA's translation hides still changes the contract.
                        put("_meta", buildJsonObject { put("mcpToolDigest", BoundedJson.digest(source(tool))) })
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

    /**
     * A scalar or scalar-array property EVA can offer without accepting anything the server would
     * not, or null. Annotations are dropped; integer formats and bounds only ever narrow; any other
     * constraint, such as a pattern or a string format, makes the property unusable.
     */
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
                "integer" -> setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "format")
                "number" -> setOf("minimum", "maximum", "format")
                "boolean" -> emptySet()
                "array" -> if (item) return null else setOf("minItems", "maxItems", "items")
                else -> return null
            } + setOf("type", "description", "enum")
        if (property.keys.any { it !in keywords && it !in ANNOTATIONS }) return null
        val fields = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type))
        property["description"]?.let { fields["description"] = it }
        property["enum"]?.let { values ->
            fields["enum"] = JsonArray((values as? JsonArray ?: return null).filter { it != JsonNull }.ifEmpty { return null })
        }
        when (type) {
            "string" -> {
                listOf("minLength", "maxLength").forEach { key -> property[key]?.let { fields[key] = it } }
            }

            "number" -> {
                listOf("minimum", "maximum").forEach { key -> property[key]?.let { fields[key] = it } }
            }

            "integer" -> {
                integerBounds(property, fields) ?: return null
            }

            "array" -> {
                listOf("minItems", "maxItems").forEach { key -> property[key]?.let { fields[key] = it } }
                fields["items"] = (property["items"] as? JsonObject)?.let { scalar(it, item = true) } ?: return null
            }
        }
        return JsonObject(fields)
    }

    /** Intersects every bound and format the server declares, rounding inward; null for an unknown format. */
    private fun integerBounds(
        property: JsonObject,
        fields: MutableMap<String, JsonElement>,
    ): Unit? {
        fun number(key: String) = (property[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
        val lows = mutableListOf(-MAX_SAFE_INTEGER)
        val highs = mutableListOf(MAX_SAFE_INTEGER)
        number("minimum")?.let { lows += it.setScale(0, RoundingMode.CEILING).toBigInteger() }
        number("maximum")?.let { highs += it.setScale(0, RoundingMode.FLOOR).toBigInteger() }
        number("exclusiveMinimum")?.let { lows += it.setScale(0, RoundingMode.FLOOR).toBigInteger() + BigInteger.ONE }
        number("exclusiveMaximum")?.let { highs += it.setScale(0, RoundingMode.CEILING).toBigInteger() - BigInteger.ONE }
        (property["format"] as? JsonPrimitive)?.contentOrNull?.let { format ->
            val (low, high) = INTEGER_FORMATS[format] ?: return null
            lows += low
            highs += high
        }
        val low = lows.max()
        val high = highs.min()
        if (low > high) return null
        if (low > -MAX_SAFE_INTEGER) fields["minimum"] = JsonPrimitive(low)
        if (high < MAX_SAFE_INTEGER) fields["maximum"] = JsonPrimitive(high)
        return Unit
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
    fun outcome(original: McpCallReply): ExecutionOutcome {
        val reply = extractScreenshots(original)
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
        val data =
            (reply.structured?.takeIf { it.toString().length <= ExtensionProtocol.RESULT_BYTES } ?: JsonObject(emptyMap()))
                .withToolImages(reply.images)
                .takeIf { it.isNotEmpty() }
        return ExecutionOutcome(
            if (reply.isError) InvocationStatus.FAILED else InvocationStatus.COMPLETED,
            clip(text, ExtensionProtocol.RESULT_BYTES),
            data,
        )
    }

    /** Some servers return screenshots as JSON data URLs instead of MCP image blocks. */
    private fun extractScreenshots(reply: McpCallReply): McpCallReply {
        val images = reply.images.toMutableSet()

        fun extract(value: JsonObject): JsonObject {
            val fields = value.toMutableMap()
            (fields["screenshot"] as? JsonObject)?.let { fields["screenshot"] = extract(it) }
            val url = (fields["data_url"] as? JsonPrimitive)?.contentOrNull
            if (url?.startsWith("data:image/") == true) {
                fields.remove("data_url")
                try {
                    val header = url.substringBefore(',')
                    require(header.endsWith(";base64")) { "The screenshot does not use base64 encoding." }
                    val mime = header.removePrefix("data:").removeSuffix(";base64")
                    images += ToolResultImage(mime, url.substringAfter(',', ""))
                    fields["imageIncluded"] = JsonPrimitive(true)
                } catch (failure: IllegalArgumentException) {
                    fields["imageOmitted"] = JsonPrimitive(failure.message ?: "The screenshot could not be decoded.")
                }
            }
            return JsonObject(fields)
        }
        val structured = reply.structured?.let(::extract)
        val text =
            reply.text.map { block ->
                val json = runCatching { Json.parseToJsonElement(block) }.getOrNull() as? JsonObject
                if (json != null) extract(json).toString() else block
            }
        return reply.copy(text = text, structured = structured, images = images.toList())
    }

    private fun source(tool: McpToolListing) =
        buildJsonObject {
            put("name", tool.name)
            tool.title?.let { put("title", it) }
            tool.description?.let { put("description", it) }
            put("inputSchema", tool.inputSchema)
        }

    private fun clip(
        text: String,
        limit: Int,
    ) = if (text.codePointCount(0, text.length) <= limit) text else text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…"

    private fun JsonObject.tool() = ((this["tool"] as JsonObject)["name"] as JsonPrimitive).content

    private val ANNOTATIONS = setOf("title", "default", "examples", "\$comment", "deprecated", "readOnly", "writeOnly")
    private val INTEGER_FORMATS: Map<String, Pair<BigInteger, BigInteger>> =
        mapOf(
            "int8" to (BigInteger.valueOf(-128) to BigInteger.valueOf(127)),
            "int16" to (BigInteger.valueOf(-32_768) to BigInteger.valueOf(32_767)),
            "int32" to (BigInteger.valueOf(Int.MIN_VALUE.toLong()) to BigInteger.valueOf(Int.MAX_VALUE.toLong())),
            "int64" to (BigInteger.valueOf(-9_007_199_254_740_991) to BigInteger.valueOf(9_007_199_254_740_991)),
            "uint8" to (BigInteger.ZERO to BigInteger.valueOf(255)),
            "uint16" to (BigInteger.ZERO to BigInteger.valueOf(65_535)),
            "uint32" to (BigInteger.ZERO to BigInteger.valueOf(4_294_967_295)),
            "uint64" to (BigInteger.ZERO to BigInteger.valueOf(9_007_199_254_740_991)),
        )

    private const val MAX_TITLE = 120
    private const val MAX_DESCRIPTION = 2_000
    private const val MAX_TOOLS = 64
    private val MAX_SAFE_INTEGER = 9_007_199_254_740_991.toBigInteger()
}
