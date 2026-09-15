package com.colonelpanic.eva.capability.extensions

import com.colonelpanic.eva.capability.BoundedJson
import com.colonelpanic.eva.capability.ExecutionOutcome
import com.colonelpanic.eva.capability.InvocationStatus
import com.colonelpanic.eva.capability.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/**
 * Pure wire validation; decoding a descriptor neither registers tools nor grants access.
 *
 * Capabilities carry an MCP tool object; results carry MCP content blocks and structured
 * content under EVA's outcome envelope. `_meta` objects are digested but not interpreted.
 */
object ExtensionProtocol {
    const val PROTOCOL_VERSION = 1L
    const val CATALOG_BYTES = 65_536
    const val ARGUMENT_BYTES = 16_384
    const val RESULT_BYTES = 16_384
    const val DEFAULT_WAIT_MILLIS = 30_000L
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val PROPERTY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val REVISION = Regex("[A-Za-z0-9._:-]{1,128}")
    private val SAFE_INTEGER = BigDecimal("9007199254740991")
    private val statuses =
        mapOf(
            "completed" to InvocationStatus.COMPLETED,
            "not_executed" to InvocationStatus.NOT_EXECUTED,
            "failed" to InvocationStatus.FAILED,
            "handed_off" to InvocationStatus.HANDED_OFF,
            "unknown" to InvocationStatus.UNKNOWN,
        )
    private val rejectionCodes =
        setOf(
            "stale_descriptor",
            "not_configured",
            "busy",
            "invalid_arguments",
            "unauthorized_caller",
        )
    private val annotationHints = setOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint")

    /** What EVA sends with `describe`, so a provider can pick a version EVA understands. */
    fun describeRequest(): String =
        JsonObject(
            mapOf(
                "protocolVersion" to JsonPrimitive(PROTOCOL_VERSION),
                "supportedProtocolVersions" to JsonArray(listOf(JsonPrimitive(PROTOCOL_VERSION))),
            ),
        ).toString()

    fun describe(payload: String): DescriptionReply {
        val root = BoundedJson.parse(payload, CATALOG_BYTES).obj()
        root.fields(
            setOf("protocolVersion", "status", "reasonCode", "truncated", "content", "descriptor"),
            setOf("structuredContent", "_meta"),
        )
        val result = result(root)
        val descriptor =
            if (result.outcome.status == InvocationStatus.COMPLETED) {
                require(!result.truncated)
                descriptor(root.getValue("descriptor").obj())
            } else {
                require(result.outcome.status in setOf(InvocationStatus.NOT_EXECUTED, InvocationStatus.FAILED))
                require(root["descriptor"] == JsonNull)
                null
            }
        return DescriptionReply(result, descriptor)
    }

    fun executeResult(
        payload: String,
        maxBytes: Int = RESULT_BYTES,
        outputSchema: JsonObject? = null,
    ): ResultReply {
        require(maxBytes in 1..RESULT_BYTES)
        val root = BoundedJson.parse(payload, maxBytes).obj()
        root.fields(setOf("protocolVersion", "status", "reasonCode", "truncated", "content"), setOf("structuredContent", "_meta"))
        val reply = result(root)
        val data = reply.outcome.data
        if (outputSchema != null && data != null) {
            require(ToolSchema.error(outputSchema, data) == null) { "Structured content does not match the output schema" }
        }
        return reply
    }

