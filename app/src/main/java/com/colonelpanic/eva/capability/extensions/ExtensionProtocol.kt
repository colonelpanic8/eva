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

/** Pure wire validation; decoding a descriptor neither registers tools nor grants access. */
object ExtensionProtocol {
    const val CATALOG_BYTES = 65_536
    const val ARGUMENT_BYTES = 16_384
    const val RESULT_BYTES = 16_384
    private val NAME = Regex("[a-z][a-z0-9_]{0,63}")
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

    fun describe(payload: String): DescriptionReply {
        val root = BoundedJson.parse(payload, CATALOG_BYTES).obj()
        root.fields("protocolVersion", "status", "reasonCode", "message", "truncated", "descriptor")
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
    ): ResultReply {
        require(maxBytes in 1..RESULT_BYTES)
        val root = BoundedJson.parse(payload, maxBytes).obj()
        root.fields("protocolVersion", "status", "reasonCode", "message", "truncated")
        return result(root)
    }

    fun encodeResult(
        reply: ResultReply,
        maxBytes: Int = RESULT_BYTES,
    ): String {
        val wireStatus = statuses.entries.single { it.value == reply.outcome.status }.key
        val payload =
            JsonObject(
                mapOf(
                    "protocolVersion" to JsonPrimitive(1),
                    "status" to JsonPrimitive(wireStatus),
                    "reasonCode" to (reply.reasonCode?.let(::JsonPrimitive) ?: JsonNull),
                    "message" to JsonPrimitive(reply.outcome.message),
                    "truncated" to JsonPrimitive(reply.truncated),
                ),
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
        args.forEach { (key, value) -> checkScalar(properties.getValue(key).obj(), value) }
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

    fun checkSchema(schema: JsonObject) {
        ToolSchema.check(schema)
        require(schema["type"] == JsonPrimitive("object"))
        require(schema.keys.all { it in setOf("type", "properties", "required", "additionalProperties", "description") })
        schema["description"]?.text(2000)
        schema.getValue("properties").obj().forEach { (name, child) ->
            require(PROPERTY.matches(name))
            val property = child.obj()
            val type = property.getValue("type").text(20)
            require(type in setOf("string", "integer", "number", "boolean"))
            property["description"]?.text(2000)
            if (type == "integer") {
                listOf("minimum", "maximum").forEach { bound -> property[bound]?.let { checkInteger(it) } }
            }
            property["enum"]?.let { values ->
                val entries = values as JsonArray
                entries.forEach { checkScalar(property, it) }
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
    }

    private fun descriptor(root: JsonObject): Descriptor {
        root.fields("protocolVersion", "descriptorRevision", "authorizationScopeRevision", "title", "schemaVersion", "capabilities")
        require(root.getValue("protocolVersion").integer() == 1L)
        val revision = root.getValue("descriptorRevision").text(128).also { require(REVISION.matches(it)) }
        val scope = root.getValue("authorizationScopeRevision").text(128).also { require(REVISION.matches(it)) }
        val title = root.getValue("title").text(120)
        require(root["schemaVersion"] == JsonPrimitive("flat-scalar-v1"))
        val capabilities = root.getValue("capabilities") as? JsonArray ?: error("Expected capabilities array")
        require(capabilities.size in 1..64)
        val parsed = capabilities.map { capability(it.obj()) }
        require(parsed.map { it.name }.distinct().size == parsed.size) { "Duplicate capability" }
        return Descriptor(revision, scope, title, parsed, BoundedJson.digest(root))
    }

    private fun capability(root: JsonObject): Capability {
        root.fields("name", "title", "description", "inputSchema", "effects", "execution", "result")
        val name = root.getValue("name").text(64).also { require(NAME.matches(it)) }
        val title = root.getValue("title").text(120)
        val description = root.getValue("description").text(2000)
        val schema = root.getValue("inputSchema").obj().also(::checkSchema)
        val effect =
            when (root.getValue("effects").text(20)) {
                "read" -> Effect.READ
                "write" -> Effect.WRITE
                "unknown" -> Effect.UNKNOWN
                else -> error("Unsupported effect")
            }
        val execution = root.getValue("execution").obj()
        execution.fields("requiresForeground", "maxDurationMillis", "cancellation", "idempotency")
        require(execution["requiresForeground"] == JsonPrimitive(false)) { "Foreground execution is unsupported" }
        require(execution["cancellation"] == JsonPrimitive("none") && execution["idempotency"] == JsonPrimitive("none"))
        val duration = execution.getValue("maxDurationMillis").integer().also { require(it in 1..60_000) }
        val result = root.getValue("result").obj()
        result.fields("mediaType", "maxBytes")
        require(result["mediaType"] == JsonPrimitive("text/plain"))
        val bytes =
            result
                .getValue("maxBytes")
                .integer()
                .also { require(it in 1..RESULT_BYTES) }
                .toInt()
        return Capability(name, title, description, schema, effect, duration, bytes)
    }

    private fun result(root: JsonObject): ResultReply {
        require(root.getValue("protocolVersion").integer() == 1L)
        val status = statuses[root.getValue("status").text(20)] ?: error("Unsupported status")
        val reason = root.getValue("reasonCode").let { if (it == JsonNull) null else it.text(40) }
        require(reason == null || reason in rejectionCodes || reason == "deadline_exceeded")
        if (status in setOf(InvocationStatus.COMPLETED, InvocationStatus.HANDED_OFF)) require(reason == null)
        if (reason in rejectionCodes) require(status == InvocationStatus.NOT_EXECUTED)
        if (reason == "deadline_exceeded") require(status in setOf(InvocationStatus.NOT_EXECUTED, InvocationStatus.UNKNOWN))
        val message = root.getValue("message").text(4000, allowEmpty = true)
        val truncated = (root["truncated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Expected boolean")
        return ResultReply(ExecutionOutcome(status, message), reason, truncated)
    }

    private fun checkScalar(
        schema: JsonObject,
        value: JsonElement,
    ) {
        if (schema["type"] == JsonPrimitive("integer")) checkInteger(value)
        if (schema["type"] == JsonPrimitive("string")) require(BoundedJson.validUnicode((value as JsonPrimitive).content))
    }

    private fun checkInteger(value: JsonElement) {
        val token = (value as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: error("Expected integer")
        val n = token.toBigDecimalOrNull() ?: error("Expected integer")
        require(n.stripTrailingZeros().scale() <= 0 && n.abs() <= SAFE_INTEGER)
    }

    private fun JsonElement.integer(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: error("Expected integer")

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: error("Expected object")

    private fun JsonElement.text(
        max: Int,
        allowEmpty: Boolean = false,
    ): String {
        val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Expected string")
        require(BoundedJson.validUnicode(text) && text.codePointCount(0, text.length) <= max && (allowEmpty || text.isNotEmpty()))
        return text
    }

    private fun JsonObject.fields(vararg names: String) {
        require(keys == names.toSet()) { "Unexpected or missing fields" }
    }
}

enum class Effect { READ, WRITE, UNKNOWN }

data class Capability(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: Effect,
    val maxDurationMillis: Long,
    val maxResultBytes: Int,
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