    fun encodeResult(
        reply: ResultReply,
        maxBytes: Int = RESULT_BYTES,
    ): String {
        val wireStatus = statuses.entries.single { it.value == reply.outcome.status }.key
        val text = reply.outcome.message
        val payload =
            JsonObject(
                buildMap {
                    put("protocolVersion", JsonPrimitive(PROTOCOL_VERSION))
                    put("status", JsonPrimitive(wireStatus))
                    put("reasonCode", reply.reasonCode?.let(::JsonPrimitive) ?: JsonNull)
                    put("truncated", JsonPrimitive(reply.truncated))
                    put(
                        "content",
                        JsonArray(
                            if (text.isEmpty()) {
                                emptyList()
                            } else {
                                listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))
                            },
                        ),
                    )
                    reply.outcome.data?.let { put("structuredContent", it) }
                },
            ).toString()
        executeResult(payload, maxBytes)
        return payload
    }

    fun arguments(
        schema: JsonObject,
        payload: String,
    ): JsonObject {
        checkSchema(schema)
        val args = BoundedJson.parse(payload, ARGUMENT_BYTES).obj()
        require(ToolSchema.error(schema, args) == null) { "Arguments do not match schema" }
        val properties = schema.getValue("properties").obj()
        args.forEach { (key, value) -> checkValue(properties.getValue(key).obj(), value) }
        return args
    }

    fun encodeArguments(
        schema: JsonObject,
        arguments: Map<String, String>,
    ): String {
        val payload = ToolSchema.coerce(schema, arguments).toString()
        arguments(schema, payload)
        return payload
    }

    /** Input schemas: a closed object of scalars or scalar arrays, with EVA's bounded keywords. */
    fun checkSchema(schema: JsonObject) {
        ToolSchema.check(schema)
        require(schema["type"] == JsonPrimitive("object"))
        require(schema.keys.all { it in setOf("type", "properties", "required", "additionalProperties", "description") })
        schema["description"]?.text(2000)
        schema.getValue("properties").obj().forEach { (name, child) ->
            require(PROPERTY.matches(name))
            checkProperty(child.obj())
        }
    }

    /** Output schemas: an object root that may nest objects and arrays and may stay open. */
    fun checkOutputSchema(schema: JsonObject) {
        ToolSchema.check(schema, output = true)
        require(schema["type"] == JsonPrimitive("object")) { "Output schemas describe an object" }
    }

    private fun checkProperty(property: JsonObject) {
        val type = property.getValue("type").text(20)
        require(type in ToolSchema.scalarTypes || type == "array")
        property["description"]?.text(2000)
        if (type == "array") {
            val items = property.getValue("items").obj()
            require(items.keys.all { it in setOf("type", "description", "enum", "minLength", "maxLength", "minimum", "maximum") })
            checkProperty(items)
            return
        }
        if (type == "integer") {
            listOf("minimum", "maximum").forEach { bound -> property[bound]?.let { checkInteger(it) } }
        }
        property["enum"]?.let { values ->
            val entries = values as JsonArray
            entries.forEach { checkValue(property, it) }
            val identities =
                entries.map {
                    if (type in setOf("number", "integer")) {
                        (it as JsonPrimitive).doubleOrNull!!.let { n -> if (n == 0.0) 0.0 else n }
                    } else {
                        it
                    }
                }
            require(identities.distinct().size == entries.size) { "Duplicate enum value" }
        }
    }

    private fun descriptor(root: JsonObject): Descriptor {
        root.fields(setOf("protocolVersion", "descriptorRevision", "authorizationScopeRevision", "title", "capabilities"), setOf("_meta"))
        require(root.getValue("protocolVersion").integer() == PROTOCOL_VERSION)
        root.meta()
        val revision = root.getValue("descriptorRevision").text(128).also { require(REVISION.matches(it)) }
        val scope = root.getValue("authorizationScopeRevision").text(128).also { require(REVISION.matches(it)) }
        val title = root.getValue("title").text(120)
        val capabilities = root.getValue("capabilities") as? JsonArray ?: error("Expected capabilities array")
        require(capabilities.size in 1..64)
        val parsed = capabilities.map { capability(it.obj()) }
        require(parsed.map { it.name }.distinct().size == parsed.size) { "Duplicate capability" }
        return Descriptor(revision, scope, title, parsed, BoundedJson.digest(root))
    }

    private fun capability(root: JsonObject): Capability {
        root.fields(setOf("tool", "effects", "execution", "result"), setOf("_meta"))
        root.meta()
        val tool = tool(root.getValue("tool").obj())
        val effect = effect(root.getValue("effects").text(20))
        tool.annotations?.let { checkAnnotations(it, effect) }
        val execution = root.getValue("execution").obj()
        execution.fields(setOf("mode", "requiresForeground"), setOf("maxWaitMillis"))
        require(execution["mode"] == JsonPrimitive("synchronous")) { "Installed extensions execute synchronously" }
        require(execution["requiresForeground"] == JsonPrimitive(false)) { "Foreground execution is unsupported" }
        val wait =
            execution["maxWaitMillis"]?.takeUnless { it == JsonNull }?.integer()?.also { require(it in 1..60_000) }
                ?: DEFAULT_WAIT_MILLIS
        val result = root.getValue("result").obj()
        result.fields(setOf("maxBytes"))
        val bytes =
            result
                .getValue("maxBytes")
                .integer()
                .also { require(it in 1..RESULT_BYTES) }
                .toInt()
        return Capability(
            tool.name,
            tool.title,
            tool.description,
            tool.inputSchema,
            effect,
            wait,
            bytes,
            tool.outputSchema,
            tool.annotations,
        )
    }

    /** Shared with the declarative codec: one MCP tool object, EVA's bounds applied. */
    fun tool(root: JsonObject): McpTool {
        root.fields(setOf("name", "title", "description", "inputSchema"), setOf("outputSchema", "annotations", "_meta"))
        root.meta()
        val name = root.getValue("name").text(64).also { require(NAME.matches(it)) }
        val title = root.getValue("title").text(120)
        val description = root.getValue("description").text(2000)
        val schema = root.getValue("inputSchema").obj().also(::checkSchema)
        val output = root["outputSchema"]?.obj()?.also(::checkOutputSchema)
        val annotations =
            root["annotations"]?.obj()?.also { annotations ->
                annotations.fields(emptySet(), annotationHints + "title")
                annotations["title"]?.text(120)
                annotationHints.forEach { hint -> annotations[hint]?.let { it.boolean() } }
            }
        return McpTool(name, title, description, schema, output, annotations)
    }

    fun effect(name: String): Effect =
        when (name) {
            "read" -> Effect.READ
            "write" -> Effect.WRITE
            "external_handoff" -> Effect.HANDOFF
            "unknown" -> Effect.UNKNOWN
            else -> error("Unsupported effect")
        }

    /** MCP hints may restate EVA's effect; they may not contradict it. */
    fun checkAnnotations(
        annotations: JsonObject,
        effect: Effect,
    ) {
        annotations["readOnlyHint"]?.let { require(it.boolean() == (effect == Effect.READ)) { "readOnlyHint contradicts effects" } }
        annotations["destructiveHint"]?.let { require(!(it.boolean() && effect == Effect.READ)) { "destructiveHint contradicts effects" } }
    }

    private fun result(root: JsonObject): ResultReply {
        require(root.getValue("protocolVersion").integer() == PROTOCOL_VERSION)
        root.meta()
        val status = statuses[root.getValue("status").text(20)] ?: error("Unsupported status")
        val reason = root.getValue("reasonCode").let { if (it == JsonNull) null else it.text(40) }
        require(reason == null || reason in rejectionCodes || reason == "deadline_exceeded")
        if (status in setOf(InvocationStatus.COMPLETED, InvocationStatus.HANDED_OFF)) require(reason == null)
        if (reason in rejectionCodes) require(status == InvocationStatus.NOT_EXECUTED)
        if (reason == "deadline_exceeded") require(status in setOf(InvocationStatus.NOT_EXECUTED, InvocationStatus.UNKNOWN))
        val truncated = root.getValue("truncated").boolean()
        val blocks = root.getValue("content") as? JsonArray ?: error("Expected content array")
        require(blocks.size <= 64)
        val texts =
            blocks.map { block ->
                val content = block.obj()
                content.fields(setOf("type", "text"), setOf("_meta"))
                content.meta()
                require(content["type"] == JsonPrimitive("text")) { "Only text content blocks are supported" }
                content.getValue("text").text(RESULT_BYTES, allowEmpty = true)
            }
        val message = texts.joinToString("\n")
        require(message.codePointCount(0, message.length) <= RESULT_BYTES)
        val data = root["structuredContent"]?.takeUnless { it == JsonNull }?.obj()
        return ResultReply(ExecutionOutcome(status, message, data), reason, truncated)
    }

    private fun checkValue(
        schema: JsonObject,
        value: JsonElement,
    ) {
        when (schema["type"]) {
            JsonPrimitive("integer") -> checkInteger(value)
            JsonPrimitive("string") -> require(BoundedJson.validUnicode((value as JsonPrimitive).content))
            JsonPrimitive("array") -> (value as JsonArray).forEach { checkValue(schema.getValue("items").obj(), it) }
            else -> Unit
        }
    }

    private fun checkInteger(value: JsonElement) {
        val token = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: error("Expected integer")
        val n = token.toBigDecimalOrNull() ?: error("Expected integer")
        require(n.stripTrailingZeros().scale() <= 0 && n.abs() <= SAFE_INTEGER)
    }

    private fun JsonElement.integer(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Expected integer")

    private fun JsonElement.boolean(): Boolean =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Expected boolean")

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("Expected object")

    private fun JsonObject.meta() {
        this["_meta"]?.let { it.obj() }
    }

    private fun JsonElement.text(
        max: Int,
        allowEmpty: Boolean = false,
    ): String {
        val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
        require(BoundedJson.validUnicode(text) && text.codePointCount(0, text.length) <= max && (allowEmpty || text.isNotEmpty()))
        return text
    }

    private fun JsonObject.fields(
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        require(keys.containsAll(required) && keys.all { it in required || it in optional }) { "Unexpected or missing fields" }
    }
}

enum class Effect { READ, WRITE, HANDOFF, UNKNOWN }

/** The MCP tool object embedded in a capability, after EVA's bounds are applied. */
data class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject?,
    val annotations: JsonObject?,
)

data class Capability(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: Effect,
    val maxWaitMillis: Long,
    val maxResultBytes: Int,
    val outputSchema: JsonObject? = null,
    val annotations: JsonObject? = null,
)

data class Descriptor(
    val revision: String,
    val authorizationScopeRevision: String,
    val title: String,
    val capabilities: List<Capability>,
    val digest: String,
)

data class ResultReply(
    val outcome: ExecutionOutcome,
    val reasonCode: String?,
    val truncated: Boolean,
)

data class DescriptionReply(
    val result: ResultReply,
    val descriptor: Descriptor?,
)
